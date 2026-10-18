"""ブラウザ操作: URLを開く、サイト内検索、タブ・ページ操作、今のURLの取得。

タブ操作は、Chrome / Edge / Firefox / Brave 共通のキーボードショートカットを
ブラウザのウィンドウを前面に出してから送る方式。
"""
from __future__ import annotations

import time
import urllib.parse
import webbrowser

from .. import sites
from . import registry

BROWSER_TITLES = ("Google Chrome", "Microsoft Edge", "Mozilla Firefox", "Brave", "Opera", "Vivaldi", "Chrome")

SITES_LEGACY = {
    "google": "https://www.google.com/search?q={}",
    "youtube": "https://www.youtube.com/results?search_query={}",
    "map": "https://www.google.com/maps/search/{}",
}


def _focus_browser() -> bool:
    """ブラウザのウィンドウを前面に出す。すでに前面なら何もしない。"""
    try:
        import pygetwindow as gw
    except ImportError:
        return False
    active = gw.getActiveWindow()
    if active and any(b in (active.title or "") for b in BROWSER_TITLES):
        return True
    for w in gw.getAllWindows():
        if w.title and any(b in w.title for b in BROWSER_TITLES):
            try:
                if w.isMinimized:
                    w.restore()
                w.activate()
                time.sleep(0.4)
                return True
            except Exception:  # noqa: BLE001
                continue
    return False


@registry.add("open_url", "ブラウザでURLを開く", {
    "url": {"type": "string", "description": "開くURL"},
}, ["url"])
def open_url(url: str) -> str:
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    webbrowser.open(url)
    return "ページを開きました。"


@registry.add("web_search", "ブラウザで検索結果を開く", {
    "query": {"type": "string", "description": "検索語"},
    "site": {"type": "string", "description": "検索先 (google / youtube / map など)"},
}, ["query"])
def web_search(query: str, site: str = "google") -> str:
    found = sites.find(site) if site else None
    if found and found[3]:
        webbrowser.open(sites.search_url(found, query))
        return f"{found[0]}で「{query}」を検索しました。"
    tmpl = SITES_LEGACY.get(site, SITES_LEGACY["google"])
    webbrowser.open(tmpl.format(urllib.parse.quote(query)))
    return f"「{query}」を検索しました。"


@registry.add("site_search", "サイトの中で検索する (Amazon, 楽天, YouTube, メルカリ, 食べログ など)", {
    "site": {"type": "string"}, "query": {"type": "string"},
}, ["site", "query"])
def site_search(site: str, query: str) -> str:
    found = sites.find(site)
    if not found:
        return f"「{site}」は対応していないサイトです。"
    webbrowser.open(sites.search_url(found, query))
    return f"{found[0]}で「{query}」を検索しました。"


_ACTIONS = {
    "new_tab": (("ctrl", "t"), "新しいタブを開きました。"),
    "close_tab": (("ctrl", "w"), "タブを閉じました。"),
    "next_tab": (("ctrl", "tab"), "次のタブに切り替えました。"),
    "prev_tab": (("ctrl", "shift", "tab"), "前のタブに切り替えました。"),
    "back": (("alt", "left"), "前のページに戻りました。"),
    "forward": (("alt", "right"), "次のページに進みました。"),
    "reload": (("f5",), "再読み込みしました。"),
    "zoom_in": (("ctrl", "+"), "拡大しました。"),
    "zoom_out": (("ctrl", "-"), "縮小しました。"),
    "zoom_reset": (("ctrl", "0"), "表示倍率を戻しました。"),
    "top": (("home",), "ページの一番上に移動しました。"),
    "bottom": (("end",), "ページの一番下に移動しました。"),
    "bookmark": (("ctrl", "d"), "ブックマークの画面を開きました。"),
    "incognito": (("ctrl", "shift", "n"), "シークレットウィンドウを開きました。"),
    "fullscreen": (("f11",), "全画面表示を切り替えました。"),
}


@registry.add("browser_control", "ブラウザのタブやページを操作する", {
    "action": {"type": "string", "enum": list(_ACTIONS) + ["find"]},
    "text": {"type": "string", "description": "action=find の時に探す文字"},
}, ["action"])
def browser_control(action: str, text: str = "") -> str:
    import pyautogui
    if not _focus_browser():
        if action == "new_tab":
            webbrowser.open("https://www.google.com")
            return "ブラウザを開きました。"
        return "ブラウザのウィンドウが見つかりませんでした。"
    if action == "find":
        import pyperclip
        pyautogui.hotkey("ctrl", "f")
        time.sleep(0.3)
        if text:
            pyperclip.copy(text)
            pyautogui.hotkey("ctrl", "v")
            pyautogui.press("enter")
        return f"ページ内で「{text}」を探しました。" if text else "ページ内検索を開きました。"
    keys, msg = _ACTIONS.get(action, (None, None))
    if not keys:
        return "そのブラウザ操作には対応していません。"
    pyautogui.hotkey(*keys)
    return msg


@registry.add("current_url", "ブラウザで今開いているページのURLを調べる")
def current_url() -> str:
    import pyautogui
    import pyperclip
    if not _focus_browser():
        return ""
    old = pyperclip.paste()
    pyperclip.copy("")
    pyautogui.hotkey("ctrl", "l")
    time.sleep(0.15)
    pyautogui.hotkey("ctrl", "c")
    time.sleep(0.2)
    pyautogui.press("esc")
    url = pyperclip.paste().strip()
    pyperclip.copy(old)
    return url if "." in url and " " not in url else ""


@registry.add("navigate", "地図で目的地までの道順を開く", {
    "destination": {"type": "string"},
}, ["destination"])
def navigate(destination: str) -> str:
    webbrowser.open("https://www.google.com/maps/dir/?api=1&destination=" + urllib.parse.quote(destination))
    return f"{destination}までの道順を地図で開きました。"


@registry.add("compose_mail", "メールの作成画面を開く", {
    "to": {"type": "string"}, "subject": {"type": "string"}, "body": {"type": "string"},
})
def compose_mail(to: str = "", subject: str = "", body: str = "") -> str:
    q = urllib.parse.urlencode({k: v for k, v in (("subject", subject), ("body", body)) if v}, quote_via=urllib.parse.quote)
    webbrowser.open(f"mailto:{to if '@' in to else ''}" + (f"?{q}" if q else ""))
    return "メールの作成画面を開きました。"


@registry.add("post_x", "X(旧Twitter)の投稿画面を開く(投稿はユーザーが押す)", {
    "text": {"type": "string"},
}, ["text"])
def post_x(text: str) -> str:
    webbrowser.open("https://x.com/intent/post?text=" + urllib.parse.quote(text))
    return "投稿画面を開きました。内容を確認して投稿してください。"
