"""アプリ起動・終了、ウィンドウ操作、メディア/音量、システム情報。"""
from __future__ import annotations

import datetime as _dt
import difflib
import os
import subprocess
from pathlib import Path

from . import CONFIRM, SAFE, registry

# よく使うアプリの別名 → 起動コマンド
KNOWN_APPS = {
    "メモ帳": "notepad", "めもちょう": "notepad", "notepad": "notepad",
    "電卓": "calc", "計算機": "calc", "calculator": "calc",
    "エクスプローラー": "explorer", "explorer": "explorer",
    "コマンドプロンプト": "cmd", "cmd": "cmd",
    "パワーシェル": "powershell", "powershell": "powershell",
    "ターミナル": "wt", "terminal": "wt",
    "ペイント": "mspaint", "paint": "mspaint",
    "設定": "ms-settings:", "settings": "ms-settings:",
    "タスクマネージャー": "taskmgr",
    "カメラ": "microsoft.windows.camera:",
}

# 読みが違う有名アプリ (Start Menu のショートカット名に寄せる)
NAME_HINTS = {
    "クローム": "chrome", "グーグルクローム": "chrome", "エッジ": "edge",
    "ファイアフォックス": "firefox", "スポティファイ": "spotify", "ディスコード": "discord",
    "スチーム": "steam", "ビジュアルスタジオコード": "visual studio code", "ブイエスコード": "visual studio code",
    "vscode": "visual studio code", "ユニティ": "unity hub", "ライン": "line",
    "ワード": "word", "エクセル": "excel", "パワーポイント": "powerpoint", "アウトルック": "outlook",
    "ズーム": "zoom", "スラック": "slack", "オブス": "obs studio", "ノーション": "notion",
}


def _norm(s: str) -> str:
    return s.lower().replace(" ", "").replace("　", "")


def _start_menu_shortcuts() -> list[Path]:
    dirs = [
        Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
        Path(os.environ.get("PROGRAMDATA", "C:/ProgramData")) / "Microsoft/Windows/Start Menu/Programs",
        Path.home() / "Desktop",
    ]
    found = []
    for d in dirs:
        if d.is_dir():
            found.extend(d.rglob("*.lnk"))
            found.extend(d.rglob("*.url"))
    return found


def find_shortcut(name: str) -> Path | None:
    target = _norm(NAME_HINTS.get(_norm(name), name))
    shortcuts = _start_menu_shortcuts()
    best, best_score = None, 0.0
    for p in shortcuts:
        stem = _norm(p.stem)
        if "uninstall" in stem or "アンインストール" in stem:
            continue
        if stem == target:
            return p
        score = difflib.SequenceMatcher(None, target, stem).ratio()
        if target in stem:
            score += 0.5
        if score > best_score:
            best, best_score = p, score
    return best if best_score >= 0.6 else None


@registry.add("open_app", "PCのアプリを起動する", {
    "name": {"type": "string", "description": "アプリ名 (例: メモ帳, Chrome, Spotify)"},
}, ["name"])
def open_app(name: str) -> str:
    key = _norm(name)
    if key in {_norm(k) for k in KNOWN_APPS}:
        cmd = next(v for k, v in KNOWN_APPS.items() if _norm(k) == key)
        os.startfile(cmd)  # type: ignore[attr-defined]  (Windows only)
        return f"{name}を起動しました。"
    shortcut = find_shortcut(name)
    if shortcut:
        os.startfile(str(shortcut))  # type: ignore[attr-defined]
        return f"{shortcut.stem}を起動しました。"
    return f"{name}というアプリが見つかりませんでした。"


def _windows_matching(name: str):
    import pygetwindow as gw
    key = _norm(NAME_HINTS.get(_norm(name), name))
    return [w for w in gw.getAllWindows() if w.title and key in _norm(w.title)]


@registry.add("close_app", "PCのアプリのウィンドウを閉じる(未保存なら保存確認が出る)", {
    "name": {"type": "string", "description": "アプリ名またはウィンドウタイトルの一部"},
}, ["name"])
def close_app(name: str) -> str:
    wins = _windows_matching(name)
    if not wins:
        return f"{name}のウィンドウは見つかりませんでした。"
    for w in wins:
        w.close()
    return f"{name}のウィンドウを{len(wins)}個閉じました。"


@registry.add("kill_process", "応答しないアプリを強制終了する", {
    "name": {"type": "string", "description": "プロセス名 (例: chrome.exe)"},
}, ["name"], risk=CONFIRM)
def kill_process(name: str) -> str:
    import psutil
    key = _norm(name).removesuffix(".exe")
    killed = 0
    for p in psutil.process_iter(["name"]):
        pname = _norm(p.info.get("name") or "").removesuffix(".exe")
        if pname == key:
            try:
                p.kill()
                killed += 1
            except psutil.Error:
                pass
    return f"{name}を{killed}個強制終了しました。" if killed else f"{name}は実行されていません。"


@registry.add("window_control", "ウィンドウを前面に出す・最小化・最大化・元に戻す", {
    "action": {"type": "string", "enum": ["focus", "minimize", "maximize", "restore"]},
    "name": {"type": "string", "description": "対象ウィンドウ。省略で現在のウィンドウ"},
}, ["action"])
def window_control(action: str, name: str = "") -> str:
    import pygetwindow as gw
    wins = _windows_matching(name) if name else [gw.getActiveWindow()]
    wins = [w for w in wins if w]
    if not wins:
        return "対象のウィンドウが見つかりませんでした。"
    w = wins[0]
    if action == "focus":
        if w.isMinimized:
            w.restore()
        w.activate()
    elif action == "minimize":
        w.minimize()
    elif action == "maximize":
        w.maximize()
    else:
        w.restore()
    return f"「{w.title}」を{ {'focus': '前面に', 'minimize': '最小化', 'maximize': '最大化', 'restore': '元のサイズに'}[action] }しました。"


@registry.add("show_desktop", "全ウィンドウを最小化してデスクトップを表示する")
def show_desktop() -> str:
    import pyautogui
    pyautogui.hotkey("win", "d")
    return "デスクトップを表示しました。"


@registry.add("media_control", "音楽・動画の再生操作と音量操作", {
    "action": {"type": "string",
               "enum": ["play_pause", "next", "previous", "volume_up", "volume_down", "mute"]},
    "steps": {"type": "integer", "description": "音量を何段階動かすか (1段階=2%)。既定5"},
}, ["action"])
def media_control(action: str, steps: int = 5) -> str:
    import pyautogui
    keys = {"play_pause": "playpause", "next": "nexttrack", "previous": "prevtrack",
            "volume_up": "volumeup", "volume_down": "volumedown", "mute": "volumemute"}
    key = keys.get(action)
    if not key:
        return "不明な操作です。"
    n = max(1, min(int(steps), 50)) if action.startswith("volume_") else 1
    pyautogui.press(key, presses=n)
    return {"play_pause": "再生/一時停止しました。", "next": "次の曲にしました。",
            "previous": "前の曲にしました。", "volume_up": "音量を上げました。",
            "volume_down": "音量を下げました。", "mute": "ミュートを切り替えました。"}[action]


@registry.add("system_info", "現在時刻・日付・バッテリー・CPU・メモリ使用率を調べる")
def system_info() -> str:
    import psutil
    now = _dt.datetime.now()
    parts = [now.strftime("%Y年%m月%d日 %H時%M分"),
             f"CPU {psutil.cpu_percent(interval=0.3):.0f}%",
             f"メモリ {psutil.virtual_memory().percent:.0f}%"]
    bat = psutil.sensors_battery()
    if bat:
        parts.append(f"バッテリー {bat.percent:.0f}%{'(充電中)' if bat.power_plugged else ''}")
    return "、".join(parts)


@registry.add("lock_pc", "PCをロックする")
def lock_pc() -> str:
    import ctypes
    ctypes.windll.user32.LockWorkStation()  # type: ignore[attr-defined]
    return "PCをロックしました。"


@registry.add("sleep_pc", "PCをスリープさせる", risk=CONFIRM)
def sleep_pc() -> str:
    subprocess.Popen(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
    return "スリープします。"


_SETTINGS = {"wifi": "ms-settings:network-wifi", "bluetooth": "ms-settings:bluetooth", "display": "ms-settings:display",
             "sound": "ms-settings:sound", "battery": "ms-settings:batterysaver", "location": "ms-settings:privacy-location",
             "apps": "ms-settings:appsfeatures", "general": "ms-settings:"}


@registry.add("open_settings", "Windowsの設定画面を開く", {
    "page": {"type": "string", "enum": list(_SETTINGS)},
}, ["page"])
def open_settings(page: str = "general") -> str:
    os.startfile(_SETTINGS.get(page, "ms-settings:"))  # type: ignore[attr-defined]
    return "設定を開きました。"
