"""設定の読み込み。config.yaml が無ければ config.example.yaml の既定値で動く。"""
from __future__ import annotations

import copy
import secrets
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

DEFAULTS: dict = {
    "device": {"name": "pc", "aliases": ["パソコン", "ピーシー", "PC", "コンピューター"]},
    "link": {
        "port": 8765,
        "token": "",
        "discovery": True,
        "peers": [],
        "allow_remote_dangerous": False,
    },
    "asr": {
        "model": "small",
        "device": "auto",
        "compute_type": "auto",
        "wake_words": ["ジャーヴィス", "ジャービス", "じゃーびす", "jarvis"],
        "hotkey": "ctrl+alt+j",
        "follow_up_seconds": 6,
    },
    "llm": {
        "host": "http://localhost:11434",
        "model": "qwen2.5:7b",
        "vision_model": "qwen2.5vl:7b",
        "history_turns": 8,
    },
    "tts": {"voicevox_host": "http://localhost:50021", "speaker": 13, "speed": 1.1},
    "safety": {"confirm_dangerous": True},
}


def _merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def _ensure_token(cfg: dict) -> None:
    token = str(cfg["link"].get("token") or "").strip()
    if token and token != "change-me":
        cfg["link"]["token"] = token
        return
    token_file = ROOT / ".jarvis_token"
    if token_file.exists():
        cfg["link"]["token"] = token_file.read_text(encoding="utf-8").strip()
        return
    token = secrets.token_urlsafe(24)
    token_file.write_text(token, encoding="utf-8")
    cfg["link"]["token"] = token
    print("=" * 60)
    print(" Link token generated (enter this in the Android app):")
    print(f"   {token}")
    print(f" saved to {token_file}")
    print("=" * 60)


def load_config(path: str | Path | None = None) -> dict:
    path = Path(path) if path else ROOT / "config.yaml"
    user = {}
    if path.exists():
        with open(path, encoding="utf-8") as f:
            user = yaml.safe_load(f) or {}
    cfg = _merge(DEFAULTS, user)
    _ensure_token(cfg)
    return cfg
