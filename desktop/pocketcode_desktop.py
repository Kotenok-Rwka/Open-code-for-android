#!/usr/bin/env python3
"""PocketCode Desktop — полноценный компаньон для Linux и Windows.

Это не урезанный «мост»: в нём есть чат с сессиями и очередью, проекты и файлы,
агенты, навыки, локальные модели, настройки эко-режима и двусторонняя
синхронизация с телефоном по протоколу v2. Интерфейс — тема «Аврора».

Запуск: python3 pocketcode_desktop.py  (браузер откроется сам: http://127.0.0.1:8765)
Зависимостей нет: только стандартная библиотека Python 3.
"""

import argparse
import hashlib
import json
import os
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

HOME = Path.home() / ".pocketcode-desktop"
HOME.mkdir(exist_ok=True)
PROFILES = HOME / "profiles.json"
TASKS = HOME / "tasks.json"
CHAT = HOME / "chat.jsonl"
SESSIONS = HOME / "sessions.json"
QUEUE = HOME / "queue.json"
SKILLS = HOME / "skills.json"
AGENTS = HOME / "agents.json"
ROLES = HOME / "roles.json"
WIDGETS = HOME / "widgets.json"
SETTINGS = HOME / "settings.json"
MODELS = HOME / "local_models.json"
PROJECTS = HOME / "projects"
SYNC = HOME / "sync"
for d in (PROJECTS, SYNC):
    d.mkdir(exist_ok=True)
SYNC_EVENTS = SYNC / "events.jsonl"
SYNC_STATE = SYNC / "state.json"
SYNC_FILES = SYNC / "files"
SYNC_OUTBOX = SYNC / "outbox.jsonl"
SYNC_CONFIG = SYNC / "config.json"
TOKEN_FILE = SYNC / "token.txt"
LOCK = threading.RLock()

PAGE = ""  # загружается ниже из aurora.html
SKIP_DIRS = {".git", ".gradle", "build", "__pycache__", "node_modules", "dist", ".venv", "venv", ".idea", ".next", "target"}
SKIP_FILE = re.compile(r"(^\.env.*|.*\.(pem|key|keystore|jks|p12|pyc|class|apk|aab|zip|so)$|^id_(rsa|ed25519).*)", re.I)
MAX_FILE = 2_000_000
MAX_FILES = 300
MAX_EVENTS = 2000


def load_page():
    """HTML интерфейса лежит отдельным файлом; в PyInstaller он попадает через --add-data."""
    candidates = [Path(__file__).resolve().parent / "aurora.html", HOME / "aurora.html"]
    for p in candidates:
        try:
            if p.is_file() and p.stat().st_size > 100:
                return p.read_text(encoding="utf-8")
        except OSError:
            continue
    return "<!doctype html><meta charset=utf-8><title>PocketCode</title><p>Не найден файл интерфейса aurora.html</p>"


PAGE = load_page()


# ---------- безопасное хранилище ----------

def load_json(path, default):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, type(default)) else default
    except (OSError, ValueError, UnicodeDecodeError):
        return default


def save_json(path, value):
    """Атомарная запись: обрыв процесса не оставит половину файла настроек."""
    data = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    tmp = path.with_suffix(path.suffix + ".tmp")
    with LOCK:
        tmp.write_text(data, encoding="utf-8")
        os.replace(tmp, path)


def read_lines(path):
    try:
        return [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    except (OSError, UnicodeDecodeError):
        return []


def read_jsonl(path):
    out = []
    for line in read_lines(path):
        try:
            v = json.loads(line)
        except ValueError:
            continue
        if isinstance(v, dict):
            out.append(v)
    return out


def append_jsonl(path, value):
    with LOCK:
        with path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(value, ensure_ascii=False) + "\n")


def safe_path(root, rel):
    """Путь внутри root; отказывает в .., абсолютных путях иescape-символах."""
    root = Path(root).resolve()
    raw = str(rel or "").replace("\\", "/")
    if raw.startswith("/") or raw.startswith("~"):
        return None
    text = raw.strip("/")
    if not text or ".." in text.split("/") or "\x00" in text:
        return None
    p = (root / text).resolve()
    return p if root == p or root in p.parents else None


def call_llm(profile, history, tools_text=""):
    base = str(profile.get("base_url", "")).strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        raise ValueError("Некорректный адрес сервера модели")
    messages = [{"role": "system", "content": "Ты PocketCode Desktop, помощник по проектам и задачам. Отвечай кратко и по делу."}]
    if tools_text:
        messages[0]["content"] += "\n\n" + tools_text[:4000]
    for m in history:
        if isinstance(m, dict) and m.get("role") in ("user", "assistant"):
            messages.append({"role": m["role"], "content": str(m.get("text", ""))})
    body = json.dumps({
        "model": profile.get("model", ""),
        "messages": messages[-40:],
        "temperature": 0.4,
        "max_tokens": int(profile.get("max_tokens", 0) or 2048),
    }).encode()
    key = str(profile.get("api_key", "") or "")
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(base + "/chat/completions", data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    try:
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as e:
        raise ValueError("Модель вернула пустой ответ: " + str(e)) from e


def access_token():
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(24), encoding="utf-8")
        try:
            os.chmod(TOKEN_FILE, 0o600)
        except OSError as e:
            sys.stderr.write("Внимание: не удалось ограничить права токена (%s)\n" % mask(e))
    return TOKEN_FILE.read_text(encoding="utf-8").strip()


def mask(text):
    """Не показываем токены и ключи в ответах/логах."""
    s = str(text or "")
    for secret in (access_token(), load_json(SYNC_CONFIG, {}).get("token", "")):
        if secret:
            s = s.replace(secret, "***")
    return re.sub(r"(?i)(api[_-]?key|token|password)\s*[:=]\s*[\w\-]{6,}", r"\1=***", s)


# ---------- синхронизация ----------

def next_seq():
    state = load_json(SYNC_STATE, {"seq": 0})
    state["seq"] = int(state.get("seq", 0)) + 1
    save_json(SYNC_STATE, state)
    return state["seq"]


def make_event(kind, data, project=""):
    return {
        "v": 2, "type": "event", "seq": next_seq(), "device": "PocketCode Desktop",
        "project": project, "ts": int(time.time() * 1000), "kind": kind, "data": data,
    }


def full_packet():
    msgs = read_jsonl(CHAT)[-200:]
    files = []
    root = PROJECTS.resolve()
    if root.is_dir():
        count = 0
        for p in root.rglob("*"):
            if count >= MAX_FILES:
                break
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            parts = rel.split("/")
            if any(x in SKIP_DIRS for x in parts[:-1]) or SKIP_FILE.match(parts[-1]) or p.stat().st_size > MAX_FILE:
                continue
            try:
                files.append({"path": rel, "text": p.read_text(encoding="utf-8")})
                count += 1
            except (OSError, UnicodeDecodeError):
                continue
    return {
        "v": 2, "type": "full", "seq": next_seq(), "device": "PocketCode Desktop",
        "project": load_json(ROLES, {}).get("project", "desktop"),
        "ts": int(time.time() * 1000), "items": msgs, "files": files,
    }


def _sync_key(password, salt):
    """Тот же ключ, что и в Android SyncCrypto: SHA-256 пароля, 2000 итераций."""
    d = hashlib.sha256()
    for _ in range(2000):
        d.update(password.encode("utf-8"))
    return d.digest()


def pcenc_encrypt(payload, password):
    """Формат PCENC1:<iv>:<ciphertext+tag>, полностью совместимый с Android SyncCrypto."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    iv = secrets.token_bytes(12)
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    enc = AESGCM(_sync_key(password, b"")).encrypt(iv, data, None)
    import base64
    return "PCENC1:" + base64.b64encode(iv).decode() + ":" + base64.b64encode(enc).decode()


def pcenc_decrypt(text, password):
    """Расшифровать пакет от телефона; при неверном пароле вернуть None."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import base64
    try:
        parts = text.split(":", 2)
        if len(parts) != 3 or parts[0] != "PCENC1":
            return None
        iv = base64.b64decode(parts[1])
        enc = base64.b64decode(parts[2])
        plain = AESGCM(_sync_key(password, b"")).decrypt(iv, enc, None)
        value = json.loads(plain.decode("utf-8"))
        return value if isinstance(value, dict) else None
    except Exception:
        return None


def encrypt_payload(payload, password):
    if not password:
        return json.dumps(payload, ensure_ascii=False)
    try:
        return pcenc_encrypt(payload, password)
    except ImportError as e:
        raise ValueError("Для E2E-шифрования нужен пакет cryptography: pip install cryptography") from e


def push_to_phone(payload, cfg=None):
    cfg = cfg or load_json(SYNC_CONFIG, {})
    url = str(cfg.get("phoneUrl", "") or cfg.get("serverUrl", "")).strip()
    if not url:
        return False, "Не задан адрес телефона"
    url = url.rstrip("/") + "/api/sync/push"
    if not url.startswith(("http://", "https://")):
        return False, "Некорректный адрес телефона"
    body = encrypt_payload(payload, cfg.get("password", ""))
    headers = {"Content-Type": "application/json", "User-Agent": "PocketCode-Desktop"}
    token = str(cfg.get("token", "") or "")
    if token:
        headers["X-PocketCode-Token"] = token
    req = urllib.request.Request(url, data=body.encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return 200 <= r.status < 300, "OK"
    except urllib.error.HTTPError as e:
        return False, "HTTP " + str(e.code)
    except Exception as e:
        return False, mask(e)


def queue_outbound(event):
    append_jsonl(SYNC_OUTBOX, event)


def flush_outbox(limit=10):
    """Доставляем накопленные события на телефон. При ошибке оставляем их в очереди."""
    cfg = load_json(SYNC_CONFIG, {})
    pending = read_jsonl(SYNC_OUTBOX)
    if not pending:
        return 0, 0
    ok = 0
    failed = []
    for e in pending[:limit]:
        good, _ = push_to_phone(e, cfg)
        if good:
            ok += 1
        else:
            failed.append(e)
    failed += pending[limit:]
    with LOCK:
        SYNC_OUTBOX.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in failed[-MAX_EVENTS:]), encoding="utf-8")
    return ok, len(failed)


def send_later(kind, data, project=""):
    event = make_event(kind, data, project)
    queue_outbound(event)
    if thread_sync and thread_sync.is_alive():
        return
    threading.Thread(target=flush_outbox, daemon=True).start()


thread_sync = None


def sync_loop(stop):
    """Фоновая доставка: ретраи с backoff + периодический full-пакет."""
    cfg = load_json(SYNC_CONFIG, {})
    interval = max(1, int(cfg.get("intervalMin", 15) or 15)) * 60
    last_full = 0.0
    while not stop.is_set():
        cfg = load_json(SYNC_CONFIG, {})
        if cfg.get("enabled") and cfg.get("phoneUrl"):
            flush_outbox(20)
            if cfg.get("full", True) and time.time() - last_full >= interval:
                good, _ = push_to_phone(full_packet(), cfg)
                if good:
                    last_full = time.time()
        stop.wait(20)


def decrypt_payload(body, password):
    """Телефон шлёт либо JSON, либо PCENC1 (AES-GCM, совместимый с SyncCrypto)."""
    if isinstance(body, dict):
        return body, True
    text = str(body or "")
    if text.startswith("PCENC1:"):
        if not password:
            return None, False
        value = pcenc_decrypt(text, password)
        return (value, True) if value is not None else (None, False)
    try:
        value = json.loads(text)
        return (value, True) if isinstance(value, dict) else (None, False)
    except ValueError:
        return None, False


# ---------- HTTP ----------

class Handler(BaseHTTPRequestHandler):
    server_version = "PocketCodeDesktop/3"

    def log_message(self, fmt, *args):
        # В лог не попадают заголовки с токеном и содержимое сообщений.
        sys.stderr.write("%s - %s\n" % (self.address_string(), mask(fmt % args)))

    def send(self, obj, code=200):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def token_ok(self):
        cfg = load_json(SYNC_CONFIG, {})
        expected = access_token()
        supplied = self.headers.get("X-PocketCode-Token", "")
        return secrets.compare_digest(str(supplied), expected) or bool(
            cfg.get("token") and secrets.compare_digest(str(supplied), str(cfg["token"]))
        )

    def local_or_token(self):
        """Локальный браузер — без токена; удалённый — только с токеном."""
        if self.client_address[0] in ("127.0.0.1", "::1") or self.token_ok():
            return True
        self.send({"error": "token required"}, 401)
        return False

    def body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0 or n > 20_000_000:
            raise ValueError("Некорректный размер запроса")
        v = json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        return v

    def do_GET(self):
        if self.path in ("/", "/index.html") or self.path.startswith("/index"):
            raw = PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)
            return
        if not self.local_or_token():
            return
        path = urlparse(self.path).path
        q = parse_qs(urlparse(self.path).query)
        if path == "/api/state":
            self.send(bootstrap())
        elif path == "/api/messages":
            self.send(read_jsonl(CHAT))
        elif path == "/api/profiles":
            self.send(load_json(PROFILES, {}))
        elif path == "/api/tasks":
            self.send(load_json(TASKS, []))
        elif path == "/api/agents":
            self.send(load_json(AGENTS, []))
        elif path == "/api/roles":
            self.send(load_json(ROLES, {}))
        elif path == "/api/widgets":
            self.send(load_json(WIDGETS, []))
        elif path == "/api/settings":
            self.send(load_json(SETTINGS, {}))
        elif path == "/api/skills":
            self.send(load_json(SKILLS, []))
        elif path == "/api/models":
            self.send(load_json(MODELS, []))
        elif path == "/api/queue":
            self.send(load_json(QUEUE, []))
        elif path == "/api/projects":
            self.send([{"name": p.name} for p in PROJECTS.iterdir() if p.is_dir()])
        elif path == "/api/files":
            project = str(q.get("project", [""])[0])
            root = safe_path(PROJECTS, project)
            if root is None or not root.is_dir():
                self.send({"error": "Нет такого проекта"}, 404); return
            out = []
            for p in sorted(root.rglob("*")):
                if not p.is_file():
                    continue
                rel = p.relative_to(root).as_posix()
                if any(x in SKIP_DIRS for x in rel.split("/")[:-1]) or SKIP_FILE.match(p.name):
                    continue
                out.append({"path": rel, "size": p.stat().st_size})
                if len(out) >= MAX_FILES:
                    break
            self.send(out)
        elif path == "/api/file":
            fp = safe_path(PROJECTS, q.get("project", [""])[0] + "/" + q.get("path", [""])[0])
            if fp is None or not fp.is_file() or fp.stat().st_size > MAX_FILE:
                self.send({"error": "Файл не найден"}, 404); return
            try:
                self.send({"path": q.get("path", [""])[0], "text": fp.read_text(encoding="utf-8")})
            except UnicodeDecodeError:
                self.send({"error": "Это бинарный файл"}, 400)
        elif path == "/api/sync/token":
            if self.client_address[0] not in ("127.0.0.1", "::1") and not self.token_ok():
                self.send({"error": "local only"}, 403); return
            self.send({"token": access_token()})
        elif path == "/api/sync/status":
            state = load_json(SYNC_STATE, {"seq": 0, "last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
            self.send({**state, "pending": len(read_lines(SYNC_OUTBOX)), "cfg": public_sync_cfg()})
        elif path == "/api/sync/pull":
            try:
                since = int(q.get("since", ["0"])[0])
            except (TypeError, ValueError):
                since = 0
            events = read_jsonl(SYNC_EVENTS)
            self.send([x for x in events if int(x.get("seq", 0)) > since][-200:])
        elif path == "/api/events":
            try:
                since = int(q.get("since", ["0"])[0])
            except (TypeError, ValueError):
                since = 0
            deadline = time.time() + 20
            while time.time() < deadline:
                events = [x for x in read_jsonl(SYNC_EVENTS) if int(x.get("seq", 0)) > since]
                if events:
                    self.send(events[-100:]); return
                time.sleep(0.5)
            self.send([])
        elif path == "/api/messages/full":
            self.send({"version": 1, "items": read_jsonl(CHAT), "project": load_json(ROLES, {}).get("project", "")})
        elif path == "/api/sync/pack":
            root = safe_path(PROJECTS, load_json(ROLES, {}).get("project", ""))
            self.send({"version": 1, "items": read_jsonl(CHAT), "project": load_json(ROLES, {}).get("project", ""),
                       "files": collect_files(root) if root and root.is_dir() else []})
        else:
            self.send({"error": "not found"}, 404)

    def do_POST(self):
        try:
            b = self.body()
            path = urlparse(self.path).path
            if path == "/api/chat":
                text = str(b.get("text", "")).strip() if isinstance(b, dict) else ""
                if not text:
                    self.send({"error": "Пустое сообщение"}, 400); return
                profiles = load_json(PROFILES, {})
                profile = profiles.get(profiles.get("_active", ""))
                if not isinstance(profile, dict):
                    self.send({"error": "Сначала сохрани и выбери провайдера"}, 400); return
                history = read_jsonl(CHAT)
                history.append({"role": "user", "text": text, "ts": int(time.time() * 1000)})
                try:
                    answer = call_llm(profile, history[-20:], tools_notes())
                except Exception as e:
                    self.send({"error": "Модель не ответила: " + mask(e)}, 502); return
                history.append({"role": "assistant", "text": answer, "ts": int(time.time() * 1000)})
                with LOCK:
                    CHAT.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in history[-500:]), encoding="utf-8")
                send_later("message", {"role": "assistant", "text": answer})
                self.send({"ok": True, "text": answer})
            elif path == "/api/message":
                role = str(b.get("role", "user"))
                text = str(b.get("text", "")).strip()
                if role not in ("user", "assistant") or not text:
                    self.send({"error": "Нужны role и text"}, 400); return
                append_jsonl(CHAT, {"role": role, "text": text, "ts": int(time.time() * 1000)})
                send_later("message", {"role": role, "text": text})
                self.send({"ok": True})
            elif path == "/api/clear":
                with LOCK:
                    CHAT.write_text("", encoding="utf-8")
                send_later("clear", {})
                self.send({"ok": True})
            elif path == "/api/profiles":
                name = str(b.get("name", "")).strip()
                base = str(b.get("base_url", "")).strip()
                model = str(b.get("model", "")).strip()
                if not name or not base or not model:
                    self.send({"error": "Нужны имя, адрес сервера и модель"}, 400); return
                if not base.startswith(("http://", "https://")):
                    self.send({"error": "Адрес должен начинаться с http:// или https://"}, 400); return
                profiles = load_json(PROFILES, {})
                profiles[name] = {"base_url": base, "model": model, "api_key": b.get("api_key", ""), "max_tokens": b.get("max_tokens", 0)}
                profiles.setdefault("_active", name)
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif path == "/api/use":
                profiles = load_json(PROFILES, {})
                if not isinstance(profiles.get(b.get("name")), dict):
                    self.send({"error": "Нет такого профиля"}, 404); return
                profiles["_active"] = b["name"]
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif path == "/api/agents":
                agents = load_json(AGENTS, [])
                agents.append({"name": str(b.get("name", "")), "goal": str(b.get("goal", "")), "enabled": True})
                save_json(AGENTS, agents)
                self.send({"ok": True})
            elif path == "/api/agent/toggle":
                agents = load_json(AGENTS, [])
                for a in agents:
                    if a.get("name") == b.get("name"):
                        a["enabled"] = bool(b.get("enabled", True))
                save_json(AGENTS, agents)
                self.send({"ok": True})
            elif path == "/api/roles":
                save_json(ROLES, b)
                self.send({"ok": True})
            elif path == "/api/settings":
                old = load_json(SETTINGS, {})
                old.update({k: v for k, v in b.items() if isinstance(k, str)})
                save_json(SETTINGS, old)
                self.send({"ok": True})
            elif path == "/api/skills":
                skills = load_json(SKILLS, [])
                name = str(b.get("name", "")).strip()
                if not name or not str(b.get("body", "")).strip():
                    self.send({"error": "Нужны имя и текст навыка"}, 400); return
                skills = [s for s in skills if s.get("name") != name]
                skills.append({"name": name, "description": str(b.get("description", "")), "body": str(b.get("body", ""))})
                save_json(SKILLS, skills)
                self.send({"ok": True})
            elif path == "/api/skill/toggle":
                skills = load_json(SKILLS, [])
                for s in skills:
                    if s.get("name") == b.get("name"):
                        s["enabled"] = bool(b.get("enabled", True))
                save_json(SKILLS, skills)
                self.send({"ok": True})
            elif path == "/api/models":
                models = load_json(MODELS, [])
                name = str(b.get("name", "")).strip()
                url = str(b.get("url", "")).strip()
                if not name or not url.startswith(("http://", "https://")):
                    self.send({"error": "Нужны имя и HTTPS/HTTP-адрес"}, 400); return
                models = [m for m in models if m.get("name") != name]
                models.append({"name": name, "url": url, "model": str(b.get("model", ""))})
                save_json(MODELS, models)
                self.send({"ok": True})
            elif path == "/api/models/use":
                profiles = load_json(PROFILES, {})
                m = next((x for x in load_json(MODELS, []) if x.get("name") == b.get("name")), None)
                if m is None:
                    self.send({"error": "Нет такой модели"}, 404); return
                profiles["local"] = {"base_url": m["url"], "model": m.get("model", m["name"]), "api_key": "local", "max_tokens": 2048}
                profiles["_active"] = "local"
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif path == "/api/queue":
                items = load_json(QUEUE, [])
                items.append({"id": secrets.token_hex(4), "text": str(b.get("text", "")).strip(), "ts": int(time.time() * 1000)})
                save_json(QUEUE, items)
                self.send({"ok": True, "size": len(items)})
            elif path == "/api/queue/remove":
                items = [x for x in load_json(QUEUE, []) if x.get("id") != b.get("id")]
                save_json(QUEUE, items)
                self.send({"ok": True})
            elif path == "/api/queue/run":
                items = load_json(QUEUE, [])
                if not items:
                    self.send({"error": "Очередь пуста"}, 400); return
                item = items.pop(0)
                save_json(QUEUE, items)
                self.send({"ok": True, "text": item.get("text", "")})
            elif path == "/api/file":
                fp = safe_path(PROJECTS, str(b.get("project", "")) + "/" + str(b.get("path", "")))
                if fp is None:
                    self.send({"error": "Недопустимый путь"}, 400); return
                text = str(b.get("text", ""))
                if len(text.encode("utf-8")) > MAX_FILE:
                    self.send({"error": "Файл слишком большой"}, 400); return
                fp.parent.mkdir(parents=True, exist_ok=True)
                fp.write_text(text, encoding="utf-8")
                send_later("file", {"path": fp.relative_to(PROJECTS.resolve()).as_posix(), "text": text})
                self.send({"ok": True})
            elif path == "/api/project":
                root = safe_path(PROJECTS, b.get("name", ""))
                if root is None:
                    self.send({"error": "Недопустимое имя проекта"}, 400); return
                root.mkdir(parents=True, exist_ok=True)
                self.send({"ok": True})
            elif path == "/api/sync/import":
                items = b.get("items", [])
                if not isinstance(items, list):
                    self.send({"error": "Нужны items"}, 400); return
                with LOCK:
                    with CHAT.open("a", encoding="utf-8") as f:
                        for m in items[:500]:
                            if isinstance(m, dict):
                                f.write(json.dumps({"role": m.get("role", "user"), "text": str(m.get("text", ""))}, ensure_ascii=False) + "\n")
                self.send({"ok": True, "imported": len(items)})
            elif path == "/api/sync/config":
                if not isinstance(b, dict):
                    self.send({"error": "bad config"}, 400); return
                cfg = load_json(SYNC_CONFIG, {})
                cfg.update(b)
                cfg.pop("token", None)  # токен хранится отдельно, в access_token()
                save_json(SYNC_CONFIG, cfg)
                self.send({"ok": True})
            elif path == "/api/sync/test":
                good, detail = push_to_phone(make_event("ping", {"text": "проверка связи"}))
                self.send({"ok": good, "detail": detail})
            elif path == "/api/sync/push":
                if not self.token_ok():
                    self.send({"error": "token required"}, 401); return
                cfg = load_json(SYNC_CONFIG, {})
                payload, valid = decrypt_payload(b, cfg.get("password", ""))
                if not valid:
                    self.send({"error": "bad encrypted payload or wrong password"}, 400); return
                try:
                    seq = int(payload.get("seq", 0) or 0)
                except (TypeError, ValueError):
                    self.send({"error": "bad seq"}, 400); return
                kind = str(payload.get("type", "event"))
                with LOCK:
                    events = read_jsonl(SYNC_EVENTS)
                    if not any(int(x.get("seq", 0)) == seq and x.get("device") == payload.get("device") for x in events[-500:]):
                        append_jsonl(SYNC_EVENTS, payload)
                        events = read_jsonl(SYNC_EVENTS)
                    state = load_json(SYNC_STATE, {"seq": 0, "last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
                    state["last_seq"] = max(int(state.get("last_seq", 0)), seq)
                    state["last_ts"] = payload.get("ts", 0)
                    state["events"] = int(state.get("events", 0)) + 1
                    if kind == "full":
                        state["fulls"] = int(state.get("fulls", 0)) + 1
                    save_json(SYNC_STATE, state)
                    if len(events) > MAX_EVENTS:
                        SYNC_EVENTS.write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in events[-MAX_EVENTS:]), encoding="utf-8")
                if kind == "full" and cfg.get("applyRemote", True):
                    items = payload.get("items", [])
                    if isinstance(items, list) and items:
                        with LOCK:
                            CHAT.write_text("".join(json.dumps(
                                {"role": m.get("role", "user"), "text": str(m.get("text", "")), "ts": m.get("ts", 0)},
                                ensure_ascii=False) + "\n" for m in items[-500:] if isinstance(m, dict)), encoding="utf-8")
                    for fp in payload.get("files", [])[:MAX_FILES] if isinstance(payload.get("files"), list) else []:
                        if not isinstance(fp, dict):
                            continue
                        path = safe_path(SYNC_FILES, fp.get("path", ""))
                        if path is None:
                            continue
                        try:
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_text(str(fp.get("text", "")), encoding="utf-8")
                        except OSError:
                            continue
                self.send({"ok": True, "seq": seq})
            elif path == "/api/tasks/import":
                incoming = b if isinstance(b, list) else [b]
                tasks = load_json(TASKS, [])
                save_json(TASKS, tasks + incoming[:500])
                self.send({"ok": True})
            else:
                self.send({"error": "not found"}, 404)
        except Exception as e:
            self.send({"error": mask(e)}, 500)


def collect_files(root):
    out = []
    if root is None or not root.is_dir():
        return out
    for p in sorted(root.rglob("*")):
        if len(out) >= MAX_FILES:
            break
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        if any(x in SKIP_DIRS for x in rel.split("/")[:-1]) or SKIP_FILE.match(p.name) or p.stat().st_size > MAX_FILE:
            continue
        try:
            out.append({"path": rel, "text": p.read_text(encoding="utf-8")})
        except UnicodeDecodeError:
            continue
    return out


def tools_notes():
    skills = [s for s in load_json(SKILLS, []) if s.get("enabled", True)]
    agents = [a for a in load_json(AGENTS, []) if a.get("enabled", True)]
    if not skills and not agents:
        return ""
    return "Доступные навыки: " + ", ".join(s["name"] for s in skills[:20]) + "\nАгенты: " + ", ".join(a["name"] for a in agents[:10])


def public_sync_cfg():
    cfg = load_json(SYNC_CONFIG, {})
    return {k: v for k, v in cfg.items() if k not in ("password", "token")}


def bootstrap():
    """Один запрос для первого экрана — меньше сетевых вызовов и меньше задержек."""
    return {
        "messages": read_jsonl(CHAT)[-200:],
        "profiles": load_json(PROFILES, {}),
        "tasks": load_json(TASKS, []),
        "agents": load_json(AGENTS, []),
        "roles": load_json(ROLES, {}),
        "widgets": load_json(WIDGETS, []),
        "settings": load_json(SETTINGS, {}),
        "skills": load_json(SKILLS, []),
        "models": load_json(MODELS, []),
        "queue": load_json(QUEUE, []),
        "projects": [{"name": p.name} for p in PROJECTS.iterdir() if p.is_dir()],
        "sync": {**load_json(SYNC_STATE, {"seq": 0}), "pending": len(read_lines(SYNC_OUTBOX)), "cfg": public_sync_cfg()},
    }


def main():
    global thread_sync
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--no-browser", action="store_true", help="не открывать браузер")
    a = ap.parse_args()
    try:
        server = ThreadingHTTPServer((a.host, a.port), Handler)
    except OSError as e:
        print(f"Не удалось занять порт {a.port}: {e}. Возможно, компаньон уже запущен — открой http://127.0.0.1:{a.port}/")
        sys.exit(1)
    stop = threading.Event()
    if load_json(SYNC_CONFIG, {}).get("enabled"):
        thread_sync = threading.Thread(target=sync_loop, args=(stop,), daemon=True)
        thread_sync.start()
    url = f"http://127.0.0.1:{a.port}/"
    print(f"PocketCode Desktop: http://{a.host}:{a.port}/  (Ctrl+C — выход)")
    if not a.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nВыход по Ctrl+C")
    finally:
        stop.set()
        server.server_close()


if __name__ == "__main__":
    main()
