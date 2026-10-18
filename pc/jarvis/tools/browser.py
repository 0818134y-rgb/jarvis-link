"""ブラウザ操作: URLを開く、Web/YouTube/地図検索。"""
from __future__ import annotations

import urllib.parse
import webbrowser

from . import registry

SITES = {
    "google": "https://www.google.com/search?q={}",
    "youtube": "https://www.youtube.com/results?search_query={}",
    "map": "https://www.google.com/maps/search/{}",
    "wikipedia": "https://ja.wikipedia.org/w/index.php?search={}",
    "amazon": "https://www.amazon.co.jp/s?k={}",
    "github": "https://github.com/search?q={}",
}


@registry.add("open_url", "ブラウザでURLを開く", {
    "url": {"type": "string", "description": "開くURL"},
}, ["url"])
def open_url(url: str) -> str:
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    webbrowser.open(url)
    return f"{url}を開きました。"


@registry.add("web_search", "ブラウザで検索結果を開く", {
    "query": {"type": "string", "description": "検索語"},
    "site": {"type": "string", "enum": list(SITES), "description": "検索先。既定google"},
}, ["query"])
def web_search(query: str, site: str = "google") -> str:
    tmpl = SITES.get(site, SITES["google"])
    webbrowser.open(tmpl.format(urllib.parse.quote(query)))
    return f"{site}で「{query}」を検索しました。"
