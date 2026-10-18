"""JARVIS Link: 端末間連携 (protocol/PROTOCOL.md 準拠)。

* 自分の WebSocket サーバーを立てて他端末からの依頼を受ける
* mDNS で同一Wi-Fi内の端末を自動発見、手動ピア(Tailscale等)も扱う
* 他端末への依頼は 接続→相互認証→1リクエスト→切断 の使い捨て接続
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import secrets
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Callable

PROTOCOL_VERSION = 1
SERVICE_TYPE = "_jarvislink._tcp.local."
PLATFORM = "windows"

PLATFORM_ALIASES = {
    "android": ["スマホ", "スマートフォン", "携帯", "けいたい", "アンドロイド", "android", "phone",
                "スマフォ", "モバイル"],
    "windows": ["pc", "パソコン", "ピーシー", "コンピューター", "コンピュータ", "windows", "デスクトップ"],
}


def mac_of(token: str, nonce: str) -> str:
    return hmac.new(token.encode("utf-8"), nonce.encode("utf-8"), hashlib.sha256).hexdigest()


def _dumps(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False)


class LinkError(Exception):
    pass


@dataclass
class Peer:
    name: str
    platform: str
    host: str
    port: int
    source: str  # "mdns" | "manual"
    seen: float = 0.0

    @property
    def uri(self) -> str:
        host = f"[{self.host}]" if ":" in self.host and not self.host.startswith("[") else self.host
        return f"ws://{host}:{self.port}"


def _local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class Link:
    def __init__(self, cfg: dict, dispatch: Callable[[dict, str], dict]):
        """dispatch(request, peer_name) -> {"ok": bool, "text": str} は別スレッドから呼ばれる。"""
        self.name: str = cfg["device"]["name"]
        self.aliases: list[str] = cfg["device"].get("aliases", [])
        lc = cfg["link"]
        self.port: int = int(lc["port"])
        self.token: str = lc["token"]
        self.discovery: bool = bool(lc.get("discovery", True))
        self.manual: list[str] = list(lc.get("peers") or [])
        self.dispatch = dispatch
        self.peers: dict[str, Peer] = {}
        self._lock = threading.Lock()
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True, name="jarvis-link")
        self._zc = None
        self._fail_until: dict[str, float] = {}

    # ---------- 起動 ----------
    def start(self) -> None:
        self._thread.start()
        fut = asyncio.run_coroutine_threadsafe(self._start_server(), self._loop)
        fut.result(timeout=10)
        print(f"[link] server listening on 0.0.0.0:{self.port} as '{self.name}'")
        if self.discovery:
            try:
                self._start_mdns()
            except Exception as e:  # noqa: BLE001
                print(f"[link] mDNS unavailable: {e}")
        if self.manual:
            threading.Thread(target=self.refresh_manual, daemon=True).start()

    def stop(self) -> None:
        if self._zc:
            try:
                self._zc.unregister_all_services()
                self._zc.close()
            except Exception:  # noqa: BLE001
                pass
        self._loop.call_soon_threadsafe(self._loop.stop)

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    async def _start_server(self) -> None:
        from websockets.asyncio.server import serve
        self._server = await serve(self._handle, "0.0.0.0", self.port, max_size=2 ** 22)

    # ---------- mDNS ----------
    def _start_mdns(self) -> None:
        from zeroconf import ServiceBrowser, ServiceInfo, ServiceStateChange, Zeroconf

        self._zc = Zeroconf()
        ip = _local_ip()
        info = ServiceInfo(
            SERVICE_TYPE,
            f"{self.name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=self.port,
            properties={"name": self.name, "platform": PLATFORM, "v": str(PROTOCOL_VERSION)},
            server=f"jarvis-{self.name}.local.",
        )
        self._zc.register_service(info, allow_name_change=True)

        def on_change(zeroconf, service_type, name, state_change):
            if state_change is ServiceStateChange.Removed:
                with self._lock:
                    for k, p in list(self.peers.items()):
                        if p.source == "mdns" and name.startswith(p.name + "."):
                            del self.peers[k]
                return
            si = zeroconf.get_service_info(service_type, name, timeout=3000)
            if not si:
                return
            props = {k.decode(): (v.decode() if isinstance(v, bytes) else v)
                     for k, v in (si.properties or {}).items()}
            pname = props.get("name") or name.split(".")[0]
            if pname == self.name:
                return
            addrs = si.parsed_addresses()
            if not addrs:
                return
            v4 = [a for a in addrs if ":" not in a]
            peer = Peer(pname, props.get("platform", "unknown"), (v4 or addrs)[0], si.port, "mdns", time.time())
            with self._lock:
                self.peers[pname.lower()] = peer
            print(f"[link] found {peer.name} ({peer.platform}) at {peer.host}:{peer.port}")

        self._browser = ServiceBrowser(self._zc, SERVICE_TYPE, handlers=[on_change])
        print(f"[link] mDNS registered ({ip})")

    # ---------- 手動ピア ----------
    def refresh_manual(self) -> None:
        for entry in self.manual:
            host, _, port = entry.strip().rpartition(":")
            if not host:
                host, port = entry.strip(), str(self.port)
            try:
                info = self.probe(host, int(port))
            except Exception as e:  # noqa: BLE001
                print(f"[link] manual peer {entry} unreachable: {e}")
                continue
            dev = info.get("device", {})
            pname = dev.get("name", host)
            with self._lock:
                self.peers[pname.lower()] = Peer(pname, dev.get("platform", "unknown"),
                                                 host, int(port), "manual", time.time())
            print(f"[link] manual peer {pname} ({dev.get('platform')}) at {host}:{port}")

    def probe(self, host: str, port: int) -> dict:
        return self._run(self._probe(host, port), timeout=8)

    async def _probe(self, host: str, port: int) -> dict:
        from websockets.asyncio.client import connect
        peer = Peer("?", "?", host, port, "manual")
        async with connect(peer.uri, open_timeout=5, proxy=None) as ws:
            return json.loads(await asyncio.wait_for(ws.recv(), 5))

    # ---------- 名前解決 ----------
    def list_peers(self) -> list[Peer]:
        with self._lock:
            return list(self.peers.values())

    def resolve(self, target: str) -> Peer | None:
        t = target.strip().lower()
        with self._lock:
            peers = list(self.peers.values())
        for p in peers:
            if p.name.lower() == t:
                return p
        for platform, words in PLATFORM_ALIASES.items():
            if any(w.lower() in t for w in words):
                cands = [p for p in peers if p.platform == platform]
                if cands:
                    return max(cands, key=lambda p: p.seen)
        for p in peers:
            if p.name.lower() in t or t in p.name.lower():
                return p
        return None

    # ---------- 送信 ----------
    def request(self, target: str, payload: dict, timeout: float = 90) -> dict:
        peer = self.resolve(target)
        if peer is None and self.manual:
            self.refresh_manual()
            peer = self.resolve(target)
        if peer is None:
            known = ", ".join(p.name for p in self.list_peers()) or "なし"
            raise LinkError(f"端末「{target}」が見つかりません(見つかっている端末: {known})")
        return self._run(self._request(peer, payload, timeout), timeout=timeout + 15)

    def _run(self, coro, timeout: float):
        if threading.current_thread() is self._thread:
            raise RuntimeError("Link.request must not be called from the link thread")
        return asyncio.run_coroutine_threadsafe(coro, self._loop).result(timeout=timeout)

    async def _request(self, peer: Peer, payload: dict, timeout: float) -> dict:
        from websockets.asyncio.client import connect
        payload = dict(payload)
        payload.setdefault("id", uuid.uuid4().hex[:12])
        async with connect(peer.uri, open_timeout=6, max_size=2 ** 22, proxy=None) as ws:
            ch = json.loads(await asyncio.wait_for(ws.recv(), 6))
            if ch.get("type") != "challenge":
                raise LinkError("相手がJARVIS Linkではありません")
            cnonce = secrets.token_hex(16)
            await ws.send(_dumps({
                "type": "auth", "mac": mac_of(self.token, ch["nonce"]), "cnonce": cnonce,
                "device": {"name": self.name, "platform": PLATFORM},
            }))
            ok = json.loads(await asyncio.wait_for(ws.recv(), 6))
            if ok.get("type") != "auth_ok" or not hmac.compare_digest(
                    str(ok.get("mac", "")), mac_of(self.token, cnonce)):
                raise LinkError("認証に失敗しました(トークンが一致しているか確認してください)")
            await ws.send(_dumps(payload))
            while True:
                res = json.loads(await asyncio.wait_for(ws.recv(), timeout))
                if res.get("type") == "result" and res.get("id") == payload["id"]:
                    return res

    # ---------- 受信 ----------
    async def _handle(self, ws) -> None:
        from websockets.exceptions import ConnectionClosed
        ip = ws.remote_address[0] if ws.remote_address else "?"
        if self._fail_until.get(ip, 0) > time.time():
            await ws.close()
            return
        nonce = secrets.token_hex(16)
        try:
            await ws.send(_dumps({"type": "challenge", "v": PROTOCOL_VERSION, "nonce": nonce,
                                  "device": {"name": self.name, "platform": PLATFORM}}))
            msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if msg.get("type") != "auth" or not hmac.compare_digest(
                    str(msg.get("mac", "")), mac_of(self.token, nonce)):
                self._fail_until[ip] = time.time() + 3
                print(f"[link] auth failed from {ip}")
                await ws.send(_dumps({"type": "auth_fail"}))
                return
            peer_name = str(msg.get("device", {}).get("name", ip))
            await ws.send(_dumps({"type": "auth_ok", "mac": mac_of(self.token, str(msg.get("cnonce", "")))}))
            loop = asyncio.get_running_loop()
            async for raw in ws:
                req = json.loads(raw)
                rid = req.get("id", "")
                try:
                    res = await loop.run_in_executor(None, self.dispatch, req, peer_name)
                except Exception as e:  # noqa: BLE001
                    res = {"ok": False, "text": f"エラー: {e}"}
                await ws.send(_dumps({"type": "result", "id": rid, "ok": bool(res.get("ok")),
                                      "text": str(res.get("text", ""))}))
        except (ConnectionClosed, asyncio.TimeoutError):
            pass  # probe や切断は正常
        except json.JSONDecodeError:
            pass
