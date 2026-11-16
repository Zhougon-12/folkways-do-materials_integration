#!/usr/bin/env python3
"""Folkways blueprint conversion panel.

Local-only web UI around folkways-ornamentum/tools/convert_blueprints.py.
No third-party Python packages are required.
"""
from __future__ import annotations

import json
import os
import queue
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
CONVERTER_DIR = PROJECT / "folkways-ornamentum" / "tools"
DEFAULT_SOURCE = PROJECT / "blueprints-src"
DEFAULT_OUTPUT = PROJECT / "blueprints-panel-out"
INDEX = HERE / "index.html"

sys.path.insert(0, str(CONVERTER_DIR))
try:
    import convert_blueprints as converter
except Exception:  # pragma: no cover - surfaced in the UI
    converter = None
    CONVERTER_IMPORT_ERROR = traceback.format_exc()
else:
    CONVERTER_IMPORT_ERROR = ""

JOB_LOCK = threading.Lock()
JOB = {
    "running": False,
    "total": 0,
    "done": 0,
    "failed": 0,
    "blocks": 0,
    "do_blocks": 0,
    "textured_blocks": 0,
    "current": "",
    "source": str(DEFAULT_SOURCE),
    "output": str(DEFAULT_OUTPUT),
    "started": None,
    "finished": None,
    "error": "",
    "logs": [],
}


def log(message: str, level: str = "info") -> None:
    stamp = time.strftime("%H:%M:%S")
    with JOB_LOCK:
        JOB["logs"].append({"time": stamp, "level": level, "message": message})
        if len(JOB["logs"]) > 500:
            del JOB["logs"][:100]


def public_job() -> dict:
    with JOB_LOCK:
        return json.loads(json.dumps(JOB, ensure_ascii=False))


def blueprint_files(source: Path) -> list[Path]:
    if source.is_file() and source.suffix.lower() == ".blueprint":
        return [source]
    return sorted(
        path for path in source.rglob("*")
        if path.is_file() and path.suffix.lower() == ".blueprint"
    )


def count_nbt(source: Path) -> int:
    if not source.exists():
        return 0
    return sum(1 for path in source.rglob("*.nbt") if path.is_file())


def scan_payload(source_raw: str) -> dict:
    source = Path(source_raw).expanduser()
    if not source.exists():
        return {"ok": False, "error": "源目录不存在"}
    files = blueprint_files(source)
    return {
        "ok": True,
        "source": str(source),
        "blueprints": len(files),
        "existing_nbt": count_nbt(source),
        "bytes": sum(path.stat().st_size for path in files[:5000]),
    }


def convert_worker(files: list[Path], source_root: Path, output_root: Path, solid: str) -> None:
    manifest = {"source": str(source_root), "output": str(output_root), "entries": [], "skipped": []}
    try:
        output_root.mkdir(parents=True, exist_ok=True)
        for index, source in enumerate(files, 1):
            relative = source.name if source_root.is_file() else source.relative_to(source_root)
            destination = output_root / Path(relative).with_suffix(".nbt")
            with JOB_LOCK:
                JOB["current"] = str(relative)
            try:
                destination.parent.mkdir(parents=True, exist_ok=True)
                stats = converter.convert_one(str(source), str(destination), solid)
                with JOB_LOCK:
                    JOB["done"] += 1
                    for key in ("blocks", "do_blocks", "textured_blocks"):
                        JOB["blocks"] += int(stats.get(key, 0)) if key == "blocks" else 0
                        if key != "blocks":
                            JOB[key] += int(stats.get(key, 0))
                manifest["entries"].append({
                    "source": str(source),
                    "output": str(destination.relative_to(output_root)).replace("\\", "/"),
                    **{k: v for k, v in stats.items() if isinstance(v, (str, int, float, bool))},
                })
                log(f"[{index}/{len(files)}] {relative} → {destination.name}", "ok")
            except Exception as error:  # noqa: BLE001
                with JOB_LOCK:
                    JOB["failed"] += 1
                manifest["skipped"].append({"source": str(relative), "error": repr(error)})
                log(f"[{index}/{len(files)}] 跳过 {relative}: {error}", "error")
        with open(output_root / "conversion_manifest.json", "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
        with JOB_LOCK:
            JOB["running"] = False
            JOB["finished"] = time.time()
            JOB["current"] = ""
        log(f"转换完成：{JOB['done']} 个文件，{JOB['failed']} 个错误", "ok")
    except Exception as error:  # noqa: BLE001
        with JOB_LOCK:
            JOB["running"] = False
            JOB["finished"] = time.time()
            JOB["error"] = str(error)
        log(f"转换中止：{error}", "error")


def start_job(source_raw: str, output_raw: str, solid: str) -> tuple[bool, str]:
    global JOB
    if converter is None:
        return False, "转换器加载失败：" + CONVERTER_IMPORT_ERROR
    source = Path(source_raw).expanduser()
    output = Path(output_raw).expanduser()
    if not source.exists():
        return False, "源目录不存在"
    if not solid or ":" not in solid:
        return False, "地面方块 ID 格式应为 namespace:block"
    files = blueprint_files(source)
    if not files:
        return False, "源目录里没有 .blueprint 文件"
    with JOB_LOCK:
        if JOB["running"]:
            return False, "已有转换任务在运行"
        JOB.update({
            "running": True, "total": len(files), "done": 0, "failed": 0,
            "blocks": 0, "do_blocks": 0, "textured_blocks": 0,
            "current": "", "source": str(source), "output": str(output),
            "started": time.time(), "finished": None, "error": "", "logs": [],
        })
    log(f"开始转换：{len(files)} 个蓝图", "info")
    log(f"源目录：{source}", "info")
    log(f"输出目录：{output}", "info")
    log(f"地面方块：{solid}", "info")
    thread = threading.Thread(target=convert_worker, args=(files, source, output, solid), daemon=True)
    thread.start()
    return True, "已开始"


class Handler(BaseHTTPRequestHandler):
    server_version = "FolkwaysPanel/1.0"

    def log_message(self, fmt, *args):  # keep console quiet
        return

    def send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self):  # noqa: N802
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            body = INDEX.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/config":
            self.send_json({
                "source": str(DEFAULT_SOURCE),
                "output": str(DEFAULT_OUTPUT),
                "solid": "minecraft:stone_bricks",
                "converter_ready": converter is not None,
                "converter_error": CONVERTER_IMPORT_ERROR,
            })
            return
        if path == "/api/job":
            self.send_json(public_job())
            return
        self.send_json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        body = self.read_json()
        if path == "/api/scan":
            result = scan_payload(body.get("source", ""))
            log("扫描：" + (f"{result.get('blueprints', 0)} 个蓝图" if result.get("ok") else result.get("error", "")), "ok" if result.get("ok") else "error")
            self.send_json(result)
            return
        if path == "/api/convert":
            ok, message = start_job(
                str(body.get("source", "")),
                str(body.get("output", "")),
                str(body.get("solid", "minecraft:stone_bricks")),
            )
            self.send_json({"ok": ok, "message": message})
            return
        if path == "/api/pick":
            kind = body.get("kind", "source")
            try:
                import tkinter as tk
                from tkinter import filedialog
                root = tk.Tk()
                root.withdraw()
                selected = filedialog.askdirectory(title="选择蓝图目录" if kind == "source" else "选择输出目录")
                root.destroy()
                self.send_json({"ok": bool(selected), "path": selected})
            except Exception as error:  # noqa: BLE001
                self.send_json({"ok": False, "error": str(error)})
            return
        if path == "/api/open":
            target = Path(str(body.get("output", "")))
            target.mkdir(parents=True, exist_ok=True)
            try:
                os.startfile(str(target))  # type: ignore[attr-defined]
                self.send_json({"ok": True})
            except Exception as error:  # noqa: BLE001
                self.send_json({"ok": False, "error": str(error)})
            return
        self.send_json({"ok": False, "error": "not found"}, 404)


def main() -> None:
    host = "127.0.0.1"
    port = int(os.environ.get("PANEL_PORT", "8765"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"Folkways blueprint panel: http://{host}:{port}")
    print("Ctrl+C to stop")
    server.serve_forever()


if __name__ == "__main__":
    main()
