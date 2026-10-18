"""連携・ツール・エージェントの結合テスト (マイク・Ollama不要)。

    python -m pytest tests   または   python tests/test_link_agent.py
"""
from __future__ import annotations

import http.server
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jarvis.config import DEFAULTS, _merge  # noqa: E402
from jarvis.link import Link, LinkError  # noqa: E402
from jarvis.tools import CONFIRM, REMOTE_BLOCKED, Registry  # noqa: E402


def cfg_for(name, port, token, peers=(), platform_dangerous=False, llm_port=0):
    return _merge(DEFAULTS, {
        "device": {"name": name, "aliases": []},
        "link": {"port": port, "token": token, "discovery": False, "peers": list(peers),
                 "allow_remote_dangerous": platform_dangerous},
        "llm": {"host": f"http://127.0.0.1:{llm_port}"},
    })


def make_registry():
    reg = Registry()
    state = {"light": False}

    @reg.add("flashlight", "ライト", {"on": {"type": "boolean"}}, ["on"])
    def flashlight(on: bool):
        state["light"] = on
        return "ライトをつけました" if on else "ライトを消しました"

    @reg.add("run_shell", "シェル", {"command": {"type": "string"}}, ["command"], risk=CONFIRM)
    def run_shell(command: str):
        return f"ran {command}"

    @reg.add("tap", "タップ", {}, [], risk=REMOTE_BLOCKED)
    def tap():
        return "tapped"

    return reg, state


def test_registry_risk():
    reg, _ = make_registry()
    assert reg.call("run_shell", {"command": "x"}, origin="remote:a").startswith("安全のため")
    assert reg.call("tap", {}, origin="remote:a").startswith("安全のため")
    assert reg.call("tap", {}, origin="local") == "tapped"
    assert reg.call("run_shell", {"command": "x"}, confirm=lambda d: False) == "ユーザーが実行をキャンセルしました。"
    assert reg.call("run_shell", {"command": "x"}, confirm=lambda d: True) == "ran x"
    assert reg.call("run_shell", {"command": "x"}, origin="remote:a", allow_remote_dangerous=True) == "ran x"
    # LLMが余計な引数を付けても動く
    assert reg.call("flashlight", {"on": True, "bogus": 1}) == "ライトをつけました"


def test_link_end_to_end():
    llm_port = 0
    token = "test-token-123"
    reg_b, state_b = make_registry()
    cfg_b = cfg_for("phone", 18766, token, llm_port=llm_port)
    holder = {}

    def dispatch_b(req, peer):
        if req["type"] == "command":
            if "ライト" in req["text"]:
                return {"ok": True, "text": reg_b.call("flashlight", {"on": True}, origin=f"remote:{peer}")}
            return {"ok": True, "text": "?"}
        if req["type"] == "tool":
            return {"ok": True, "text": reg_b.call(req["name"], req.get("args"), origin=f"remote:{peer}")}
        if req["type"] == "ping":
            return {"ok": True, "text": "pong"}
        return {"ok": False, "text": "?"}

    link_b = Link(cfg_b, dispatch_b)
    link_b.start()

    cfg_a = cfg_for("pc", 18765, token, peers=["127.0.0.1:18766"], llm_port=llm_port)
    link_a = Link(cfg_a, lambda r, p: {"ok": True, "text": "a"})
    link_a.start()
    time.sleep(1.0)
    link_a.refresh_manual()
    assert link_a.resolve("phone") is not None, link_a.list_peers()

    res = link_a.request("phone", {"type": "ping"})
    assert res["ok"] and res["text"] == "pong"

    res = link_a.request("phone", {"type": "command", "text": "ライトをつけて"})
    assert res["ok"], res
    assert state_b["light"] is True
    assert "ライトをつけました" in res["text"]

    res = link_a.request("phone", {"type": "tool", "name": "run_shell", "args": {"command": "rm"}})
    assert "安全のため" in res["text"]

    # トークン違いは拒否される
    cfg_bad = cfg_for("evil", 18767, "wrong-token", peers=["127.0.0.1:18766"])
    link_bad = Link(cfg_bad, lambda r, p: {"ok": True, "text": ""})
    link_bad.start()
    link_bad.refresh_manual()
    try:
        link_bad.request("phone", {"type": "ping"})
        raise AssertionError("should have failed")
    except LinkError as e:
        assert "認証" in str(e)

    # 存在しない端末
    try:
        link_a.request("tablet", {"type": "ping"})
        raise AssertionError("should have failed")
    except LinkError:
        pass

    for l in (link_a, link_b, link_bad):
        l.stop()


def test_wake_word():
    from jarvis.main import WAKE_RE, YES_RE, NO_RE
    for t in ["ジャーヴィス、ライトつけて", "ジャービスメモ帳開いて", "Jarvis open", "じゃーびす"]:
        assert WAKE_RE.search(t), t
    assert not WAKE_RE.search("今日の天気は")
    assert YES_RE.search("はい、お願いします") and not NO_RE.search("はい、お願いします")
    assert NO_RE.search("いいえ、やめて")


if __name__ == "__main__":
    test_registry_risk()
    test_wake_word()
    test_link_end_to_end()
    print("LINK TESTS PASSED")
