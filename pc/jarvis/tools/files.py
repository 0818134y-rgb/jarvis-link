"""ファイル検索・開く・移動・削除(ごみ箱)・フォルダ作成。"""
from __future__ import annotations

import os
import shutil
import time
from pathlib import Path

from . import CONFIRM, SAFE, registry

HOME = Path.home()
SEARCH_ROOTS = [HOME / d for d in ("Desktop", "Documents", "Downloads", "Pictures", "Music", "Videos")]
FOLDER_ALIASES = {
    "デスクトップ": HOME / "Desktop", "ドキュメント": HOME / "Documents",
    "ダウンロード": HOME / "Downloads", "ピクチャ": HOME / "Pictures", "写真": HOME / "Pictures",
    "画像": HOME / "Pictures", "ミュージック": HOME / "Music", "音楽": HOME / "Music",
    "ビデオ": HOME / "Videos", "動画": HOME / "Videos", "ホーム": HOME,
}
SKIP_DIRS = {"node_modules", ".git", "__pycache__", "Library", ".venv", "venv", "AppData"}

_last_results: list[Path] = []


def resolve_folder(name: str | None) -> Path | None:
    if not name:
        return None
    if name in FOLDER_ALIASES:
        return FOLDER_ALIASES[name]
    p = Path(os.path.expandvars(os.path.expanduser(name)))
    return p if p.is_absolute() else HOME / p


def _resolve_target(path_or_index: str) -> Path | None:
    s = str(path_or_index).strip()
    if s.isdigit() and _last_results:
        i = int(s) - 1
        return _last_results[i] if 0 <= i < len(_last_results) else None
    folder = resolve_folder(s)
    return folder


@registry.add("search_files", "PC内のファイルやフォルダを名前で探す(デスクトップ・ドキュメント・ダウンロード等)", {
    "query": {"type": "string", "description": "ファイル名の一部 (例: 見積書, .pdf)"},
    "folder": {"type": "string", "description": "探す場所 (例: ダウンロード)。省略で主要フォルダ全部"},
}, ["query"])
def search_files(query: str, folder: str = "") -> str:
    global _last_results
    roots = [resolve_folder(folder)] if folder else SEARCH_ROOTS
    q = query.lower().strip()
    hits: list[Path] = []
    deadline = time.time() + 8
    for root in roots:
        if not root or not root.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
            for n in dirnames + filenames:
                if q in n.lower():
                    hits.append(Path(dirpath) / n)
            if time.time() > deadline or len(hits) > 200:
                break
    hits.sort(key=lambda p: p.stat().st_mtime if p.exists() else 0, reverse=True)
    _last_results = hits[:10]
    from .screen import set_numbered_list
    set_numbered_list([(p.name, p) for p in _last_results], "files")
    if not _last_results:
        return f"「{query}」に一致するファイルは見つかりませんでした。"
    lines = [f"{i}. {p.name} ({p.parent})" for i, p in enumerate(_last_results, 1)]
    return f"{len(hits)}件見つかりました。新しい順に:\n" + "\n".join(lines) + \
        "\n「1番を開いて」のように番号で開けます。"


@registry.add("open_path", "ファイルやフォルダを開く。検索結果の番号でも指定可", {
    "target": {"type": "string", "description": "パス、フォルダ名(ダウンロード等)、または検索結果の番号"},
}, ["target"])
def open_path(target: str) -> str:
    p = _resolve_target(target)
    if not p or not p.exists():
        return f"{target}が見つかりません。"
    os.startfile(str(p))  # type: ignore[attr-defined]
    return f"{p.name}を開きました。"


@registry.add("move_file", "ファイルを別のフォルダへ移動する", {
    "target": {"type": "string", "description": "パスまたは検索結果の番号"},
    "destination": {"type": "string", "description": "移動先フォルダ (例: ドキュメント)"},
}, ["target", "destination"], risk=CONFIRM)
def move_file(target: str, destination: str) -> str:
    src = _resolve_target(target)
    dst = resolve_folder(destination)
    if not src or not src.exists():
        return f"{target}が見つかりません。"
    if not dst:
        return "移動先がわかりません。"
    dst.mkdir(parents=True, exist_ok=True)
    final = Path(shutil.move(str(src), str(dst / src.name)))
    return f"{src.name}を{final.parent}へ移動しました。"


@registry.add("delete_file", "ファイルをごみ箱へ移動する(完全削除はしない)", {
    "target": {"type": "string", "description": "パスまたは検索結果の番号"},
}, ["target"], risk=CONFIRM)
def delete_file(target: str) -> str:
    from send2trash import send2trash
    p = _resolve_target(target)
    if not p or not p.exists():
        return f"{target}が見つかりません。"
    send2trash(str(p))
    return f"{p.name}をごみ箱へ移動しました。"


@registry.add("create_folder", "フォルダを作成する", {
    "path": {"type": "string", "description": "作るフォルダ (例: Documents/新しいプロジェクト)"},
}, ["path"])
def create_folder(path: str) -> str:
    p = resolve_folder(path)
    p.mkdir(parents=True, exist_ok=True)
    return f"{p}を作成しました。"
