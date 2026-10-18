"""PCのタイマー・アラーム・Windowsの通知表示。"""
from __future__ import annotations

import datetime as dt
import subprocess
import threading

from . import registry

_speaker = None
_timers: list[threading.Timer] = []


def configure(speaker) -> None:
    global _speaker
    _speaker = speaker


def show_toast(title: str, text: str) -> None:
    """Windows の通知を表示する (追加ライブラリ不要。PowerShell経由)。"""
    def esc(s: str) -> str:
        return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "''").replace("\n", " "))[:200]
    ps = (
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;"
        "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null;"
        "$x = New-Object Windows.Data.Xml.Dom.XmlDocument;"
        f"$x.LoadXml('<toast><visual><binding template=\"ToastGeneric\"><text>{esc(title)}</text><text>{esc(text)}</text></binding></visual></toast>');"
        "$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';"
        "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($x))"
    )
    try:
        subprocess.Popen(["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps],
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except Exception as e:  # noqa: BLE001
        print(f"[toast] {title}: {text} ({e})")


def _ring(message: str) -> None:
    show_toast("JARVIS", message)
    if _speaker:
        _speaker.say(message)
    else:
        print("JARVIS>", message)


@registry.add("set_timer", "PCでタイマーをセットする (時間になると読み上げと通知でお知らせ)", {
    "minutes": {"type": "integer"}, "seconds": {"type": "integer"}, "label": {"type": "string"},
})
def set_timer(minutes: int = 0, seconds: int = 0, label: str = "") -> str:
    total = int(minutes) * 60 + int(seconds)
    if total <= 0:
        return "時間がわかりませんでした。"
    what = f"{label}の" if label else ""
    t = threading.Timer(total, _ring, args=(f"ボス、{what}{_fmt(total)}のタイマーが終わりました。",))
    t.daemon = True
    t.start()
    _timers.append(t)
    return f"{_fmt(total)}のタイマーをセットしました。"


@registry.add("set_alarm", "PCでアラームをセットする (指定時刻に読み上げと通知でお知らせ)", {
    "hour": {"type": "integer"}, "minute": {"type": "integer"}, "label": {"type": "string"},
}, ["hour"])
def set_alarm(hour: int, minute: int = 0, label: str = "") -> str:
    now = dt.datetime.now()
    at = now.replace(hour=int(hour) % 24, minute=int(minute) % 60, second=0, microsecond=0)
    if at <= now:
        at += dt.timedelta(days=1)
    t = threading.Timer((at - now).total_seconds(), _ring, args=(f"ボス、{at:%H時%M分}です。{label}".strip(),))
    t.daemon = True
    t.start()
    _timers.append(t)
    day = "明日の" if at.date() != now.date() else ""
    return f"{day}{at.hour}時{at.minute}分にアラームをセットしました。PCを起動したままにしておいてください。"


@registry.add("cancel_timers", "PCのタイマーとアラームをすべて取り消す")
def cancel_timers() -> str:
    n = sum(1 for t in _timers if t.is_alive())
    for t in _timers:
        t.cancel()
    _timers.clear()
    return f"{n}件のタイマーとアラームを取り消しました。" if n else "動いているタイマーはありません。"


def _fmt(sec: int) -> str:
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return "".join(x for x in (f"{h}時間" if h else "", f"{m}分" if m else "", f"{s}秒" if s else ""))
