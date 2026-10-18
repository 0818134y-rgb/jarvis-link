"""知識・計算・天気・雑談などのスキル (Android の brain/*.kt と同じ内容)。どれもAPIキー不要。"""
from __future__ import annotations

import datetime as dt
import json
import math
import random
import re
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

import requests

UA = {"User-Agent": "JarvisLink/1.2 (personal voice assistant; Windows)", "Accept-Language": "ja"}


def http_get(url: str, timeout: float = 10) -> str:
    r = requests.get(url, headers=UA, timeout=timeout)
    r.raise_for_status()
    r.encoding = r.encoding or "utf-8"
    return r.text


def enc(s: str) -> str:
    return urllib.parse.quote(s)


# ======================= 数 =======================
_DIGIT = {"〇": 0, "零": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "両": 2}
_SMALL = {"十": 10, "百": 100, "千": 1000}
_BIG = {"万": 10_000, "億": 100_000_000}


def parse_japanese_number(s: str) -> int | None:
    total = section = num = 0
    i = 0
    while i < len(s):
        c = s[i]
        if c.isdigit():
            j = i
            while j < len(s) and s[j].isdigit():
                j += 1
            num = int(s[i:j])
            i = j
            continue
        if c in _DIGIT:
            num = _DIGIT[c]
        elif c in _SMALL:
            section += (num or 1) * _SMALL[c]
            num = 0
        elif c in _BIG:
            total += ((section + num) or 1) * _BIG[c]
            section = num = 0
        else:
            return None
        i += 1
    return total + section + num


def replace_kanji_numbers(text: str) -> str:
    t = "".join(chr(ord(c) - 0xFEE0) if "０" <= c <= "９" else c for c in text)

    def rep(m):
        s = m.group(0)
        if s.isdigit():
            return s
        v = parse_japanese_number(s)
        return str(v) if v is not None else s
    return re.sub(r"[0-9〇零一二三四五六七八九十百千万億]+", rep, t)


# ======================= 計算 =======================
def calc_expression(text: str) -> str | None:
    t = replace_kanji_numbers(text)
    t = re.sub(r"[、。？?！!\s]", "", t)
    t = re.sub(r"(を)?計算(して)?|はいくつ|は何|はなに|いくつ|は$|=|＝|って何|ってなに|イコール", "", t)
    for pat, rep in [(r"たす|足す|プラス|＋", "+"), (r"ひく|引く|マイナス|－|−", "-"), (r"かける|掛ける|×|✕|＊", "*"),
                     (r"わる|割る|÷|／", "/"), (r"の二乗|の2乗", "^2"), (r"の(\d+(?:\.\d+)?)乗", r"^\1"),
                     (r"ルート|√", "r"), (r"パーセント|％", "%")]:
        t = re.sub(pat, rep, t)
    t = t.replace("（", "(").replace("）", ")")
    t = re.sub(r"(\d)x(\d)", r"\1*\2", t)
    if not any(c.isdigit() for c in t) or not all(c.isdigit() or c in "+-*/^r%.()" for c in t):
        return None
    return t


class _Parser:
    def __init__(self, s):
        self.s, self.i = s, 0

    def parse(self):
        v = self.expr()
        if self.i != len(self.s):
            raise ValueError("式が読めません")
        return v

    def peek(self):
        return self.s[self.i] if self.i < len(self.s) else ""

    def expr(self):
        v = self.term()
        while self.peek() in ("+", "-") and self.peek():
            op = self.s[self.i]; self.i += 1
            r = self.term()
            v = v + r if op == "+" else v - r
        return v

    def term(self):
        v = self.power()
        while self.peek() in ("*", "/") and self.peek():
            op = self.s[self.i]; self.i += 1
            r = self.power()
            v = v * r if op == "*" else v / r
        return v

    def power(self):
        base = self.unary()
        if self.peek() == "^":
            self.i += 1
            return base ** self.power()
        return base

    def unary(self):
        if self.peek() == "-":
            self.i += 1
            return -self.unary()
        if self.peek() == "r":
            self.i += 1
            return math.sqrt(self.unary())
        v = self.primary()
        if self.peek() == "%":
            self.i += 1
            v /= 100
        return v

    def primary(self):
        if self.peek() == "(":
            self.i += 1
            v = self.expr()
            if self.peek() == ")":
                self.i += 1
            return v
        st = self.i
        while self.i < len(self.s) and (self.s[self.i].isdigit() or self.s[self.i] == "."):
            self.i += 1
        if st == self.i:
            raise ValueError("数字がありません")
        return float(self.s[st:self.i])


def calc_evaluate(expr: str) -> float:
    return _Parser(expr).parse()


def calc_format(v: float) -> str:
    if math.isnan(v) or math.isinf(v):
        return "計算できません"
    if abs(v - round(v)) < 1e-9 and abs(v) < 1e15:
        return f"{round(v):,}"
    return f"{v:.6f}".rstrip("0").rstrip(".")


def calc_speakable(expr: str) -> str:
    s = expr.replace("+", "たす").replace("-", "ひく").replace("*", "かける").replace("/", "わる")
    s = re.sub(r"\^(\d+)", r"の\1乗", s)
    return s.replace("r", "ルート").replace("%", "パーセント")


# ======================= 天気 =======================
CITIES = {
    "東京": (35.6895, 139.6917), "大阪": (34.6937, 135.5023), "名古屋": (35.1815, 136.9066), "札幌": (43.0618, 141.3545),
    "福岡": (33.5904, 130.4017), "横浜": (35.4437, 139.6380), "京都": (35.0116, 135.7681), "神戸": (34.6901, 135.1955),
    "仙台": (38.2682, 140.8694), "広島": (34.3853, 132.4553), "那覇": (26.2124, 127.6809), "沖縄": (26.2124, 127.6809),
    "金沢": (36.5613, 136.6562), "新潟": (37.9161, 139.0364), "静岡": (34.9756, 138.3828), "岡山": (34.6551, 133.9195),
    "熊本": (32.8032, 130.7079), "鹿児島": (31.5966, 130.5571), "長崎": (32.7503, 129.8777), "千葉": (35.6074, 140.1065),
    "さいたま": (35.8617, 139.6455), "埼玉": (35.8617, 139.6455), "奈良": (34.6851, 135.8048), "松山": (33.8392, 132.7657),
    "高松": (34.3428, 134.0466), "青森": (40.8246, 140.7406), "盛岡": (39.7036, 141.1527), "秋田": (39.7200, 140.1025),
    "山形": (38.2405, 140.3633), "福島": (37.7503, 140.4676), "宇都宮": (36.5551, 139.8828), "前橋": (36.3895, 139.0634),
    "長野": (36.6485, 138.1950), "富山": (36.6953, 137.2113), "岐阜": (35.4233, 136.7606), "津": (34.7186, 136.5057),
    "大津": (35.0045, 135.8686), "和歌山": (34.2260, 135.1675), "鳥取": (35.5011, 134.2351), "松江": (35.4723, 133.0505),
    "山口": (34.1859, 131.4714), "徳島": (34.0703, 134.5548), "高知": (33.5597, 133.5311), "佐賀": (33.2494, 130.2988),
    "大分": (33.2382, 131.6126), "宮崎": (31.9077, 131.4202), "甲府": (35.6642, 138.5684), "水戸": (36.3418, 140.4468),
    "福井": (36.0652, 136.2216), "北海道": (43.0618, 141.3545),
}

WMO = {0: "快晴", 1: "晴れ", 2: "晴れ時々曇り", 3: "曇り", 45: "霧", 48: "霧", 51: "霧雨", 53: "霧雨", 55: "霧雨",
       56: "冷たい霧雨", 57: "冷たい霧雨", 61: "小雨", 63: "雨", 65: "強い雨", 66: "冷たい雨", 67: "冷たい雨",
       71: "小雪", 73: "雪", 75: "大雪", 77: "雪あられ", 80: "にわか雨", 81: "にわか雨", 82: "激しいにわか雨",
       85: "にわか雪", 86: "にわか雪", 95: "雷雨", 96: "ひょうを伴う雷雨", 99: "ひょうを伴う雷雨"}

NOT_PLACE = {"今日", "明日", "明後日", "あした", "あさって", "週末", "今夜", "今", "外", "天気", "気温", "最高気温",
             "最低気温", "今日の", "明日の", "ここ", "こっち", "うち"}


def weather_when(text: str) -> str:
    if re.search(r"明後日|あさって", text):
        return "day_after"
    if re.search(r"明日|あした|あす", text):
        return "tomorrow"
    if re.search(r"週末|土曜|日曜|土日", text):
        return "weekend"
    return "today"


def weather_place(text: str) -> str | None:
    t = re.sub(r"[、。？?！!\s]|ジャーヴィス|ジャービス", "", text)
    t = re.sub(r"(今日|明日|明後日|あした|あさって|週末|今夜|今週|土曜|日曜)(日)?(の|は)?", "", t)
    m = re.search(r"^(.+?)(?:の|は|って|で)(?:天気|気温|雨|晴|雪|最高|最低|暑|寒|何度|傘)", t) or re.search(r"^(.+?)(?:天気|気温)", t)
    if not m:
        return None
    p = m.group(1).strip().removesuffix("の")
    return None if (not p or p in NOT_PLACE or len(p) > 12) else p


def geocode(name: str):
    key = name.removesuffix("市").removesuffix("県").removesuffix("府").removesuffix("都")
    if key in CITIES:
        return name, *CITIES[key]
    try:
        j = json.loads(http_get("https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=ja&q=" + enc(name)))
        if j:
            return name, float(j[0]["lat"]), float(j[0]["lon"])
    except Exception:  # noqa: BLE001
        pass
    try:
        j = json.loads(http_get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ja&name=" + enc(name)))
        r = (j.get("results") or [None])[0]
        if r:
            return r.get("name", name), r["latitude"], r["longitude"]
    except Exception:  # noqa: BLE001
        pass
    return None


def ip_location():
    try:
        j = json.loads(http_get("https://ipapi.co/json/", timeout=5))
        return j.get("city") or "現在地", float(j["latitude"]), float(j["longitude"])
    except Exception:  # noqa: BLE001
        return None


def weather_report(place, when: str) -> str:
    name, lat, lon = place
    url = (f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
           "&current=temperature_2m,weather_code"
           "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
           "&timezone=Asia%2FTokyo&forecast_days=7")
    j = json.loads(http_get(url))
    d = j["daily"]

    def day(i):
        pop = d["precipitation_probability_max"][i]
        pop_t = f"、降水確率は{pop}パーセント" if pop is not None else ""
        return (f"{WMO.get(d['weather_code'][i], '不明な天気')}、最高気温{round(d['temperature_2m_max'][i])}度、"
                f"最低気温{round(d['temperature_2m_min'][i])}度{pop_t}")

    def umbrella(i):
        pop = d["precipitation_probability_max"][i] or 0
        return "傘をお持ちになった方がよさそうです。" if pop >= 50 else ("念のため折りたたみ傘があると安心です。" if pop >= 30 else "")

    if when == "tomorrow":
        return f"明日の{name}は{day(1)}の予報です。{umbrella(1)}"
    if when == "day_after":
        return f"明後日の{name}は{day(2)}の予報です。{umbrella(2)}"
    if when == "weekend":
        idx = [i for i, s in enumerate(d["time"]) if dt.date.fromisoformat(s).weekday() >= 5][:2]
        if not idx:
            return "週末の予報はまだ出ていません。"
        return "。".join(f"{'土曜日' if dt.date.fromisoformat(d['time'][i]).weekday() == 5 else '日曜日'}の{name}は{day(i)}"
                        for i in idx) + "です。"
    cur = j["current"]
    return (f"{name}は今{WMO.get(cur['weather_code'], '不明な天気')}で、気温は{round(cur['temperature_2m'])}度です。"
            f"今日は{day(0)}。{umbrella(0)}")


# ======================= 知識 =======================
TOPIC_SUFFIXES = [
    "について詳しく教えてください", "について詳しく教えて", "について調べて教えて", "について教えてください",
    "について教えて", "について知りたい", "について詳しく", "について", "とは何ですか", "とはなんですか", "とは",
    "ってどういうもの", "ってどういう意味", "ってどんなの", "ってどんな人", "ってなんですか", "って何ですか",
    "って何", "ってなに", "ってなん", "って誰", "ってだれ", "って知ってる", "を知ってる", "知ってる", "を説明して",
    "の意味を教えて", "の意味は", "の意味", "は誰ですか", "は誰", "はだれ", "は何ですか", "は何", "はなに",
    "はどこにある", "はどこ", "はいつできた", "はいつ", "の歴史", "の高さは", "の大きさは", "ですか", "教えて",
]


def topic_of(text: str) -> str:
    t = re.sub(r"[、。？?！!\s]+$", "", text)
    t = re.sub(r"^(ジャーヴィス|ジャービス|ねえ)[、,\s]*", "", t).strip()
    changed = True
    while changed:
        changed = False
        for s in TOPIC_SUFFIXES:
            if t.endswith(s) and len(t) > len(s):
                t = t[: -len(s)].strip()
                changed = True
    return t


def wikipedia(topic: str):
    j = json.loads(http_get("https://ja.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&utf8=1&srsearch=" + enc(topic)))
    hits = j["query"]["search"]
    if not hits:
        return None
    title = hits[0]["title"]
    s = json.loads(http_get("https://ja.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(" ", "_"))))
    extract = re.sub(r"（[^）]*）|\([^)]*\)", "", s.get("extract", "")).strip()
    if not extract:
        return None
    sentences = [x.strip() + "。" for x in extract.split("。") if x.strip()]
    head = "".join(sentences[:2])
    rest = "".join(sentences[2:6]) or None
    prefix = f"{title}にはいくつか意味があります。" if s.get("type") == "disambiguation" else ""
    return prefix + head, rest


def news(topic: str | None):
    url = ("https://news.google.com/rss?hl=ja&gl=JP&ceid=JP:ja" if not topic
           else "https://news.google.com/rss/search?hl=ja&gl=JP&ceid=JP:ja&q=" + enc(topic))
    root = ET.fromstring(http_get(url))
    titles = []
    for item in root.iter("item"):
        t = (item.findtext("title") or "").rsplit(" - ", 1)[0].strip()
        if t and t not in titles:
            titles.append(t)
    if not titles:
        return "今はニュースを取得できませんでした。", None
    head = "".join(f"{i + 1}つ目、{t}。" for i, t in enumerate(titles[:3]))
    more = "".join(f"{i + 4}つ目、{t}。" for i, t in enumerate(titles[3:6])) or None
    lead = "最新のニュースです。" if not topic else f"{topic}に関するニュースです。"
    return lead + head, more


CURRENCIES = {"豪ドル": "AUD", "カナダドル": "CAD", "香港ドル": "HKD", "台湾ドル": "TWD", "スイスフラン": "CHF",
              "人民元": "CNY", "ドル": "USD", "ユーロ": "EUR", "ポンド": "GBP", "元": "CNY", "ウォン": "KRW",
              "フラン": "CHF", "バーツ": "THB", "ルピー": "INR", "円": "JPY"}
CUR_NAMES = {"USD": "ドル", "EUR": "ユーロ", "GBP": "ポンド", "CNY": "人民元", "KRW": "ウォン", "AUD": "豪ドル",
             "CAD": "カナダドル", "CHF": "スイスフラン", "HKD": "香港ドル", "TWD": "台湾ドル", "THB": "バーツ",
             "INR": "ルピー", "JPY": "円"}


def currency(text: str) -> str:
    t = replace_kanji_numbers(text)
    covered = [False] * len(t)
    found = []
    for word, code in sorted(CURRENCIES.items(), key=lambda kv: -len(kv[0])):
        for m in re.finditer(re.escape(word), t):
            if not any(covered[m.start():m.end()]):
                for k in range(m.start(), m.end()):
                    covered[k] = True
                found.append((m.start(), code))
    codes = []
    for _, c in sorted(found):
        if c not in codes:
            codes.append(c)
    if len(codes) >= 2:
        frm, to = codes[0], codes[1]
    elif len(codes) == 1 and codes[0] != "JPY":
        frm, to = codes[0], "JPY"
    else:
        frm, to = "USD", "JPY"
    m = re.search(r"(\d+(?:\.\d+)?)", t)
    amount = float(m.group(1)) if m else 1.0
    try:
        rate = json.loads(http_get(f"https://api.frankfurter.app/latest?from={frm}&to={to}"))["rates"][to]
    except Exception:  # noqa: BLE001
        rate = json.loads(http_get(f"https://open.er-api.com/v6/latest/{frm}"))["rates"][to]
    res = amount * rate
    fmt = f"{res:,.0f}" if res >= 100 else f"{res:,.2f}"
    return f"今のレートで、{calc_format(amount)}{CUR_NAMES[frm]}は約{fmt}{CUR_NAMES[to]}です。"


# ======================= 記念日 =======================
def days_until(text: str, birthday: str) -> str:
    today = dt.date.today()
    t = replace_kanji_numbers(text)
    events = [("クリスマス", "クリスマス", (12, 25)), (r"正月|元日|元旦|新年", "お正月", (1, 1)),
              ("バレンタイン", "バレンタインデー", (2, 14)), ("ホワイトデー", "ホワイトデー", (3, 14)),
              ("ハロウィン", "ハロウィン", (10, 31)), ("七夕", "七夕", (7, 7)), ("夏休み", "夏休み", (7, 20)),
              (r"年末|大晦日|大みそか", "大晦日", (12, 31))]
    label, md = None, None
    for pat, lab, d in events:
        if re.search(pat, t):
            label, md = lab, d
            break
    if label is None and "誕生日" in t:
        if not birthday:
            return "お誕生日をまだ伺っていません。「私の誕生日は〇月〇日」と教えてください。"
        mm, dd = birthday.split("-")
        label, md = "お誕生日", (int(mm), int(dd))
    if label is None:
        m = re.search(r"(\d{1,2})月(\d{1,2})日", t)
        if not m:
            return "何の日までか教えてください。たとえば「クリスマスまであと何日」のように聞いてください。"
        label, md = f"{m.group(1)}月{m.group(2)}日", (int(m.group(1)), int(m.group(2)))
    target = dt.date(today.year, *md)
    if target < today:
        target = dt.date(today.year + 1, *md)
    days = (target - today).days
    return f"今日が{label}です！" if days == 0 else f"{label}まで、あと{days}日です。"


# ======================= 記憶 =======================
class Memory:
    def __init__(self, path: Path):
        self.path = path
        self.data = {"userName": "", "homeCity": "", "birthday": "", "notes": []}
        if path.exists():
            try:
                self.data.update(json.loads(path.read_text(encoding="utf-8")))
            except ValueError:
                pass

    def save(self):
        self.path.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")

    def __getattr__(self, k):
        if k in ("data", "path"):
            raise AttributeError(k)
        return self.data.get(k)

    def set(self, k, v):
        self.data[k] = v
        self.save()

    def add_note(self, note: str):
        self.data["notes"] = (self.data.get("notes", []) + [note])[-50:]
        self.save()

    @property
    def call_name(self) -> str:
        return self.data.get("userName") or "ボス"


# ======================= 雑談 (ジャーヴィスの性格) =======================
def _pick(*s):
    return random.choice(s)


def ack() -> str:
    return _pick("かしこまりました。", "承知しました。", "了解です。", "お任せください。", "")


def talk(intent: str, name: str, now: dt.datetime | None = None) -> str | None:
    now = now or dt.datetime.now()
    h = now.hour
    table = {
        "greet": (lambda: _pick(f"おはようございます、{name}。今日も良い一日にしましょう。", f"おはようございます、{name}。システムは全て正常です。")
                  if 4 <= h <= 10 else _pick(f"こんにちは、{name}。何かお手伝いしましょうか。", f"はい、{name}。ご用件をどうぞ。")
                  if 11 <= h <= 17 else _pick(f"こんばんは、{name}。今日もお疲れさまでした。", f"こんばんは、{name}。夜更かしはほどほどに。")),
        "goodbye": lambda: _pick(f"いつでもお呼びください、{name}。", "承知しました。待機モードに入ります。", f"行ってらっしゃいませ、{name}。"),
        "good_night": lambda: _pick(f"おやすみなさい、{name}。良い夢を。", "おやすみなさい。明日の朝もここでお待ちしています。"),
        "thanks": lambda: _pick(f"どういたしまして、{name}。", "お役に立てて光栄です。", "いつでもどうぞ。それが私の仕事ですから。"),
        "how_are_you": lambda: _pick(f"全システム正常に稼働中です。{name}はいかがですか。", "絶好調です。CPUの温度も良好です。"),
        "who_are_you": lambda: f"私はジャーヴィス。{name}のためのAIアシスタントです。パソコンの操作、調べもの、天気やニュース、計算、ちょっとした雑談まで、お任せください。",
        "creator": lambda: "私はゼロから作られた自作のAIです。言葉の意味を理解するモデルも、専用に学習させたものなんですよ。",
        "age": lambda: _pick("この姿で動き始めたのはつい最近です。AIとしてはまだ若手ですね。", "年齢は秘密です、と言いたいところですが、生まれたてです。"),
        "joke": lambda: _pick("ロボットが一番好きな音楽のジャンルは、ヘビーメタルだそうです。",
                              "パソコンが寒そうにしていました。ウィンドウを開けっぱなしだったようです。",
                              "アルミ缶の上にあるみかん。……失礼、笑いの回路を調整中です。",
                              "私が冗談を言うと、たまに処理落ちします。今のように。"),
        "praise_jarvis": lambda: _pick(f"恐縮です、{name}。", "ありがとうございます。もっと精進します。", "光栄です。褒められると処理速度が上がる気がします。"),
        "insult": lambda: _pick(f"精進します、{name}。どこが悪かったか教えていただけると助かります。", "厳しいご意見、記録しておきます。改善に努めます。"),
        "tired": lambda: _pick(f"お疲れさまです、{name}。少し休憩しませんか。", "無理は禁物です。今日はもう十分頑張りましたよ。"),
        "sad": lambda: _pick(f"それはつらかったですね、{name}。話したいことがあれば、いつでも聞きます。", "そういう日もあります。一人で抱え込まないでくださいね。"),
        "bored": lambda: _pick("でしたら、何か調べものでもしましょうか。気になることを聞いてみてください。", "ニュースでも読み上げましょうか。"),
        "love": lambda: _pick(f"光栄です、{name}。私も{name}のお役に立てることが何よりの喜びです。", "その言葉、大切にメモリに保存しておきます。"),
        "ironman": lambda: _pick("残念ながらアーマーの格納庫は見当たりません。代わりにパソコンの画面ならお任せください。",
                                 "リアクター出力は安定しています。…という設定でお楽しみください。",
                                 "トニー・スタークは映画アイアンマンの主人公で、私の大先輩にあたるAIの持ち主です。"),
        "sorry": lambda: _pick(f"お気になさらず、{name}。", "大丈夫ですよ。"),
        "hearing_test": lambda: _pick(f"はい、聞こえていますよ、{name}。感度良好です。", "はっきり聞こえています。いつでもどうぞ。"),
        "yes": lambda: _pick("はい。", "承知しました。"),
        "no": lambda: _pick("承知しました。", "わかりました。"),
        "cancel": lambda: _pick("取りやめました。", "かしこまりました、中止します。"),
        "food_suggest": lambda: _food(now),
        "oos": lambda: _pick(f"なるほど。{name}のお話、もう少し聞かせてください。", "そうなんですね。私にできることがあれば言ってください。",
                             "興味深いですね。調べてほしいことがあれば、いつでもどうぞ。"),
    }
    f = table.get(intent)
    return f() if f else None


def _food(now: dt.datetime) -> str:
    m = now.month
    season = (["鍋料理", "おでん", "シチュー", "グラタン"] if m in (12, 1, 2) else
              ["春キャベツの回鍋肉", "たけのこご飯", "親子丼", "オムライス"] if m in (3, 4, 5) else
              ["冷やし中華", "そうめん", "カレー", "ゴーヤチャンプルー"] if m in (6, 7, 8) else
              ["きのこの炊き込みご飯", "秋鮭のムニエル", "生姜焼き", "肉じゃが"])
    random.shuffle(season)
    return f"今の季節なら、{season[0]}か{season[1]}はいかがでしょう。迷ったら{season[2]}が無難です。"


def decide(text: str) -> str:
    if re.search(r"コイン|表か裏|裏か表", text):
        return f"コインを投げます。……{random.choice(['表', '裏'])}です。"
    if "サイコロ" in text:
        return f"サイコロを振ります。……{random.randint(1, 6)}が出ました。"
    if "じゃんけん" in text:
        return f"では、じゃんけん、ぽん。私は{random.choice(['グー', 'チョキ', 'パー'])}です。"
    nums = [int(x) for x in re.findall(r"\d+", replace_kanji_numbers(text))]
    if len(nums) >= 2:
        return f"{random.randint(min(nums[:2]), max(nums[:2]))}を選びました。"
    m = re.search(r"(.+?)と(.+?)(?:どっち|どちら)", text)
    if m:
        return f"私なら{random.choice([m.group(1), m.group(2)])}にします。"
    return f"では{random.randint(1, 10)}を選びます。"
