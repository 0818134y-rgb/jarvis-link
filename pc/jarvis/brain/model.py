"""自前で学習した意図理解モデルの推論 (Android の IntentModel.kt と同じ処理)。"""
from __future__ import annotations

import json
import math
import unicodedata
from pathlib import Path

import numpy as np

MODEL_DIR = Path(__file__).resolve().parent.parent / "brain_model"
REMOVE_CHARS = set(" \t\n\r　、。,.!！?？・「」『』\"'()（）〜~…ー-")
WAKE_WORDS = ["じゃゔぃす", "じゃびす", "jarvis"]


def normalize(text: str) -> str:
    t = unicodedata.normalize("NFKC", text).lower()
    t = "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in t)
    t = "".join(c for c in t if c not in REMOVE_CHARS)
    for w in WAKE_WORDS:
        t = t.replace(w, "")
    return t


def ngrams(t: str, n_min: int = 1, n_max: int = 3) -> set[str]:
    s = "^" + t + "$"
    return {s[i:i + n] for n in range(n_min, n_max + 1) for i in range(len(s) - n + 1)}


class IntentModel:
    def __init__(self, model_dir: Path = MODEL_DIR):
        meta = json.loads((model_dir / "model.json").read_text(encoding="utf-8"))
        self.intents: list[str] = meta["intents"]
        self.n_min, self.n_max = meta["ngram_min"], meta["ngram_max"]
        self.vocab = {g: i for i, g in enumerate(meta["vocab"])}
        raw = np.frombuffer((model_dir / "weights.bin").read_bytes(), dtype="<f4")
        c, v = len(self.intents), len(self.vocab)
        if raw.size != c * v + c:
            raise ValueError("weights.bin のサイズがモデルと一致しません")
        self.W = raw[:c * v].reshape(c, v)
        self.b = raw[c * v:]

    def predict(self, text: str, k: int = 3) -> list[tuple[str, float]]:
        idx = [self.vocab[g] for g in ngrams(normalize(text), self.n_min, self.n_max) if g in self.vocab]
        logits = self.b.astype(np.float64).copy()
        if idx:
            logits += self.W[:, idx].sum(axis=1) / math.sqrt(len(idx))
        e = np.exp(logits - logits.max())
        p = e / e.sum()
        order = np.argsort(-p)[:k]
        return [(self.intents[i], float(p[i])) for i in order]
