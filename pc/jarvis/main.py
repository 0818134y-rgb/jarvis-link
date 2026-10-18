"""JARVIS Link (PC) エントリポイント。

    python -m jarvis.main            # マイクで会話
    python -m jarvis.main --text     # キーボード入力で試す (マイク・Whisper不要)
    python -m jarvis.main --no-link  # 端末連携なし
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time

from .agent import Agent
from .config import load_config
from .link import Link, PLATFORM
from .tools import load_all

WAKE_RE = re.compile(r"(?:じゃ|ジャ|ヂャ)ー?(?:び|ビ|ヴィ|ヴぃ|ヴイ|ぶい)(?:す|ス|ズ)|jarvis|ジャーベス", re.IGNORECASE)
YES_RE = re.compile(r"はい|お願い|おねがい|いいよ|オッケー|オーケー|ok|okay|実行|どうぞ|やって|イエス|yes", re.IGNORECASE)
NO_RE = re.compile(r"いいえ|だめ|ダメ|やめ|キャンセル|ノー|中止|待って", re.IGNORECASE)
BYE_RE = re.compile(r"^(?:ありがとう|以上|もういい|おしまい|終わり|大丈夫)[。!！]*$")


def build(cfg: dict, use_link: bool, text_mode: bool):
    listener = None
    if not text_mode:
        from .voice import Listener
        listener = Listener(cfg)
    from .voice import Speaker
    speaker = Speaker(cfg, listener)

    holder: dict = {}

    def dispatch(req: dict, peer: str) -> dict:
        t = req.get("type")
        agent: Agent = holder["agent"]
        registry = holder["registry"]
        if t == "ping":
            return {"ok": True, "text": "pong"}
        if t == "info":
            return {"ok": True, "text": json.dumps({
                "name": cfg["device"]["name"], "platform": PLATFORM,
                "tools": registry.describe()}, ensure_ascii=False)}
        if t == "command":
            text = str(req.get("text", ""))
            print(f"[{peer}] {text}")
            reply = agent.run(text, origin=f"remote:{peer}")
            print(f"[-> {peer}] {reply}")
            return {"ok": True, "text": reply}
        if t == "tool":
            out = registry.call(str(req.get("name", "")), req.get("args") or {},
                                origin=f"remote:{peer}",
                                allow_remote_dangerous=agent.allow_remote_dangerous)
            return {"ok": not out.startswith(("エラー", "安全のため", "ツール ")), "text": out}
        if t == "speak":
            speaker.say(str(req.get("text", "")))
            return {"ok": True, "text": "読み上げました"}
        if t == "clipboard":
            import pyperclip
            pyperclip.copy(str(req.get("text", "")))
            print(f"[{peer}] clipboard received")
            return {"ok": True, "text": "クリップボードに設定しました"}
        return {"ok": False, "text": f"未対応のリクエスト: {t}"}

    link = Link(cfg, dispatch) if use_link else None
    registry = load_all(cfg, link, speaker)
    agent = Agent(cfg, registry, link)
    holder.update(agent=agent, registry=registry)
    return listener, speaker, link, agent


def main() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=None)
    ap.add_argument("--text", action="store_true", help="keyboard input instead of microphone")
    ap.add_argument("--no-link", action="store_true", help="disable device linking")
    args = ap.parse_args()

    cfg = load_config(args.config)
    listener, speaker, link, agent = build(cfg, not args.no_link, args.text)
    if link:
        link.start()

    if args.text:
        def confirm(desc: str) -> bool:
            ans = input(f"確認: {desc} を実行しますか? [y/N] ").strip()
            return bool(YES_RE.search(ans)) or ans.lower() == "y"

        print("テキストモード。Ctrl+C で終了。")
        while True:
            try:
                text = input("YOU> ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if text:
                print("JARVIS>", agent.run(text, confirm=confirm,
                                           on_tool=lambda n, a: print(f"  ...{n} {a}")))
        if link:
            link.stop()
        return

    assert listener is not None

    def confirm(desc: str) -> bool:
        speaker.say(f"確認です。{desc}。実行してよろしいですか？")
        ans = listener.listen(timeout=8)
        print(f"YOU> {ans}")
        ok = bool(YES_RE.search(ans)) and not NO_RE.search(ans)
        if not ok:
            speaker.say("中止しました。")
        return ok

    forced = threading.Event()
    hotkey = cfg["asr"].get("hotkey")
    if hotkey:
        try:
            import keyboard
            def on_hotkey():
                forced.set()
                threading.Thread(target=speaker.beep, daemon=True).start()
            keyboard.add_hotkey(hotkey, on_hotkey)
            print(f"[asr] hotkey {hotkey}: press then speak without wake word")
        except Exception as e:  # noqa: BLE001
            print(f"[asr] hotkey unavailable: {e}")

    listener.start()
    follow_sec = float(cfg["asr"].get("follow_up_seconds", 6))
    follow_until = 0.0
    speaker.say("ジャーヴィス、起動しました。ご用件をどうぞ、ボス。")

    try:
        while True:
            text = listener.listen()
            if not text:
                continue
            was_forced = forced.is_set()
            forced.clear()
            print(f"YOU> {text}")
            in_follow = listener.last_start < follow_until
            m = WAKE_RE.search(text)
            if m:
                cmd = text[m.end():].strip(" 、。,.!！?？")
            elif in_follow or was_forced:
                cmd = text.strip()
                if BYE_RE.match(cmd):
                    speaker.say("いつでもお呼びください。")
                    follow_until = 0
                    continue
            else:
                continue
            if not cmd:
                speaker.beep()
                cmd = listener.listen(timeout=8)
                print(f"YOU> {cmd}")
                if not cmd:
                    continue
            reply = agent.run(cmd, confirm=confirm,
                              on_tool=lambda n, a: print(f"  ...{n} {a}"))
            speaker.say(reply)
            follow_until = time.time() + follow_sec
    except KeyboardInterrupt:
        pass
    finally:
        if link:
            link.stop()


if __name__ == "__main__":
    main()
