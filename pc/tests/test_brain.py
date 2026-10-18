"""自作AIエンジン (PC版) のテスト。マイク・ネット不要。

    python tests/test_brain.py
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jarvis.brain import rules, skills  # noqa: E402
from jarvis.brain.brain import Brain  # noqa: E402
from jarvis.brain.model import IntentModel, MODEL_DIR, normalize  # noqa: E402
from jarvis.config import DEFAULTS, _merge  # noqa: E402
from jarvis.tools import Registry  # noqa: E402

TOOL_NAMES = ["open_app", "close_app", "window_control", "show_desktop", "media_control", "system_info", "lock_pc",
              "sleep_pc", "search_files", "open_path", "open_url", "web_search", "site_search", "browser_control",
              "current_url", "navigate", "compose_mail", "post_x", "list_items", "tap_item", "click_on", "read_screen",
              "type_text", "press_keys", "scroll", "screenshot", "set_timer", "set_alarm", "send_to_device",
              "speak_on_device", "share_page", "read_phone_notifications", "open_settings"]


def make_brain():
    calls = []
    reg = Registry()
    for name in TOOL_NAMES:
        def f(_n=name, **kw):
            calls.append((_n, kw))
            return f"{_n} ok"
        reg.add(name, name)(f)
    cfg = _merge(DEFAULTS, {"link": {"token": "t"}})
    mem = Path(tempfile.mkdtemp()) / "memory.json"
    return Brain(cfg, reg, link=None, memory_path=mem), calls


def test_parity_with_training():
    """学習時(Python)と同じ予測になること。Android側も同じファイルでテストしている。"""
    model = IntentModel()
    cases = json.loads((MODEL_DIR / "brain_cases.json").read_text(encoding="utf-8"))
    for c in cases:
        assert normalize(c["text"]) == c["normalized"], c
        intent, prob = model.predict(c["text"], 1)[0]
        assert intent == c["intent"] and abs(prob - c["prob"]) < 0.01, (c, intent, prob)


def test_rules():
    assert rules.match("スマホのライトをつけて").args == {"device": "phone", "command": "ライトをつけて"}
    assert rules.match("5分のタイマー").args == {"minutes": 5, "seconds": 0}
    assert rules.match("三分タイマー").args["minutes"] == 3
    assert rules.match("午後3時半にアラーム").args == {"hour": 15, "minute": 30}
    assert rules.match("パソコンでメモ帳を開いて").args == {"name": "メモ帳"}
    assert rules.match("スマホに「ご飯だよ」と伝えて").tool == "speak_on_device"


def test_skills():
    assert skills.calc_format(skills.calc_evaluate(skills.calc_expression("3たす5は"))) == "8"
    assert skills.calc_format(skills.calc_evaluate(skills.calc_expression("2の10乗"))) == "1,024"
    assert skills.calc_format(skills.calc_evaluate(skills.calc_expression("3万5千たす0"))) == "35,000"
    assert skills.topic_of("富士山って何？") == "富士山"
    assert skills.weather_place("明日の大阪の天気は") == "大阪"
    assert skills.weather_place("今日の天気は") is None
    assert skills.weather_when("週末の天気") == "weekend"
    assert skills.geocode("大阪")[1] > 34


def check(brain_calls, text, tool, **expect):
    brain, calls = brain_calls
    calls.clear()
    reply = brain.handle(text)
    assert calls, f"no tool for {text!r}: {reply}"
    name, args = calls[-1]
    assert name == tool, (text, calls, reply)
    for k, v in expect.items():
        assert args.get(k) == v, (text, args)
    return reply


def test_dispatch():
    b = make_brain()
    check(b, "Amazonでイヤホンを探して", "site_search", site="Amazon", query="イヤホン")
    check(b, "新しいタブを開いて", "browser_control", action="new_tab")
    check(b, "タブを閉じて", "browser_control", action="close_tab")
    check(b, "3番をクリックして", "tap_item", number=3)
    check(b, "画面の項目を教えて", "list_items")
    check(b, "保存して", "press_keys", keys="ctrl+s")
    check(b, "見積書というファイルを探して", "search_files", query="見積書")
    check(b, "5分のタイマー", "set_timer", minutes=5)
    check(b, "スマホのライトをつけて", "send_to_device", device="phone", command="ライトをつけて")
    check(b, "このページをスマホで開いて", "share_page", device="phone")
    check(b, "ダウンロードフォルダを開いて", "open_path", target="ダウンロード")
    check(b, "github.comを開いて", "open_url", url="github.com")
    check(b, "東京駅までナビして", "navigate", destination="東京駅")
    check(b, "画面をロックして", "lock_pc")
    check(b, "メモ帳を閉じて", "close_app", name="メモ帳")


def test_compound_and_followup():
    brain, calls = b = make_brain()
    calls.clear()
    brain.handle("YouTubeを開いて、猫動画を検索して")
    assert [c[0] for c in calls] == ["open_app", "site_search"], calls
    assert calls[1][1] == {"site": "YouTube", "query": "猫動画"}, calls
    # 聞き返し → 答え
    calls.clear()
    assert "何分" in brain.handle("タイマーかけて")
    brain.handle("3分")
    assert calls and calls[-1] == ("set_timer", {"minutes": 3, "seconds": 0}), calls


def test_memory_and_talk():
    brain, _ = make_brain()
    assert "ゼン" in brain.handle("私の名前はゼンです")
    assert "ゼン" in brain.handle("僕の名前わかる？")
    assert "覚えました" in brain.handle("牛乳を買うって覚えておいて")
    assert "牛乳を買う" in brain.handle("メモを読んで")
    assert "8" in brain.handle("3たす5は")
    assert brain.handle("ありがとう")


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
    print("BRAIN TESTS PASSED")
