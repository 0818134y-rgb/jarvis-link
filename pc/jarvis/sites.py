"""よく使うサイトとサイト内検索URL (Android の Sites.kt と同じ内容)。"""
from __future__ import annotations

import re
import urllib.parse

SITES = [
    ("Google", ["google", "グーグル", "ぐぐる"], "https://www.google.com", "https://www.google.com/search?q=%s"),
    ("YouTube", ["youtube", "ユーチューブ", "ようつべ"], "https://www.youtube.com", "https://www.youtube.com/results?search_query=%s"),
    ("Amazon", ["amazon", "アマゾン"], "https://www.amazon.co.jp", "https://www.amazon.co.jp/s?k=%s"),
    ("楽天", ["楽天", "らくてん", "rakuten"], "https://www.rakuten.co.jp", "https://search.rakuten.co.jp/search/mall/%s/"),
    ("メルカリ", ["メルカリ", "mercari"], "https://jp.mercari.com", "https://jp.mercari.com/search?keyword=%s"),
    ("ヤフー", ["ヤフー", "yahoo", "やふー"], "https://www.yahoo.co.jp", "https://search.yahoo.co.jp/search?p=%s"),
    ("Wikipedia", ["wikipedia", "ウィキペディア", "ウィキ"], "https://ja.wikipedia.org", "https://ja.wikipedia.org/w/index.php?search=%s"),
    ("Googleマップ", ["googleマップ", "グーグルマップ", "地図", "マップ"], "https://www.google.com/maps", "https://www.google.com/maps/search/%s"),
    ("食べログ", ["食べログ", "たべろぐ"], "https://tabelog.com", "https://tabelog.com/rstLst/?sw=%s"),
    ("価格.com", ["価格.com", "価格コム", "かかくコム"], "https://kakaku.com", "https://search.kakaku.com/%s/"),
    ("X", ["ツイッター", "twitter", "エックス"], "https://x.com", "https://x.com/search?q=%s"),
    ("Spotify", ["spotify", "スポティファイ"], "https://open.spotify.com", "https://open.spotify.com/search/%s"),
    ("GitHub", ["github", "ギットハブ"], "https://github.com", "https://github.com/search?q=%s"),
    ("Netflix", ["netflix", "ネットフリックス", "ネトフリ"], "https://www.netflix.com", "https://www.netflix.com/search?q=%s"),
    ("Gmail", ["gmail", "ジーメール", "gメール"], "https://mail.google.com", None),
    ("ChatGPT", ["chatgpt", "チャットgpt"], "https://chatgpt.com", None),
]


def find(text: str):
    t = text.lower()
    pairs = sorted(((a, s) for s in SITES for a in s[1]), key=lambda p: -len(p[0]))
    for alias, site in pairs:
        if alias.lower() in t:
            return site
    return None


def search_url(site, query: str) -> str:
    return (site[3] or site[2]).replace("%s", urllib.parse.quote(query))


def looks_like_domain(s: str) -> bool:
    return bool(re.fullmatch(r"[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(/\S*)?", s.strip()))


def query_of(text: str, site) -> str:
    t = re.sub(r"^(ジャーヴィス|ジャービス|ねえ)[、,\s]*", "", text)
    for a in sorted(site[1], key=len, reverse=True):
        t = re.sub(re.escape(a) + r"(アプリ)?(で|の|から)", "", t, flags=re.IGNORECASE)
    t = re.sub(r"^(を)?開いて", "", t)
    t = re.sub(r"(の値段)?(を|が)?(検索して|検索|探して|調べて|見せて|再生して|見たい|買いたい|聴きたい|流して)[。、！!？?]*$", "", t)
    return t.strip()
