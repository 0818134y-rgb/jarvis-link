"""ジャーヴィスの頭脳 PC版 (外部AIを使わない自作エンジン。Android の Brain.kt と同じ考え方)。

1. 意図理解: 自前で学習したモデル (Android と同じモデルファイル) が意図を判定
2. 情報抽出: 「何分」「どのアプリ」「どこの天気」などを取り出す
3. 対話管理: 聞き返し・はい/いいえの確認・直前の話題・連続指示
4. 実行と応答: PCのツールを呼ぶ。スマホにしかできないこと (ライト・電話・写真…) はスマホへ自動で頼む
"""
from __future__ import annotations

import datetime as dt
import re
import time
import traceback
from pathlib import Path
from typing import Callable

import requests

from .. import sites
from ..tools import Registry
from . import rules, skills
from .model import IntentModel

ACTION_INTENTS = {"flashlight", "volume", "media", "music_play", "timer", "alarm", "open_app", "call", "sms", "navigate",
                  "browser_search", "browser_control", "settings", "screenshot", "go_back", "go_home", "recents",
                  "notifications", "lock_screen", "scroll", "tap_text", "type_text", "list_items", "tap_item", "long_press",
                  "swipe", "share_page", "take_photo", "keys", "close_app", "window", "file_search", "calendar_add", "mail",
                  "post_x", "read_screen", "weather", "news", "time", "battery"}
PHONE_ONLY = {"flashlight", "call", "sms", "take_photo", "calendar_add"}
REMOTE_TOOLS = {"send_to_device", "speak_on_device", "get_device_clipboard", "send_clipboard"}
NEWS_TOPICS = ["スポーツ", "経済", "IT", "芸能", "政治", "野球", "サッカー", "テクノロジー", "国際", "AI", "エンタメ",
               "科学", "ビジネス", "株", "ゲーム"]
PC_WORDS = re.compile(r"パソコン|pc|ピーシー|コンピュータ", re.I)
PHONE_WORDS = re.compile(r"スマホ|携帯|スマートフォン|アンドロイド|android", re.I)


class Brain:
    def __init__(self, cfg: dict, registry: Registry, link=None, memory_path: Path | None = None):
        self.cfg = cfg
        self.registry = registry
        self.link = link
        self.model = IntentModel()
        self.memory = skills.Memory(memory_path or Path(__file__).resolve().parent.parent.parent / "memory.json")
        self.allow_remote_dangerous = bool(cfg["link"].get("allow_remote_dangerous", False))
        self.confirm_enabled = bool(cfg.get("safety", {}).get("confirm_dangerous", True))
        self.pending: tuple[str, str] | None = None
        self.confirm_action: Callable[[], str] | None = None
        self.last_intent: str | None = None
        self.last_place: str | None = None
        self.more: str | None = None
        self.last_opened: tuple[str, float] | None = None
        self.last_time = 0.0
        self._confirm = None
        self._on_tool = None

    # ------------------------------------------------------------------
    def handle(self, text: str, origin: str = "local", confirm=None, on_tool=None) -> str:
        """発話を処理して、読み上げる返答を返す。confirm(desc)->bool は危険な操作の音声確認。"""
        self._confirm, self._on_tool = confirm, on_tool
        local = origin == "local"
        if local:
            if time.time() - self.last_time > 180:
                self.pending = self.confirm_action = self.last_intent = self.more = None
            self.last_time = time.time()
        try:
            parts = self._split_compound(text.strip())
            if len(parts) > 1:
                out = []
                for i, p in enumerate(parts):
                    out.append(self._respond(p, origin))
                    if self.pending or self.confirm_action:
                        break
                    if i < len(parts) - 1:
                        time.sleep(1.5 if self.last_intent in ("open_app", "browser_search") else 0.5)
                return " ".join(out)
            return self._respond(text.strip(), origin)
        except requests.RequestException:
            return "ネットワークに接続できませんでした。"
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            return f"申し訳ありません、{self.memory.call_name}。処理中に問題が起きました。"

    # ------------------------------------------------------------------
    def _tool(self, name: str, args: dict | None = None, origin: str = "local") -> str:
        if self._on_tool:
            self._on_tool(name, args or {})
        out = self.registry.call(name, args or {}, origin=origin, allow_remote_dangerous=self.allow_remote_dangerous,
                                 confirm=self._confirm if self.confirm_enabled else None)
        print(f"[tool] {name}({args}) -> {out[:120]}")
        return out

    def _ask(self, intent: str, q: str) -> str:
        self.pending = (intent, q)
        return q

    def _phone(self, text: str, origin: str) -> str:
        if not self.link or not any(p.platform == "android" for p in self.link.list_peers()):
            return "それはスマホの機能です。スマホのJARVISとつながっている時にお試しください。"
        return self._tool("send_to_device", {"device": "phone", "command": text}, origin)

    def _split_compound(self, text: str) -> list[str]:
        parts = [p.strip(" 、,") for p in re.split(r"(?<=て)(?:から)?[、,]\s*|(?<=て)から|[、,]?(?:それから|そのあと|その後|そして|次に)[、,]?", text)]
        parts = [p for p in parts if len(p) >= 2]
        if len(parts) < 2:
            return [text]
        ok = all((lambda r: r[1] >= 0.5 and r[0] in ACTION_INTENTS)(self.model.predict(p, 1)[0]) for p in parts)
        return parts if ok else [text]

    def _merge(self, intent: str, ans: str) -> str | None:
        has_num = re.search(r"\d|[一二三四五六七八九十]", ans)
        return {
            "timer": f"{ans}のタイマー" if has_num else None,
            "alarm": f"{re.sub(r'に$', '', ans)}にアラーム" if has_num else None,
            "open_app": f"{ans}を開いて", "browser_search": f"{ans}を検索して", "wiki": f"{ans}について教えて",
            "calc": ans, "tap_text": f"{ans}をクリックして", "type_text": f"{ans}と入力して", "tap_item": ans,
            "file_search": f"{ans}というファイルを探して", "user_name_tell": f"私の名前は{ans}です",
            "remember_note": f"メモ {ans}", "navigate": f"{ans.removesuffix('まで')}までナビして",
        }.get(intent)

    # ------------------------------------------------------------------
    def _respond(self, text: str, origin: str) -> str:
        local = origin == "local"
        name = self.memory.call_name
        if not skills_norm(text):
            return f"はい、{name}。ご用件をどうぞ。"

        # 「このページをスマホで開いて」
        if re.search(r"このページ|このサイト|今のページ|このURL|見てるページ|開いてるページ", text) and PHONE_WORDS.search(text):
            return self._tool("share_page", {"device": "phone"}, origin)
        r = rules.match(text)
        if r and r.tool in REMOTE_TOOLS:
            if r.tool == "send_to_device" and re.fullmatch(r"(?:を)?(?:探して|鳴らして)", r.args.get("command", "")):
                return self._find_phone(origin)
            return self._tool(r.tool, r.args, origin)

        (intent, prob), *_ = self.model.predict(text, 2)
        print(f"[brain] '{text}' -> {intent} {prob:.2f}")

        if self.confirm_action and local:
            action, self.confirm_action = self.confirm_action, None
            if intent == "yes":
                return action()
            if intent in ("no", "cancel"):
                return "承知しました。"

        if self.pending and local:
            p_intent, _ = self.pending
            self.pending = None
            if intent == "cancel" or (intent == "no" and prob > 0.6):
                return "かしこまりました、取りやめます。"
            new_request = prob >= 0.85 and intent != p_intent and intent not in ("oos", "yes", "wiki")
            if not new_request:
                merged = self._merge(p_intent, text)
                if merged:
                    return self._dispatch(p_intent, merged, origin)

        if re.match(r"^(もっと|続き|つづき|詳しく|くわしく|他には|ほかには)", text) and self.more:
            m, self.more = self.more, None
            return m

        if local and len(skills_norm(text)) <= 10 and prob < 0.8:
            if self.last_intent == "weather" and re.search(r"明日|あした|明後日|あさって|週末|今日|今|は[？?]?$", text) \
                    and intent not in ("yes", "no"):
                return self._dispatch("weather", text, origin)
            if self.last_intent == "news" and re.search(r"は[？?]?$", text):
                return self._dispatch("news", text, origin)

        if prob < 0.35:
            if r and r.tool in self.registry.tools:
                return skills.ack() + self._tool(r.tool, r.args, origin)
            return self._unknown(text, origin)
        return self._dispatch(intent, text, origin)

    # ------------------------------------------------------------------
    def _rule_args(self, text: str, expect: str) -> dict | None:
        r = rules.match(text)
        return r.args if r and r.tool == expect else None

    def _find_phone(self, origin: str) -> str:
        from ..tools.remote import call_phone_tool
        try:
            return call_phone_tool("find_phone", {}, timeout=40)
        except Exception as e:  # noqa: BLE001
            return f"スマホに届きませんでした。{e}"

    def _dispatch(self, intent: str, text: str, origin: str) -> str:
        local = origin == "local"
        name = self.memory.call_name
        if local:
            self.last_intent = intent
        ack = skills.ack()
        T = self._tool

        # ---------------- スマホにしかできないこと ----------------
        if intent in PHONE_ONLY:
            return self._phone(text, origin)
        if intent == "find_phone":
            return self._find_phone(origin)

        # ---------------- PC操作 ----------------
        if intent == "volume":
            a = self._rule_args(text, "set_volume")
            act = a["action"] if a else ("mute" if re.search(r"ミュート|消音|音を消", text) else
                                         "set" if re.search(r"最大|マックス", text) else
                                         "down" if re.search(r"うるさい|静か|小さ|下げ|ダウン", text) else "up")
            if act == "set":
                level = (a or {}).get("level", 100)
                T("media_control", {"action": "volume_down", "steps": 50}, origin)
                return ack + T("media_control", {"action": "volume_up", "steps": max(1, level // 2)}, origin)
            return ack + T("media_control", {"action": {"up": "volume_up", "down": "volume_down", "mute": "mute"}[act]}, origin)
        if intent == "media":
            act = ("next" if re.search(r"次|スキップ|飛ばし", text) else "previous" if re.search(r"前|戻し", text) else "play_pause")
            return ack + T("media_control", {"action": act}, origin)
        if intent == "music_play":
            q = re.sub(r"(の曲|の音楽)?(を|が)?(かけて|流して|再生して|聴きたい|聞きたい).*$", "", re.sub(r"^(ジャーヴィス|ジャービス)[、,\s]*", "", text)).strip()
            if not q or re.fullmatch(r"音楽|曲|何か曲|BGM|作業用の音楽", q):
                return T("media_control", {"action": "play_pause"}, origin)
            return T("site_search", {"site": "YouTube", "query": q}, origin)
        if intent == "timer":
            a = self._rule_args(text, "set_timer")
            return T("set_timer", a, origin) if a else self._ask("timer", "何分のタイマーにしますか？")
        if intent == "alarm":
            a = self._rule_args(text, "set_alarm")
            return T("set_alarm", a, origin) if a else self._ask("alarm", "何時にセットしますか？")
        if intent == "open_app":
            a = self._rule_args(text, "open_app")
            app = (a or {}).get("name", "")
            if not app or app == "アプリ":
                return self._ask("open_app", "どのアプリを開きますか？")
            from ..tools.files import FOLDER_ALIASES
            folder = app.removesuffix("フォルダ").removesuffix("フォルダー")
            if folder in FOLDER_ALIASES:
                return T("open_path", {"target": folder}, origin)
            if sites.looks_like_domain(app):
                return T("open_url", {"url": app}, origin)
            if local:
                self.last_opened = (app, time.time())
            out = T("open_app", {"name": app}, origin)
            site = sites.find(app)
            if "見つかりませんでした" in out and site:
                return T("open_url", {"url": site[2]}, origin)
            return out
        if intent == "navigate":
            a = self._rule_args(text, "navigate")
            if not a:
                m = re.search(r"^(.+?)(?:に連れてって|へのルート|までどうやって)", text)
                a = {"destination": m.group(1)} if m else None
            return T("navigate", a, origin) if a else self._ask("navigate", "どこまでの道順を出しますか？")
        if intent == "browser_search":
            recent = self.last_opened[0] if self.last_opened and time.time() - self.last_opened[1] < 60 else None
            site = sites.find(text) or (sites.find(recent) if recent else None)
            m = re.search(r"^(?:ジャーヴィス[、,\s]*)?(.+?)(?:アプリ)?で(.+?)(?:を|って)?(?:検索|探して|調べて|さがして)", text)
            if not site and m and not re.fullmatch(r"ネット|ブラウザ|ウェブ|web|インターネット|画面|ここ", m.group(1), re.I) \
                    and _is_app(m.group(1)):
                return T("app_search", {"app": m.group(1), "query": m.group(2)}, origin)
            if site:
                q = sites.query_of(text, site)
                return T("site_search", {"site": site[0], "query": q}, origin) if q else T("open_url", {"url": site[2]}, origin)
            a = self._rule_args(text, "web_search")
            return T("web_search", a, origin) if a else self._ask("browser_search", "何を検索しますか？")
        if intent == "browser_control":
            m = re.search(r"ページ内で(.+?)を(?:探|検索)", text)
            if m:
                return T("browser_control", {"action": "find", "text": m.group(1)}, origin)
            act = next((a for pat, a in [(r"新しいタブ", "new_tab"), (r"シークレット", "incognito"), (r"タブを閉じ|ページを閉じ", "close_tab"),
                                         (r"次のタブ|タブを切り替え", "next_tab"), (r"前のタブ", "prev_tab"), (r"戻", "back"),
                                         (r"進", "forward"), (r"再読み込み|更新|リロード", "reload"), (r"拡大|大きく", "zoom_in"),
                                         (r"縮小|小さく", "zoom_out"), (r"一番上|トップ", "top"), (r"一番下", "bottom"),
                                         (r"ブックマーク|お気に入り", "bookmark"), (r"全画面", "fullscreen")]
                        if re.search(pat, text)), None)
            return T("browser_control", {"action": act}, origin) if act else "そのブラウザ操作はまだ覚えていません。"
        if intent == "settings":
            page = next((p for pat, p in [(r"wi-?fi|ワイファイ|無線", "wifi"), (r"bluetooth|ブルートゥース", "bluetooth"),
                                          (r"明るさ|画面|ディスプレイ", "display"), (r"音|サウンド", "sound"),
                                          (r"バッテリー|電池", "battery"), (r"位置", "location")]
                         if re.search(pat, text, re.I)), "general")
            return T("open_settings", {"page": page}, origin)
        if intent == "battery":
            return T("system_info", {}, origin)
        if intent == "screenshot":
            return T("screenshot", {}, origin)
        if intent == "go_back":
            return T("browser_control", {"action": "back"}, origin)
        if intent == "go_home":
            return T("show_desktop", {}, origin)
        if intent == "recents":
            return T("press_keys", {"keys": "win+tab"}, origin)
        if intent == "notifications":
            from ..tools.remote import phone_notifications
            return T("read_phone_notifications", {}, origin) if phone_notifications else T("press_keys", {"keys": "win+n"}, origin)
        if intent == "lock_screen":
            return T("lock_pc", {}, origin)
        if intent == "sleep":
            return T("sleep_pc", {}, origin)
        if intent == "read_screen":
            return T("read_screen", {}, origin)
        if intent == "scroll":
            d = "up" if re.search(r"上|戻", text) else "left" if "左" in text else "right" if "右" in text else "down"
            return T("scroll", {"direction": d}, origin)
        if intent == "tap_text":
            a = self._rule_args(text, "click_on")
            return T("click_on", a, origin) if a else self._ask("tap_text", "どれをクリックしますか？")
        if intent == "list_items":
            return T("list_items", {}, origin)
        if intent == "tap_item":
            m = re.search(r"\d+", skills.replace_kanji_numbers(text))
            return T("tap_item", {"number": int(m.group(0))}, origin) if m else self._ask("tap_item", "何番を選びますか？")
        if intent == "long_press":
            m = re.search(r"^(?:ジャーヴィス[、,\s]*)?(.+?)(?:を)?(?:長押し|押しっぱなし)", text)
            return T("click_on", {"target": m.group(1), "right": True}, origin) if m else "どれを右クリックしますか？"
        if intent == "swipe":
            return T("press_keys", {"keys": "left" if re.search(r"右|前", text) else "right"}, origin)
        if intent == "type_text":
            m = re.search(r"[「『](.+?)[」』]", text) or re.search(r"^(?:.+?欄に)?(.+?)(?:と|って)(?:入力|打って|書いて|入れて)", text)
            return T("type_text", {"text": m.group(1)}, origin) if m else self._ask("type_text", "何と入力しますか？")
        if intent == "keys":
            m = re.search(r"コントロール\s*([a-zA-Zａ-ｚＡ-Ｚ])", text)
            if m:
                return T("press_keys", {"keys": "ctrl+" + m.group(1).lower()}, origin)
            keys = next((k for pat, k in [(r"保存", "ctrl+s"), (r"コピー", "ctrl+c"), (r"貼り付け|ペースト", "ctrl+v"),
                                          (r"切り取", "ctrl+x"), (r"元に戻", "ctrl+z"), (r"やり直", "ctrl+y"),
                                          (r"全部選択|全選択", "ctrl+a"), (r"印刷", "ctrl+p"), (r"エンター|決定|確定", "enter"),
                                          (r"エスケープ|キャンセル", "esc")] if re.search(pat, text)), None)
            return T("press_keys", {"keys": keys}, origin) if keys else "どのキーを押しますか？"
        if intent == "share_page":
            if PHONE_WORDS.search(text):
                return T("share_page", {"device": "phone"}, origin)
            return "このページはもうパソコンで開いていますよ。スマホで開く時は「このページをスマホで開いて」と言ってください。"
        if intent == "file_search":
            if re.search(r"最近|ダウンロードした", text):
                return T("search_files", {"query": "", "folder": "ダウンロード"}, origin)
            q = re.sub(r"^(ジャーヴィス|ジャービス)[、,\s]*", "", text)
            q = re.sub(r"(という|って)?(ファイル)?(を|は|が)?(探して|どこ|見つけて|見せて).*$", "", q).strip()
            q = q.removesuffix("の").removesuffix("ファイル").strip()
            return T("search_files", {"query": q}, origin) if q else self._ask("file_search", "どんな名前のファイルですか？")
        if intent == "window":
            if re.search(r"デスクトップ|全部のウィンドウ|隠して", text):
                return T("show_desktop", {}, origin)
            act = "maximize" if re.search(r"最大|全画面|大きく", text) else "minimize" if re.search(r"最小|小さく", text) else \
                "restore" if re.search(r"元に戻", text) else "focus"
            m = re.search(r"^(.+?)を(?:前に出して|前面|表示)", text)
            return T("window_control", {"action": act, "name": m.group(1) if m else ""}, origin)
        if intent == "close_app":
            m = re.search(r"^(?:ジャーヴィス[、,\s]*)?(.+?)を(?:閉じて|終了|消して)", text)
            app = m.group(1) if m else ""
            if not app or re.fullmatch(r"この(アプリ|ウィンドウ)|アプリ|ウィンドウ", app):
                return T("press_keys", {"keys": "alt+f4"}, origin)
            return T("close_app", {"name": app}, origin)
        if intent == "mail":
            m = re.search(r"[「『](.+?)[」』]", text)
            return T("compose_mail", {"body": m.group(1) if m else ""}, origin)
        if intent == "post_x":
            m = re.search(r"[「『](.+?)[」』]", text) or re.search(r"(?:Xに|ツイッターに|エックスに)?(.+?)(?:と|って)(?:投稿|つぶやいて|ポスト|ツイート)", text, re.I)
            return T("post_x", {"text": m.group(1) if m else ""}, origin)

        # ---------------- 情報 ----------------
        if intent == "time":
            now = dt.datetime.now()
            return f"ただいま{'午前' if now.hour < 12 else '午後'}{now.hour % 12 or 12}時{now.minute}分です。"
        if intent == "date":
            d = dt.date.today()
            return f"今日は{d.month}月{d.day}日 {'月火水木金土日'[d.weekday()]}曜日です。"
        if intent == "days_until":
            return skills.days_until(text, self.memory.birthday or "")
        if intent == "weather":
            when = skills.weather_when(text)
            pname = skills.weather_place(text) or (self.last_place if self.last_intent == "weather" else None)
            if pname:
                place = skills.geocode(pname)
                if not place:
                    return f"「{pname}」という場所が見つかりませんでした。"
            else:
                place = (skills.geocode(self.memory.homeCity) if self.memory.homeCity else None) \
                    or skills.ip_location() or skills.geocode("東京")
            if local:
                self.last_place = pname or self.last_place
            return skills.weather_report(place, when)
        if intent == "news":
            topic = next((t for t in NEWS_TOPICS if t.lower() in text.lower()), None)
            if not topic:
                m = re.search(r"^(.+?)(?:の|で)(?:ニュース|最新情報|結果)", text)
                topic = m.group(1) if m and m.group(1) not in ("今日", "最新", "主な", "面白い") else None
            head, more = skills.news(topic)
            if local:
                self.more = f"続きです。{more}" if more else None
            return head + ("続きを聞きたい時は「続き」と言ってください。" if more and local else "")
        if intent == "wiki":
            topic = skills.topic_of(text)
            if not topic or len(topic) > 30:
                return self._ask("wiki", "何について調べますか？")
            res = skills.wikipedia(topic)
            if not res:
                return self._offer_search(topic, f"{topic}についての情報は見つかりませんでした。")
            if local:
                self.more = res[1]
            return res[0]
        if intent == "calc":
            expr = skills.calc_expression(text)
            if not expr:
                return self._ask("calc", "何を計算しますか？")
            try:
                return f"{skills.calc_speakable(expr)}は、{skills.calc_format(skills.calc_evaluate(expr))}です。"
            except Exception:  # noqa: BLE001
                return "すみません、その式は計算できませんでした。"
        if intent == "currency":
            return skills.currency(text)

        # ---------------- 記憶 ----------------
        if intent == "user_name_tell":
            m = re.search(r"(?:名前は|僕は|私は|俺は|わたしは|ぼくは|おれは)(.+?)(?:です|だよ|だ|って言います|といいます)?$", text) \
                or re.search(r"^(.+?)(?:と|って)呼んで", text)
            if not m:
                return self._ask("user_name_tell", "お名前を教えてください。")
            n = m.group(1).strip().removesuffix("さん")
            self.memory.set("userName", n)
            return f"{n}さんですね。覚えました。これからは{n}さんとお呼びします。"
        if intent == "user_name_ask":
            return f"{self.memory.userName}さんです。忘れるはずがありません。" if self.memory.userName \
                else "まだお名前を伺っていません。「私の名前は〇〇」と教えてください。"
        if intent == "home_tell":
            m = re.search(r"^(?:私は|僕は|家は)?(.+?)(?:に住んで|在住|にある|に住ん)", text)
            if not m:
                return "どちらにお住まいですか？"
            self.memory.set("homeCity", m.group(1))
            return f"{m.group(1)}ですね。天気を聞かれた時の基準にします。"
        if intent == "birthday_tell":
            m = re.search(r"(\d{1,2})月(\d{1,2})日", skills.replace_kanji_numbers(text))
            if not m:
                return "誕生日は何月何日ですか？"
            self.memory.set("birthday", f"{int(m.group(1)):02d}-{int(m.group(2)):02d}")
            return f"{m.group(1)}月{m.group(2)}日ですね。覚えておきます。"
        if intent == "remember_note":
            note = re.sub(r"^(ジャーヴィス|ジャービス)[、,\s]*", "", text)
            note = re.sub(r"^(メモして|メモしておいて|メモ|覚えておいて|覚えて)[:：、,\s]*", "", note)
            note = re.sub(r"(って|と)?(覚えておいて|覚えといて|覚えて|メモしておいて|メモして|メモ)$", "", note).strip()
            if not note or re.fullmatch(r"(メモ|覚え).*", note):
                return self._ask("remember_note", "何を覚えておきましょうか？")
            self.memory.add_note(note)
            return f"「{note}」、覚えました。"
        if intent == "recall_notes":
            facts = []
            if self.memory.userName:
                facts.append(f"お名前は{self.memory.userName}さん")
            if self.memory.homeCity:
                facts.append(f"お住まいは{self.memory.homeCity}")
            if self.memory.birthday:
                mm, dd = self.memory.birthday.split("-")
                facts.append(f"誕生日は{int(mm)}月{int(dd)}日")
            notes = self.memory.notes or []
            if not facts and not notes:
                return "まだ何も覚えていません。「〇〇って覚えておいて」と言ってください。"
            return (("、".join(facts) + "です。") if facts else "") + \
                (f"メモは{len(notes)}件あります。" + "。".join(notes[-5:]) + "。" if notes else "")
        if intent == "clear_notes":
            def clear():
                self.memory.set("notes", [])
                return "メモをすべて消去しました。"
            self.confirm_action = clear
            return f"メモを{len(self.memory.notes or [])}件すべて消去します。よろしいですか？"

        # ---------------- 会話 ----------------
        if intent == "capabilities":
            return ("パソコンのことならお任せください。アプリを開く・閉じる、ウィンドウの操作、ファイル探し、"
                    "ブラウザのタブ操作やAmazon・YouTubeなどのサイト内検索、画面の項目を番号で読み上げてクリック、文字入力やショートカット、"
                    "タイマーやアラーム。調べものは天気、ニュース、Wikipedia、計算、為替。"
                    "スマホとつながっていれば「スマホのライトをつけて」「このページをスマホで開いて」「スマホを探して」も使えます。")
        if intent == "decide":
            return skills.decide(text)
        if intent == "oos":
            return self._unknown(text, origin) if _looks_like_question(text) else skills.talk("oos", name)
        return skills.talk(intent, name) or self._unknown(text, origin)

    # ------------------------------------------------------------------
    def _unknown(self, text: str, origin: str) -> str:
        if _looks_like_question(text):
            topic = skills.topic_of(text)
            if topic and len(topic) <= 20:
                res = skills.wikipedia(topic)
                if res:
                    if origin == "local":
                        self.more, self.last_intent = res[1], "wiki"
                    return res[0]
        return self._offer_search(text, f"申し訳ありません、{self.memory.call_name}。その件はまだ勉強中です。")

    def _offer_search(self, query: str, lead: str) -> str:
        self.confirm_action = lambda: self._tool("web_search", {"query": query})
        return f"{lead}ブラウザで調べましょうか？"


def _is_app(name: str) -> bool:
    """PCにあるアプリ名か (起動中のウィンドウ・スタートメニュー・よく使うアプリ)。"""
    try:
        from ..tools.apps import KNOWN_APPS, _norm, _windows_matching, find_shortcut
        return _norm(name) in {_norm(k) for k in KNOWN_APPS} or bool(_windows_matching(name)) or find_shortcut(name) is not None
    except Exception:  # noqa: BLE001  (Windows以外のテスト環境など)
        return False


def _looks_like_question(text: str) -> bool:
    return bool(re.search(r"(何|なに|誰|だれ|どこ|いつ|なぜ|どうして|どう|いくら|教えて|知ってる|とは|って|か)[？?]?$", text))


def skills_norm(text: str) -> str:
    from .model import normalize
    return normalize(text)
