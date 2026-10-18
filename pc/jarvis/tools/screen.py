"""画面を見て操作する: 画面の説明、ボタン等のクリック、文字入力、キー操作。

クリックは2段構え:
  1. Windows UI Automation でアクティブウィンドウ内の部品を名前で探す (速く正確)
  2. 見つからなければスクリーンショットをビジョンモデル(qwen2.5vl)に渡して座標を推定
"""
from __future__ import annotations

import base64
import io
import json
import re
import time

import requests

from . import REMOTE_BLOCKED, registry

_cfg: dict = {}

# qwen2.5-VL は 28px 単位で画像を扱う。こちらで 28 の倍数に縮小しておけば
# モデル側で再リサイズされず、返ってくる座標をそのまま元画面に戻せる。
VISION_W, VISION_H = 1288, 728


def configure(cfg: dict) -> None:
    _cfg.update(cfg.get("llm", {}))


def _screenshot():
    import mss
    from PIL import Image
    with mss.mss() as sct:
        mon = sct.monitors[1]  # メインモニター
        raw = sct.grab(mon)
        img = Image.frombytes("RGB", raw.size, raw.rgb)
    return img, mon


def _to_b64(img) -> str:
    small = img.resize((VISION_W, VISION_H))
    buf = io.BytesIO()
    small.save(buf, format="JPEG", quality=85)
    return base64.b64encode(buf.getvalue()).decode()


def _vision(prompt: str, img) -> str:
    r = requests.post(f"{_cfg.get('host', 'http://localhost:11434')}/api/chat", json={
        "model": _cfg.get("vision_model", "qwen2.5vl:7b"),
        "stream": False,
        "options": {"temperature": 0},
        "messages": [{"role": "user", "content": prompt, "images": [_to_b64(img)]}],
    }, timeout=180)
    r.raise_for_status()
    return r.json()["message"]["content"]


@registry.add("describe_screen", "今のPC画面に何が表示されているかを見て答える", {
    "question": {"type": "string", "description": "画面について知りたいこと。省略で概要"},
}, risk=REMOTE_BLOCKED)
def describe_screen(question: str = "") -> str:
    img, _ = _screenshot()
    q = question or "この画面に何が表示されているか、日本語で3文以内で要約してください。"
    return _vision(q + "\n日本語で簡潔に答えてください。", img)


def _uia_click(target: str, double: bool) -> str | None:
    try:
        from pywinauto import Desktop
    except ImportError:
        return None
    try:
        import pygetwindow as gw
        active = gw.getActiveWindow()
        if not active:
            return None
        win = Desktop(backend="uia").window(handle=active._hWnd)
        key = target.replace(" ", "").lower()
        start = time.time()
        best = None
        for ctrl in win.descendants():
            name = (ctrl.window_text() or "").replace(" ", "").lower()
            if not name:
                continue
            if name == key:
                best = ctrl
                break
            if key in name and best is None:
                best = ctrl
            if time.time() - start > 4:
                break
        if best is None:
            return None
        if double:
            best.double_click_input()
        else:
            best.click_input()
        return f"「{best.window_text()}」をクリックしました。"
    except Exception:  # noqa: BLE001 - UIAが使えないアプリは視覚フォールバックへ
        return None


def _parse_point(text: str) -> tuple[float, float] | None:
    m = re.search(r"\{[^{}]*\}", text, re.S)
    if m:
        try:
            d = json.loads(m.group(0))
            if "bbox_2d" in d or "bbox" in d:
                x1, y1, x2, y2 = d.get("bbox_2d") or d.get("bbox")
                return (x1 + x2) / 2, (y1 + y2) / 2
            if "x" in d and "y" in d:
                return float(d["x"]), float(d["y"])
        except (ValueError, TypeError, KeyError):
            pass
    nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", text)]
    if len(nums) >= 4:
        return (nums[0] + nums[2]) / 2, (nums[1] + nums[3]) / 2
    if len(nums) >= 2:
        return nums[0], nums[1]
    return None


@registry.add("click_on", "画面上のボタン・リンク・アイコンなどを名前や見た目で指定してクリックする", {
    "target": {"type": "string", "description": "クリックしたいもの (例: 保存ボタン, 右上の×)"},
    "double": {"type": "boolean", "description": "ダブルクリックするか"},
}, ["target"], risk=REMOTE_BLOCKED)
def click_on(target: str, double: bool = False) -> str:
    import pyautogui
    done = _uia_click(target, double)
    if done:
        return done
    img, mon = _screenshot()
    answer = _vision(
        f"画像 ({VISION_W}x{VISION_H}px) の中から「{target}」を探し、その中心座標を"
        ' {"x": 数値, "y": 数値} のJSONだけで答えてください。見つからなければ {"x": -1, "y": -1}。',
        img,
    )
    pt = _parse_point(answer)
    if not pt or pt[0] < 0 or pt[1] < 0:
        return f"画面上に「{target}」が見つかりませんでした。"
    x = mon["left"] + pt[0] * mon["width"] / VISION_W
    y = mon["top"] + pt[1] * mon["height"] / VISION_H
    x = max(mon["left"], min(x, mon["left"] + mon["width"] - 1))
    y = max(mon["top"], min(y, mon["top"] + mon["height"] - 1))
    pyautogui.moveTo(x, y, duration=0.25)
    if double:
        pyautogui.doubleClick()
    else:
        pyautogui.click()
    return f"「{target}」と思われる位置 ({int(x)}, {int(y)}) をクリックしました。"


@registry.add("type_text", "今カーソルがある場所に文字を入力する(日本語可)", {
    "text": {"type": "string", "description": "入力する文字"},
    "enter": {"type": "boolean", "description": "入力後にEnterを押すか"},
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
    "direction": {"type": "string", "enum": ["up", "down"]},
    "amount": {"type": "integer", "description": "量 (既定5)"},
}, ["direction"], risk=REMOTE_BLOCKED)
def scroll(direction: str, amount: int = 5) -> str:
    import pyautogui
    pyautogui.scroll(120 * int(amount) * (1 if direction == "up" else -1))
    return "スクロールしました。"
