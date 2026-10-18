"""他の端末(スマホ等)への指示・読み上げ・クリップボード共有。"""
from __future__ import annotations

from . import registry

_link = None
_speaker = None


def configure(link, speaker) -> None:
    global _link, _speaker
    _link, _speaker = link, speaker


def _need_link():
    if _link is None:
        raise RuntimeError("端末連携が無効です")
    return _link


@registry.add("list_devices", "連携できる他の端末の一覧を調べる")
def list_devices() -> str:
    peers = _need_link().list_peers()
    if not peers:
        return "連携できる端末はまだ見つかっていません。"
    return "、".join(f"{p.name}({p.platform}, {'同じWi-Fi' if p.source == 'mdns' else '手動接続'})"
                    for p in peers)


@registry.add("send_to_device", "他の端末(スマホ等)に自然文の指示を送って実行させる。"
              "例: スマホのライトをつける、スマホでタイマーをセット", {
                  "device": {"type": "string", "description": "端末名または種類 (例: phone, スマホ)"},
                  "command": {"type": "string", "description": "その端末で実行させる指示 (例: ライトをつけて)"},
              }, ["device", "command"])
def send_to_device(device: str, command: str) -> str:
    res = _need_link().request(device, {"type": "command", "text": command})
    prefix = "" if res.get("ok") else "失敗: "
    return f"{device}からの返答: {prefix}{res.get('text', '')}"


@registry.add("speak_on_device", "他の端末で文章を読み上げさせる(呼び出し・伝言)", {
    "device": {"type": "string", "description": "端末名または種類"},
    "text": {"type": "string", "description": "読み上げる文章"},
}, ["device", "text"])
def speak_on_device(device: str, text: str) -> str:
    res = _need_link().request(device, {"type": "speak", "text": text}, timeout=30)
    return f"{device}で読み上げました。" if res.get("ok") else f"失敗: {res.get('text')}"


@registry.add("send_clipboard", "PCのクリップボードの内容を他の端末のクリップボードへ送る", {
    "device": {"type": "string", "description": "端末名または種類"},
}, ["device"])
def send_clipboard(device: str) -> str:
    import pyperclip
    text = pyperclip.paste()
    if not text:
        return "クリップボードが空です。"
    res = _need_link().request(device, {"type": "clipboard", "text": text}, timeout=15)
    return f"{device}にクリップボードを送りました。" if res.get("ok") else f"失敗: {res.get('text')}"


@registry.add("get_clipboard", "このPCのクリップボードの文字を取得する(主に他端末からの取り込み用)")
def get_clipboard() -> str:
    import pyperclip
    return pyperclip.paste() or ""


@registry.add("get_device_clipboard", "他の端末のクリップボードの内容をPCのクリップボードへ取り込む", {
    "device": {"type": "string", "description": "端末名または種類"},
}, ["device"])
def get_device_clipboard(device: str) -> str:
    import pyperclip
    res = _need_link().request(device, {"type": "tool", "name": "get_clipboard", "args": {}}, timeout=15)
    if not res.get("ok"):
        return f"失敗: {res.get('text')}"
    pyperclip.copy(res.get("text", ""))
    return "取り込みました。"
