"""ツールの登録簿。各ツールは名前・説明・引数スキーマ・リスク区分を持つ。

リスク区分 (protocol/PROTOCOL.md と共通):
  SAFE           どこからでも実行可
  REMOTE_BLOCKED ローカル音声なら実行、他端末からの依頼は拒否 (allow_remote_dangerous で許可)
  CONFIRM        ローカルでは音声確認後に実行、他端末からは拒否 (同上)
"""
from __future__ import annotations

import inspect
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable

SAFE = "safe"
REMOTE_BLOCKED = "remote_blocked"
CONFIRM = "confirm"


@dataclass
class Tool:
    name: str
    description: str
    params: dict  # JSON Schema の properties
    required: list[str]
    risk: str
    func: Callable[..., Any]

    def schema(self) -> dict:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": self.params,
                    "required": self.required,
                },
            },
        }


@dataclass
class Registry:
    tools: dict[str, Tool] = field(default_factory=dict)

    def add(self, name: str, description: str, params: dict | None = None,
            required: list[str] | None = None, risk: str = SAFE):
        def deco(func):
            self.tools[name] = Tool(name, description, params or {}, required or [], risk, func)
            return func
        return deco

    def schemas(self) -> list[dict]:
        return [t.schema() for t in self.tools.values()]

    def describe(self) -> list[dict]:
        return [{"name": t.name, "description": t.description, "risk": t.risk}
                for t in self.tools.values()]

    def call(self, name: str, args: dict | None, *, origin: str = "local",
             allow_remote_dangerous: bool = False,
             confirm: Callable[[str], bool] | None = None) -> str:
        tool = self.tools.get(name)
        if tool is None:
            return f"ツール {name} は存在しません。"
        args = args or {}
        remote = origin != "local"
        if tool.risk != SAFE and remote and not allow_remote_dangerous:
            return f"安全のため、他の端末からの『{name}』は許可されていません。"
        if tool.risk == CONFIRM and not remote and confirm is not None:
            summary = ", ".join(f"{k}={v}" for k, v in args.items())
            if not confirm(f"{tool.description}({summary})"):
                return "ユーザーが実行をキャンセルしました。"
        # LLMが余計な引数を付けることがあるので、関数が受け取れるものだけ渡す
        sig = inspect.signature(tool.func)
        accepts_kwargs = any(p.kind == p.VAR_KEYWORD for p in sig.parameters.values())
        if not accepts_kwargs:
            args = {k: v for k, v in args.items() if k in sig.parameters}
        try:
            result = tool.func(**args)
            return str(result) if result is not None else "完了しました。"
        except TypeError as e:
            return f"引数が正しくありません: {e}"
        except Exception as e:  # noqa: BLE001 - ツールの失敗は会話を止めずにLLMへ返す
            traceback.print_exc()
            return f"エラー: {e}"


registry = Registry()


def load_all(cfg: dict, link=None, speaker=None) -> Registry:
    """全ツールモジュールを読み込んで registry を返す。"""
    from . import apps, browser, files, remote, screen, shell  # noqa: F401

    screen.configure(cfg)
    remote.configure(link, speaker)
    return registry
