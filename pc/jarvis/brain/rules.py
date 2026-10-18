"""日本語の定型パターンから「何をするか」と「引数」を取り出す (Android の Rules.kt と同じ規則)。

PC版では「スマホで〜」が他端末宛て、「パソコンで〜」が自分宛てになる。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

I = re.IGNORECASE
PHONE = r"(?:スマホ|スマートフォン|携帯|けいたい|アンドロイド|android|phone)"


@dataclass
class Match:
    tool: str
    args: dict = field(default_factory=dict)
    fallback: bool = False


_KANJI = {"〇": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def _kanji_to_int(s: str) -> int | None:
    total = cur = 0
    for c in s:
        if c == "百":
            total += (cur or 1) * 100
            cur = 0
        elif c == "十":
            total += (cur or 1) * 10
            cur = 0
        elif c in _KANJI:
            cur = _KANJI[c]
        else:
            return None
    return total + cur


def normalize(raw: str) -> str:
    t = raw.strip().rstrip("。、！？!?. ")
    t = t.replace("ジャーヴィス", "").replace("ジャービス", "").lstrip("、, ")
    t = "".join(chr(ord(c) - 0xFEE0) if "０" <= c <= "９" else c for c in t)
    t = t.replace("一時停止", "ポーズ")
    t = re.sub(r"([〇一二三四五六七八九十百]+)(?=\s*(?:時間|時|分|秒|%|パーセント))",
               lambda m: str(_kanji_to_int(m.group(1))) if _kanji_to_int(m.group(1)) is not None else m.group(0), t)
    return t


def match(raw: str) -> Match | None:
    t = normalize(raw)
    if not t:
        return None

    # ---- 他端末 (スマホ) 宛て ----
    m = re.match(rf"^{PHONE}(?:に|で)[、,\s]*[「『]?(.+?)[」』]?(?:って|と)(?:読み上げて|伝えて|言って|しゃべって)$", t, I)
    if m:
        return Match("speak_on_device", {"device": "phone", "text": m.group(1)})
    if re.search(rf"^{PHONE}(?:の|から)クリップボード", t, I):
        return Match("get_device_clipboard", {"device": "phone"})
    if re.search(rf"クリップボードを?{PHONE}(?:に|へ)", t, I):
        return Match("send_clipboard", {"device": "phone"})
    m = re.match(rf"^{PHONE}(?:で|に|の|から)[、,\s]*(.+)$", t, I)
    if m:
        return Match("send_to_device", {"device": "phone", "command": m.group(1)})

    # 「パソコンで〜」は自分宛てなので前置きを外す
    s = re.sub(r"^(?:パソコン|pc|ピーシー|このpc)(?:で|の)[、,\s]*", "", t, flags=I)

    # ---- 音量 ----
    if "音量" in s or "ボリューム" in s:
        m = re.search(r"(\d{1,3})\s*(?:%|パーセント)", s)
        if m:
            return Match("set_volume", {"action": "set", "level": int(m.group(1))})
        if re.search(r"上げ|大きく|アップ", s):
            return Match("set_volume", {"action": "up"})
        if re.search(r"下げ|小さく|ダウン", s):
            return Match("set_volume", {"action": "down"})
    if re.fullmatch(r"(?:ミュート|消音)(?:して|に)?", s):
        return Match("set_volume", {"action": "mute"})

    # ---- メディア ----
    if re.search(r"次の曲|スキップ", s):
        return Match("media_control", {"action": "next"})
    if re.search(r"前の曲", s):
        return Match("media_control", {"action": "previous"})
    if re.fullmatch(r"(?:音楽|曲|動画)?を?(?:止めて|ポーズ|ストップ)(?:して)?", s):
        return Match("media_control", {"action": "pause"})
    if re.fullmatch(r"(?:音楽|曲|動画)?を?(?:再生|再開)(?:して)?", s):
        return Match("media_control", {"action": "play"})

    # ---- タイマー ----
    if "タイマー" in s:
        h = int(m.group(1)) if (m := re.search(r"(\d+)\s*時間", s)) else 0
        mi = int(m.group(1)) if (m := re.search(r"(\d+)\s*分", s)) else 0
        se = int(m.group(1)) if (m := re.search(r"(\d+)\s*秒", s)) else 0
        if h + mi + se > 0:
            return Match("set_timer", {"minutes": h * 60 + mi, "seconds": se})

    # ---- アラーム ----
    m = re.search(r"(午前|午後|朝|夜)?\s*(\d{1,2})\s*時\s*(?:(\d{1,2})\s*分|(半))?.*(?:アラーム|起こして)", s)
    if m:
        hour = int(m.group(2))
        if m.group(1) in ("午後", "夜") and hour < 12:
            hour += 12
        minute = int(m.group(3)) if m.group(3) else (30 if m.group(4) else 0)
        return Match("set_alarm", {"hour": hour, "minute": minute})

    # ---- 画面操作 ----
    if re.fullmatch(r"(?:戻って|戻る|バック|1つ前に戻って)", s):
        return Match("go_back")
    if re.search(r"^(?:下|した)に?スクロール", s):
        return Match("scroll", {"direction": "down"})
    if re.search(r"^(?:上|うえ)に?スクロール", s):
        return Match("scroll", {"direction": "up"})
    if re.search(r"スクショ|スクリーンショット", s):
        return Match("screenshot")

    # ---- ナビ・電話 ----
    m = re.search(r"^(.+?)(?:まで|へ)の?(?:ナビ|道案内|案内|行き方)", s)
    if m:
        return Match("navigate", {"destination": m.group(1)})
    m = re.fullmatch(r"(.+?)に電話(?:して|をかけて|かけて)?", s)
    if m:
        return Match("call", {"target": m.group(1)}, True)

    # ---- タップ ----
    m = re.fullmatch(r"(.+?)(?:を|って)?(?:タップ|押して|クリック)(?:して)?", s)
    if m:
        return Match("click_on", {"target": m.group(1).removesuffix("ボタン")}, True)

    # ---- 検索 ----
    m = re.search(r"^(?:ユーチューブ|youtube)で(.+?)を?(?:検索|探して|調べて|再生|見せて)", s, I)
    if m:
        return Match("web_search", {"query": m.group(1), "site": "youtube"})
    m = re.fullmatch(r"(.+?)(?:を|で|って|について)?(?:検索して|検索|調べて|ググって)", s)
    if m:
        return Match("web_search", {"query": m.group(1)})

    # ---- アプリ起動 ----
    m = re.fullmatch(r"(.+?)(?:を|の)?(?:開いて|ひらいて|起動して|起動|立ち上げて|開けて|つけて)", s)
    if m:
        name = m.group(1).removesuffix("アプリ").removesuffix("の").strip()
        if name:
            return Match("open_app", {"name": name}, True)
    return None
