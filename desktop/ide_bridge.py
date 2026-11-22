#!/usr/bin/env python3
"""PocketCode IDE Bridge — локальный мост для IDE (VS Code, JetBrains, Vim).

Принимает задачи по HTTP на 127.0.0.1:8766 и пересылает их в PocketCode Desktop
(или любой OpenAI-совместимый endpoint). Запуск: python3 ide_bridge.py
"""

import json
import os
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DESKTOP = "http://127.0.0.1:8765"
QUEUE = Path.home() / ".pocketcode-desktop" / "ide_queue.jsonl"
QUEUE.parent.mkdir(exist_ok=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, value, code=200):
        raw = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self):
        self.send({"ok": True})

    def do_GET(self):
        if self.path == "/api/queue":
            self.send([json.loads(x) for x in QUEUE.read_text().splitlines()] if QUEUE.exists() else [])
        else:
            self.send({"error": "not found"}, 404)

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode() or "{}")
            task = {
                "title": body.get("title", "IDE task"),
                "text": body.get("text", ""),
                "file": body.get("file", ""),
                "project": body.get("project", ""),
            }
            with QUEUE.open("a") as f:
                f.write(json.dumps(task, ensure_ascii=False) + "\n")
            # Best effort: notify desktop if it is running
            try:
                req = urllib.request.Request(
                    DESKTOP + "/api/tasks/import",
                    data=json.dumps([task]).encode(),
                    headers={"Content-Type": "application/json"},
                )
                urllib.request.urlopen(req, timeout=3).read()
            except Exception:
                pass
            self.send({"ok": True, "queued": task})
        except Exception as e:
            self.send({"error": str(e)}, 500)


def main():
    port = int(os.environ.get("POCKETCODE_IDE_PORT", "8766"))
    print(f"PocketCode IDE Bridge: http://127.0.0.1:{port}/ (POST /api/task)")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
