"""画面のアプリを操作する: 項目に番号を振ってクリック、名前でクリック、文字入力、キー操作、画面の読み取り。

外部のAIは使わず、Windows の UI Automation (アプリが公開している画面部品の情報) で操作する。
"""
from __future__ import annotations

import datetime as dt
import time
from pathlib import Path

from . import REMOTE_BLOCKED, registry

CLICKABLE_TYPES = {"Button", "Hyperlink", "MenuItem", "ListItem", "TabItem", "CheckBox", "RadioButton",
                   "TreeItem", "SplitButton", "Edit", "ComboBox", "DataItem"}

_last_items: list = []          # [(label, control)]
_last_kind: str = ""            # "screen" | "files"


def configure(cfg: dict) -> None:  # 旧版との互換用
    pass


def set_numbered_list(items: list, kind: str) -> None:
    """番号で選べる一覧を登録 (画面の項目・ファイル検索結果)。"""
    global _last_items, _last_kind
    _last_items, _last_kind = items, kind


def _foreground():
    from pywinauto import Desktop
    import pygetwindow as gw
    active = gw.getActiveWindow()
    if not active:
        return None
    return Desktop(backend="uia").window(handle=active._hWnd)


def _controls(limit_sec: float = 4.0):
    win = _foreground()
    if win is None:
        return []
    start = time.time()
    out = []
    for c in win.descendants():
        if time.time() - start > limit_sec:
            break
        try:
            if not c.is_visible():
                continue
            out.append(c)
        except Exception:  # noqa: BLE001
            continue
    return out


@registry.add("list_items", "今のウィンドウの押せる項目に番号を振って読み上げる(「3番をクリック」で使う)", risk=REMOTE_BLOCKED)
def list_items(max_items: int = 20) -> str:
    items, seen = [], set()
    for c in _controls():
        try:
            ctype = c.element_info.control_type
            name = (c.window_text() or "").strip()
        except Exception:  # noqa: BLE001
            continue
        if ctype not in CLICKABLE_TYPES:
            continue
        if not name and ctype == "Edit":
            name = "入力欄"
        if not name or name in seen or len(name) > 60:
            continue
        seen.add(name)
        items.append((name, c))
        if len(items) >= max_items:
            break
    set_numbered_list(items, "screen")
    if not items:
        return "押せる項目が見つかりませんでした。"
    return f"{len(items)}個あります。" + "。".join(f"{i}番、{n}" for i, (n, _) in enumerate(items, 1)) + "。"


@registry.add("tap_item", "list_items やファイル検索で読み上げた番号の項目を選ぶ", {
    "number": {"type": "integer"},
}, ["number"], risk=REMOTE_BLOCKED)
def tap_item(number: int) -> str:
    if _last_kind == "files":
        from .files import open_path
        return open_path(str(number))
    if not _last_items:
        list_items()
    if not (1 <= int(number) <= len(_last_items)):
        return f"{number}番の項目がありません。「画面の項目を教えて」で番号を確認してください。"
    name, ctrl = _last_items[int(number) - 1]
    try:
        if ctrl.element_info.control_type == "Edit":
            ctrl.set_focus()
        else:
            ctrl.click_input()
    except Exception as e:  # noqa: BLE001
        return f"{number}番をクリックできませんでした: {e}"
    return f"{number}番、{name}をクリックしました。"


def _find_by_name(target: str):
    key = target.replace(" ", "").lower()
    best = None
    for c in _controls():
        try:
            name = (c.window_text() or "").replace(" ", "").lower()
        except Exception:  # noqa: BLE001
            continue
        if not name:
            continue
        if name == key:
            return c
        if key in name and best is None:
            best = c
    return best


@registry.add("click_on", "画面上のボタンやリンクを名前でクリックする", {
    "target": {"type": "string", "description": "クリックしたいものの表示名 (例: 保存, OK, ログイン)"},
    "double": {"type": "boolean"}, "right": {"type": "boolean", "description": "右クリックするか"},
}, ["target"], risk=REMOTE_BLOCKED)
def click_on(target: str, double: bool = False, right: bool = False) -> str:
    c = _find_by_name(target)
    if c is None:
        return f"画面に「{target}」が見つかりませんでした。「画面の項目を教えて」で番号から選ぶこともできます。"
    try:
        if right:
            c.right_click_input()
        elif double:
            c.double_click_input()
        else:
            c.click_input()
    except Exception as e:  # noqa: BLE001
        return f"クリックできませんでした: {e}"
    return f"「{c.window_text()}」を{'右' if right else ''}クリックしました。"


@registry.add("read_screen", "今のウィンドウに表示されている文字を読み取る", risk=REMOTE_BLOCKED)
def read_screen() -> str:
    win = _foreground()
    if win is None:
        return "アクティブなウィンドウがありません。"
    texts, seen = [], set()
    for c in _controls():
        try:
            t = (c.window_text() or "").strip()
        except Exception:  # noqa: BLE001
            continue
        if t and t not in seen and len(t) < 200:
            seen.add(t)
            texts.append(t)
        if sum(len(x) for x in texts) > 800:
            break
    title = win.window_text()
    return f"「{title}」の画面です。" + ("、".join(texts[:40]) if texts else "文字は読み取れませんでした。")


@registry.add("type_text", "今カーソルがある場所に文字を入力する(日本語可)", {
    "text": {"type": "string"}, "enter": {"type": "boolean", "description": "入力後にEnterを押すか"},
}, ["text"], risk=REMOTE_BLOCKED)
def type_text(text: str, enter: bool = False) -> str:
    import pyautogui
    import pyperclip
    old = None
    try:
        old = pyperclip.paste()
    except Exception:  # noqa: BLE001
        pass
    pyperclip.copy(text)
    pyautogui.hotkey("ctrl", "v")
    time.sleep(0.2)
    if enter:
        pyautogui.press("enter")
    if old is not None:
        time.sleep(0.3)
        pyperclip.copy(old)
    return "入力しました。"


@registry.add("press_keys", "キーボードショートカットやキーを押す", {
    "keys": {"type": "string", "description": "例: ctrl+s, alt+tab, enter, win+shift+s"},
}, ["keys"], risk=REMOTE_BLOCKED)
def press_keys(keys: str) -> str:
    import pyautogui
    parts = [k.strip().lower() for k in keys.replace(" ", "").split("+") if k.strip()]
    alias = {"control": "ctrl", "windows": "win", "escape": "esc", "return": "enter"}
    parts = [alias.get(p, p) for p in parts]
    if len(parts) == 1:
        pyautogui.press(parts[0])
    else:
        pyautogui.hotkey(*parts)
    return f"{keys}を押しました。"


@registry.add("scroll", "画面をスクロールする", {
    "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
    "amount": {"type": "integer"},
}, ["direction"], risk=REMOTE_BLOCKED)
def scroll(direction: str, amount: int = 5) -> str:
    import pyautogui
    if direction in ("left", "right"):
        pyautogui.hscroll(120 * int(amount) * (-1 if direction == "left" else 1))
    else:
        pyautogui.scroll(120 * int(amount) * (1 if direction == "up" else -1))
    return "スクロールしました。"


@registry.add("screenshot", "画面全体のスクリーンショットをピクチャフォルダに保存する")
def screenshot() -> str:
    import mss
    import mss.tools
    folder = Path.home() / "Pictures" / "JARVIS"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"screenshot_{dt.datetime.now():%Y%m%d_%H%M%S}.png"
    with mss.mss() as sct:
        shot = sct.grab(sct.monitors[0])
        mss.tools.to_png(shot.rgb, shot.size, output=str(path))
    return f"スクリーンショットを保存しました。ピクチャのJARVISフォルダにあります。"
