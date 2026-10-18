"""JARVIS 意図理解モデルの学習 (numpy だけで一から実装)。

モデル: 文字 1〜3-gram の有無を特徴量にしたソフトマックス回帰 (多クラスロジスティック回帰)。
出力:   android/app/src/main/assets/brain/model.json   (意図名・語彙)
        android/app/src/main/assets/brain/weights.bin  (float32 little endian: W[C][V] の後に b[C])
        android/app/src/test/resources/brain_cases.json (Kotlin側と推論結果が一致するかのテスト用)

    pip install numpy
    python brain/train.py
"""
from __future__ import annotations

import json
import math
import struct
import sys
import unicodedata
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dataset import build  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/app/src/main/assets/brain"
TEST_RES = ROOT / "android/app/src/test/resources"
PC_MODEL = ROOT / "pc/jarvis/brain_model"   # PC版も同じモデルを使う

NGRAM_MIN, NGRAM_MAX = 1, 3
REMOVE_CHARS = set(" \t\n\r\u3000、。,.!！?？・「」『』\"'()（）〜~…ー-")  # 長音記号も揺れが大きいので除去
WAKE_WORDS = ["じゃゔぃす", "じゃびす", "jarvis"]  # 長音除去後の表記


def normalize(text: str) -> str:
    """Kotlin の IntentModel.normalize と完全に同じ処理にすること。"""
    t = unicodedata.normalize("NFKC", text).lower()
    # カタカナ -> ひらがな (ァ..ヶ -> ぁ..ゖ)
    t = "".join(chr(ord(c) - 0x60) if "\u30a1" <= c <= "\u30f6" else c for c in t)
    t = "".join(c for c in t if c not in REMOVE_CHARS)
    for w in WAKE_WORDS:
        t = t.replace(w, "")
    return t


def ngrams(t: str) -> set[str]:
    s = "^" + t + "$"
    out = set()
    for n in range(NGRAM_MIN, NGRAM_MAX + 1):
        for i in range(len(s) - n + 1):
            out.add(s[i:i + n])
    return out


def featurize(texts, vocab):
    X = np.zeros((len(texts), len(vocab)), dtype=np.float32)
    for r, t in enumerate(texts):
        idx = [vocab[g] for g in ngrams(normalize(t)) if g in vocab]
        if idx:
            X[r, idx] = 1.0 / math.sqrt(len(idx))
    return X


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def train(X, y, C, epochs=120, lr=0.05, l2=1e-5, batch=64, seed=0):
    rng = np.random.default_rng(seed)
    n, V = X.shape
    W = np.zeros((V, C), dtype=np.float32)
    b = np.zeros(C, dtype=np.float32)
    mW = np.zeros_like(W); vW = np.zeros_like(W); mb = np.zeros_like(b); vb = np.zeros_like(b)
    b1, b2, eps, step = 0.9, 0.999, 1e-8, 0
    Y = np.eye(C, dtype=np.float32)[y]
    for ep in range(epochs):
        perm = rng.permutation(n)
        for i in range(0, n, batch):
            j = perm[i:i + batch]
            P = softmax(X[j] @ W + b)
            G = (P - Y[j]) / len(j)
            gW = X[j].T @ G + l2 * W
            gb = G.sum(axis=0)
            step += 1
            for p, g, m, v in ((W, gW, mW, vW), (b, gb, mb, vb)):
                m *= b1; m += (1 - b1) * g
                v *= b2; v += (1 - b2) * g * g
                p -= lr * (m / (1 - b1 ** step)) / (np.sqrt(v / (1 - b2 ** step)) + eps)
        if ep % 30 == 0 or ep == epochs - 1:
            P = softmax(X @ W + b)
            loss = -np.log(P[np.arange(n), y] + 1e-9).mean()
            print(f"  epoch {ep:3d} loss {loss:.4f} train-acc {(P.argmax(1) == y).mean():.3f}")
    return W, b


def main():
    data = [(s, i) for s, i in build() if normalize(s)]
    intents = sorted({i for _, i in data})
    cid = {k: n for n, k in enumerate(intents)}

    # 評価: 2割を検証用に分けて精度を確認
    rng = np.random.default_rng(1)
    order = rng.permutation(len(data))
    cut = int(len(data) * 0.8)
    tr = [data[k] for k in order[:cut]]
    te = [data[k] for k in order[cut:]]

    def vocab_of(rows):
        counts = {}
        for s, _ in rows:
            for g in ngrams(normalize(s)):
                counts[g] = counts.get(g, 0) + 1
        return {g: n for n, g in enumerate(sorted(g for g, c in counts.items() if c >= 1))}

    vocab = vocab_of(tr)
    Xtr, ytr = featurize([s for s, _ in tr], vocab), np.array([cid[i] for _, i in tr])
    Xte, yte = featurize([s for s, _ in te], vocab), np.array([cid[i] for _, i in te])
    print(f"validation run: {len(tr)} train / {len(te)} test, {len(intents)} intents, vocab {len(vocab)}")
    W, b = train(Xtr, ytr, len(intents))
    acc = (softmax(Xte @ W + b).argmax(1) == yte).mean()
    print(f"  held-out accuracy: {acc:.3f}")

    # 本番: 全データで学習して書き出し
    vocab = vocab_of(data)
    X, y = featurize([s for s, _ in data], vocab), np.array([cid[i] for _, i in data])
    print(f"final run: {len(data)} examples, vocab {len(vocab)}")
    W, b = train(X, y, len(intents))

    ASSETS.mkdir(parents=True, exist_ok=True)
    inv_vocab = [None] * len(vocab)
    for g, n in vocab.items():
        inv_vocab[n] = g
    (ASSETS / "model.json").write_text(json.dumps({
        "version": 1, "ngram_min": NGRAM_MIN, "ngram_max": NGRAM_MAX,
        "intents": intents, "vocab": inv_vocab,
    }, ensure_ascii=False), encoding="utf-8")
    Wt = W.T.astype("<f4")  # [C][V]
    with open(ASSETS / "weights.bin", "wb") as f:
        f.write(Wt.tobytes())
        f.write(b.astype("<f4").tobytes())
    print(f"wrote {ASSETS} ({(ASSETS / 'weights.bin').stat().st_size // 1024} KB weights)")
    PC_MODEL.mkdir(parents=True, exist_ok=True)
    for name in ("model.json", "weights.bin"):
        (PC_MODEL / name).write_bytes((ASSETS / name).read_bytes())

    # Kotlin 側との一致テスト用に、いろいろな文の予測結果を保存
    probes = ["ライトつけて", "ジャーヴィス、今何時？", "明日の大阪の天気は", "富士山って何", "3たす5は",
              "聞こえる", "今何ができんの", "5分のタイマー", "YouTubeを開いて", "ニュースを教えて",
              "疲れた", "1ドルは何円", "クリスマスまであと何日", "ありがとう", "東京駅までナビして",
              "スクショ撮って", "私の名前はゼンです", "メモを読んで", "量子力学について教えて", "天気どう？",
              "新しいタブを開いて", "3番をタップして", "このページをパソコンで開いて", "スマホを探して",
              "Amazonでイヤホンを探して", "左にスワイプ", "保存して", "見積書というファイルを探して"]
    X_p = featurize(probes, vocab)
    P = softmax(X_p @ W + b)
    cases = [{"text": t, "intent": intents[int(P[k].argmax())], "prob": round(float(P[k].max()), 4),
              "normalized": normalize(t)} for k, t in enumerate(probes)]
    TEST_RES.mkdir(parents=True, exist_ok=True)
    (TEST_RES / "brain_cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
    (PC_MODEL / "brain_cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
    for c in cases:
        print(f"  {c['text']:22s} -> {c['intent']:15s} {c['prob']:.2f}")


if __name__ == "__main__":
    main()
