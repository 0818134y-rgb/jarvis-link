"""PowerShellコマンド実行 (常に確認付き / 他端末からは既定で拒否)。"""
from __future__ import annotations

import subprocess

from . import CONFIRM, registry

MAX_OUTPUT = 1500


@registry.add("run_shell", "PowerShellコマンドを実行して結果を返す", {
    "command": {"type": "string", "description": "実行するPowerShellコマンド"},
}, ["command"], risk=CONFIRM)
def run_shell(command: str) -> str:
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command",
             "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " + command],
            capture_output=True, timeout=60, encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired:
        return "60秒以内に終わらなかったので打ち切りました。"
    out = (proc.stdout or "") + (("\n[stderr]\n" + proc.stderr) if proc.stderr.strip() else "")
    out = out.strip() or "(出力なし)"
    if len(out) > MAX_OUTPUT:
        out = out[:MAX_OUTPUT] + "\n...(省略)"
    return f"終了コード {proc.returncode}\n{out}"
