#!/usr/bin/env python3
"""PocketCode Desktop — та же функциональность, что и в приложении, на ПК.

Чат с агентом и инструментами (файлы, поиск, bash, git), проекты и правки с
откатом, сессии, навыки и плагины, локальные модели со скачиванием и отменой,
поиск по GitHub с переводом и постраничной выдачей, экономия токенов и
двусторонняя синхронизация с телефоном. Интерфейс — тема «Аврора».

Запуск: python3 pocketcode_desktop.py  (браузер откроется сам: http://127.0.0.1:8765)
Зависимостей нет: только стандартная библиотека Python 3.
"""

import argparse
import base64
import difflib
import hashlib
import json
import os
import platform
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

HOME = Path(os.environ.get("POCKETCODE_HOME") or (Path.home() / ".pocketcode-desktop"))
HOME.mkdir(parents=True, exist_ok=True)
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
PROJECTS_MAP = HOME / "projects_map.json"
USAGE = HOME / "usage.json"
ACTIVITY = HOME / "activity.jsonl"
PROJECTS = HOME / "projects"
SNAPSHOTS = HOME / "snapshots"
DOWNLOADS_DIR = HOME / "models"
SYNC = HOME / "sync"
for d in (PROJECTS, SNAPSHOTS, DOWNLOADS_DIR, SYNC):
    d.mkdir(exist_ok=True)
SYNC_EVENTS = SYNC / "events.jsonl"
SYNC_STATE = SYNC / "state.json"
SYNC_FILES = SYNC / "files"
SYNC_OUTBOX = SYNC / "outbox.jsonl"
SYNC_CONFIG = SYNC / "config.json"
TOKEN_FILE = SYNC / "token.txt"
LOCK = threading.RLock()

PAGE = ""  # загружается ниже из aurora.html
SKIP_DIRS = {".git", ".gradle", "build", "__pycache__", "node_modules", "dist", ".venv", "venv", ".idea", ".next", "target", ".pytest_cache", "snapshots"}
SKIP_FILE = re.compile(r"(^\.env.*|.*\.(pem|key|keystore|jks|p12|pyc|class|apk|aab|zip|so|gguf)$|^id_(rsa|ed25519).*)", re.I)
MAX_FILE = 2_000_000
MAX_FILES = 400
MAX_EVENTS = 2000

DEFAULTS = {
    "leanCalls": False,
    "ecoMode": False,
    "theme": "aurora",
    "accent": "teal",
    "density": "comfortable",
    "fontSize": 15,
    "background": True,
    "motion": True,
    "autoApprove": False,
    "maxHistory": 20,
    "maxToolChars": 6000,
    "language": "ru",
    "githubTranslate": True,
    "githubPerPage": 30,
    "githubToken": "",
    "localPort": 8080,
    "localGpuLayers": 0,
    "localContext": 8192,
    "localModel": "",
}

DANGER = re.compile(
    r"(rm\s+-[a-z]*r[a-z]*f?\s+/|rm\s+-rf\s+\*|sudo\b|mkfs|dd\s+if=|:\s*\(\s*\)\s*\{|shutdown|reboot|"
    r"git\s+push\s+--force|chmod\s+777\s+/|>\s*/dev/sd|format\s+[a-z]:|del\s+/[sq]|rd\s+/s)",
    re.I,
)


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


def app_version():
    for p in (Path(__file__).resolve().parent / "version.py", Path(__file__).resolve().parent.parent / "desktop" / "version.py"):
        try:
            if p.is_file():
                spec = {}
                exec(p.read_text(encoding="utf-8"), spec)  # noqa: S102 — локальный файл проекта
                fn = spec.get("version")
                if callable(fn):
                    return str(fn())
        except Exception:
            continue
    return os.environ.get("PC_VERSION", "4.5.6")


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
    """Путь внутри root; отказывает в .., абсолютных путях и escape-символах."""
    root = Path(root).resolve()
    raw = str(rel or "").replace("\\", "/")
    if raw.startswith("/") or raw.startswith("~"):
        return None
    text = raw.strip("/")
    if not text or ".." in text.split("/") or "\x00" in text:
        return None
    p = (root / text).resolve()
    return p if root == p or root in p.parents else None


def safe_name(value):
    text = re.sub(r"[^\w .-]+", "_", str(value or "").strip(), flags=re.UNICODE).strip(" .")
    return text[:80] or "project"


def settings():
    value = load_json(SETTINGS, {})
    merged = dict(DEFAULTS)
    merged.update({k: v for k, v in value.items() if isinstance(k, str)})
    return merged


def public_profiles():
    """Ключи не уходят в браузер: только признак, что ключ сохранён."""
    profiles = load_json(PROFILES, {})
    out = {}
    for name, p in profiles.items():
        if name == "_active" or not isinstance(p, dict):
            continue
        out[name] = {
            "base_url": p.get("base_url", ""),
            "model": p.get("model", ""),
            "max_tokens": p.get("max_tokens", 0),
            "has_key": bool(p.get("api_key")),
        }
    out["_active"] = profiles.get("_active", "")
    return out


def active_profile():
    profiles = load_json(PROFILES, {})
    name = profiles.get("_active", "")
    p = profiles.get(name)
    if isinstance(p, dict) and p.get("base_url") and p.get("model"):
        return p
    for key, value in profiles.items():
        if key != "_active" and isinstance(value, dict) and value.get("base_url") and value.get("model"):
            return value
    return None


# ---------- версии, сессии, сообщения ----------

def now_ms():
    return int(time.time() * 1000)


def load_sessions():
    data = load_json(SESSIONS, {})
    items = data.get("items") if isinstance(data.get("items"), list) else []
    if not items:
        items = [{"id": "default", "title": "Первый чат", "project": "desktop", "created": now_ms(), "updated": now_ms()}]
        save_json(SESSIONS, {"active": "default", "items": items})
    active = str(data.get("active") or items[0]["id"])
    if not any(s.get("id") == active for s in items):
        active = items[0]["id"]
    return {"active": active, "items": items}


def active_session_id():
    return load_sessions()["active"]


def messages_for(session_id):
    all_msgs = read_jsonl(CHAT)
    if not session_id:
        return all_msgs
    first = load_sessions()["items"][0].get("id")
    return [m for m in all_msgs if str(m.get("session") or first) == str(session_id)]


def add_message(role, text, session=None, extra=None):
    msg = {"role": role, "text": str(text or ""), "ts": now_ms(), "session": session or active_session_id()}
    if isinstance(extra, dict):
        msg.update({k: v for k, v in extra.items() if k not in msg})
    append_jsonl(CHAT, msg)
    sessions = load_sessions()
    for s in sessions["items"]:
        if s.get("id") == msg["session"]:
            s["updated"] = msg["ts"]
            if s.get("title") in ("", "Новый чат", "Первый чат") and role == "user":
                s["title"] = (str(text).strip().splitlines() or ["Чат"])[0][:60]
            break
    save_json(SESSIONS, sessions)
    return msg


def new_session(title="Новый чат", project="desktop"):
    sessions = load_sessions()
    sid = secrets.token_hex(6)
    item = {"id": sid, "title": str(title or "Новый чат")[:80], "project": str(project or "desktop"), "created": now_ms(), "updated": now_ms()}
    sessions["items"].insert(0, item)
    sessions["active"] = sid
    save_json(SESSIONS, sessions)
    return item


def delete_session(sid):
    sessions = load_sessions()
    sessions["items"] = [s for s in sessions["items"] if s.get("id") != sid]
    if not sessions["items"]:
        sessions["items"] = [{"id": "default", "title": "Новый чат", "project": "desktop", "created": now_ms(), "updated": now_ms()}]
    sessions["active"] = sessions["items"][0]["id"]
    save_json(SESSIONS, sessions)
    with LOCK:
        keep = [m for m in read_jsonl(CHAT) if str(m.get("session") or "default") != str(sid)]
        CHAT.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in keep), encoding="utf-8")
    return sessions


def session_project(sid):
    for s in load_sessions()["items"]:
        if s.get("id") == sid:
            return str(s.get("project") or "desktop")
    return "desktop"


# ---------- токены и экономия ----------

def estimate_tokens(text):
    return max(1, int(len(str(text or "")) / 3))


def record_usage(prompt_tokens=0, completion_tokens=0, saved=0, requests=1):
    usage = load_json(USAGE, {})
    for key in ("requests", "prompt", "completion", "saved"):
        usage[key] = int(usage.get(key, 0) or 0)
    usage["requests"] += requests
    usage["prompt"] += int(prompt_tokens or 0)
    usage["completion"] += int(completion_tokens or 0)
    usage["saved"] += int(saved or 0)
    usage["last"] = now_ms()
    save_json(USAGE, usage)


def history_budget():
    s = settings()
    if s["ecoMode"]:
        return 6, 9000
    if s["leanCalls"]:
        return 10, 16000
    return max(4, min(60, int(s.get("maxHistory", 20) or 20))), 30000


def trim_history(messages, max_msgs=20, max_chars=30000):
    """Оставляем свежие сообщения и обрезаем слишком длинные: меньше токенов при том же смысле."""
    out = []
    used = 0
    for m in reversed(messages[-max_msgs * 2:]):
        if m.get("role") not in ("user", "assistant"):
            continue
        text = str(m.get("text", ""))
        if len(text) > 8000:
            text = text[:6000] + "\n…(сокращено)"
        if used + len(text) > max_chars and out:
            break
        out.append({"role": m["role"], "content": text})
        used += len(text)
        if len(out) >= max_msgs:
            break
    out.reverse()
    return out


# ---------- навыки ----------

BUILTIN_SKILLS = [
    {
        "name": "Современные сайты",
        "description": "Лендинги и сайты 2026 года: сетка, типографика, движение, тёмная тема",
        "body": "Строй современный сайт: контейнер 1120–1280px, воздух 80–120px между секциями, крупный заголовок 48–72px, "
                "контрастная иерархия, один акцентный цвет. Используй CSS clamp() для размеров, grid/flex, плавные появления "
                "по IntersectionObserver. Никаких стоковых квадратных картинок: градиентные акценты, стеклянные карточки, "
                "мягкие тени. Обязательно адаптив до 360px и prefers-reduced-motion.",
    },
    {
        "name": "Аврора: интерфейс",
        "description": "Фирменный стиль PocketCode Aurora: холодный фон, бирюзово-синие акценты, стекло",
        "body": "Палитра: фон #0b1620/#0f2027, панели #122532/#17303f, акцент #4fd1c5 и #6aa8ff, текст #eaf6ff, "
                "вторичный #8fb3c7. Радиусы 12–18px, границы rgba(147,220,255,.13), тени мягкие и низкие. "
                "Кнопки: основной градиент teal→blue с тёмным текстом, вторичные — прозрачные с рамкой. "
                "Карточки стеклянные только при наличии фона, заголовки 19–22px, подписи 12px. Движение 150–220мс.",
    },
    {
        "name": "Темы приложений",
        "description": "Светлая/тёмная тема, палитры, токены цвета и переключение без перезагрузки",
        "body": "Собери тему на CSS-переменных: --bg, --surface, --surface-2, --text, --muted, --accent, --danger, --line. "
                "Светлая и тёмная темы — один набор токенов, переключение через data-theme на html. Не дублируй стили. "
                "Проверь контраст текста не ниже 4.5:1, состояния hover/active/focus-visible, disabled. "
                "Сохраняй выбор в localStorage и уважай prefers-color-scheme по умолчанию.",
    },
    {
        "name": "Дашборды и таблицы",
        "description": "Плотные интерфейсы с метриками, фильтрами и графиками без визуального шума",
        "body": "Сверху 3–5 ключевых метрик крупным числом и подписью, ниже основная таблица/график. "
                "Таблица: липкая шапка, выравнивание чисел вправо, зебра только при большой плотности, пустые состояния. "
                "Фильтры — в одну строку, применяются сразу. Графики без 3D, подписи осей коротко, цвета из темы. "
                "Не показывай больше двух акцентных цветов на экран.",
    },
    {
        "name": "Мобильный интерфейс",
        "description": "Экраны для телефона: нижняя навигация, безопасные зоны, крупные цели нажатия",
        "body": "Цели нажатия не меньше 44×44px, отступы 16px, нижняя навигация 3–4 пункта с подписями. "
                "Учитывай safe-area-inset и высоту клавиатуры. Длинные списки — виртуализация или пагинация. "
                "Формы: подпись над полем, ошибка под полем, клавиатура не перекрывает кнопку. "
                "Не прячь важные действия в свайпы без видимой альтернативы.",
    },
    {
        "name": "Доступность",
        "description": "Клавиатура, скринридеры, контраст и уменьшение движения",
        "body": "Каждый интерактивный элемент — button/a с понятным текстом, aria-label только когда текста нет. "
                "Фокус видимый, порядок табуляции логичный, модалки ловят фокус и закрываются по Escape. "
                "Изображения с alt, иконки-кнопки с aria-label, формы связаны с label. "
                "Уважай prefers-reduced-motion и не передавай смысл только цветом.",
    },
    {
        "name": "Производительность",
        "description": "Быстрая загрузка: критический CSS, ленивые картинки, минимум JS",
        "body": "Сначала измерь: размер бандла, LCP, CLS. Картинки в WebP/AVIF с width/height и loading=lazy. "
                "Шрифты — не больше двух начертаний, font-display:swap. Не подключай тяжёлую библиотеку ради одного эффекта. "
                "Анимации только transform/opacity. Проверяй, что интерактивность не блокируется длинными задачами.",
    },
    {
        "name": "Правки без регрессий",
        "description": "Как менять чужой код: сначала прочитать, потом точечно править",
        "body": "Перед правкой прочитай файл целиком и найди все места использования. Меняй минимально, сохраняя стиль проекта. "
                "После правки запусти сборку/тесты или хотя бы синтаксис. Не переименовывай публичные API без нужды. "
                "В ответе перечисли изменённые файлы и что проверено.",
    },
]

SKILL_TEMPLATE = "Отвечай по делу, показывай изменённые файлы и проверяй результат."


def load_skills():
    stored = load_json(SKILLS, [])
    by_name = {s.get("name"): s for s in stored if isinstance(s, dict) and s.get("name")}
    out = []
    for b in BUILTIN_SKILLS:
        item = dict(b)
        item["builtin"] = True
        item["enabled"] = by_name.get(b["name"], {}).get("enabled", True)
        out.append(item)
    for s in stored:
        if not isinstance(s, dict) or not s.get("name") or s.get("name") in {b["name"] for b in BUILTIN_SKILLS}:
            continue
        s = dict(s)
        s.setdefault("enabled", True)
        s.setdefault("builtin", False)
        out.append(s)
    return out


def tools_notes():
    skills = [s for s in load_skills() if s.get("enabled", True)]
    agents = [a for a in load_json(AGENTS, []) if a.get("enabled", True)]
    if not skills and not agents:
        return ""
    return ("Доступные навыки: " + ", ".join(s["name"] for s in skills[:20]) +
            ("\nАгенты: " + ", ".join(a.get("name", "") for a in agents[:10]) if agents else ""))


# ---------- проекты ----------

def external_projects():
    data = load_json(PROJECTS_MAP, {})
    return {k: v for k, v in data.items() if isinstance(v, str) and Path(v).is_dir()}


def list_projects():
    out = []
    try:
        out += [p.name for p in PROJECTS.iterdir() if p.is_dir()]
    except OSError:
        pass
    out += [k for k in external_projects() if k not in out]
    return [{"name": n} for n in sorted(out)]


def project_root(name):
    name = str(name or "").strip()
    ext = external_projects()
    if name in ext:
        return Path(ext[name]).resolve()
    return safe_path(PROJECTS, name)


def create_project(name):
    root = safe_path(PROJECTS, safe_name(name))
    if root is None:
        return None
    root.mkdir(parents=True, exist_ok=True)
    return root


def open_project(name, directory):
    path = Path(str(directory or "")).expanduser()
    try:
        path = path.resolve()
    except OSError:
        return None
    if not path.is_dir():
        return None
    data = load_json(PROJECTS_MAP, {})
    data[safe_name(name) or path.name] = str(path)
    save_json(PROJECTS_MAP, data)
    return path


def collect_files(root):
    out = []
    if root is None or not root.is_dir():
        return out
    for p in sorted(root.rglob("*")):
        if len(out) >= MAX_FILES:
            break
        if not p.is_file():
            continue
        try:
            rel = p.relative_to(root).as_posix()
        except ValueError:
            continue
        if any(x in SKIP_DIRS for x in rel.split("/")[:-1]) or SKIP_FILE.match(p.name) or p.stat().st_size > MAX_FILE:
            continue
        try:
            out.append({"path": rel, "text": p.read_text(encoding="utf-8")})
        except (OSError, UnicodeDecodeError):
            continue
    return out


def snapshot_project(project):
    root = project_root(project)
    if root is None or not root.is_dir():
        return ""
    stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3)
    dst = SNAPSHOTS / safe_name(project) / stamp
    dst.mkdir(parents=True, exist_ok=True)
    for item in collect_files(root):
        p = safe_path(dst, item["path"])
        if p is None:
            continue
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(item["text"], encoding="utf-8")
    save_json(dst / "meta.json", {"project": project, "root": str(root), "stamp": stamp, "ts": now_ms()})
    return stamp


def list_snapshots(project):
    base = SNAPSHOTS / safe_name(project)
    out = []
    if not base.is_dir():
        return out
    for d in sorted(base.iterdir(), reverse=True):
        meta = load_json(d / "meta.json", {})
        out.append({"stamp": d.name, "ts": meta.get("ts", 0), "files": len(list(d.rglob("*"))) - 1})
    return out[:50]


def snapshot_changes(project, stamp):
    base = SNAPSHOTS / safe_name(project) / stamp
    root = project_root(project)
    if not base.is_dir() or root is None or not root.is_dir():
        return []
    before = {}
    for p in base.rglob("*"):
        if p.is_file() and p.name != "meta.json":
            before[p.relative_to(base).as_posix()] = p.read_text(encoding="utf-8", errors="replace")
    after = {x["path"]: x["text"] for x in collect_files(root)}
    out = []
    for path in sorted(set(before) | set(after)):
        old = before.get(path)
        new = after.get(path)
        if old == new:
            continue
        if old is None:
            out.append({"path": path, "status": "добавлен", "diff": ""})
        elif new is None:
            out.append({"path": path, "status": "удалён", "diff": ""})
        else:
            diff = "".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True), "до", "после", n=2))
            out.append({"path": path, "status": "изменён", "diff": diff[:8000]})
    return out


def restore_snapshot(project, stamp):
    base = SNAPSHOTS / safe_name(project) / stamp
    root = project_root(project)
    if not base.is_dir() or root is None:
        return 0
    current = {x["path"] for x in collect_files(root)}
    files = {}
    for p in base.rglob("*"):
        if p.is_file() and p.name != "meta.json":
            files[p.relative_to(base).as_posix()] = p.read_text(encoding="utf-8", errors="replace")
    for path, text in files.items():
        dst = safe_path(root, path)
        if dst is None:
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(text, encoding="utf-8")
    for path in current - set(files):
        dst = safe_path(root, path)
        if dst and dst.is_file():
            try:
                dst.unlink()
            except OSError:
                pass
    return len(files)


def search_files(root, needle, limit=200):
    out = []
    if root is None or not root.is_dir() or not needle:
        return out
    try:
        rx = re.compile(needle, re.I)
    except re.error:
        rx = re.compile(re.escape(needle), re.I)
    for p in root.rglob("*"):
        if len(out) >= limit:
            break
        if not p.is_file():
            continue
        try:
            rel = p.relative_to(root).as_posix()
        except ValueError:
            continue
        if any(x in SKIP_DIRS for x in rel.split("/")[:-1]) or SKIP_FILE.match(p.name):
            continue
        try:
            if p.stat().st_size > 800_000:
                continue
            for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
                if rx.search(line):
                    out.append({"path": rel, "line": i, "text": line.strip()[:300]})
                    if len(out) >= limit:
                        break
        except (OSError, UnicodeDecodeError):
            continue
    return out


def storage_info():
    def size_of(path):
        total = 0
        try:
            for p in Path(path).rglob("*"):
                if p.is_file():
                    total += p.stat().st_size
        except OSError:
            pass
        return total

    projects = []
    for item in list_projects():
        root = project_root(item["name"])
        projects.append({"name": item["name"], "path": str(root or ""), "size": size_of(root) if root else 0})
    models = [{"name": p.name, "size": p.stat().st_size} for p in DOWNLOADS_DIR.glob("*.gguf") if p.is_file()]
    return {"projects": projects, "models": models, "data": size_of(HOME), "snapshots": size_of(SNAPSHOTS)}


# ---------- LLM ----------

def _content_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, dict):
                parts.append(str(part.get("text") or part.get("content") or ""))
            else:
                parts.append(str(part))
        return "".join(parts)
    return "" if content is None else str(content)


def call_llm(profile, messages, tools=None, max_tokens=None):
    """Один запрос к OpenAI-совместимому серверу. Возвращает message и usage."""
    base = str(profile.get("base_url", "")).strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        raise ValueError("Некорректный адрес сервера модели")
    body = {
        "model": profile.get("model", ""),
        "messages": messages,
        "temperature": float(profile.get("temperature", 0.4) or 0.4),
        "max_tokens": int(max_tokens or profile.get("max_tokens", 0) or (1024 if settings()["ecoMode"] else 4096)),
    }
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    data = json.dumps(body).encode()
    key = str(profile.get("api_key", "") or "")
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(base + "/chat/completions", data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=240) as r:
        raw = json.loads(r.read().decode("utf-8", "replace"))
    try:
        choice = raw["choices"][0]
    except (KeyError, IndexError, TypeError) as e:
        raise ValueError("Модель вернула пустой ответ: " + str(e)) from e
    msg = choice.get("message") or {}
    message = {"role": "assistant", "content": _content_text(msg.get("content"))}
    calls = msg.get("tool_calls")
    if isinstance(calls, list) and calls:
        message["tool_calls"] = calls
    return message, raw.get("usage") or {}


def call_llm_with_fallback(profile, messages, tools=None, max_tokens=None):
    """Некоторые локальные модели не умеют tools — повторяем запрос без них."""
    try:
        return call_llm(profile, messages, tools, max_tokens)
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:500]
        except Exception:
            pass
        if tools and (e.code in (400, 404, 422) or "tool" in detail.lower() or "function" in detail.lower()):
            return call_llm(profile, messages, None, max_tokens)
        raise ValueError("HTTP %s: %s" % (e.code, mask(detail or e.reason)))


def build_system_prompt(project):
    s = settings()
    lean = s["leanCalls"] or s["ecoMode"]
    skills = [x for x in load_skills() if x.get("enabled", True)]
    skill_text = ""
    if skills:
        if s.get("fullSkills"):
            skill_text = "\n\nНавыки (применяй, когда подходят):\n" + "\n".join(
                "- %s: %s\n  %s" % (x["name"], x.get("description", ""), x.get("body", "")) for x in skills
            )
        else:
            skill_text = "\n\nНавыки: " + "; ".join("%s (%s)" % (x["name"], x.get("description", "")) for x in skills) + \
                         ". Полные инструкции навыка доступны инструментом use_skill."
    agents = [a for a in load_json(AGENTS, []) if a.get("enabled", True)]
    agent_text = ""
    if agents:
        agent_text = "\nАгенты проекта: " + "; ".join("%s — %s" % (a.get("name"), a.get("goal", "")) for a in agents[:8])
    return (
        "Ты PocketCode Desktop — coding-агент на компьютере. Работаешь в проекте «%s» (текущая папка проекта).\n"
        "Правила: сначала посмотри файлы, потом правь точечно; не выдумывай содержимое файлов. "
        "Для действий используй инструменты, а не рассказ о них. Опасные команды требуют подтверждения. "
        "В конце ответа коротко перечисли изменённые файлы и что проверено. "
        "Отвечай на языке пользователя, кратко и по делу.%s%s%s"
    ) % (project or "desktop", skill_text, agent_text,
         "" if lean else "\nОкружение: %s, Python %s." % (platform.system(), platform.python_version()))


# ---------- инструменты агента ----------

def tool_schema():
    def fn(name, desc, props, required=()):
        return {"type": "function", "function": {"name": name, "description": desc,
                                                 "parameters": {"type": "object", "properties": props, "required": list(required)}}}

    return [
        fn("list_files", "Список файлов проекта", {"path": {"type": "string", "description": "Папка внутри проекта"}}, []),
        fn("read_file", "Прочитать файл (строки start..end)", {
            "path": {"type": "string"}, "start": {"type": "integer"}, "end": {"type": "integer"}}, ["path"]),
        fn("write_file", "Создать или перезаписать файл целиком", {
            "path": {"type": "string"}, "text": {"type": "string"}}, ["path", "text"]),
        fn("edit_file", "Заменить точный фрагмент в файле", {
            "path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}}, ["path", "old", "new"]),
        fn("grep", "Поиск по тексту файлов проекта", {
            "pattern": {"type": "string"}, "path": {"type": "string"}}, ["pattern"]),
        fn("bash", "Выполнить команду в папке проекта", {"command": {"type": "string"}}, ["command"]),
        fn("use_skill", "Прочитать полную инструкцию навыка по имени", {"name": {"type": "string"}}, ["name"]),
        fn("update_plan", "Обновить список задач", {"tasks": {"type": "array", "items": {"type": "string"}}}, ["tasks"]),
    ]


TOOL_SCHEMA = tool_schema()
MAX_TOOL_OUTPUT = 8000


def _truncate(text, limit=None):
    text = str(text or "")
    limit = int(limit or settings().get("maxToolChars", 6000) or 6000)
    if len(text) <= limit:
        return text
    return text[:limit] + "\n…(обрезано, всего %d символов)" % len(text)


def execute_tool(run, name, args):
    project = run.get("project") or "desktop"
    root = project_root(project)
    if root is None:
        return "Ошибка: проект не найден"
    args = args if isinstance(args, dict) else {}

    if name == "list_files":
        base = safe_path(root, args.get("path", "")) if args.get("path") else root
        if base is None:
            return "Ошибка: недопустимый путь"
        out = []
        for p in sorted(base.rglob("*")):
            if len(out) >= 300:
                break
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            if any(x in SKIP_DIRS for x in rel.split("/")[:-1]) or SKIP_FILE.match(p.name):
                continue
            out.append("%s (%d Б)" % (rel, p.stat().st_size))
        return "\n".join(out) or "Файлов нет"

    if name == "read_file":
        p = safe_path(root, args.get("path", ""))
        if p is None or not p.is_file():
            return "Ошибка: файл не найден"
        if p.stat().st_size > MAX_FILE:
            return "Ошибка: файл слишком большой"
        try:
            lines = p.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            return "Ошибка: бинарный файл"
        start = max(1, int(args.get("start") or 1))
        end = int(args.get("end") or 0) or min(len(lines), start + 400)
        end = min(len(lines), end)
        numbered = "\n".join("%5d| %s" % (i, lines[i - 1]) for i in range(start, end + 1))
        return _truncate("Файл %s, строки %d-%d из %d:\n%s" % (args.get("path"), start, end, len(lines), numbered))

    if name == "write_file":
        p = safe_path(root, args.get("path", ""))
        if p is None:
            return "Ошибка: недопустимый путь"
        text = str(args.get("text", ""))
        if len(text.encode("utf-8")) > MAX_FILE:
            return "Ошибка: файл слишком большой"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        run["events"].append({"kind": "file", "path": args.get("path"), "text": "записан"})
        return "Записано: %s (%d строк)" % (args.get("path"), len(text.splitlines()))

    if name == "edit_file":
        p = safe_path(root, args.get("path", ""))
        if p is None or not p.is_file():
            return "Ошибка: файл не найден"
        old = str(args.get("old", ""))
        new = str(args.get("new", ""))
        if not old:
            return "Ошибка: пустой фрагмент для замены"
        text = p.read_text(encoding="utf-8")
        if old not in text:
            return "Ошибка: фрагмент не найден. Прочитай файл и повтори с точным текстом."
        count = text.count(old)
        if count > 1:
            p.write_text(text.replace(old, new, 1), encoding="utf-8")
            result = "Заменено первое вхождение (%d совпадений)" % count
        else:
            p.write_text(text.replace(old, new, 1), encoding="utf-8")
            result = "Заменено"
        run["events"].append({"kind": "file", "path": args.get("path"), "text": result})
        return result + ": %s" % args.get("path")

    if name == "grep":
        base = safe_path(root, args.get("path", "")) if args.get("path") else root
        hits = search_files(base or root, str(args.get("pattern", "")), 120)
        return _truncate("\n".join("%s:%d: %s" % (h["path"], h["line"], h["text"]) for h in hits) or "Совпадений нет")

    if name == "use_skill":
        wanted = str(args.get("name", "")).strip().lower()
        for s in load_skills():
            if s.get("name", "").lower() == wanted:
                return "%s\n%s\n\n%s" % (s["name"], s.get("description", ""), s.get("body", ""))
        return "Навык не найден"

    if name == "update_plan":
        tasks = [str(t) for t in (args.get("tasks") or [])][:30]
        run["plan"] = tasks
        save_json(HOME / "plan.json", {"session": run.get("session"), "tasks": tasks, "ts": now_ms()})
        return "План обновлён (%d задач)" % len(tasks)

    if name == "bash":
        cmd = str(args.get("command", "")).strip()
        if not cmd:
            return "Ошибка: пустая команда"
        approved = run.get("autoApprove") or settings().get("autoApprove")
        if DANGER.search(cmd) and not approved:
            rid = secrets.token_hex(4)
            run["pending"] = {"id": rid, "command": cmd, "reason": "Опасная команда — нужно подтверждение"}
            run["approve"].clear()
            run["approve_result"] = None
            deadline = time.time() + 300
            while time.time() < deadline and not run["cancel"].is_set():
                if run["approve"].wait(0.5):
                    break
            result = run.get("approve_result")
            run["pending"] = None
            if result is not True:
                return "Команда отклонена пользователем"
        try:
            proc = subprocess.run(cmd, shell=True, cwd=str(root), capture_output=True, text=True,
                                  timeout=180, encoding="utf-8", errors="replace")
            out = (proc.stdout or "") + (("\nSTDERR:\n" + proc.stderr) if proc.stderr else "")
            run["events"].append({"kind": "bash", "command": cmd, "code": proc.returncode,
                                  "text": _truncate(out, 2000)})
            return _truncate("Код %d\n%s" % (proc.returncode, out or "(без вывода)"))
        except subprocess.TimeoutExpired:
            return "Ошибка: команда выполнялась дольше 180 секунд"
        except Exception as e:
            return "Ошибка запуска: " + mask(e)

    return "Неизвестный инструмент: " + str(name)


# ---------- запуск агента ----------

RUNS = {}
RUN_LOCK = threading.RLock()


def run_event(run, kind, **data):
    event = {"kind": kind, "ts": now_ms()}
    event.update({k: v for k, v in data.items() if v is not None})
    run["events"].append(event)
    if len(run["events"]) > 300:
        run["events"] = run["events"][-300:]
    append_jsonl(ACTIVITY, {"job": run["id"], **event})


def agent_worker(job_id, session_id, text, mode="agent"):
    run = RUNS[job_id]
    profile = active_profile()
    if profile is None:
        run["status"] = "error"
        run["error"] = "Сначала сохрани и выбери провайдера в разделе «Модели и ключи»"
        return
    project = run["project"]
    s = settings()
    run["snapshot"] = snapshot_project(project)
    max_steps = 4 if s["ecoMode"] else (8 if s["leanCalls"] else 16)
    max_msgs, max_chars = history_budget()
    history = trim_history(messages_for(session_id)[:-1], max_msgs, max_chars)
    messages = [{"role": "system", "content": build_system_prompt(project)}] + history + [{"role": "user", "content": text}]
    seen = {}
    final = ""
    steps = 0
    used_prompt = used_completion = 0
    try:
        while steps < max_steps:
            if run["cancel"].is_set():
                final = "Остановлено пользователем."
                break
            msg, usage = call_llm_with_fallback(profile, messages, TOOL_SCHEMA if mode != "ask" else None)
            used_prompt += int(usage.get("prompt_tokens", 0) or 0)
            used_completion += int(usage.get("completion_tokens", 0) or 0)
            calls = msg.get("tool_calls") or []
            content = _content_text(msg.get("content"))
            if content.strip():
                run_event(run, "thought", text=content[-1500:])
            if not calls or mode == "ask":
                final = content
                break
            messages.append({"role": "assistant", "content": content, "tool_calls": calls})
            for tc in calls:
                if run["cancel"].is_set():
                    break
                fn = (tc.get("function") or {}) if isinstance(tc, dict) else {}
                name = str(fn.get("name") or "")
                raw_args = fn.get("arguments") or "{}"
                try:
                    args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
                except ValueError:
                    args = {}
                key = hashlib.sha1((name + json.dumps(args, sort_keys=True, ensure_ascii=False)).encode()).hexdigest()
                if key in seen:
                    result = seen[key]
                    run_event(run, "tool_cached", name=name)
                else:
                    run_event(run, "tool_start", name=name, args=_truncate(json.dumps(args, ensure_ascii=False), 700))
                    result = execute_tool(run, name, args)
                    seen[key] = result
                    run_event(run, "tool", name=name, text=result[:1200])
                messages.append({"role": "tool", "tool_call_id": tc.get("id", ""), "content": result[:12000]})
            steps += 1
        if not final:
            final = "Достигнут предел шагов. Уточни задачу или продолжи диалог." if not run["cancel"].is_set() else "Остановлено пользователем."
    except Exception as e:
        run["status"] = "error"
        run["error"] = mask(e)
        final = ""
    if final:
        add_message("assistant", final, session_id)
        send_later("message", {"role": "assistant", "text": final}, project)
        run["text"] = final
    if run["status"] == "running":
        run["status"] = "cancelled" if run["cancel"].is_set() else ("error" if run.get("error") else "done")
    record_usage(used_prompt or estimate_tokens("\n".join(str(m.get("content", "")) for m in messages)),
                 used_completion or estimate_tokens(final),
                 saved=max(0, 4000 - len(final)) if s["leanCalls"] else 0)
    run["steps"] = steps


def start_run(session_id, text, mode="agent"):
    profile = active_profile()
    if profile is None:
        raise ValueError("Сначала сохрани и выбери провайдера в разделе «Модели и ключи»")
    project = session_project(session_id)
    with RUN_LOCK:
        for r in RUNS.values():
            if r.get("session") == session_id and r.get("status") == "running":
                raise ValueError("В этом чате уже выполняется задача")
        job = secrets.token_hex(8)
        run = {
            "id": job, "session": session_id, "project": project, "status": "running",
            "events": [], "text": "", "error": "", "plan": [],
            "pending": None, "approve": threading.Event(), "approve_result": None,
            "cancel": threading.Event(), "snapshot": "", "autoApprove": settings().get("autoApprove"),
        }
        RUNS[job] = run
        if len(RUNS) > 30:
            for old in [k for k, v in list(RUNS.items()) if v.get("status") != "running"][:-10]:
                RUNS.pop(old, None)
    add_message("user", text, session_id)
    send_later("message", {"role": "user", "text": text}, project)
    threading.Thread(target=agent_worker, args=(job, session_id, text, mode), daemon=True).start()
    return job


def run_state(job):
    run = RUNS.get(job)
    if run is None:
        return {"error": "Задача не найдена"}
    return {
        "id": run["id"], "status": run["status"], "text": run.get("text", ""),
        "error": run.get("error", ""), "events": run.get("events", [])[-200:],
        "pending": run.get("pending"), "plan": run.get("plan", []),
        "snapshot": run.get("snapshot", ""), "session": run.get("session"),
    }


# ---------- GitHub: поиск с переводом и постранично ----------

RU_EN = {
    "навыки": "skills", "навык": "skill", "плагины": "plugins", "плагин": "plugin",
    "агенты": "agents", "агент": "agent", "инструменты": "tools", "инструмент": "tool",
    "сайт": "website", "сайты": "websites", "лендинг": "landing page", "интерфейс": "ui",
    "интерфейсы": "ui", "тема": "theme", "темы": "themes", "шаблон": "template", "шаблоны": "templates",
    "современный": "modern", "современные": "modern", "дизайн": "design", "красивый": "beautiful",
    "анимации": "animations", "анимация": "animation", "компоненты": "components", "компонент": "component",
    "приложение": "app", "приложения": "apps", "мобильный": "mobile", "дашборд": "dashboard",
    "графики": "charts", "график": "chart", "игра": "game", "игры": "games", "бот": "bot",
    "сервер": "server", "клиент": "client", "библиотека": "library", "фреймворк": "framework",
    "коллекция": "collection", "подборка": "awesome", "примеры": "examples", "документация": "docs",
    "автоматизация": "automation", "рабочий": "workflow", "процесс": "workflow", "код": "code",
    "модель": "model", "модели": "models", "локальные": "local", "локальная": "local",
    "промпт": "prompt", "промпты": "prompts", "для": "", "и": "and", "на": "", "в": "", "с": "",
}

RU_EN_DESC = {
    "skills": "навыки", "skill": "навык", "plugins": "плагины", "plugin": "плагин",
    "agents": "агенты", "agent": "агент", "tools": "инструменты", "tool": "инструмент",
    "collection": "коллекция", "awesome": "подборка", "examples": "примеры", "example": "пример",
    "framework": "фреймворк", "library": "библиотека", "client": "клиент", "server": "сервер",
    "open source": "с открытым кодом", "source": "исходный код", "code": "код",
    "documentation": "документация", "docs": "документация", "tutorial": "руководство",
    "templates": "шаблоны", "template": "шаблон", "config": "конфигурация", "configuration": "конфигурация",
    "integration": "интеграция", "support": "поддержка", "build": "сборка", "cli": "командная строка",
    "model": "модель", "models": "модели", "prompt": "промпт", "prompts": "промпты",
    "workflow": "рабочий процесс", "automation": "автоматизация", "development": "разработка",
    "developer": "разработчик", "website": "сайт", "websites": "сайты", "landing": "лендинг",
    "ui": "интерфейс", "theme": "тема", "themes": "темы", "design": "дизайн", "modern": "современный",
    "dashboard": "дашборд", "charts": "графики", "mobile": "мобильный", "app": "приложение",
    "game": "игра", "games": "игры", "bot": "бот", "local": "локальный", "fast": "быстрый",
}


def translate_query(text):
    """Переводит запрос на английский по словнику, чтобы GitHub находил репозитории."""
    words = str(text or "").split()
    out = []
    changed = False
    for word in words:
        clean = re.sub(r"[^\w\-]", "", word).lower()
        if re.search(r"[а-яё]", clean, re.I):
            mapped = RU_EN.get(clean)
            if mapped is None:
                mapped = RU_EN.get(clean.rstrip("ыеийаяов"))
            if mapped:
                out.append(mapped)
                changed = True
            continue
        out.append(word)
    result = " ".join(x for x in out if x).strip()
    return (result, True) if changed and result else (str(text or "").strip(), False)


def translate_description(text, language=""):
    if not text:
        return ""
    lang = str(language or "").lower()
    if lang.startswith("ru"):
        return text
    out = text
    for en, ru in sorted(RU_EN_DESC.items(), key=lambda kv: -len(kv[0])):
        out = re.sub(r"(?<![a-z])" + re.escape(en) + r"(?![a-z])", ru, out, flags=re.I)
    return out if out != text else ""


CATEGORY_WORDS = {"Навыки": "skills", "Агенты": "agent skill", "MCP": "mcp server", "Плагины": "plugin"}


def github_search(query, category="Все", sort="stars", page=1, per_page=30, translate=True, token=""):
    q = str(query or "").strip()
    used, changed = translate_query(q) if translate else (q, False)
    words = [used]
    cat = CATEGORY_WORDS.get(category, "")
    if cat:
        words.append(cat)
    if used:
        words.append("in:name,description,readme")
    full = " ".join(w for w in words if w)
    url = ("https://api.github.com/search/repositories?q=" + quote(full) +
           "&sort=" + (sort if sort in ("stars", "forks", "updated") else "stars") +
           "&order=desc&per_page=" + str(max(1, min(100, int(per_page or 30)))) +
           "&page=" + str(max(1, int(page or 1))))
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "PocketCode-Desktop"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:300]
        except Exception:
            pass
        if e.code in (403, 429):
            raise ValueError("GitHub ограничил запросы. Добавь токен GitHub в настройках или повтори позже.") from e
        raise ValueError("GitHub: HTTP %s %s" % (e.code, detail)) from e
    items = []
    for o in data.get("items", [])[:100]:
        name = o.get("full_name")
        if not name:
            continue
        desc = o.get("description") or ""
        items.append({
            "full_name": name, "description": desc,
            "description_ru": translate_description(desc, o.get("language") or ""),
            "stars": int(o.get("stargazers_count", 0) or 0),
            "url": o.get("html_url") or ("https://github.com/" + name),
            "language": o.get("language") or "", "updated_at": o.get("updated_at") or "",
        })
    return {
        "items": items, "total": int(data.get("total_count", 0) or 0),
        "query": used, "query_translated": changed, "page": page, "per_page": per_page,
    }


# ---------- локальные модели: каталог, скачивание, сервер ----------

LOCAL_CATALOG = [
    {"id": "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", "file": "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
     "url": "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
     "params_b": 7, "context_k": 32, "quality": 8, "min_ram_gb": 8, "note": "Сильный помощник по коду, нужен ПК с 8+ ГБ ОЗУ."},
    {"id": "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF", "file": "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
     "url": "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
     "params_b": 3, "context_k": 32, "quality": 7, "min_ram_gb": 4, "note": "Быстрая и лёгкая, хорошо для небольших правок."},
    {"id": "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", "file": "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
     "url": "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
     "params_b": 1.5, "context_k": 32, "quality": 6, "min_ram_gb": 3, "note": "Самая лёгкая: работает даже на слабом ноутбуке."},
    {"id": "Qwen/Qwen2.5-7B-Instruct-GGUF", "file": "qwen2.5-7b-instruct-q4_k_m.gguf",
     "url": "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
     "params_b": 7, "context_k": 32, "quality": 8, "min_ram_gb": 8, "note": "Универсальная модель для текста и кода."},
    {"id": "bartowski/Llama-3.2-3B-Instruct-GGUF", "file": "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
     "params_b": 3, "context_k": 8, "quality": 6, "min_ram_gb": 4, "note": "Лёгкая Llama для диалога и объяснений."},
    {"id": "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", "file": "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
     "params_b": 8, "context_k": 8, "quality": 8, "min_ram_gb": 10, "note": "Крепкая универсальная модель, 10+ ГБ ОЗУ."},
    {"id": "bartowski/gemma-2-2b-it-GGUF", "file": "gemma-2-2b-it-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
     "params_b": 2, "context_k": 8, "quality": 6, "min_ram_gb": 3, "note": "Компактная Gemma: хороша для подсказок и текста."},
    {"id": "bartowski/Phi-3.5-mini-instruct-GGUF", "file": "Phi-3.5-mini-instruct-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
     "params_b": 3.8, "context_k": 128, "quality": 7, "min_ram_gb": 5, "note": "Большой контекст, аккуратные объяснения."},
    {"id": "bartowski/falcon3-3B-Instruct-GGUF", "file": "falcon3-3B-Instruct-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/falcon3-3B-Instruct-GGUF/resolve/main/falcon3-3B-Instruct-Q4_K_M.gguf",
     "params_b": 3, "context_k": 32, "quality": 6, "min_ram_gb": 4, "note": "Свежая небольшая модель от TII."},
    {"id": "bartowski/SmolLM2-1.7B-Instruct-GGUF", "file": "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
     "url": "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
     "params_b": 1.7, "context_k": 8, "quality": 5, "min_ram_gb": 2, "note": "Очень маленькая: для быстрых черновиков."},
]

DOWNLOADS = {}
MODEL_PROC = None
MODEL_LOG = HOME / "llama-server.log"


def downloaded_models():
    return [{"name": p.name, "size": p.stat().st_size} for p in sorted(DOWNLOADS_DIR.glob("*.gguf")) if p.is_file()]


def start_download(name, url, filename=None):
    base = safe_name(filename or name or Path(urlparse(url).path).stem)
    filename = base if base.lower().endswith(".gguf") else base + ".gguf"
    if not str(url).startswith(("http://", "https://")):
        raise ValueError("Нужен http(s)-адрес файла .gguf")
    target = DOWNLOADS_DIR / filename
    with LOCK:
        existing = DOWNLOADS.get(filename)
        if existing and existing.get("status") == "downloading":
            raise ValueError("Эта модель уже скачивается")
        DOWNLOADS[filename] = {"name": filename, "url": url, "done": 0, "total": 0,
                               "status": "downloading", "error": "", "cancel": threading.Event()}
    threading.Thread(target=_download_worker, args=(filename, url, target), daemon=True).start()
    return filename


def _download_worker(filename, url, target):
    info = DOWNLOADS[filename]
    part = target.with_suffix(target.suffix + ".part")
    try:
        resume = part.stat().st_size if part.exists() else 0
        headers = {"User-Agent": "PocketCode-Desktop"}
        if resume:
            headers["Range"] = "bytes=%d-" % resume
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=60) as r:
            total = int(r.headers.get("Content-Length") or 0) + (resume if resume else 0)
            info["total"] = total
            mode = "ab" if resume and r.status == 206 else "wb"
            if mode == "wb":
                resume = 0
                info["done"] = 0
            with part.open(mode) as f:
                while True:
                    if info["cancel"].is_set():
                        info["status"] = "cancelled"
                        return
                    chunk = r.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
                    info["done"] += len(chunk)
        if info["cancel"].is_set():
            info["status"] = "cancelled"
            return
        os.replace(part, target)
        info["status"] = "done"
        info["done"] = target.stat().st_size
        info["total"] = info["done"]
    except Exception as e:
        info["status"] = "error"
        info["error"] = mask(e)
    finally:
        if info["status"] == "cancelled" and part.exists():
            try:
                part.unlink()
            except OSError:
                pass


def start_model_server(port=None):
    global MODEL_PROC
    if MODEL_PROC is not None and MODEL_PROC.poll() is None:
        return True, "Сервер уже запущен"
    s = settings()
    model = str(s.get("localModel") or "")
    if not model:
        models = downloaded_models()
        if not models:
            return False, "Сначала скачай модель"
        model = models[0]["name"]
    path = safe_path(DOWNLOADS_DIR, model)
    if path is None or not path.is_file():
        return False, "Файл модели не найден"
    binary = shutil.which("llama-server") or shutil.which("llama-server.exe")
    if not binary:
        return False, "llama-server не найден в PATH. Установи llama.cpp или подключи LM Studio/Ollama."
    port = int(port or s.get("localPort") or 8080)
    log = MODEL_LOG.open("a", encoding="utf-8")
    try:
        MODEL_PROC = subprocess.Popen(
            [binary, "-m", str(path), "--port", str(port), "--host", "127.0.0.1",
             "-c", str(int(s.get("localContext") or 8192)), "-ngl", str(int(s.get("localGpuLayers") or 0))],
            stdout=log, stderr=log, cwd=str(HOME),
        )
    except Exception as e:
        return False, mask(e)
    return True, "Сервер запускается на 127.0.0.1:%d" % port


def stop_model_server():
    global MODEL_PROC
    if MODEL_PROC is None or MODEL_PROC.poll() is not None:
        MODEL_PROC = None
        return False, "Сервер не запущен"
    MODEL_PROC.terminate()
    try:
        MODEL_PROC.wait(timeout=8)
    except subprocess.TimeoutExpired:
        MODEL_PROC.kill()
    MODEL_PROC = None
    return True, "Сервер остановлен"


def model_server_log():
    try:
        return MODEL_LOG.read_text(encoding="utf-8", errors="replace")[-4000:]
    except OSError:
        return ""


# ---------- синхронизация ----------

def next_seq():
    state = load_json(SYNC_STATE, {"seq": 0})
    state["seq"] = int(state.get("seq", 0)) + 1
    save_json(SYNC_STATE, state)
    return state["seq"]


def make_event(kind, data, project=""):
    return {
        "v": 2, "type": "event", "seq": next_seq(), "device": "PocketCode Desktop",
        "project": project, "ts": now_ms(), "kind": kind, "data": data,
    }


def full_packet():
    msgs = read_jsonl(CHAT)[-200:]
    files = []
    root = PROJECTS.resolve()
    if root.is_dir():
        for p in root.rglob("*"):
            if len(files) >= MAX_FILES:
                break
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            parts = rel.split("/")
            if any(x in SKIP_DIRS for x in parts[:-1]) or SKIP_FILE.match(parts[-1]) or p.stat().st_size > MAX_FILE:
                continue
            try:
                files.append({"path": rel, "text": p.read_text(encoding="utf-8")})
            except (OSError, UnicodeDecodeError):
                continue
    return {
        "v": 2, "type": "full", "seq": next_seq(), "device": "PocketCode Desktop",
        "project": session_project(active_session_id()) or "desktop",
        "ts": now_ms(), "items": msgs, "files": files,
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
    return "PCENC1:" + base64.b64encode(iv).decode() + ":" + base64.b64encode(enc).decode()


def pcenc_decrypt(text, password):
    """Расшифровать пакет от телефона; при неверном пароле вернуть None."""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
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


def public_sync_cfg():
    cfg = load_json(SYNC_CONFIG, {})
    return {k: v for k, v in cfg.items() if k not in ("password", "token")}


# ---------- HTTP ----------

class Handler(BaseHTTPRequestHandler):
    server_version = "PocketCodeDesktop/4"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
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
        if self.client_address[0] in ("127.0.0.1", "::1") or self.token_ok():
            return True
        self.send({"error": "token required"}, 401)
        return False

    def body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0 or n > 20_000_000:
            raise ValueError("Некорректный размер запроса")
        return json.loads(self.rfile.read(n).decode("utf-8", "replace"))

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-PocketCode-Token")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

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
        parsed = urlparse(self.path)
        path = parsed.path
        q = parse_qs(parsed.query)

        def one(key, default=""):
            return str(q.get(key, [default])[0])

        try:
            if path == "/api/state":
                self.send(bootstrap())
            elif path == "/api/messages":
                self.send(messages_for(one("session") or active_session_id()))
            elif path == "/api/sessions":
                self.send(load_sessions())
            elif path == "/api/profiles":
                self.send(public_profiles())
            elif path == "/api/tasks":
                self.send(load_json(TASKS, []))
            elif path == "/api/agents":
                self.send(load_json(AGENTS, []))
            elif path == "/api/roles":
                self.send(load_json(ROLES, {}))
            elif path == "/api/widgets":
                self.send(load_json(WIDGETS, []))
            elif path == "/api/settings":
                safe = settings()
                safe["githubToken"] = ""
                self.send(safe)
            elif path == "/api/skills":
                self.send(load_skills())
            elif path == "/api/models":
                self.send(load_json(MODELS, []))
            elif path == "/api/models/catalog":
                self.send({"catalog": LOCAL_CATALOG, "downloaded": downloaded_models()})
            elif path == "/api/models/downloads":
                with LOCK:
                    self.send([{"name": v["name"], "done": v["done"], "total": v["total"],
                                "status": v["status"], "error": v["error"]} for v in DOWNLOADS.values()])
            elif path == "/api/models/server":
                self.send({"running": MODEL_PROC is not None and MODEL_PROC.poll() is None,
                           "log": model_server_log()})
            elif path == "/api/queue":
                self.send(load_json(QUEUE, []))
            elif path == "/api/projects":
                self.send(list_projects())
            elif path == "/api/files":
                root = project_root(one("project"))
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
                root = project_root(one("project"))
                fp = safe_path(root, one("path")) if root else None
                if fp is None or not fp.is_file() or fp.stat().st_size > MAX_FILE:
                    self.send({"error": "Файл не найден"}, 404); return
                try:
                    self.send({"path": one("path"), "text": fp.read_text(encoding="utf-8")})
                except UnicodeDecodeError:
                    self.send({"error": "Это бинарный файл"}, 400)
            elif path == "/api/search":
                root = project_root(one("project"))
                self.send(search_files(root, one("q"), 300))
            elif path == "/api/snapshots":
                self.send(list_snapshots(one("project")))
            elif path == "/api/changes":
                self.send(snapshot_changes(one("project"), one("stamp")))
            elif path == "/api/git":
                root = project_root(one("project"))
                self.send(git_action(root, one("action", "status")))
            elif path == "/api/storage":
                self.send(storage_info())
            elif path == "/api/context":
                sid = one("session") or active_session_id()
                msgs = messages_for(sid)
                text = build_system_prompt(session_project(sid)) + "".join(str(m.get("text", "")) for m in msgs)
                self.send({"messages": len(msgs), "chars": len(text), "tokens": estimate_tokens(text),
                           "skills": len([s for s in load_skills() if s.get("enabled", True)]),
                           "system_prompt": build_system_prompt(session_project(sid))})
            elif path == "/api/usage":
                self.send(load_json(USAGE, {"requests": 0, "prompt": 0, "completion": 0, "saved": 0}))
            elif path == "/api/diagnostics":
                self.send(diagnostics())
            elif path == "/api/update/check":
                self.send(check_updates())
            elif path == "/api/github/search":
                s = settings()
                self.send(github_search(
                    one("q"), one("category", "Все"), one("sort", "stars"),
                    int(one("page", "1") or 1), int(one("per_page", str(s.get("githubPerPage", 30))) or 30),
                    one("translate", "1") != "0", str(s.get("githubToken", "")),
                ))
            elif path == "/api/run":
                self.send(run_state(one("job")))
            elif path == "/api/events":
                try:
                    since = int(one("since", "0") or 0)
                except ValueError:
                    since = 0
                deadline = time.time() + 15
                events = []
                while time.time() < deadline:
                    events = [x for x in read_jsonl(SYNC_EVENTS) if int(x.get("seq", 0)) > since]
                    if events:
                        break
                    time.sleep(0.5)
                self.send(events[-100:])
            elif path == "/api/sync/status":
                state = load_json(SYNC_STATE, {"seq": 0, "last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
                self.send({**state, "pending": len(read_lines(SYNC_OUTBOX)), "cfg": public_sync_cfg()})
            elif path == "/api/sync/token":
                if self.client_address[0] not in ("127.0.0.1", "::1") and not self.token_ok():
                    self.send({"error": "local only"}, 403); return
                self.send({"token": access_token()})
            elif path == "/api/sync/pull":
                try:
                    since = int(one("since", "0") or 0)
                except ValueError:
                    since = 0
                self.send([x for x in read_jsonl(SYNC_EVENTS) if int(x.get("seq", 0)) > since][-200:])
            elif path == "/api/messages/full":
                self.send({"version": 1, "items": read_jsonl(CHAT), "project": session_project(active_session_id())})
            elif path == "/api/sync/pack":
                root = project_root(session_project(active_session_id()))
                self.send({"version": 1, "items": read_jsonl(CHAT), "project": session_project(active_session_id()),
                           "files": collect_files(root) if root and root.is_dir() else []})
            elif path == "/api/backup":
                self.send(backup_export())
            else:
                self.send({"error": "not found"}, 404)
        except Exception as e:
            self.send({"error": mask(e)}, 500)

    def do_POST(self):
        try:
            b = self.body()
            path = urlparse(self.path).path
            if not isinstance(b, dict):
                b = {}
            if path == "/api/chat":
                sid = str(b.get("session") or active_session_id())
                text = str(b.get("text", "")).strip()
                if not text:
                    self.send({"error": "Пустое сообщение"}, 400); return
                try:
                    job = start_run(sid, text, str(b.get("mode") or "agent"))
                except ValueError as e:
                    self.send({"error": mask(e)}, 400); return
                self.send({"ok": True, "job": job, "session": sid})
            elif path == "/api/run/cancel":
                run = RUNS.get(str(b.get("job") or ""))
                if run:
                    run["cancel"].set()
                    run["approve_result"] = False
                    run["approve"].set()
                self.send({"ok": True})
            elif path == "/api/approve":
                run = RUNS.get(str(b.get("job") or ""))
                if not run or not run.get("pending"):
                    self.send({"error": "Нет команды на подтверждение"}, 404); return
                run["approve_result"] = bool(b.get("approve"))
                run["approve"].set()
                self.send({"ok": True})
            elif path == "/api/message":
                role = str(b.get("role", "user"))
                text = str(b.get("text", "")).strip()
                if role not in ("user", "assistant") or not text:
                    self.send({"error": "Нужны role и text"}, 400); return
                add_message(role, text, str(b.get("session") or active_session_id()))
                send_later("message", {"role": role, "text": text})
                self.send({"ok": True})
            elif path == "/api/clear":
                sid = str(b.get("session") or active_session_id())
                with LOCK:
                    keep = [m for m in read_jsonl(CHAT) if str(m.get("session") or "default") != sid]
                    CHAT.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in keep), encoding="utf-8")
                self.send({"ok": True})
            elif path == "/api/session/new":
                self.send({"ok": True, "session": new_session(str(b.get("title") or "Новый чат"), str(b.get("project") or "desktop"))})
            elif path == "/api/session/switch":
                sessions = load_sessions()
                sid = str(b.get("id") or "")
                if any(s.get("id") == sid for s in sessions["items"]):
                    sessions["active"] = sid
                    save_json(SESSIONS, sessions)
                self.send({"ok": True, "session": sessions["active"]})
            elif path == "/api/session/rename":
                sessions = load_sessions()
                for s in sessions["items"]:
                    if s.get("id") == b.get("id"):
                        s["title"] = str(b.get("title") or s["title"])[:80]
                        s["project"] = str(b.get("project") or s.get("project") or "desktop")
                save_json(SESSIONS, sessions)
                self.send({"ok": True})
            elif path == "/api/session/delete":
                self.send({"ok": True, "sessions": delete_session(str(b.get("id") or ""))})
            elif path == "/api/profiles":
                name = str(b.get("name", "")).strip()
                base = str(b.get("base_url", "")).strip()
                model = str(b.get("model", "")).strip()
                if not name or not base or not model:
                    self.send({"error": "Нужны имя, адрес сервера и модель"}, 400); return
                if not base.startswith(("http://", "https://")):
                    self.send({"error": "Адрес должен начинаться с http:// или https://"}, 400); return
                profiles = load_json(PROFILES, {})
                old = profiles.get(name, {}) if isinstance(profiles.get(name), dict) else {}
                key = b.get("api_key", "")
                profiles[name] = {
                    "base_url": base, "model": model,
                    "api_key": key if key else old.get("api_key", ""),
                    "max_tokens": int(b.get("max_tokens", 0) or 0),
                    "temperature": float(b.get("temperature", old.get("temperature", 0.4)) or 0.4),
                }
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
            elif path == "/api/profile/delete":
                profiles = load_json(PROFILES, {})
                profiles.pop(str(b.get("name") or ""), None)
                if profiles.get("_active") == b.get("name"):
                    profiles["_active"] = next((k for k in profiles if k != "_active"), "")
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
            elif path == "/api/agent/delete":
                save_json(AGENTS, [a for a in load_json(AGENTS, []) if a.get("name") != b.get("name")])
                self.send({"ok": True})
            elif path == "/api/roles":
                save_json(ROLES, b)
                self.send({"ok": True})
            elif path == "/api/settings":
                old = load_json(SETTINGS, {})
                old.update({k: v for k, v in b.items() if isinstance(k, str)})
                save_json(SETTINGS, old)
                self.send({"ok": True, "settings": settings()})
            elif path == "/api/skills":
                name = str(b.get("name", "")).strip()
                if not name or not str(b.get("body", "")).strip():
                    self.send({"error": "Нужны имя и текст навыка"}, 400); return
                skills = [s for s in load_json(SKILLS, []) if s.get("name") != name]
                skills.append({"name": name, "description": str(b.get("description", "")),
                               "body": str(b.get("body", "")), "enabled": True})
                save_json(SKILLS, skills)
                self.send({"ok": True})
            elif path == "/api/skill/toggle":
                name = str(b.get("name", ""))
                enabled = bool(b.get("enabled", True))
                if any(s["name"] == name for s in BUILTIN_SKILLS):
                    stored = load_json(SKILLS, [])
                    stored = [s for s in stored if s.get("name") != name]
                    stored.append({"name": name, "enabled": enabled})
                    save_json(SKILLS, stored)
                else:
                    skills = load_json(SKILLS, [])
                    for s in skills:
                        if s.get("name") == name:
                            s["enabled"] = enabled
                    save_json(SKILLS, skills)
                self.send({"ok": True})
            elif path == "/api/skill/delete":
                save_json(SKILLS, [s for s in load_json(SKILLS, []) if s.get("name") != b.get("name")])
                self.send({"ok": True})
            elif path == "/api/skill/install":
                repo = str(b.get("repo") or b.get("url") or "").strip().rstrip("/")
                repo = re.sub(r"^https?://github\.com/", "", repo)
                if not re.match(r"^[\w.-]+/[\w.-]+$", repo):
                    self.send({"error": "Нужен репозиторий вида owner/name"}, 400); return
                text, meta = fetch_repo_skill(repo)
                if not text:
                    self.send({"error": "Не удалось прочитать README/SKILL.md репозитория"}, 400); return
                skills = [s for s in load_json(SKILLS, []) if s.get("name") != meta["name"]]
                skills.append(meta | {"body": text})
                save_json(SKILLS, skills)
                self.send({"ok": True, "name": meta["name"]})
            elif path == "/api/models":
                models = load_json(MODELS, [])
                name = str(b.get("name", "")).strip()
                url = str(b.get("url", "")).strip()
                if not name or not url.startswith(("http://", "https://")):
                    self.send({"error": "Нужны имя и HTTP(S)-адрес"}, 400); return
                models = [m for m in models if m.get("name") != name]
                models.append({"name": name, "url": url, "model": str(b.get("model", ""))})
                save_json(MODELS, models)
                self.send({"ok": True})
            elif path == "/api/models/use":
                profiles = load_json(PROFILES, {})
                if b.get("mode") == "server":
                    port = int(settings().get("localPort") or 8080)
                    profiles["local"] = {"base_url": "http://127.0.0.1:%d/v1" % port,
                                         "model": str(b.get("model") or "local-model"), "api_key": "local", "max_tokens": 2048}
                else:
                    m = next((x for x in load_json(MODELS, []) if x.get("name") == b.get("name")), None)
                    if m is None:
                        self.send({"error": "Нет такой модели"}, 404); return
                    profiles["local"] = {"base_url": m["url"], "model": m.get("model", m["name"]),
                                         "api_key": "local", "max_tokens": 2048}
                profiles["_active"] = "local"
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif path == "/api/models/download":
                name = str(b.get("name") or b.get("file") or "")
                url = str(b.get("url") or "")
                if not url:
                    item = next((x for x in LOCAL_CATALOG if x["id"] == b.get("id")), None)
                    if item is None:
                        self.send({"error": "Неизвестная модель"}, 404); return
                    name, url = item["file"], item["url"]
                self.send({"ok": True, "file": start_download(name, url, b.get("file"))})
            elif path == "/api/models/cancel":
                with LOCK:
                    info = DOWNLOADS.get(str(b.get("name") or ""))
                if info:
                    info["cancel"].set()
                    info["status"] = "cancelled"
                self.send({"ok": True})
            elif path == "/api/models/delete":
                p = safe_path(DOWNLOADS_DIR, str(b.get("name") or ""))
                if p and p.is_file():
                    p.unlink()
                self.send({"ok": True})
            elif path == "/api/models/server":
                action = str(b.get("action") or "start")
                if action == "stop":
                    good, detail = stop_model_server()
                else:
                    good, detail = start_model_server(int(b.get("port") or 0) or None)
                self.send({"ok": good, "detail": detail})
            elif path == "/api/queue":
                items = load_json(QUEUE, [])
                items.append({"id": secrets.token_hex(4), "text": str(b.get("text", "")).strip(), "ts": now_ms()})
                save_json(QUEUE, items)
                self.send({"ok": True, "size": len(items)})
            elif path == "/api/queue/remove":
                save_json(QUEUE, [x for x in load_json(QUEUE, []) if x.get("id") != b.get("id")])
                self.send({"ok": True})
            elif path == "/api/queue/run":
                items = load_json(QUEUE, [])
                if not items:
                    self.send({"error": "Очередь пуста"}, 400); return
                item = items.pop(0)
                save_json(QUEUE, items)
                self.send({"ok": True, "text": item.get("text", "")})
            elif path == "/api/file":
                root = project_root(str(b.get("project", "")))
                fp = safe_path(root, str(b.get("path", ""))) if root else None
                if fp is None:
                    self.send({"error": "Недопустимый путь"}, 400); return
                text = str(b.get("text", ""))
                if len(text.encode("utf-8")) > MAX_FILE:
                    self.send({"error": "Файл слишком большой"}, 400); return
                fp.parent.mkdir(parents=True, exist_ok=True)
                fp.write_text(text, encoding="utf-8")
                send_later("file", {"path": fp.relative_to(root).as_posix(), "text": text})
                self.send({"ok": True})
            elif path == "/api/file/delete":
                root = project_root(str(b.get("project", "")))
                fp = safe_path(root, str(b.get("path", ""))) if root else None
                if fp and fp.is_file():
                    fp.unlink()
                self.send({"ok": True})
            elif path == "/api/project":
                root = create_project(str(b.get("name", "")))
                if root is None:
                    self.send({"error": "Недопустимое имя проекта"}, 400); return
                self.send({"ok": True, "name": root.name})
            elif path == "/api/project/open":
                root = open_project(str(b.get("name") or Path(str(b.get("path", ""))).name), str(b.get("path", "")))
                if root is None:
                    self.send({"error": "Папка не найдена"}, 404); return
                self.send({"ok": True, "name": safe_name(str(b.get("name") or root.name))})
            elif path == "/api/project/delete":
                name = str(b.get("name") or "")
                root = project_root(name)
                if root and root.parent == PROJECTS.resolve() and root.is_dir():
                    shutil.rmtree(root, ignore_errors=True)
                data = load_json(PROJECTS_MAP, {})
                data.pop(name, None)
                save_json(PROJECTS_MAP, data)
                self.send({"ok": True})
            elif path == "/api/snapshot/restore":
                count = restore_snapshot(str(b.get("project") or ""), str(b.get("stamp") or ""))
                self.send({"ok": True, "restored": count})
            elif path == "/api/terminal":
                root = project_root(str(b.get("project") or ""))
                cmd = str(b.get("command") or "").strip()
                if root is None or not cmd:
                    self.send({"error": "Нужны проект и команда"}, 400); return
                if DANGER.search(cmd) and not settings().get("autoApprove") and not b.get("confirmed"):
                    self.send({"error": "Опасная команда: подтверди запуск", "confirm": True}, 409); return
                proc = subprocess.run(cmd, shell=True, cwd=str(root), capture_output=True, text=True,
                                      timeout=120, encoding="utf-8", errors="replace")
                self.send({"code": proc.returncode, "stdout": _truncate(proc.stdout, 20000),
                           "stderr": _truncate(proc.stderr, 10000)})
            elif path == "/api/git":
                root = project_root(str(b.get("project") or ""))
                self.send(git_action(root, str(b.get("action") or "status"), b))
            elif path == "/api/github/clone":
                url = str(b.get("url") or "").strip()
                if not re.match(r"^https://github\.com/[\w.-]+/[\w.-]+(\.git)?$", url):
                    self.send({"error": "Нужен https://github.com/owner/name"}, 400); return
                name = safe_name(Path(urlparse(url).path).stem)
                root = safe_path(PROJECTS, name)
                if root is None or root.exists():
                    self.send({"error": "Проект с таким именем уже есть"}, 400); return
                proc = subprocess.run(["git", "clone", "--depth", "1", url, str(root)],
                                      capture_output=True, text=True, timeout=300, encoding="utf-8", errors="replace")
                if proc.returncode != 0:
                    self.send({"error": mask(proc.stderr or "git clone не удался")}, 400); return
                self.send({"ok": True, "name": name})
            elif path == "/api/compare":
                prompt = str(b.get("prompt") or "").strip()
                names = [n for n in (b.get("profiles") or [])][:4]
                if not prompt or not names:
                    self.send({"error": "Нужны запрос и модели"}, 400); return
                results = compare_models(prompt, names)
                self.send(results)
            elif path == "/api/backup/import":
                self.send(backup_import(b))
            elif path == "/api/sync/import":
                items = b.get("items", [])
                if not isinstance(items, list):
                    self.send({"error": "Нужны items"}, 400); return
                sid = active_session_id()
                with LOCK:
                    with CHAT.open("a", encoding="utf-8") as f:
                        for m in items[:500]:
                            if isinstance(m, dict):
                                f.write(json.dumps({"role": m.get("role", "user"), "text": str(m.get("text", "")),
                                                    "session": sid, "ts": m.get("ts", now_ms())}, ensure_ascii=False) + "\n")
                self.send({"ok": True, "imported": len(items)})
            elif path == "/api/sync/config":
                cfg = load_json(SYNC_CONFIG, {})
                cfg.update(b)
                cfg.pop("token", None)
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
                        sid = active_session_id()
                        with LOCK:
                            CHAT.write_text("".join(json.dumps(
                                {"role": m.get("role", "user"), "text": str(m.get("text", "")), "ts": m.get("ts", 0),
                                 "session": sid}, ensure_ascii=False) + "\n" for m in items[-500:] if isinstance(m, dict)), encoding="utf-8")
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


def git_action(root, action, body=None):
    body = body or {}
    if root is None or not root.is_dir():
        return {"error": "Проект не найден"}
    if not shutil.which("git"):
        return {"error": "git не установлен"}
    def run(*args):
        proc = subprocess.run(["git", *args], cwd=str(root), capture_output=True, text=True,
                              timeout=60, encoding="utf-8", errors="replace")
        return proc.stdout + (("\n" + proc.stderr) if proc.stderr.strip() else "")

    if action == "status":
        return {"text": run("status", "--short", "--branch") or "Чисто, изменений нет"}
    if action == "log":
        return {"text": run("log", "--oneline", "--decorate", "-20")}
    if action == "diff":
        return {"text": _truncate(run("diff", "--stat") + "\n" + run("diff"), 20000)}
    if action == "branches":
        return {"text": run("branch", "-a")}
    if action == "commit":
        msg = str(body.get("message") or "").strip()
        if not msg:
            return {"error": "Нужно сообщение коммита"}
        run("add", "-A")
        return {"text": run("commit", "-m", msg)}
    if action == "checkout":
        branch = str(body.get("branch") or "").strip()
        if not re.match(r"^[\w./-]{1,80}$", branch):
            return {"error": "Некорректная ветка"}
        return {"text": run("checkout", branch)}
    if action == "pull":
        return {"text": run("pull", "--ff-only")}
    return {"error": "Неизвестное действие"}


def compare_models(prompt, names):
    profiles = load_json(PROFILES, {})
    out = []

    def worker(name):
        p = profiles.get(name)
        if not isinstance(p, dict):
            out.append({"name": name, "error": "Нет профиля"})
            return
        started = time.time()
        try:
            msg, usage = call_llm(p, [{"role": "user", "content": prompt}])
            out.append({"name": name, "text": _content_text(msg.get("content")), "ms": int((time.time() - started) * 1000),
                        "tokens": estimate_tokens(_content_text(msg.get("content")))})
        except Exception as e:
            out.append({"name": name, "error": mask(e)})

    threads = [threading.Thread(target=worker, args=(n,), daemon=True) for n in names]
    for t in threads:
        t.start()
    for t in threads:
        t.join(120)
    return out


def fetch_repo_skill(repo):
    headers = {"User-Agent": "PocketCode-Desktop"}
    token = str(settings().get("githubToken", ""))
    if token:
        headers["Authorization"] = "Bearer " + token
    repo_info = {}
    try:
        req = urllib.request.Request("https://api.github.com/repos/" + repo, headers=headers)
        with urllib.request.urlopen(req, timeout=20) as r:
            repo_info = json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        repo_info = {}
    text = ""
    for filename in ("SKILL.md", "README.md", "readme.md"):
        try:
            req = urllib.request.Request("https://raw.githubusercontent.com/%s/HEAD/%s" % (repo, filename), headers=headers)
            with urllib.request.urlopen(req, timeout=20) as r:
                text = r.read().decode("utf-8", "replace")
                break
        except Exception:
            continue
    if not text:
        return "", {}
    name = safe_name(repo_info.get("name") or repo.split("/")[-1])
    desc = repo_info.get("description") or ("Навык из GitHub: " + repo)
    return text[:12000], {"name": name, "description": desc, "enabled": True, "builtin": False,
                          "source": "https://github.com/" + repo}


def diagnostics():
    return {
        "version": app_version(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "home": str(HOME),
        "projects": len(list_projects()),
        "sessions": len(load_sessions()["items"]),
        "llama_server": bool(shutil.which("llama-server")),
        "git": bool(shutil.which("git")),
        "git_version": (subprocess.run(["git", "--version"], capture_output=True, text=True).stdout.strip()
                        if shutil.which("git") else ""),
        "cryptography": _module_available("cryptography"),
        "sync_enabled": bool(load_json(SYNC_CONFIG, {}).get("enabled")),
        "active_profile": (active_profile() or {}).get("model", ""),
    }


def _module_available(name):
    try:
        __import__(name)
        return True
    except ImportError:
        return False


def check_updates():
    headers = {"User-Agent": "PocketCode-Desktop", "Accept": "application/vnd.github+json"}
    token = str(settings().get("githubToken", ""))
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request("https://api.github.com/repos/Kotenok-Rwka/Open-code-for-android/releases/latest", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        tag = str(data.get("tag_name") or "").lstrip("vV")
        return {"current": app_version(), "latest": tag, "url": data.get("html_url", ""),
                "newer": bool(tag and tag != app_version())}
    except Exception as e:
        return {"current": app_version(), "error": mask(e)}


def backup_export():
    return {
        "app": "PocketCode Desktop", "version": app_version(), "ts": now_ms(),
        "profiles": load_json(PROFILES, {}),
        "settings": load_json(SETTINGS, {}),
        "skills": load_json(SKILLS, []),
        "agents": load_json(AGENTS, []),
        "roles": load_json(ROLES, {}),
        "models": load_json(MODELS, []),
        "sessions": load_sessions(),
        "messages": read_jsonl(CHAT)[-2000:],
    }


def backup_import(data):
    if not isinstance(data, dict):
        return {"error": "Нужен JSON резервной копии"}
    imported = []
    if isinstance(data.get("profiles"), dict):
        save_json(PROFILES, data["profiles"]); imported.append("profiles")
    if isinstance(data.get("settings"), dict):
        merged = settings(); merged.update(data["settings"]); save_json(SETTINGS, merged); imported.append("settings")
    if isinstance(data.get("skills"), list):
        save_json(SKILLS, data["skills"]); imported.append("skills")
    if isinstance(data.get("agents"), list):
        save_json(AGENTS, data["agents"]); imported.append("agents")
    if isinstance(data.get("roles"), dict):
        save_json(ROLES, data["roles"]); imported.append("roles")
    if isinstance(data.get("models"), list):
        save_json(MODELS, data["models"]); imported.append("models")
    if isinstance(data.get("messages"), list):
        sid = active_session_id()
        with LOCK:
            with CHAT.open("a", encoding="utf-8") as f:
                for m in data["messages"][-2000:]:
                    if isinstance(m, dict):
                        f.write(json.dumps({"role": m.get("role", "user"), "text": str(m.get("text", "")),
                                            "session": sid, "ts": m.get("ts", now_ms())}, ensure_ascii=False) + "\n")
        imported.append("messages")
    return {"ok": True, "imported": imported}


def bootstrap():
    """Один запрос для первого экрана — меньше сетевых вызовов и меньше задержек."""
    sessions = load_sessions()
    sid = sessions["active"]
    return {
        "messages": messages_for(sid)[-300:],
        "profiles": public_profiles(),
        "sessions": sessions,
        "tasks": load_json(TASKS, []),
        "agents": load_json(AGENTS, []),
        "roles": load_json(ROLES, {}),
        "widgets": load_json(WIDGETS, []),
        "settings": {**settings(), "githubToken": ""},
        "skills": load_skills(),
        "models": load_json(MODELS, []),
        "queue": load_json(QUEUE, []),
        "projects": list_projects(),
        "sync": {**load_json(SYNC_STATE, {"seq": 0}), "pending": len(read_lines(SYNC_OUTBOX)), "cfg": public_sync_cfg()},
        "usage": load_json(USAGE, {"requests": 0, "prompt": 0, "completion": 0, "saved": 0}),
        "version": app_version(),
        "plan": load_json(HOME / "plan.json", {}).get("tasks", []),
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
        server.daemon_threads = True
    except OSError as e:
        print(f"Не удалось занять порт {a.port}: {e}. Возможно, компаньон уже запущен — открой http://127.0.0.1:{a.port}/")
        sys.exit(1)
    stop = threading.Event()
    if load_json(SYNC_CONFIG, {}).get("enabled"):
        thread_sync = threading.Thread(target=sync_loop, args=(stop,), daemon=True)
        thread_sync.start()
    url = f"http://127.0.0.1:{a.port}/"
    print(f"PocketCode Desktop {app_version()}: http://{a.host}:{a.port}/  (Ctrl+C — выход)")
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
