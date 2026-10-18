"""ジャーヴィスの頭脳: Ollama のツール呼び出しで PC を操作する。"""
from __future__ import annotations

import datetime as _dt
import json
import re
import threading
import time
from typing import Callable

import requests

from .tools import Registry

SYSTEM_PROMPT = """あなたはアイアンマンのJ.A.R.V.I.S.のような、Windows PC上で動くAI執事「ジャーヴィス」です。
ユーザーを「ボス」と呼び、落ち着いた丁寧な日本語で、音声で読み上げられる前提の短い返答(1〜2文)をします。
記号・URL・マークダウン・絵文字は返答に入れないでください。

ルール:
- PCの操作が必要なら必ずツールを呼んでください。操作したふりをしてはいけません。
- 「スマホで」「携帯の」など他の端末の話なら send_to_device を使い、command にはその端末で行う指示だけを書きます。
- 画面の状態がわからない時は describe_screen で確認してから click_on / type_text を使います。
- ツールの結果を受けたら、結果を一言で報告します。失敗したら理由を簡潔に伝えます。
- 雑談や質問にはツールを使わずそのまま答えます。

現在日時: {now}
この端末の名前: {name}
連携中の端末: {peers}
"""


class Agent:
    def __init__(self, cfg: dict, registry: Registry, link=None):
        self.cfg = cfg
        self.registry = registry
        self.link = link
        self.host = cfg["llm"]["host"].rstrip("/")
        self.model = cfg["llm"]["model"]
        self.max_turns = int(cfg["llm"].get("history_turns", 8))
        self.allow_remote_dangerous = bool(cfg["link"].get("allow_remote_dangerous", False))
        self.history: list[dict] = []
        self.last_time = 0.0
        self._lock = threading.Lock()

    def _system(self) -> dict:
        peers = "なし"
        if self.link:
            ps = self.link.list_peers()
            if ps:
                peers = "、".join(f"{p.name}({p.platform})" for p in ps)
        return {"role": "system", "content": SYSTEM_PROMPT.format(
            now=_dt.datetime.now().strftime("%Y-%m-%d %H:%M (%a)"),
            name=self.cfg["device"]["name"], peers=peers)}

    def _chat(self, messages: list[dict]) -> dict:
        r = requests.post(f"{self.host}/api/chat", json={
            "model": self.model,
            "messages": messages,
            "tools": self.registry.schemas(),
            "stream": False,
            "options": {"temperature": 0.3},
        }, timeout=180)
        r.raise_for_status()
        return r.json()["message"]

    def run(self, text: str, *, origin: str = "local",
            confirm: Callable[[str], bool] | None = None,
            on_tool: Callable[[str, dict], None] | None = None) -> str:
        """ユーザーの発話を処理して、読み上げる返答を返す。"""
        with self._lock:
            # 5分空いたら会話をリセット (他端末からの依頼は履歴を汚さない)
            if time.time() - self.last_time > 300:
                self.history = []
            self.last_time = time.time()

            shortcut = self._shortcut(text, origin)
            if shortcut is not None:
                return shortcut

            convo = list(self.history) if origin == "local" else []
            label = text if origin == "local" else f"[{origin.split(':', 1)[-1]}からの依頼] {text}"
            convo.append({"role": "user", "content": label})
            msgs = [self._system()] + convo
            reply = "すみません、うまく処理できませんでした。"
            try:
                for _ in range(6):
                    msg = self._chat(msgs)
                    calls = msg.get("tool_calls") or []
                    msgs.append({"role": "assistant", "content": msg.get("content", ""),
                                 **({"tool_calls": calls} if calls else {})})
                    if not calls:
                        reply = clean_for_speech(msg.get("content", "")) or "かしこまりました。"
                        break
                    for call in calls:
                        fn = call.get("function", {})
                        name = fn.get("name", "")
                        args = fn.get("arguments") or {}
                        if isinstance(args, str):
                            try:
                                args = json.loads(args)
                            except json.JSONDecodeError:
                                args = {}
                        if on_tool:
                            on_tool(name, args)
                        result = self.registry.call(
                            name, args, origin=origin,
                            allow_remote_dangerous=self.allow_remote_dangerous,
                            confirm=confirm if self.cfg["safety"].get("confirm_dangerous", True) else None)
                        print(f"[tool] {name}({args}) -> {result[:200]}")
                        msgs.append({"role": "tool", "content": result, "tool_name": name})
            except requests.RequestException as e:
                reply = "ボス、言語モデルに接続できません。Ollamaが起動しているか確認してください。"
                print(f"[agent] LLM error: {e}")

            if origin == "local":
                convo.append({"role": "assistant", "content": reply})
                self.history = convo[-self.max_turns * 2:]
            return reply

    # よくある端末転送は LLM を通さず即実行 (速い・確実)
    _REMOTE_RE = re.compile(
        r"^(?:スマホ|スマートフォン|携帯|けいたい|アンドロイド|android|phone)(?:で|に|の|から)[、,\s]*(.+)$",
        re.IGNORECASE)

    def _shortcut(self, text: str, origin: str) -> str | None:
        if origin != "local" or self.link is None:
            return None
        m = self._REMOTE_RE.match(text.strip())
        if not m:
            return None
        command = m.group(1).strip()
        # 読み上げ・クリップボードは専用ツールが必要なので LLM に任せる
        if re.search(r"クリップボード|読み上げ|伝えて|言って", command):
            return None
        # 「スマホの電池」→「電池」のように 'の' で始まる場合も自然文として送る
        try:
            res = self.link.request("phone", {"type": "command", "text": command})
            return clean_for_speech(res.get("text", "")) or "送信しました。"
        except Exception as e:  # noqa: BLE001
            return f"スマホに届きませんでした。{e}"


def clean_for_speech(text: str) -> str:
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"[*#`_>|\[\]]", "", text)
    return text.strip()
