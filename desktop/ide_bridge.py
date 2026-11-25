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
from urllib.parse import urlparse

DESKTOP = "http://127.0.0.1:8765"
QUEUE = Path.home() / ".pocketcode-desktop" / "ide_queue.jsonl"
QUEUE.parent.mkdir(exist_ok=True)
LOOPBACK_HOSTS = ("127.0.0.1", "::1", "localhost")


def _loopback_name(value):
    try:
        host = urlparse(value if "://" in value else "//" + value).hostname
    except ValueError:
        return False
    return (host or "").lower() in LOOPBACK_HOSTS


def request_is_local(client_ip, headers):
    """Локальный адрес клиента, локальный Host и локальный Origin (если браузер его прислал)."""
    if client_ip not in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
        return False
    if not _loopback_name(headers.get("Host") or ""):
        return False
    origin = headers.get("Origin")
    return origin is None or _loopback_name(origin)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, value, code=200):
        raw = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def from_editor(self):
        """Запрос пришёл от редактора на этом компьютере, а не со страницы сайта в браузере."""
        if request_is_local(self.client_address[0], self.headers):
            return True
        self.send({"error": "local only"}, 403)
        return False

    def do_GET(self):
        if not self.from_editor():
            return
        if self.path == "/api/queue":
            self.send([json.loads(x) for x in [l for l in QUEUE.read_text(encoding="utf-8").splitlines() if l.strip()]] if QUEUE.exists() else [])
        else:
            self.send({"error": "not found"}, 404)

    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if not self.from_editor():
                return
            body = json.loads(raw.decode() or "{}")
            task = {
                "title": body.get("title", "IDE task"),
                "text": body.get("text", ""),
                "file": body.get("file", ""),
                "project": body.get("project", ""),
            }
            with QUEUE.open("a", encoding="utf-8") as f:
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
