#!/usr/bin/env python3
"""Логика расширенных фич PocketCode Desktop: память, критик плана, роутер,
обрезка истории, сканер секретов, песочница, конфликты, шаблоны и пр.
Только стандартная библиотека."""
import csv
import glob as globmod
import hashlib
import json
import os
import platform as platform_module
import re
try:
    import resource
except ImportError:
    resource = None
import shlex
import shutil
import socket
import sqlite3
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

EDIT_BUDGET = 6
DEVICE_ONLINE_SECS = 120


def slug(name):
    s = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(name or "x").strip()).strip("-")
    return s or "x"


# ---------- 1/8. память и правила проекта ----------
def _dotfile(project, name):
    p = Path(project) if Path(project).is_absolute() else None
    if p is None or not p.exists():
        return None
    d = p / ".pocketcode"
    d.mkdir(exist_ok=True)
    return d / name


def read_dotfile(project, name, default=""):
    f = _dotfile(project, name)
    if f is None or not f.exists():
        return default
    try:
        return f.read_text(encoding="utf-8")
    except OSError:
        return default


def update_dotfile(project, name, text):
    f = _dotfile(project, name)
    if f is None:
        return False
    f.write_text(str(text or ""), encoding="utf-8")
    return True


def read_memory(project):
    return read_dotfile(project, "memory.md")


def update_memory(project, text):
    return update_dotfile(project, "memory.md", text)


def read_rules(project):
    return read_dotfile(project, "rules.md")


def update_rules(project, text):
    return update_dotfile(project, "rules.md", text)


def memory_and_rules_block(project, with_memory=True, with_rules=True):
    mem = read_memory(project).strip() if with_memory else ""
    rul = read_rules(project).strip() if with_rules else ""
    out = ""
    if mem:
        out += "\n\nПамять проекта (решения и структура):\n" + mem[:4000]
    if rul:
        out += "\n\nПравила проекта (соблюдай строго):\n" + rul[:2000]
    return out


# ---------- 2. критик плана ----------
def critique_plan(plan):
    issues = []
    if not isinstance(plan, dict):
        return ["План не является объектом"]
    files = plan.get("files")
    if not isinstance(files, list) or not files:
        issues.append("Нет списка файлов или он пуст")
    else:
        for f in files:
            s = str(f if isinstance(f, str) else (f or {}).get("path", ""))
            if not s:
                issues.append("Пустой путь файла в плане")
            elif Path(s).is_absolute() or ".." in Path(s).parts:
                issues.append("Небезопасный путь: " + s)
    if not plan.get("checklist"):
        issues.append("Нет чек-листа проверки (checklist)")
    if not plan.get("goal"):
        issues.append("Не указана цель (goal)")
    body = str(plan)
    if "TODO" in body and "решим потом" in body:
        issues.append("Отложенные решения в плане («решим потом»)")
    return issues


# ---------- 3. ключевые тесты ----------
def key_tests(test_files, max_n=8):
    files = [str(x) for x in (test_files or []) if x]
    failed = [f for f in files if "fail" in Path(f).name.lower() or "failed" in f.lower()]
    rest = sorted(set(files) - set(failed))
    return (failed + rest)[:max(0, int(max_n))]


# ---------- 4. бюджет правок ----------
def budget_left(attempts):
    try:
        a = int(attempts)
    except (TypeError, ValueError):
        a = 0
    return max(0, EDIT_BUDGET - a)


# ---------- 7. шаблоны / 24. демо ----------
TEMPLATES = {
    "landing": {
        "index.html": "<!doctype html>\n<html lang=\"ru\">\n<meta charset=\"utf-8\">\n"
                      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                      "<title>Лендинг</title>\n<style>body{font-family:system-ui;margin:0;display:grid;"
                      "place-items:center;min-height:100vh;background:#0f1220;color:#eef}</style>\n"
                      "<main><h1>Лендинг</h1><p>Замени этот текст.</p></main>\n</html>\n",
    },
    "bot": {
        "main.py": "#!/usr/bin/env python3\n# Каркас бота: ответь на сообщение.\n\n"
                   "def reply(text: str) -> str:\n    return \"Эхо: \" + text\n\n\n"
                   "if __name__ == \"__main__\":\n    print(reply(\"привет\"))\n",
    },
    "api": {
        "main.py": "#!/usr/bin/env python3\n# Минимальный JSON API на stdlib.\n"
                   "import json\nfrom http.server import BaseHTTPRequestHandler, HTTPServer\n\n\n"
                   "class H(BaseHTTPRequestHandler):\n    def do_GET(self):\n"
                   "        body = json.dumps({\"ok\": True, \"path\": self.path}).encode()\n"
                   "        self.send_response(200)\n"
                   "        self.send_header(\"Content-Type\", \"application/json\")\n"
                   "        self.send_header(\"Content-Length\", str(len(body)))\n"
                   "        self.end_headers()\n        self.wfile.write(body)\n\n\n"
                   "if __name__ == \"__main__\":\n"
                   "    HTTPServer((\"127.0.0.1\", 8010), H).serve_forever()\n",
        "requirements.txt": "",
    },
}


def create_from_template(root, name):
    files = TEMPLATES.get(str(name))
    if not files:
        return False
    base = Path(root)
    base.mkdir(parents=True, exist_ok=True)
    for rel, text in files.items():
        dst = base / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(text, encoding="utf-8")
    return True


DEMO_TASK = ("Собери лендинг из шаблона landing: заголовок, список из трёх фич, кнопка "
             "«Начать». Проверь, что страница открывается и нет ошибок в разметке.")


# ---------- 9. пакетные файлы ----------
SKIP_DIRS = {".git", "build", "node_modules", "__pycache__", ".gradle", ".kotlin", ".idea"}


def batch_files(root, pattern):
    root = Path(root)
    if not root.exists():
        return []
    out = []
    for p in root.rglob("*"):
        if p.is_dir():
            continue
        parts = set(p.relative_to(root).parts)
        if parts & SKIP_DIRS:
            continue
        if p.match(str(pattern)) or globmod.fnmatch.fnmatch(p.name, str(pattern)):
            out.append(str(p.relative_to(root)))
    return sorted(out)[:500]


# ---------- 11. роутер сложности ----------
CHEAP_HINTS = ("переимен", "rename", "формат", "format", "отступ", "отступы",
               "indent", "typo", "опечатк", "комментарий", "comment")


def route_step(text):
    t = str(text or "").strip().lower()
    if len(t) <= 80 or any(h in t for h in CHEAP_HINTS):
        return "cheap"
    return "strong"


# ---------- 12. обрезка истории ----------
def trim_history(msgs, max_chars=60000):
    msgs = list(msgs or [])
    if not msgs:
        return [], 0
    head, rest = msgs[:1], msgs[1:]
    kept = [m for m in head]
    total = len(str(head[0] if head else ""))
    dropped = 0
    for m in reversed(rest):
        n = len(str(m))
        if total + n > max_chars:
            dropped += 1
            continue
        total += n
        kept.append(m)
    kept = kept[:1] + list(reversed(kept[1:]))
    return kept, dropped


# ---------- 13. авто-режим ----------
def suggest_mode(failures, current):
    try:
        f = int(failures)
    except (TypeError, ValueError):
        f = 0
    if f >= 3:
        return "scaffold"
    if f >= 1 and current == "auto":
        return "focused"
    return str(current or "auto")


# ---------- 14. локальный fallback ----------
FALLBACK_RE = re.compile(r"timed? ?out|connection|refused|unreachable|reset|temporarily unavailable", re.I)


def fallback_provider(config, error):
    local = str((config or {}).get("localModel") or "").strip()
    if local and FALLBACK_RE.search(str(error or "")):
        return local
    return None


# ---------- 15. очередь ревью ----------
REVIEW_QUEUE = {}


def stage_review(filename, content):
    REVIEW_QUEUE[str(filename)] = str(content)
    return True


def review_pending():
    return [{"filename": k, "chars": len(v)} for k, v in sorted(REVIEW_QUEUE.items())]


def review_pop(filename, approve, project=None):
    key = str(filename)
    if key not in REVIEW_QUEUE:
        return {"ok": False, "error": "нет такого файла в очереди"}
    content = REVIEW_QUEUE.pop(key)
    if not approve:
        return {"ok": True, "applied": False}
    target = None
    if project:
        root = Path(project)
        if root.exists():
            cand = (root / key).resolve()
            try:
                cand.relative_to(root.resolve())
            except ValueError:
                cand = None
            if cand is not None:
                cand.parent.mkdir(parents=True, exist_ok=True)
                cand.write_text(content, encoding="utf-8")
                target = str(cand)
    return {"ok": True, "applied": target is not None, "path": target}


# ---------- 16. песочница ----------
def run_sandboxed(cmd, workdir, timeout=20):
    if isinstance(cmd, str) or not cmd:
        return {"rc": -1, "stdout": "", "stderr": "cmd должен быть непустым списком"}
    wd = Path(workdir or ".").resolve()

    def limits():
        resource.setrlimit(resource.RLIMIT_CPU, (10, 10))
        resource.setrlimit(resource.RLIMIT_AS, (1 << 30, 1 << 30) if False else (1536 << 20, 1536 << 20))
        resource.setrlimit(resource.RLIMIT_FSIZE, (50 << 20, 50 << 20))

    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR",
                                      "SYSTEMROOT", "TEMP", "TMP", "USERPROFILE",
                                      "APPDATA", "COMSPEC") if k in os.environ}
    env["PYTHONUTF8"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"
    try:
        p = subprocess.run([str(c) for c in cmd], cwd=str(wd), env=env, timeout=timeout,
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", preexec_fn=limits if resource is not None else None)
        return {"rc": p.returncode, "stdout": p.stdout[-8000:], "stderr": p.stderr[-8000:]}
    except subprocess.TimeoutExpired:
        return {"rc": -1, "stdout": "", "stderr": "превышен таймаут %ss" % timeout}
    except OSError as e:
        return {"rc": -1, "stdout": "", "stderr": str(e)}


# ---------- 17. сканер секретов ----------
SECRET_PATTERNS = [
    ("aws-key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("credential", re.compile(r"(?i)(api_key|apikey|token|password|secret)\s*[:=]\s*['\"][^'\"]{8,}['\"]")),
    ("eval", re.compile(r"\beval\s*\(")),
    ("exec", re.compile(r"\bexec\s*\(")),
    ("empty-except", re.compile(r"except[^:]*:\s*pass")),
]


def scan_secrets(text):
    findings = []
    for n, line in enumerate(str(text or "").splitlines(), 1):
        for kind, rx in SECRET_PATTERNS:
            m = rx.search(line)
            if m:
                findings.append({"line": n, "kind": kind, "match": m.group(0)[:60]})
    return findings


# ---------- 18. журнал ----------
def export_journal(messages, name, base_dir):
    safe = slug(name)
    d = Path(base_dir) / "journals"
    d.mkdir(parents=True, exist_ok=True)
    p = d / (safe + ".md")
    lines = ["# Журнал %s (%s)\n" % (safe, time.strftime("%Y-%m-%d %H:%M"))]
    for m in messages or []:
        role = str((m or {}).get("role", "user"))
        text = str((m or {}).get("text") or (m or {}).get("content") or "")
        lines.append("\n**%s:**\n\n%s\n" % (role, text))
    p.write_text("\n".join(lines), encoding="utf-8")
    return str(p)


# ---------- 19. устройства ----------
DEVICES = {}


def touch_device(name, ts=None):
    if not name:
        return
    DEVICES[str(name)] = float(ts if ts is not None else time.time())


def devices_status(now=None):
    now = float(now if now is not None else time.time())
    out = []
    for name, ts in sorted(DEVICES.items()):
        out.append({"name": name, "ts": ts, "online": 0 <= now - ts <= DEVICE_ONLINE_SECS})
    return out


# ---------- 20. конфликты ----------
def resolve_conflict(base, mine, theirs):
    base, mine, theirs = str(base or ""), str(mine or ""), str(theirs or "")
    if mine == theirs:
        return "same", mine
    if mine == base:
        return "theirs", theirs
    if theirs == base:
        return "mine", mine
    merged = "<<<<<<< mine\n%s\n=======\n%s\n>>>>>>> theirs" % (mine, theirs)
    return "both", merged


# ---------- 21. буфер обмена ----------
CLIPBOARD_TEXT = ""


def clipboard_set(text):
    global CLIPBOARD_TEXT
    CLIPBOARD_TEXT = str(text or "")[:100000]
    return True


def clipboard_get():
    return CLIPBOARD_TEXT


# ---------- 23. карта проекта ----------
def project_map(edits, failing):
    failing = set(failing or [])
    rows = [{"file": str(k), "edits": int(v), "failing": str(k) in failing}
            for k, v in (edits or {}).items() if isinstance(v, (int, float))]
    rows.sort(key=lambda r: (-r["edits"], r["file"]))
    return rows


# ---------- 25. импорт навыков ----------
MAX_SKILL_BYTES = 200000
SKILL_NAME_RE = re.compile(r"[^A-Za-z0-9_.-]+")


def skill_import(url, skills_dir):
    if not str(url).startswith("https://"):
        return {"error": "Нужен https://"}
    import urllib.request
    try:
        with urllib.request.urlopen(str(url), timeout=15) as r:
            raw = r.read(MAX_SKILL_BYTES + 1)
    except (OSError, ValueError) as e:
        return {"error": str(e)}
    if len(raw) > MAX_SKILL_BYTES:
        return {"error": "Файл больше 200 КБ"}
    text = raw.decode("utf-8", "replace")
    name = SKILL_NAME_RE.sub("-", Path(str(url).split("?")[0]).stem).strip("-") or "skill"
    d = Path(skills_dir)
    d.mkdir(parents=True, exist_ok=True)
    (d / (name + ".staged.md")).write_text(text, encoding="utf-8")
    return {"name": name, "chars": len(text)}


def skills_staged(skills_dir):
    d = Path(skills_dir)
    if not d.exists():
        return []
    return sorted(p.stem.replace(".staged", "") for p in d.glob("*.staged.md"))


def skill_install(name, skills_dir):
    safe = SKILL_NAME_RE.sub("-", str(name or "")).strip("-")
    src = Path(skills_dir) / (safe + ".staged.md")
    if not src.exists():
        return {"error": "Нет такого навыка в очереди"}
    dst = src.with_name(safe + ".md")
    shutil.move(str(src), str(dst))
    return {"ok": True, "path": str(dst)}


# ---------- 5. PR из задачи ----------
def create_pr(project, title):
    root = Path(project) if Path(project).is_absolute() else None
    if root is None or not root.exists():
        return {"error": "Нет такого проекта"}
    if not (root / ".git").exists():
        return {"error": "Папка не является git-репозиторием"}
    branch = "pocketcode/" + slug(title or "task")[:40]
    def run(args):
        return subprocess.run(args, cwd=str(root), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=60)
    try:
        run(["git", "checkout", "-B", branch])
        run(["git", "add", "-A"])
        c = run(["git", "commit", "-m", title or "PocketCode changes"])
        gh = run(["gh", "pr", "create", "--head", branch, "--title", title or "PocketCode changes",
                  "--body", "Создано из PocketCode. Проверки: см. CI."])
        if gh.returncode == 0:
            url = gh.stdout.strip().splitlines()[-1]
            return {"url": url, "branch": branch}
        return {"branch": branch, "committed": c.returncode == 0,
                "error": gh.stderr.strip() or "gh недоступен — ветка создана локально"}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"error": str(e)}


# ---------- 6. снапшоты ----------
SNAPSHOT_BASE = Path.home() / ".pocketcode-snapshots"


def _snapshot_root(project):
    return Path(SNAPSHOT_BASE) / slug(Path(project).name if project else "proj")


def snapshot_dir(project, n):
    return _snapshot_root(project) / ("s%03d" % int(n))


def make_snapshot(project):
    root = Path(project) if Path(project).is_absolute() else None
    if root is None or not root.exists():
        return {"error": "Нет такого проекта"}
    snaps = _snapshot_root(project)
    snaps.mkdir(parents=True, exist_ok=True)
    n = len(list(snaps.glob("s*"))) + 1
    dst = snapshot_dir(project, n)
    dst.mkdir(parents=True, exist_ok=True)
    copied = 0
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        parts = set(p.relative_to(root).parts)
        if parts & SKIP_DIRS:
            continue
        out = dst / p.relative_to(root)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, out)
        copied += 1
    return {"ok": True, "n": n, "files": copied}


def list_snapshots(project):
    snaps = _snapshot_root(project)
    if not snaps.exists():
        return []
    out = []
    for d in sorted(snaps.glob("s*")):
        out.append({"n": int(d.name[1:]), "files": sum(1 for _ in d.rglob("*") if _.is_file()),
                    "ts": int(d.stat().st_mtime)})
    return out


def restore_snapshot(project, n):
    root = Path(project) if Path(project).is_absolute() else None
    src = snapshot_dir(project, n or 0)
    if root is None or not root.exists() or not src.exists():
        return {"error": "Нет проекта или снапшота"}
    restored = 0
    for p in src.rglob("*"):
        if p.is_file():
            dst = root / p.relative_to(src)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, dst)
            restored += 1
    return {"ok": True, "restored": restored}


# ---------- 6b. служебное ----------
def content_hash(text):
    return hashlib.sha256(str(text or "").encode("utf-8")).hexdigest()[:16]


# ================= Батч-2: синхронизация, агент, безопасность =================

# ---------- 6/40. экспорт/импорт пакета настроек ----------
SENSITIVE_KEYS = {"githubToken", "apiKey", "apiKeys", "token", "password"}


def settings_packet(settings_dict):
    out = {k: v for k, v in (settings_dict or {}).items()
           if k not in SENSITIVE_KEYS and not any(sec in k.lower() for sec in ("key", "token", "secret", "password"))}
    return {"v": 1, "kind": "settings", "data": out}


def settings_packet_apply(packet, current):
    if not isinstance(packet, dict) or packet.get("kind") != "settings":
        return {"error": "Не пакет настроек"}
    data = packet.get("data") or {}
    merged = dict(current or {})
    for k, v in data.items():
        if k not in SENSITIVE_KEYS and not any(sec in k.lower() for sec in ("key", "token", "secret", "password")):
            merged[k] = v
    return {"ok": True, "settings": merged}


# ---------- 7. журнал синхронизации ----------
SYNC_LOG = []
SYNC_LOG_MAX = 500


def sync_log_add(direction, what, detail=""):
    SYNC_LOG.append({"ts": time.time(), "dir": direction, "what": str(what)[:200], "detail": str(detail)[:300]})
    if len(SYNC_LOG) > SYNC_LOG_MAX:
        del SYNC_LOG[:len(SYNC_LOG) - SYNC_LOG_MAX]
    return True


def sync_log_recent(n=50):
    return list(reversed(SYNC_LOG[-max(1, min(int(n or 50), SYNC_LOG_MAX))]))


# ---------- 8. выбор проектов для синхронизации ----------
def sync_project_allowed(cfg, project):
    mode = str((cfg or {}).get("projectSync", "all"))
    if mode == "all":
        return True
    chosen = (cfg or {}).get("syncProjects") or []
    return str(project or "") in chosen


# ---------- 9. передача сессии между устройствами ----------
def make_handoff(device_from, project, task, notes=""):
    return {"v": 1, "kind": "handoff", "device": str(device_from), "project": str(project),
            "task": str(task)[:2000], "notes": str(notes)[:2000], "ts": time.time()}


def parse_handoff(packet):
    if not isinstance(packet, dict) or packet.get("kind") != "handoff":
        return None
    return {k: packet.get(k) for k in ("device", "project", "task", "notes", "ts")}


# ---------- 11. общий список задач ----------
def merge_tasks(mine, theirs):
    mine = list(mine or [])
    theirs = list(theirs or [])
    seen = {json.dumps(t, sort_keys=True, ensure_ascii=False) for t in mine}
    added = 0
    for t in theirs:
        k = json.dumps(t, sort_keys=True, ensure_ascii=False)
        if k not in seen:
            mine.append(t)
            seen.add(k)
            added += 1
    return mine, added


# ---------- 15. дистанционный стоп ----------
def parse_stop_packet(packet):
    if not isinstance(packet, dict) or packet.get("kind") != "stop":
        return None
    target = str(packet.get("data") or "").strip()
    return target or "*"


# ---------- 12-14. политика синхронизации ----------
def sync_allowed_now(cfg, on_metered=False):
    cfg = cfg or {}
    if cfg.get("wifiOnly") and on_metered:
        return False, "только по Wi-Fi"
    if not cfg.get("enabled", True):
        return False, "синхронизация выключена"
    return True, ""


# ---------- 18. сколько не синхронизировано ----------
def unsynced_count(outbox_len, queue_len=0):
    try:
        return int(outbox_len) + int(queue_len)
    except (TypeError, ValueError):
        return 0


# ---------- 19. обозреватель файлов (read-only) ----------
def list_files_safe(root, rel="", depth=2):
    base = Path(root).resolve()
    target = (base / str(rel or "")).resolve()
    try:
        target.relative_to(base)
    except ValueError:
        return {"error": "вне проекта"}
    if not target.exists():
        return {"error": "нет такого пути"}
    items = []
    try:
        for p in sorted(target.iterdir()):
            if p.name.startswith(".") or p.name in SKIP_DIRS:
                continue
            rel_path = str(p.relative_to(base))
            if p.is_dir():
                items.append({"name": p.name, "dir": True, "path": rel_path})
            else:
                items.append({"name": p.name, "dir": False, "path": rel_path, "size": p.stat().st_size})
            if len(items) >= 500:
                break
    except OSError as e:
        return {"error": str(e)}
    return {"path": str(rel or ""), "items": items}


def read_file_safe(root, rel, max_bytes=200000):
    base = Path(root).resolve()
    target = (base / str(rel or "")).resolve()
    try:
        target.relative_to(base)
    except ValueError:
        return {"error": "вне проекта"}
    if not target.is_file():
        return {"error": "не файл"}
    try:
        raw = target.read_bytes()[:max_bytes]
        return {"path": str(rel), "text": raw.decode("utf-8", "replace"),
                "truncated": target.stat().st_size > max_bytes}
    except OSError as e:
        return {"error": str(e)}


# ---------- 27. автоплан ----------
def split_task(text):
    t = str(text or "").strip()
    if not t:
        return []
    parts = [p.strip(" .;") for p in re.split(r"[;\n]|, затем | потом | и потом ", t) if p.strip()]
    if len(parts) == 1:
        parts = [t]
    return [{"n": i + 1, "step": p[:300], "done": False} for i, p in enumerate(parts[:12])]


# ---------- 32. оценка задачи ----------
def estimate_task(text):
    t = str(text or "")
    words = len(t.split())
    steps = max(1, min(16, words // 12 + t.count(";") + 1))
    tokens = 800 + steps * 1500
    return {"steps": steps, "tokens": tokens, "minutes": max(1, steps // 2)}


# ---------- 33. прогресс по чек-листу ----------
def progress_percent(checklist):
    items = [c for c in (checklist or []) if isinstance(c, (str, dict))]
    if not items:
        return 0
    done = 0
    for c in items:
        if isinstance(c, dict):
            done += 1 if c.get("done") else 0
        else:
            done += 1 if str(c).startswith("x ") or str(c).startswith("[x]") else 0
    return int(round(100.0 * done / len(items)))


# ---------- 37. самопроверка результата ----------
def self_check(result, task=""):
    r = str(result or "")
    issues = []
    if len(r.strip()) < 10:
        issues.append("Пустой или слишком короткий ответ")
    for bad in ("не получилось", "не смог", "не удалось"):
        if bad in r.lower():
            issues.append("Агент сообщил о неудаче: «%s»" % bad)
    if "TODO" in r or "FIXME" in r:
        issues.append("В ответе остались TODO/FIXME")
    return issues


# ---------- 39. мастер-промпты ----------
PROMPT_PRESETS = {
    "careful": "Действуй осторожно: сначала план, минимальные диффы, прогон тестов после каждой правки.",
    "fast": "Действуй быстро: минимум шагов, только суть, без длинных объяснений.",
    "cheap": "Экономь токены: короткая история, простые правки, не перечитывай файлы без нужды.",
}


def preset_names():
    return sorted(PROMPT_PRESETS)


# ---------- 44/45. лимиты расходов и отчёт ----------
def within_spend_limit(limit_usd, spent_usd):
    try:
        lim = float(limit_usd)
    except (TypeError, ValueError):
        return True, ""
    if lim <= 0:
        return True, ""
    try:
        spent = float(spent_usd)
    except (TypeError, ValueError):
        spent = 0.0
    if spent >= lim:
        return False, "лимит расходов %.2f$ исчерпан" % lim
    return True, ""


def cost_report(prompt_tokens, completion_tokens, price_in=0.0, price_out=0.0):
    try:
        cost = (int(prompt_tokens or 0) / 1e6) * float(price_in or 0) \
             + (int(completion_tokens or 0) / 1e6) * float(price_out or 0)
    except (TypeError, ValueError):
        cost = 0.0
    return {"prompt": int(prompt_tokens or 0), "completion": int(completion_tokens or 0),
            "usd": round(cost, 4)}


# ---------- 55. белый список доменов ----------
def domain_allowed(url, whitelist):
    wl = [str(x).lower() for x in (whitelist or [])]
    if not wl:
        return True
    m = re.match(r"^[a-z]+://([^/:]+)", str(url or "").lower())
    host = m.group(1) if m else ""
    for d in wl:
        if host == d or host.endswith("." + d):
            return True
    return False


# ---------- 61. аудит bash ----------
BASH_AUDIT = []
BASH_AUDIT_MAX = 1000


def audit_bash(cmd, cwd="", ok=True):
    BASH_AUDIT.append({"ts": time.time(), "cmd": str(cmd)[:500], "cwd": str(cwd)[:200], "ok": bool(ok)})
    if len(BASH_AUDIT) > BASH_AUDIT_MAX:
        del BASH_AUDIT[:len(BASH_AUDIT) - BASH_AUDIT_MAX]
    return True


def audit_export():
    return list(BASH_AUDIT)


# ---------- 65. паника ----------
def panic_state():
    return {"stopped_at": time.time()}


# ---------- 82. TODO-сканер ----------
TODO_RE = re.compile(r"(TODO|FIXME|HACK|XXX)\b[:\s]?(.{0,120})", re.I)


def scan_todos(root, max_files=2000, timeout_s=10):
    base = Path(root).resolve()
    if not base.exists():
        return []
    out = []
    deadline = time.time() + timeout_s
    scanned = 0
    for p in base.rglob("*"):
        if time.time() > deadline or scanned >= max_files:
            break
        if not p.is_file() or p.suffix in (".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".jar"):
            continue
        if set(p.relative_to(base).parts) & SKIP_DIRS:
            continue
        scanned += 1
        try:
            if p.stat().st_size > 500000:
                continue
            for n, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                m = TODO_RE.search(line)
                if m:
                    out.append({"file": str(p.relative_to(base)), "line": n,
                                "kind": m.group(1).upper(), "text": m.group(2).strip()})
                if len(out) >= 300:
                    return out
        except OSError:
            continue
    return out


# ---------- 71. поиск по проекту ----------
def search_project(root, query, pattern="*", max_hits=200, timeout_s=10):
    q = str(query or "").strip()
    if not q:
        return []
    base = Path(root).resolve()
    if not base.exists():
        return []
    hits = []
    deadline = time.time() + timeout_s
    for p in base.rglob(str(pattern or "*")):
        if time.time() > deadline:
            break
        if not p.is_file() or p.stat().st_size > 500000:
            continue
        if set(p.relative_to(base).parts) & SKIP_DIRS:
            continue
        try:
            for n, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if q.lower() in line.lower():
                    hits.append({"file": str(p.relative_to(base)), "line": n, "text": line.strip()[:200]})
                    if len(hits) >= max_hits:
                        return hits
        except OSError:
            continue
    return hits


# ---------- 91. повтор последнего запуска ----------
LAST_COMMANDS = {}


def remember_command(session, cmd):
    LAST_COMMANDS[str(session or "default")] = str(cmd or "")[:500]
    return True


def last_command(session):
    return LAST_COMMANDS.get(str(session or "default"), "")


# ================= Батч-3: продолжение списка 200 =================

# ---------- 69/81/79. git-UI: статус, история файла, ветки ----------
def _git(root, *args):
    p = subprocess.run(["git", *args], cwd=str(root), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=30)
    return p.returncode, (p.stdout or ""), (p.stderr or "")


def git_status_brief(root):
    rc, out, err = _git(root, "status", "--porcelain", "-b")
    if rc != 0:
        return {"error": (err or "git недоступен").strip()[:200]}
    lines = [l for l in out.splitlines() if l.strip()]
    branch = lines[0].lstrip("## ").split("...")[0] if lines and lines[0].startswith("##") else ""
    changes = [{"status": l[:2].strip(), "file": l[3:].strip()} for l in lines if not l.startswith("##")]
    return {"branch": branch, "changed": len(changes), "changes": changes[:100]}


def git_log(root, limit=20):
    rc, out, err = _git(root, "log", "--oneline", "-n", str(int(limit or 20)))
    if rc != 0:
        return {"error": (err or "нет коммитов").strip()[:200]}
    return {"commits": [l for l in out.splitlines() if l.strip()][:int(limit or 20)]}


def git_file_history(root, rel, limit=20):
    rc, out, err = _git(root, "log", "--follow", "--oneline", "-n", str(int(limit or 20)), "--", str(rel))
    if rc != 0:
        return {"error": (err or "нет истории").strip()[:200]}
    return {"file": str(rel), "commits": [l for l in out.splitlines() if l.strip()]}


def git_commit_all(root, message):
    rc, out, err = _git(root, "add", "-A")
    if rc != 0:
        return {"error": (err or "git add упал").strip()[:200]}
    rc, out, err = _git(root, "-c", "user.name=PocketCode", "-c", "user.email=pocketcode@local",
                        "commit", "-m", str(message or "PocketCode changes"))
    if rc != 0:
        if "nothing to commit" in (out + err):
            return {"ok": True, "committed": False}
        return {"error": (err or out).strip()[:300]}
    rc2, out2, _ = _git(root, "rev-parse", "--short", "HEAD")
    return {"ok": True, "committed": True, "sha": out2.strip()}


def git_branches(root):
    rc, out, err = _git(root, "branch", "--list")
    if rc != 0:
        return {"error": (err or "git недоступен").strip()[:200]}
    names = [l.lstrip("* ").strip() for l in out.splitlines() if l.strip()]
    current = next((l[2:].strip() for l in out.splitlines() if l.startswith("* ")), "")
    return {"branches": names, "current": current}


def git_switch(root, branch):
    name = slug(branch)
    rc, out, err = _git(root, "switch", name)
    if rc != 0:
        return {"error": (err or "не переключиться").strip()[:200]}
    return {"ok": True, "branch": name}


# ---------- 76/77. линт-метрики: грубая оценка опасных файлов ----------
def file_risk_metrics(root, max_files=50):
    base = Path(root).resolve()
    if not base.exists():
        return []
    rows = []
    deadline = time.time() + 10
    for p in base.rglob("*.py"):
        if time.time() > deadline or len(rows) >= max_files:
            break
        if set(p.relative_to(base).parts) & SKIP_DIRS:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        lines = text.splitlines()
        long_fn = sum(1 for l in lines if l.startswith("def ") or l.startswith("    def "))
        risk = 3 * text.count("except:") + 2 * text.count("eval(") + 2 * text.count("exec(") \
             + text.count("# TODO") + (1 if long_fn > 30 else 0)
        rows.append({"file": str(p.relative_to(base)), "lines": len(lines), "risk": risk})
    rows.sort(key=lambda r: -r["risk"])
    return rows


# ---------- 92. простой профилировщик запуска ----------
def profile_cmd(cmd, workdir, timeout=30):
    t0 = time.time()
    res = run_sandboxed(cmd, workdir, timeout=timeout)
    res["seconds"] = round(time.time() - t0, 2)
    return res


# ---------- 99/106/108. телефон-часть: шаблоны сообщений, поиск, закреп ----------
def template_save(templates, name, text):
    out = dict(templates or {})
    out[str(name or "")[:60]] = str(text or "")[:2000]
    return out


def template_delete(templates, name):
    out = dict(templates or {})
    out.pop(str(name or ""), None)
    return out


def toggle_pin(pinned, item):
    cur = list(pinned or [])
    s = str(item or "")
    if s in cur:
        cur.remove(s)
    else:
        cur.append(s)
    return cur


# ---------- 118/119. вкладки и палитра: модель данных ----------
def palette_commands():
    return sorted([
        {"cmd": "Новая задача", "route": "chat"},
        {"cmd": "Открыть проект", "route": "projects"},
        {"cmd": "Сканер секретов", "route": "scan"},
        {"cmd": "Поиск по проекту", "route": "search"},
        {"cmd": "TODO-сканер", "route": "todos"},
        {"cmd": "Снапшот проекта", "route": "snapshot"},
        {"cmd": "Показать QR-сопряжение", "route": "qr"},
        {"cmd": "Паника: стоп всего", "route": "panic"},
    ], key=lambda x: x["cmd"])


def palette_filter(commands, q, limit=8):
    q = str(q or "").strip().lower()
    if not q:
        return list(commands or [])[:limit]
    return [c for c in (commands or []) if q in str(c.get("cmd", "")).lower()][:limit]


# ---------- 149/151. GitHub: падения CI и issue → задача ----------
def ci_failure_text(jobs_json):
    """Достаёт упавшие job'ы из JSON ответа GitHub Actions API."""
    try:
        jobs = json.loads(jobs_json if isinstance(jobs_json, str) else json.dumps(jobs_json))
    except ValueError:
        return []
    out = []
    for j in jobs.get("jobs", []):
        if str(j.get("conclusion")) in ("failure", "timed_out"):
            out.append({"name": j.get("name"), "url": j.get("html_url")})
    return out


def issue_to_task(issue_json):
    try:
        i = json.loads(issue_json if isinstance(issue_json, str) else json.dumps(issue_json))
    except ValueError:
        return None
    if not i.get("title"):
        return None
    body = str(i.get("body") or "")[:1500]
    return {"title": str(i["title"])[:200], "task": "Почини issue #%s: %s\n\n%s" % (i.get("number"), i["title"], body)}


# ---------- 176/178/183. надёжность ----------
def health_report(sync_log_len, devices_online, last_snapshot_n):
    problems = []
    if not devices_online:
        problems.append("нет устройств на связи")
    if not last_snapshot_n:
        problems.append("снапшотов ещё нет")
    return {"ok": not problems, "problems": problems,
            "sync_events": int(sync_log_len or 0)}


def verify_packets_integrity(packets, hashes):
    ok, broken = 0, []
    for n, p in enumerate(packets or []):
        h = content_hash(json.dumps(p, sort_keys=True, ensure_ascii=False))
        if n < len(hashes or []) and hashes[n] and hashes[n] != h:
            broken.append(n)
        else:
            ok += 1
    return {"ok": ok, "broken": broken}

# ================= Батч-4: макросы, триггеры, вебхуки, SQL-консоль, диаграммы =================

def _pc_load(path, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _pc_save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


MAX_MACRO_STEPS = 50
MAX_TRIGGERS = 30
MAX_WEBHOOKS = 20
MAX_SQL_ROWS = 100
MAX_FILES = 400

# ---------- 191. макросы: запись и воспроизведение команд ----------
def _pc_json(project, name):
    d = Path(project) / ".pocketcode"
    d.mkdir(parents=True, exist_ok=True)
    return d / name


def shlex_split_or_err(cmd):
    """Разбирает команду макроса в argv; None → песочница вернёт ошибку."""
    try:
        return shlex.split(str(cmd or "")) or None
    except ValueError:
        return None


def macro_record(project, name, cmd):
    cmd = str(cmd or "").strip()
    if not cmd:
        return {"error": "пустая команда"}
    macros = _pc_load(_pc_json(project, "macros.json"), {})
    m = macros.setdefault(str(name)[:60], {"commands": [], "created": time.time()})
    if len(m["commands"]) >= MAX_MACRO_STEPS:
        return {"error": "макрос переполнен (максимум %d шагов)" % MAX_MACRO_STEPS}
    m["commands"].append(cmd[:500])
    _pc_save(_pc_json(project, "macros.json"), macros)
    return {"ok": True, "steps": len(m["commands"])}


def macro_list(project):
    macros = _pc_load(_pc_json(project, "macros.json"), {})
    return [{"name": k, "steps": len(v.get("commands", []))} for k, v in macros.items()]


def macro_delete(project, name):
    macros = _pc_load(_pc_json(project, "macros.json"), {})
    if name not in macros:
        return {"error": "макрос не найден"}
    del macros[name]
    _pc_save(_pc_json(project, "macros.json"), macros)
    return {"ok": True}


def macro_steps(project, name):
    macros = _pc_load(_pc_json(project, "macros.json"), {})
    m = macros.get(str(name))
    return list(m["commands"]) if m else None


# ---------- 192. триггеры на изменение файлов ----------
def trigger_add(project, pattern, cmd):
    pattern, cmd = str(pattern or "").strip(), str(cmd or "").strip()
    if not pattern or not cmd:
        return {"error": "нужны шаблон файла и команда"}
    tr = _pc_load(_pc_json(project, "triggers.json"), [])
    if len(tr) >= MAX_TRIGGERS:
        return {"error": "слишком много триггеров (максимум %d)" % MAX_TRIGGERS}
    item = {"id": content_hash(pattern + cmd)[:10], "glob": pattern[:200], "cmd": cmd[:300],
            "seen": {}}
    tr.append(item)
    _pc_save(_pc_json(project, "triggers.json"), tr)
    return {"ok": True, "id": item["id"]}


def trigger_list(project):
    return _pc_load(_pc_json(project, "triggers.json"), [])


def trigger_remove(project, tid):
    tr = _pc_load(_pc_json(project, "triggers.json"), [])
    left = [t for t in tr if t.get("id") != tid]
    if len(left) == len(tr):
        return {"error": "триггер не найден"}
    _pc_save(_pc_json(project, "triggers.json"), left)
    return {"ok": True}


def trigger_check(project, root, tid=None):
    """Возвращает триггеры и изменившиеся с прошлого раза файлы (снимок mtime обновляется).

    Первый проход только заполняет снимок — срабатываний нет."""
    root = Path(root)
    tr = _pc_load(_pc_json(project, "triggers.json"), [])
    fired = []
    for t in tr:
        if tid and t.get("id") != tid:
            continue
        old = dict(t.get("seen") or {})
        seen = {}
        changed = []
        files = globmod.glob(str(root / t["glob"]), recursive=True)[:MAX_FILES]
        for f in files:
            try:
                mtime = os.path.getmtime(f)
            except OSError:
                continue
            seen[f] = mtime
            if f in old and old[f] != mtime:
                changed.append(f)
        t["seen"] = seen
        if changed:
            fired.append({"id": t["id"], "cmd": t["cmd"], "files": changed[:MAX_FILES]})
    _pc_save(_pc_json(project, "triggers.json"), tr)
    return {"fired": fired}


# ---------- 193. исходящие вебхуки ----------
def webhook_add(project, url, events=None):
    url = str(url or "").strip()
    if not re.match(r"^https?://[^\s@]+$", url):
        return {"error": "нужен http(s)-адрес без логина и пароля"}
    hooks = _pc_load(_pc_json(project, "webhooks.json"), [])
    if len(hooks) >= MAX_WEBHOOKS:
        return {"error": "слишком много вебхуков (максимум %d)" % MAX_WEBHOOKS}
    evs = [str(e) for e in (events or ["task_done"])][:5]
    item = {"id": content_hash(url)[:10], "url": url[:500], "events": evs}
    hooks.append(item)
    _pc_save(_pc_json(project, "webhooks.json"), hooks)
    return {"ok": True, "id": item["id"]}


def webhook_list(project):
    return _pc_load(_pc_json(project, "webhooks.json"), [])


def webhook_remove(project, wid):
    hooks = _pc_load(_pc_json(project, "webhooks.json"), [])
    left = [h for h in hooks if h.get("id") != wid]
    if len(left) == len(hooks):
        return {"error": "вебхук не найден"}
    _pc_save(_pc_json(project, "webhooks.json"), left)
    return {"ok": True}


def webhook_fire(project, event, payload, poster=None):
    """POSTит payload на все вебхуки, подписанные на событие. poster подменяется в тестах."""
    hooks = _pc_load(_pc_json(project, "webhooks.json"), [])
    results = []
    body = json.dumps({"event": event, "payload": payload}, ensure_ascii=False).encode("utf-8")
    for h in hooks:
        if event not in (h.get("events") or []):
            continue
        if poster is not None:
            ok, err = poster(h["url"], body)
        else:
            try:
                req = urllib.request.Request(h["url"], data=body,
                                             headers={"Content-Type": "application/json"}, method="POST")
                with urllib.request.urlopen(req, timeout=5) as resp:
                    ok, err = resp.status < 400, ""
            except Exception as e:  # noqa: BLE001
                ok, err = False, str(e)[:150]
        results.append({"id": h["id"], "url": h["url"], "ok": ok, "error": err})
    return {"sent": results}


# ---------- 194. SQL-консоль (read-only, локальная база проекта) ----------
def sql_query(project, query):
    query = str(query or "").strip()
    if not query:
        return {"error": "пустой запрос"}
    if not re.match(r"(?is)^(select|pragma|explain)\b", query):
        return {"error": "разрешены только read-only запросы (select/pragma/explain)"}
    db = _pc_json(project, "data.sqlite")
    if not db.exists():
        return {"columns": [], "rows": [], "note": "база пуста, таблиц ещё нет"}
    try:
        con = sqlite3.connect("file:%s?mode=ro" % db, uri=True, timeout=5)
        try:
            cur = con.execute(query[:4000])
            rows = cur.fetchmany(MAX_SQL_ROWS)
            cols = [d[0] for d in cur.description] if cur.description else []
        finally:
            con.close()
    except sqlite3.Error as e:
        return {"error": str(e)[:200]}
    return {"columns": cols, "rows": [[str(v) if not isinstance(v, (int, float)) else v for v in r] for r in rows]}


# ---------- 195. Mermaid-диаграмма структуры проекта ----------
_SKIP_DIRS = {".git", ".gradle", ".kotlin", ".mlai", "__pycache__", "build", "node_modules", ".idea"}


def mermaid_tree(root, max_files=60):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    lines = ["graph TD"]
    root_id = "R"
    lines.append('%s["%s"]' % (root_id, root.name[:40] or "."))
    count = 0
    dirs = {str(root): root_id}

    def walk(d, pid, depth):
        nonlocal count
        if depth > 4:
            return
        try:
            entries = sorted(d.iterdir(), key=lambda p: (p.is_file(), p.name.lower()))
        except OSError:
            return
        for e in entries:
            if e.name in _SKIP_DIRS or e.name.startswith("."):
                continue
            if count >= max_files:
                return
            if e.is_dir():
                nid = "D%d" % count
                lines.append('%s["%s"]' % (nid, e.name[:30]))
                lines.append("%s --> %s" % (pid, nid))
                count += 1
                dirs[str(e)] = nid
                walk(e, nid, depth + 1)
            else:
                nid = "F%d" % count
                lines.append('%s["%s"]' % (nid, e.name[:30]))
                lines.append("%s --> %s" % (pid, nid))
                count += 1

    walk(root, root_id, 0)
    if not count:
        lines.append('E["пусто"]')
        lines.append("%s --> E" % root_id)
    return {"mermaid": "\n".join(lines), "nodes": count}


# ================= Батч-5: cron, git-конфликты, мёртвый код, логи, таблицы, импорт промптов, карантин =================

MAX_CRON = 30
MAX_TABLE_BYTES = 1_000_000
MAX_TABLE_ROWS = 5000
QUARANTINE_MAX = 50
_CRON_MIN = re.compile(r"^every (\d+)m$")
_CRON_DAILY = re.compile(r"^daily (\d{1,2}):(\d{2})$")
_LOG_LEVEL = re.compile(r"\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|CRITICAL|FATAL)\b")
_DEF_RE = re.compile(r"^(?:def|class)\s+([A-Za-z_]\w*)", re.M)


# ---------- 31. повторяющиеся задачи (cron) ----------
def cron_add(project, schedule, task, name=""):
    schedule = str(schedule or "").strip().lower()
    task = str(task or "").strip()
    if not task:
        return {"error": "пустая задача"}
    m = _CRON_MIN.match(schedule)
    d = _CRON_DAILY.match(schedule)
    if not m and not d:
        return {"error": "формат: 'every 30m' или 'daily 09:30'"}
    if m and not 1 <= int(m.group(1)) <= 1440:
        return {"error": "интервал от 1 до 1440 минут"}
    if d and (int(d.group(1)) > 23 or int(d.group(2)) > 59):
        return {"error": "время от 00:00 до 23:59"}
    entries = _pc_load(_pc_json(project, "cron.json"), [])
    if len(entries) >= MAX_CRON:
        return {"error": "слишком много задач (максимум %d)" % MAX_CRON}
    nm = ("" if name is None else str(name)).strip()
    item = {"id": content_hash(schedule + task)[:10], "schedule": schedule, "task": task[:300],
            "name": (nm or task)[:60], "last_run": 0}
    entries.append(item)
    _pc_save(_pc_json(project, "cron.json"), entries)
    return {"ok": True, "id": item["id"]}


def cron_list(project):
    return _pc_load(_pc_json(project, "cron.json"), [])


def cron_remove(project, cid):
    entries = _pc_load(_pc_json(project, "cron.json"), [])
    left = [e for e in entries if e.get("id") != cid]
    if len(left) == len(entries):
        return {"error": "задача не найдена"}
    _pc_save(_pc_json(project, "cron.json"), left)
    return {"ok": True}


def cron_due(project, now=None):
    """Задачи, которые пора запустить; у найденных обновляется last_run."""
    now = float(now if now is not None else time.time())
    entries = _pc_load(_pc_json(project, "cron.json"), [])
    due = []
    for e in entries:
        m = _CRON_MIN.match(e.get("schedule", ""))
        if m:
            interval = int(m.group(1)) * 60
            if float(e.get("last_run") or 0) + interval <= now:
                due.append(e)
                e["last_run"] = now
        else:
            m = _CRON_DAILY.match(e.get("schedule", ""))
            if m:
                lt = time.localtime(now)
                target = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday,
                                      int(m.group(1)), int(m.group(2)), 0, 0, 0, -1))
                if target <= now and float(e.get("last_run") or 0) < target:
                    due.append(e)
                    e["last_run"] = now
    if due:
        _pc_save(_pc_json(project, "cron.json"), entries)
    return {"due": [{"id": e["id"], "task": e["task"], "schedule": e["schedule"]} for e in due]}


# ---------- 79. помощник merge-конфликтов git ----------
def git_conflicts(root):
    p = subprocess.run(["git", "diff", "--name-only", "--diff-filter=U"], cwd=str(root),
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=30)
    return {"files": [l.strip() for l in p.stdout.splitlines() if l.strip()]}


def conflict_resolve(root, rel, choice):
    """Разруливает маркеры <<<<<<< ======= >>>>>>> в файле: ours | theirs | both."""
    if choice not in ("ours", "theirs", "both"):
        return {"error": "выбор: ours | theirs | both"}
    f = Path(root) / str(rel)
    try:
        text = f.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        return {"error": "не удалось прочитать файл: %s" % e}
    out, blocks, state, cur_ours, cur_theirs = [], 0, 0, [], []
    for line in text.splitlines(keepends=True):
        if state == 0 and line.startswith("<<<<<<<"):
            state, cur_ours, cur_theirs = 1, [], []
        elif state == 1 and line.startswith("======="):
            state = 2
        elif state == 2 and line.startswith(">>>>>>>"):
            if choice == "ours":
                out.extend(cur_ours)
            elif choice == "theirs":
                out.extend(cur_theirs)
            else:
                out.extend(cur_ours + cur_theirs)
            blocks += 1
            state = 0
        elif state == 1:
            cur_ours.append(line)
        elif state == 2:
            cur_theirs.append(line)
        else:
            out.append(line)
    if not blocks or state != 0:
        return {"error": "конфликтные маркеры не найдены или не закрыты"}
    try:
        f.write_text("".join(out), encoding="utf-8")
    except OSError as e:
        return {"error": "не удалось записать файл: %s" % e}
    return {"ok": True, "resolved": blocks}


# ---------- 83. поиск мёртвого кода ----------
def find_dead_code(root, max_files=300):
    """Функции и классы верхнего уровня .py-файлов, имя которых нигде не используется."""
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    texts = {}
    for p in sorted(root.rglob("*.py"))[:max_files]:
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        try:
            texts[p] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            pass
    dead = []
    for p, text in texts.items():
        for m in _DEF_RE.finditer(text):
            name = m.group(1)
            if name.startswith("_") or name in ("main", "run", "test", "setUp", "tearDown"):
                continue
            uses = sum(len(re.findall(r"\b%s\b" % re.escape(name), t)) for t in texts.values())
            if uses <= 1:
                line = text.count("\n", 0, m.start()) + 1
                dead.append({"file": str(p.relative_to(root)), "name": name, "line": line})
    return {"dead": dead[:200], "scanned": len(texts)}


# ---------- 157. просмотр логов с уровнями ----------
def log_overview(path, max_bytes=500000, tail=200):
    p = Path(path)
    if not p.is_file():
        return {"error": "файл не найден"}
    max_bytes = 500000 if max_bytes is None else max(1000, min(int(max_bytes), 5_000_000))
    tail = 200 if tail is None else max(1, min(int(tail), 1000))
    try:
        text = p.read_text(encoding="utf-8", errors="replace")[-int(max_bytes):]
    except OSError as e:
        return {"error": str(e)}
    lines = text.splitlines()
    counts, errors = {}, []
    for line in lines:
        m = _LOG_LEVEL.search(line)
        if not m:
            continue
        lvl = "WARNING" if m.group(1).startswith("WARN") else m.group(1)
        counts[lvl] = counts.get(lvl, 0) + 1
        if lvl in ("ERROR", "CRITICAL", "FATAL") and len(errors) < int(tail):
            errors.append(line[:300])
    return {"counts": counts, "errors": errors, "total_lines": len(lines)}


# ---------- 156/164. таблицы CSV/JSON и диф таблиц ----------
def table_summary(path):
    p = Path(path)
    if not p.is_file():
        return {"error": "файл не найден"}
    text = p.read_text(encoding="utf-8", errors="replace")[:MAX_TABLE_BYTES]
    rows = []
    if p.suffix.lower() == ".json":
        try:
            data = json.loads(text)
        except ValueError:
            return {"error": "битый JSON"}
        if isinstance(data, list):
            rows = [r if isinstance(r, list) else [str(r)] for r in data[:MAX_TABLE_ROWS]]
            cols = sorted({k for r in data[:MAX_TABLE_ROWS] if isinstance(r, dict) for k in r})
            if cols:
                rows = [[r.get(c) if isinstance(r, dict) else None for c in cols] for r in data[:MAX_TABLE_ROWS]]
        elif isinstance(data, dict):
            cols = sorted(data.keys())
            rows = [[data[c]] for c in cols] if cols else []
        else:
            return {"error": "JSON не является таблицей"}
    else:
        try:
            parsed = list(csv.reader(text.splitlines()))
        except csv.Error as e:
            return {"error": "битый CSV: %s" % e}
        if not parsed:
            return {"columns": [], "rows": 0, "stats": {}}
        cols, rows = parsed[0], parsed[1:MAX_TABLE_ROWS]
    n = len(cols)
    stats = {}
    for i, c in enumerate(cols):
        vals = [r[i] for r in rows if i < len(r) and r[i] is not None and str(r[i]).strip() != ""]
        numeric = []
        for v in vals:
            try:
                numeric.append(float(v))
            except (TypeError, ValueError):
                pass
        stats[str(c)] = {"rows_filled": len(vals), "missing": len(rows) - len(vals),
                         "numeric": len(numeric)}
        if numeric:
            stats[str(c)].update(min=min(numeric), max=max(numeric),
                                 mean=round(sum(numeric) / len(numeric), 4))
    return {"columns": [str(c) for c in cols], "rows": len(rows), "stats": stats}


def csv_diff(path_a, path_b, key_col=0):
    pa, pb = Path(path_a), Path(path_b)
    for p in (pa, pb):
        if not p.is_file():
            return {"error": "файл не найден: %s" % p.name}
    key = 0 if key_col is None else int(key_col)
    def load(p):
        text = p.read_text(encoding="utf-8", errors="replace")[:MAX_TABLE_BYTES]
        parsed = list(csv.reader(text.splitlines()))
        return parsed[0] if parsed else [], parsed[1:MAX_TABLE_ROWS]
    head_a, rows_a = load(pa)
    head_b, rows_b = load(pb)

    def keyed(rows):
        return {str(r[key]) if key < len(r) else "": r for r in rows}

    a, b = keyed(rows_a), keyed(rows_b)
    added = [b[k] for k in b if k not in a]
    removed = [a[k] for k in a if k not in b]
    changed = [[a[k], b[k]] for k in b if k in a and a[k] != b[k]]
    return {"added": added[:200], "removed": removed[:200], "changed": changed[:200],
            "counts": {"added": len(added), "removed": len(removed), "changed": len(changed)},
            "header_changed": head_a != head_b}


# ---------- 196. импорт промптов из конфигов Claude Code / OpenCode ----------
def import_prompts(base=None, max_prompts=200):
    """Собирает промпты/команды из .claude/commands, .claude/skills и .opencode/command."""
    base = Path(base) if base else Path.home()
    found = []
    for sub in (".claude/commands", ".claude/skills", ".opencode/command"):
        d = base / sub
        if not d.is_dir():
            continue
        for f in sorted(d.rglob("*.md"))[:max_prompts]:
            try:
                text = f.read_text(encoding="utf-8", errors="replace")[:20000]
            except OSError:
                continue
            found.append({"name": f.stem, "source": sub, "text": text})
    return {"prompts": found}


# ---------- 185. карантин битых sync-пакетов ----------
def quarantine_packet(project, packet, expected_hash=None):
    """Пакет с битым JSON, без отправителя или с несовпавшим хешем — в карантин, не в данные."""
    raw = packet if isinstance(packet, str) else json.dumps(packet, ensure_ascii=False)
    reason = None
    parsed = None
    try:
        parsed = json.loads(raw)
    except ValueError:
        reason = "не JSON"
    if parsed is not None and not isinstance(parsed, dict):
        reason = "пакет не объект"
    elif parsed is not None and reason is None and not str(parsed.get("device") or "").strip():
        reason = "нет отправителя (device)"
    elif parsed is not None and reason is None and expected_hash is not None \
            and content_hash(raw) != expected_hash:
        reason = "хеш не совпал"
    if reason is None:
        return {"ok": True, "quarantined": False, "packet": parsed}
    qdir = _pc_json(project, "quarantine")
    qdir.mkdir(parents=True, exist_ok=True)
    try:
        (qdir / ("%s.json" % content_hash(raw)[:16])).write_text(raw[:100000], encoding="utf-8")
        files = sorted(qdir.glob("*.json"))
        for old in files[:-QUARANTINE_MAX]:
            old.unlink()
    except OSError as e:
        return {"ok": False, "quarantined": False, "reason": reason, "error": str(e)}
    return {"ok": False, "quarantined": True, "reason": reason}


def quarantine_list(project):
    qdir = _pc_json(project, "quarantine")
    if not qdir.is_dir():
        return {"items": []}
    items = []
    for f in sorted(qdir.glob("*.json")):
        head = _pc_load(f, {})
        items.append({"file": f.name, "reason": head.get("reason", "?"), "device": head.get("device", "")})
    return {"items": items[:QUARANTINE_MAX]}


# ================= Батч-6: приоритеты, архив, краши, заметки, гость, целостность =================

MAX_NOTES = 500
MAX_CRASHES = 100
NOTE_BODY_MAX = 20000


# ---------- 46. приоритетная очередь задач ----------
def queue_sort_key(item):
    return (int(item.get("prio", 5)), int(item.get("ts", 0)))


def queue_pick(items):
    """Задача с наименьшим приоритетным числом (0 — срочная), при равенстве — самая старая."""
    items = [i for i in (items or []) if isinstance(i, dict)]
    if not items:
        return None
    return sorted(items, key=queue_sort_key)[0]


# ---------- 182. журнал крашей ----------
def crash_report(project, kind, text, where=""):
    kind = str(kind or "unknown")[:60]
    text = str(text or "")[:8000]
    entries = _pc_load(_pc_json(project, "crashes.json"), [])
    entries.append({"ts": int(time.time()), "kind": kind, "where": str(where or "")[:200], "text": text})
    entries = entries[-MAX_CRASHES:]
    _pc_save(_pc_json(project, "crashes.json"), entries)
    return {"ok": True, "total": len(entries)}


def crash_list(project, limit=50):
    limit = 50 if limit is None else max(1, min(int(limit), MAX_CRASHES))
    entries = _pc_load(_pc_json(project, "crashes.json"), [])
    by_kind = {}
    for e in entries:
        by_kind[e.get("kind", "?")] = by_kind.get(e.get("kind", "?"), 0) + 1
    return {"items": list(reversed(entries))[:limit], "total": len(entries), "by_kind": by_kind}


# ---------- 165. мини-CMS: структурированные заметки ----------
def note_add(project, title, body, tags=""):
    title = str(title or "").strip()
    body = str(body or "")[:NOTE_BODY_MAX]
    if not title and not body:
        return {"error": "пустая заметка"}
    tags = [t.strip().lower() for t in re.split(r"[,;]", str(tags or "")) if t.strip()][:10]
    notes = _pc_load(_pc_json(project, "notes.json"), [])
    if len(notes) >= MAX_NOTES:
        return {"error": "слишком много заметок (максимум %d)" % MAX_NOTES}
    item = {"id": content_hash(title + body)[:10], "title": title[:200], "body": body,
            "tags": tags, "ts": int(time.time())}
    notes.append(item)
    _pc_save(_pc_json(project, "notes.json"), notes)
    return {"ok": True, "id": item["id"]}


def note_list(project, query="", tag=""):
    notes = _pc_load(_pc_json(project, "notes.json"), [])
    q = str(query or "").strip().lower()
    t = str(tag or "").strip().lower()
    out = []
    for n in reversed(notes):  # свежие сверху
        if t and t not in n.get("tags", []):
            continue
        if q and q not in n.get("title", "").lower() and q not in n.get("body", "").lower() \
                and not any(q in tag for tag in n.get("tags", [])):
            continue
        out.append(n)
    return {"items": out, "total": len(out)}


def note_delete(project, nid):
    notes = _pc_load(_pc_json(project, "notes.json"), [])
    left = [n for n in notes if n.get("id") != nid]
    if len(left) == len(notes):
        return {"error": "заметка не найдена"}
    _pc_save(_pc_json(project, "notes.json"), left)
    return {"ok": True}


# ---------- 62. гостевой доступ: показать проект без секретов ----------
REDACT_PATTERNS = SECRET_PATTERNS + [
    ("github-token", re.compile(r"gh[posu]_[A-Za-z0-9]{16,}")),
    ("github-pat", re.compile(r"github_pat_[A-Za-z0-9_]{16,}")),
    ("openai-key", re.compile(r"sk-[A-Za-z0-9_-]{16,}")),
    ("bearer", re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._-]{12,}")),
]


def _redact_secrets(text):
    out = []
    for line in str(text or "").splitlines():
        for _kind, rx in REDACT_PATTERNS:
            line = rx.sub("***", line)
        out.append(line)
    return "\n".join(out)


def guest_create(project, ttl_hours=24):
    """Токен гостевого доступа с TTL: без него снапшот не показать."""
    token = content_hash(os.urandom(16).hex())[:24]
    data = {"token": token, "ts": int(time.time()), "ttl": max(1, min(int(ttl_hours or 24), 168))}
    _pc_save(_pc_json(project, "guest.json"), data)
    return {"ok": True, "token": token, "ttl_hours": data["ttl"]}


def guest_view(project, token, max_files=200):
    data = _pc_load(_pc_json(project, "guest.json"), {})
    if not data.get("token") or str(token or "") != data["token"]:
        return {"error": "неверный токен", "status": 403}
    age_h = (time.time() - float(data.get("ts", 0))) / 3600
    if age_h > float(data.get("ttl", 24)):
        return {"error": "срок гостевого доступа истёк", "status": 403}
    root = Path(project)
    if not root.is_dir():
        return {"error": "проект не найден", "status": 404}
    max_files = 200 if max_files is None else max(1, min(int(max_files), 2000))
    files = []
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        files.append(str(p.relative_to(root)))
        if len(files) >= int(max_files):
            break
    memory = ""
    mem_file = Path(project) / ".pocketcode" / "memory.md"
    if mem_file.is_file():
        try:
            memory = mem_file.read_text(encoding="utf-8", errors="replace")[:10000]
        except OSError:
            pass
    blocked = sum(1 for line in memory.splitlines() for _k, rx in REDACT_PATTERNS if rx.search(line))
    return {"status": 200, "files": files, "memory_redacted": _redact_secrets(memory),
            "secrets_blocked": blocked, "note": "секреты вырезаны автоматически"}


# ---------- 178. санитарная проверка целостности данных ----------
def integrity_check(project):
    base = _pc_json(project, "")
    problems = []
    checked = 0
    if base.is_dir():
        for f in sorted(base.glob("*.json")):
            checked += 1
            try:
                json.loads(f.read_text(encoding="utf-8"))
            except (OSError, ValueError) as e:
                problems.append({"file": f.name, "problem": "битый JSON: %s" % str(e)[:120]})
    else:
        problems.append({"file": ".pocketcode/", "problem": "каталог данных проекта не найден"})
    qs = quarantine_list(project)
    if qs["items"]:
        problems.append({"file": "quarantine/", "problem": "%d пакетов в карантине" % len(qs["items"])})
    for name in ("cron.json", "notes.json", "crashes.json"):
        entries = _pc_load(_pc_json(project, name), [])
        if entries and not isinstance(entries, list):
            problems.append({"file": name, "problem": "ожидается список"})
    return {"ok": not problems, "checked": checked, "problems": problems}


# ================= Батч-7: плагины, аналитика сессий, медиа, рейтинги =================

MEDIA_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg"}


# ---------- плагины (десктоп): локальные скрипты в каталоге plugins ----------
def plugin_list(plugins_dir, state_path):
    d = Path(plugins_dir)
    state = _pc_load(state_path, {"disabled": []})
    disabled = set(state.get("disabled", []))
    items = []
    if d.is_dir():
        for f in sorted(d.glob("*.py")):
            if f.name.startswith("_"):
                continue
            items.append({"name": f.stem, "file": f.name, "size": f.stat().st_size,
                          "enabled": f.stem not in disabled})
    return {"items": items}


def plugin_toggle(state_path, name, enabled):
    name = str(name or "").strip()
    if not name or not re.fullmatch(r"[A-Za-z0-9_\-]+", name):
        return {"error": "неверное имя плагина"}
    state = _pc_load(state_path, {"disabled": []})
    disabled = [n for n in state.get("disabled", []) if n != name]
    if not enabled:
        disabled.append(name)
    _pc_save(state_path, {"disabled": disabled})
    return {"ok": True, "enabled": enabled}


def plugin_run(plugins_dir, state_path, name, args=(), workdir=None, timeout=20):
    """Плагин — обычный скрипт: запускается в песочнице (лимиты CPU/памяти/записи)."""
    name = str(name or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_\-]+", name):
        return {"error": "неверное имя плагина"}
    state = _pc_load(state_path, {"disabled": []})
    if name in set(state.get("disabled", [])):
        return {"error": "плагин выключен", "status": 403}
    f = Path(plugins_dir) / ("%s.py" % name)
    if not f.is_file():
        return {"error": "плагин не найден", "status": 404}
    timeout = 20 if timeout is None else max(1, min(int(timeout), 120))
    interpreter = shutil.which("python3") or shutil.which("python") or sys.executable
    argv = [interpreter, str(f)] + [str(a) for a in (args or [])][:20]
    return run_sandboxed(argv, workdir or str(f.parent), timeout=timeout)


# ---------- аналитика агентских сессий ----------
def session_stats(sessions, messages):
    items = [s for s in (sessions or {}).get("items", []) if isinstance(s, dict)]
    msgs = [m for m in (messages or []) if isinstance(m, dict)]
    per, roles = {}, {"user": 0, "assistant": 0}
    days = set()
    for m in msgs:
        sid = str(m.get("session") or "default")
        per[sid] = per.get(sid, 0) + 1
        if m.get("role") in roles:
            roles[m["role"]] += 1
        ts = m.get("ts")
        if isinstance(ts, (int, float)) and ts > 0:
            days.add(time.strftime("%Y-%m-%d", time.localtime(ts / 1000 if ts > 10**11 else ts)))
    return {"sessions": len(items), "messages": len(msgs), "by_role": roles,
            "by_session": per, "active_days": len(days),
            "busiest": sorted(per.items(), key=lambda kv: -kv[1])[:5]}


# ---------- просмотрщик изображений: список медиа с размерами ----------
def _img_size(data, ext):
    """(ширина, высота) из заголовка; только stdlib-парсинг."""
    try:
        if ext == ".png" and data[:8] == b"\x89PNG\r\n\x1a\n":
            return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
        if ext == ".gif" and data[:4] == b"GIF8":
            return int.from_bytes(data[6:8], "little"), int.from_bytes(data[8:10], "little")
        if ext in (".jpg", ".jpeg") and data[:2] == b"\xff\xd8":
            i = 2
            while i < len(data) - 9:
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker in (0xC0, 0xC1, 0xC2, 0xC3):
                    return int.from_bytes(data[i + 7:i + 9], "big"), int.from_bytes(data[i + 5:i + 7], "big")
                if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                    i += 2
                else:
                    i += 2 + int.from_bytes(data[i + 2:i + 4], "big")
    except (IndexError, ValueError, struct.error):
        return None
    return None


def media_list(root, max_files=100):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    max_files = 100 if max_files is None else max(1, min(int(max_files), 1000))
    out = []
    for p in sorted(root.rglob("*")):
        if not p.is_file() or p.suffix.lower() not in MEDIA_EXTS:
            continue
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        try:
            head = p.read_bytes()[:65536]
        except OSError:
            continue
        w, h = (None, None)
        if p.suffix.lower() in (".png", ".gif", ".jpg", ".jpeg"):
            size = _img_size(head, p.suffix.lower())
            if size:
                w, h = size
        out.append({"file": str(p.relative_to(root)), "bytes": p.stat().st_size,
                    "width": w, "height": h})
        if len(out) >= int(max_files):
            break
    return {"items": out, "total": len(out)}


# ---------- рейтинги навыков/шаблонов ----------
def rate_item(path, kind, name, delta):
    kind = str(kind or "").strip().lower()
    name = str(name or "").strip()
    if kind not in ("skill", "template", "agent", "prompt"):
        return {"error": "тип: skill | template | agent | prompt"}
    if not name:
        return {"error": "пустое имя"}
    if delta not in (1, -1, "1", "-1"):
        return {"error": "голос: 1 или -1"}
    data = _pc_load(path, {})
    items = data.setdefault(kind, {})
    item = items.setdefault(name, {"up": 0, "down": 0})
    if int(delta) == 1:
        item["up"] += 1
    else:
        item["down"] += 1
    _pc_save(path, data)
    item["score"] = item["up"] - item["down"]
    return {"ok": True, "kind": kind, "name": name, "up": item["up"], "down": item["down"],
            "score": item["score"]}


def ratings_top(path, kind, limit=10):
    data = _pc_load(path, {}).get(str(kind or "").strip().lower(), {})
    items = [{"name": n, **v, "score": v.get("up", 0) - v.get("down", 0)} for n, v in data.items()]
    items.sort(key=lambda x: (-x["score"], -x.get("up", 0), x["name"]))
    return {"items": items[:max(1, min(int(limit or 10), 50))]}


# ================= Батч-8: кэш git, JSON, импорт архива, read-only, роли, репо, здоровье =================

GIT_CACHE_TTL = 15.0
_git_cache = {}
_git_cache_lock = threading.Lock()
TEAM_ROLES = ("owner", "editor", "reader")
WRITE_MAX_SECONDS = 120


# ---------- 167. кэш git-статусов ----------
def git_status_cached(root, ttl=GIT_CACHE_TTL):
    key = str(root)
    now = time.time()
    with _git_cache_lock:
        hit = _git_cache.get(key)
        if hit and now - hit[0] < float(ttl):
            return dict(hit[1], cached=True)
    val = git_status_brief(root)
    with _git_cache_lock:
        _git_cache[key] = (now, val)
    return dict(val, cached=False)


def git_cache_invalidate(root=None):
    with _git_cache_lock:
        if root is None:
            _git_cache.clear()
        else:
            _git_cache.pop(str(root), None)
    return {"ok": True}


# ---------- 158. валидатор JSON с понятными ошибками ----------
def json_validate(text, autofix=False):
    text = str(text or "")
    notes = []
    if autofix:
        fixed = re.sub(r",(\s*[}\]])", r"\1", text)  # висячие запятые
        if fixed != text:
            notes.append("убраны висячие запятые")
            text = fixed
    try:
        data = json.loads(text)
        return {"ok": True, "data": data, "notes": notes}
    except ValueError as e:
        pos = getattr(e, "pos", None)
        out = {"ok": False, "error": str(e)[:200], "notes": notes}
        if isinstance(pos, int):
            line = text.count("\n", 0, pos) + 1
            col = pos - text.rfind("\n", 0, pos)
            out.update(line=line, col=col)
        return out


# ---------- 184. режим «только чтение» при нехватке места ----------
def disk_status(home, min_free_mb=500):
    min_free_mb = 500 if min_free_mb is None else max(50, min(int(min_free_mb), 100000))
    usage = shutil.disk_usage(str(home))
    free_mb = usage.free // (1024 * 1024)
    return {"free_mb": free_mb, "total_mb": usage.total // (1024 * 1024),
            "min_free_mb": min_free_mb, "readonly": free_mb < min_free_mb}


def disk_low(home, min_free_mb=500):
    return disk_status(home, min_free_mb)["readonly"]


# ---------- 189. командные роли: владелец/редактор/читатель ----------
def team_set(project, name, role):
    name = str(name or "").strip()[:60]
    role = str(role or "").strip().lower()
    if not name:
        return {"error": "пустое имя участника"}
    if role not in TEAM_ROLES:
        return {"error": "роль: owner | editor | reader"}
    f = _pc_json(project, "team.json")
    data = _pc_load(f, {})
    owner = data.get("owner") or name  # первый назначенный становится владельцем
    members = data.get("members", {})
    if role == "owner":
        members[owner] = "editor" if owner != name and owner in members else members.get(owner, "editor")
        owner = name
    members[name] = role
    _pc_save(f, {"owner": owner, "members": members})
    return {"ok": True, "owner": owner, "members": members}


def team_get(project):
    data = _pc_load(_pc_json(project, "team.json"), {})
    return {"owner": data.get("owner", ""), "members": data.get("members", {})}


def team_check(project, name, action):
    """action: task_run / file_write / settings / sync — что разрешено каждой роли."""
    name = str(name or "").strip()
    data = _pc_load(_pc_json(project, "team.json"), {})
    role = "reader"
    if name and (name == data.get("owner") or not data.get("owner")):
        role = "owner"
    elif name:
        role = data.get("members", {}).get(name, "reader")
    need = {"task_run": "editor", "file_write": "editor", "settings": "owner", "sync": "editor"}
    need_role = need.get(str(action or ""), "owner")
    rank = {"reader": 0, "editor": 1, "owner": 2}
    allowed = rank[role] >= rank[need_role]
    return {"allowed": allowed, "role": role, "action": action, "needs": need_role}


# ---------- 197. экспорт проекта в готовый репозиторий ----------
README_TEMPLATE = """# {name}

Проект PocketCode. Экспортирован {date}.

## Что внутри
{about}

## Запуск
{run}

## Структура
см. карту проекта `.pocketcode/` и заметки.
"""

CI_TEMPLATE = """name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: |
          pip install -r requirements.txt 2>/dev/null || true
          python3 -m unittest discover -s . -p 'test_*.py' 2>/dev/null || pytest -q 2>/dev/null || echo "нет тестов"
"""


def export_project_repo(project, dest=None, run_cmd=""):
    src = Path(project)
    if not src.is_dir():
        return {"error": "проект не найден"}
    dest = Path(dest) if dest else src.parent / ("%s-repo" % src.name)
    dest.mkdir(parents=True, exist_ok=True)
    about = ""
    notes = _pc_load(_pc_json(project, "notes.json"), [])
    if notes:
        about = "\n".join("- %s: %s" % (n.get("title", ""), n.get("body", "")[:120])
                          for n in notes[-5:])
    (dest / "README.md").write_text(README_TEMPLATE.format(
        name=src.name, date=time.strftime("%Y-%m-%d"), about=about or "см. исходники",
        run=run_cmd or "см. README исходного проекта"), encoding="utf-8")
    (dest / ".github" / "workflows").mkdir(parents=True, exist_ok=True)
    (dest / ".github" / "workflows" / "ci.yml").write_text(CI_TEMPLATE, encoding="utf-8")
    lic = dest / "LICENSE"
    if not lic.exists():
        lic.write_text("MIT License\n\nCopyright (c) %s\n\nТекст лицензии MIT: "
                       "https://opensource.org/licenses/MIT\n" % time.strftime("%Y"),
                       encoding="utf-8")
    copied = 0
    for p in src.iterdir():
        if p.name in (".pocketcode", "build", "node_modules", "__pycache__") or p.name.startswith("."):
            continue
        if p.is_file():
            shutil.copy2(p, dest / p.name)
            copied += 1
        elif p.is_dir():
            shutil.copytree(p, dest / p.name, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("*.pyc", "__pycache__", "build"))
            copied += 1
    return {"ok": True, "dest": str(dest), "copied": copied}


# ---------- 199. отчёт «здоровье проекта» ----------
def project_health(project):
    integ = integrity_check(project)
    dead = find_dead_code(project)
    disk = disk_status(Path(project).parent if Path(project).parent.is_dir() else Path(project))
    qs = quarantine_list(project)
    score = 100
    score -= 20 * len(integ.get("problems", []))
    score -= min(20, 2 * len(dead.get("dead", [])))
    score -= 10 if qs["items"] else 0
    if disk["readonly"]:
        score -= 30
    return {"score": max(0, score), "integrity": integ, "dead_code": len(dead.get("dead", [])),
            "quarantined": len(qs["items"]), "disk_free_mb": disk["free_mb"],
            "readonly": disk["readonly"]}


# ---------- 180. импорт полного архива с миграцией версий ----------
def full_import(zip_path, projects_dir, home, app_version="0"):
    """Разворачивает архив от full_export: настройки — в home, проекты — в projects_dir.
    Старые версии данных мигрируют (версия архива записывается в .pocketcode/import.json)."""
    zpath = Path(zip_path)
    if not zpath.is_file():
        return {"error": "архив не найден"}
    imported, skipped = [], []
    try:
        with zipfile.ZipFile(zpath) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                name = info.filename
                target = None
                if name.startswith("projects/"):
                    rel = name[len("projects/"):]
                    if not rel or ".." in rel:
                        skipped.append(name)
                        continue
                    target = Path(projects_dir) / rel
                elif "/" not in name:
                    target = Path(home) / name
                else:
                    skipped.append(name)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as fsrc, open(target, "wb") as fdst:
                    shutil.copyfileobj(fsrc, fdst)
                imported.append(name)
    except (OSError, zipfile.BadZipFile) as e:
        return {"error": "битый архив: %s" % str(e)[:120]}
    # миграция: помечаем импортированные .pocketcode-каталоги версией архива
    migrated = 0
    for pc in Path(projects_dir).glob("*/.pocketcode"):
        marker = pc / "import.json"
        if not marker.exists():
            marker.write_text(json.dumps({"from_version": app_version,
                                          "ts": int(time.time())}, ensure_ascii=False),
                              encoding="utf-8")
            migrated += 1
    return {"ok": True, "imported": len(imported), "skipped": len(skipped), "migrated": migrated}


# ================= Батч-9: 50 фич одним проходом =================

LANG_EXTS = {".py": "Python", ".js": "JavaScript", ".ts": "TypeScript", ".kt": "Kotlin",
             ".java": "Java", ".go": "Go", ".rs": "Rust", ".c": "C", ".cpp": "C++",
             ".sh": "Shell", ".md": "Markdown", ".json": "JSON", ".html": "HTML", ".css": "CSS"}


def _safe_join(root, rel):
    p = (Path(root) / str(rel or "")).resolve()
    root = Path(root).resolve()
    if not str(p).startswith(str(root)):
        return None
    return p


# --- метрики кода ---
def cloc(root, max_files=400):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    stats, files_n = {}, 0
    for p in sorted(root.rglob("*"))[:int(max_files) * 4]:
        if not p.is_file() or p.suffix not in LANG_EXTS:
            continue
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        try:
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        code = sum(1 for l in lines if l.strip() and not l.strip().startswith(("#", "//")))
        lang = LANG_EXTS[p.suffix]
        s = stats.setdefault(lang, {"files": 0, "code": 0, "blank": 0})
        s["files"] += 1
        s["code"] += code
        s["blank"] += len(lines) - code
        files_n += 1
        if files_n >= int(max_files):
            break
    return {"languages": stats, "files": files_n}


def biggest_files(root, limit=10):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    items = []
    for p in root.rglob("*"):
        if p.is_file() and not any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            items.append((p.stat().st_size, str(p.relative_to(root))))
    items.sort(reverse=True)
    return {"items": [{"file": f, "kb": round(b / 1024, 1)} for b, f in items[:max(1, int(limit or 10))]]}


def stale_files(root, days=30, limit=20):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    days = 30 if days is None else float(days)
    limit = 20 if limit is None else max(1, min(int(limit), 500))
    cut = time.time() - days * 86400
    items = []
    for p in root.rglob("*"):
        if p.is_file() and not any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            m = p.stat().st_mtime
            if m < cut:
                items.append((m, str(p.relative_to(root))))
    items.sort()
    return {"items": [{"file": f, "days_old": int((time.time() - m) / 86400)} for m, f in items[:limit]]}


def dup_files(root, max_mb=5, limit=50):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    groups = {}
    for p in root.rglob("*"):
        if not p.is_file() or p.stat().st_size > int(max_mb) * 1024 * 1024:
            continue
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        groups.setdefault(p.stat().st_size, []).append(p)
    dups = []
    for size, ps in groups.items():
        if len(ps) < 2:
            continue
        by_hash = {}
        for p in ps:
            try:
                h = hashlib.md5(p.read_bytes()).hexdigest()  # не для криптографии
            except OSError:
                continue
            by_hash.setdefault(h, []).append(str(p.relative_to(root)))
        for h, files in by_hash.items():
            if len(files) >= 2:
                dups.append({"files": files, "kb": round(size / 1024, 1)})
                if len(dups) >= int(limit):
                    return {"items": dups}
    return {"items": dups}


def empty_dirs(root):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    out = []
    for p in sorted(root.rglob("*"), reverse=True):
        if p.is_dir() and not any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts) \
                and not any(p.iterdir()):
            out.append(str(p.relative_to(root)))
    return {"items": out}


# --- Markdown ---
def md_broken_links(root, max_files=100):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    broken = []
    checked = 0
    for p in sorted(root.rglob("*.md"))[:int(max_files)]:
        if any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for m in re.finditer(r"\[[^\]]*\]\(([^)#?][^)]*)\)", text):
            target = _safe_join(p.parent, m.group(1).strip())
            checked += 1
            if target is None or not target.exists():
                broken.append({"file": str(p.relative_to(root)), "link": m.group(1)})
    return {"broken": broken, "checked": checked}


def md_toc(text, max_depth=3):
    out = []
    in_code = False
    for line in str(text or "").splitlines():
        if line.startswith("```"):
            in_code = not in_code
            continue
        if in_code:
            continue
        m = re.match(r"^(#{1,%d})\s+(.+)$" % int(max_depth or 3), line)
        if m:
            indent = "  " * (len(m.group(1)) - 1)
            anchor = re.sub(r"[^\wа-яё ]", "", m.group(2).lower()).replace(" ", "-")
            out.append("%s- [%s](#%s)" % (indent, m.group(2), anchor))
    return {"toc": "\n".join(out)}


# --- генераторы ---
def gitignore_gen(lang="python"):
    templates = {
        "python": ["__pycache__/", "*.pyc", ".venv/", "venv/", ".pytest_cache/", "dist/", "build/"],
        "node": ["node_modules/", "dist/", ".env", "coverage/"],
        "kotlin": ["build/", ".gradle/", ".idea/", "*.iml", "local.properties"],
        "go": ["bin/", "pkg/", "*.test"],
        "rust": ["target/", "Cargo.lock"],
    }
    lang = str(lang or "python").lower()
    if lang not in templates:
        return {"error": "язык: %s" % "|".join(templates)}
    return {"gitignore": "\n".join(templates[lang]) + "\n"}


LICENSE_HEADERS = {"mit": "SPDX-License-Identifier: MIT", "apache": "SPDX-License-Identifier: Apache-2.0"}


def license_header(file, license="mit"):
    spdx = LICENSE_HEADERS.get(str(license or "mit").lower())
    if not spdx:
        return {"error": "лицензия: mit | apache"}
    p = Path(file)
    if not p.is_file():
        return {"error": "файл не найден"}
    try:
        text = p.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        return {"error": str(e)}
    if "SPDX-License-Identifier" in text:
        return {"ok": True, "changed": False}
    p.write_text("# %s\n%s" % (spdx, text), encoding="utf-8")
    return {"ok": True, "changed": True}


def changelog_from_git(root, limit=20):
    p = subprocess.run(["git", "log", "--pretty=%h %ad %s", "--date=short", "-n", str(int(limit or 20))],
                       cwd=str(root), capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=30)
    if p.returncode != 0:
        return {"error": "не git-репозиторий"}
    lines = ["- " + l for l in p.stdout.strip().splitlines() if l]
    return {"changelog": "\n".join(lines)}


# --- хранилища ---
def snippet_add(path, name, code, lang=""):
    name = str(name or "").strip()[:60]
    code = str(code or "")[:10000]
    if not name or not code:
        return {"error": "нужны имя и код"}
    data = _pc_load(path, {})
    items = data.setdefault("items", [])
    items.append({"name": name, "lang": str(lang or "")[:20], "code": code, "ts": int(time.time())})
    _pc_save(path, {"items": items[-500:]})
    return {"ok": True, "id": content_hash(name + code)[:10]}


def snippet_search(path, query=""):
    q = str(query or "").strip().lower()
    items = _pc_load(path, {}).get("items", [])
    out = [i for i in items if not q or q in i["name"].lower() or q in i["code"].lower()]
    return {"items": list(reversed(out[-50:]))}


def drafts_save(path, session, text):
    data = _pc_load(path, {})
    data[str(session or "default")[:40]] = str(text or "")[:5000]
    _pc_save(path, data)
    return {"ok": True}


def drafts_load(path, session):
    return {"text": _pc_load(path, {}).get(str(session or "default")[:40], "")}


def clipboard_push(path, text, limit=50):
    items = _pc_load(path, [])
    items.append({"text": str(text or "")[:2000], "ts": int(time.time())})
    _pc_save(path, items[-int(limit):])
    return {"ok": True}


def clipboard_history(path, limit=20):
    items = _pc_load(path, [])
    return {"items": list(reversed(items))[:max(1, int(limit or 20))]}


def pin_file(project, rel):
    p = _safe_join(project, rel)
    if p is None or not p.is_file():
        return {"error": "файл не найден"}
    f = _pc_json(project, "pins.json")
    pins = _pc_load(f, [])
    if str(rel) not in pins:
        pins.append(str(rel))
        _pc_save(f, pins)
    return {"ok": True, "pins": pins}


def unpin_file(project, rel):
    f = _pc_json(project, "pins.json")
    pins = [x for x in _pc_load(f, []) if x != str(rel)]
    _pc_save(f, pins)
    return {"ok": True, "pins": pins}


def tag_project(project, tags):
    if isinstance(tags, str):
        tags = [t for t in re.split(r"[,;]", tags) if t.strip()]
    tags = [t.strip().lower()[:30] for t in (tags or [])][:10]
    _pc_save(_pc_json(project, "tags.json"), tags)
    return {"ok": True, "tags": tags}


# --- доска TODO ---
def todo_board(root, max_files=300):
    raw = scan_todos(root, int(max_files or 300))
    items = list(raw or [])
    board = {"TODO": [], "FIXME": []}
    for i in items[:500]:
        kind = str(i.get("kind") or i.get("tag") or "TODO").upper()
        board.setdefault(kind, []).append(i)
    return {"board": board, "total": len(items)}


# --- активность и привычки ---
def heatmap(root, days=28):
    root = Path(root)
    if not root.is_dir():
        return {"error": "проект не найден"}
    cut = time.time() - float(days or 28) * 86400
    per_day = {}
    for p in root.rglob("*"):
        if p.is_file() and not any(part in _SKIP_DIRS or part.startswith(".") for part in p.parts):
            m = p.stat().st_mtime
            if m >= cut:
                day = time.strftime("%Y-%m-%d", time.localtime(m))
                per_day[day] = per_day.get(day, 0) + 1
    return {"per_day": per_day, "days": sorted(per_day)}


def streak_and_goal(root, goal=3):
    hm = heatmap(root, days=90).get("per_day", {})
    today = time.strftime("%Y-%m-%d")
    days_active = sorted(hm, reverse=True)
    streak = 0
    d = time.localtime()
    for i in range(90):
        day = time.strftime("%Y-%m-%d", time.localtime(time.time() - i * 86400))
        if day in hm:
            streak += 1
        elif i > 0:
            break
    return {"streak": streak, "today_files": hm.get(today, 0),
            "goal": int(goal or 3), "goal_met": hm.get(today, 0) >= int(goal or 3)}


def daily_digest(project, root):
    crashes = crash_list(project, limit=5)
    notes = note_list(project, query="")
    ch = changelog_from_git(root, limit=5)
    return {"date": time.strftime("%Y-%m-%d"), "crashes": crashes["total"],
            "latest_notes": notes["total"], "recent_commits": ch.get("changelog", ""),
            "health_score": project_health(root)["score"]}


# --- экспорт ---
def export_chat_md(messages, path, limit=500):
    lines = []
    for msg in list(messages or [])[-int(limit or 500):]:
        who = {"user": "**Ты**", "assistant": "**Агент**"}.get(msg.get("role"), str(msg.get("role")))
        lines.append("%s: %s" % (who, str(msg.get("text", ""))[:2000]))
    md = "\n\n".join(lines)
    try:
        Path(path).write_text(md, encoding="utf-8")
    except OSError as e:
        return {"error": str(e)}
    return {"ok": True, "path": str(path), "chars": len(md)}


def metrics_csv(rows, path):
    import io as _io
    buf = _io.StringIO()
    if rows:
        w = csv.writer(buf)
        if isinstance(rows[0], dict):
            w.writerow(list(rows[0].keys()))
            for r in rows:
                w.writerow([r[k] for k in rows[0]])
        else:
            w.writerows(rows)
    try:
        Path(path).write_text(buf.getvalue(), encoding="utf-8")
    except OSError as e:
        return {"error": str(e)}
    return {"ok": True, "rows": len(rows)}


# --- окружение и зависимости ---
def env_doctor(home):
    checks = []
    checks.append({"name": "python", "ok": sys.version_info >= (3, 10),
                   "info": platform_module.python_version()})
    g = subprocess.run(["git", "--version"], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=10)
    checks.append({"name": "git", "ok": g.returncode == 0, "info": g.stdout.strip()[:40]})
    disk = disk_status(home)
    checks.append({"name": "disk", "ok": not disk["readonly"], "info": "%d MB свободно" % disk["free_mb"]})
    try:
        socket.create_connection(("127.0.0.1", 80), timeout=0.2)
        loopback = True
    except OSError:
        loopback = False
    checks.append({"name": "network-stack", "ok": True, "info": "loopback ok" if loopback else "loopback недоступен (не критично)"})
    return {"ok": all(c["ok"] for c in checks), "checks": checks}


DEP_RULES = [("pyyaml<5.4", "известная уязвимость"), ("requests<2.20", "старая версия"),
             ("django<3.2", "нет обновлений безопасности"), ("flask<1.0", "очень старая версия")]


def dep_audit(root):
    root = Path(root)
    findings = []
    req = root / "requirements.txt"
    if req.is_file():
        try:
            for line in req.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip().lower()
                for pat, why in DEP_RULES:
                    if pat.split("<")[0] in line and "<" in line:
                        findings.append({"dep": line, "why": why})
        except OSError:
            pass
    pkg = root / "package.json"
    if pkg.is_file():
        text = pkg.read_text(encoding="utf-8", errors="replace").lower()
        for pat, why in DEP_RULES:
            name = pat.split("<")[0]
            if '"%s"' % name in text:
                findings.append({"dep": name, "why": why + " (package.json)"})
    return {"findings": findings, "checked": [f.name for f in (req, pkg) if f.is_file()]}


# --- корзина, блокировка ---
def trash_delete(root, rel):
    p = _safe_join(root, rel)
    if p is None or not p.exists():
        return {"error": "файл не найден"}
    tdir = _pc_json(root, "trash")
    tdir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = tdir / ("%s-%s" % (stamp, p.name))
    shutil.move(str(p), str(dest))
    return {"ok": True, "trash_file": dest.name}


def trash_restore(root, trash_file):
    tdir = _pc_json(root, "trash")
    src = tdir / str(trash_file or "")
    if not src.is_file() or ".." in str(trash_file):
        return {"error": "файл корзины не найден"}
    name = re.sub(r"^\d{8}-\d{6}-", "", src.name)
    shutil.move(str(src), str(Path(root) / name))
    return {"ok": True, "restored": name}


def lock_project(project, password):
    if not password:
        return {"error": "пустой пароль"}
    salt = os.urandom(8).hex()
    h = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 100_000).hex()
    _pc_save(_pc_json(project, "lock.json"), {"salt": salt, "hash": h})
    return {"ok": True}


def unlock_check(project, password):
    data = _pc_load(_pc_json(project, "lock.json"), None)
    if not data:
        return {"locked": False}
    h = hashlib.pbkdf2_hmac("sha256", str(password or "").encode(), data["salt"].encode(), 100_000).hex()
    return {"locked": True, "ok": h == data["hash"]}


# --- алиасы и таймеры ---
def alias_set(path, short, full):
    short = str(short or "").strip()[:20]
    full = str(full or "").strip()[:200]
    if not short or not full:
        return {"error": "нужны алиас и команда"}
    data = _pc_load(path, {})
    data[short] = full
    _pc_save(path, data)
    return {"ok": True}


def alias_get(path, short):
    return {"command": _pc_load(path, {}).get(str(short or "").strip(), "")}


def session_timer(path, session, action):
    session = str(session or "default")[:40]
    data = _pc_load(path, {})
    timers = data.setdefault("timers", {})
    now = int(time.time())
    if action == "start":
        if timers.get(session, {}).get("start"):
            return {"error": "таймер уже идёт"}
        timers[session] = {"start": now, "total": timers.get(session, {}).get("total", 0)}
    elif action == "stop":
        t = timers.get(session, {})
        if not t.get("start"):
            return {"error": "таймер не запущен"}
        t["total"] = t.get("total", 0) + now - t["start"]
        t["start"] = 0
    elif action == "status":
        pass
    else:
        return {"error": "действие: start | stop | status"}
    _pc_save(path, data)
    t = timers.get(session, {})
    running_total = t.get("total", 0) + (now - t["start"] if t.get("start") else 0)
    return {"ok": True, "running": bool(t.get("start")), "seconds": running_total}


# --- метрики сервера (в памяти: без записи на диск на каждый запрос) ---
_metrics = {}
_metrics_lock = threading.Lock()
METRICS_MAX = 5000


def api_metrics_record(path, route, ms, status):
    with _metrics_lock:
        items = _metrics.setdefault(str(path), [])
        items.append({"route": str(route)[:40], "ms": round(float(ms), 1), "status": int(status),
                      "ts": int(time.time())})
        if len(items) > METRICS_MAX:
            del items[:len(items) - METRICS_MAX]


def api_metrics_report(path):
    with _metrics_lock:
        calls = list(_metrics.get(str(path), []))
    if not calls:
        return {"total": 0}
    per = {}
    for c in calls:
        s = per.setdefault(c["route"], {"n": 0, "ms": 0.0, "err": 0})
        s["n"] += 1
        s["ms"] += c["ms"]
        s["err"] += 1 if c["status"] >= 400 else 0
    report = [{"route": r, "calls": s["n"], "avg_ms": round(s["ms"] / s["n"], 1),
               "error_rate": round(s["err"] / s["n"], 2)} for r, s in per.items()]
    report.sort(key=lambda x: -x["avg_ms"])
    return {"total": len(calls), "slowest": report[:5],
            "error_rate": round(sum(c["status"] >= 400 for c in calls) / len(calls), 3)}


class RateLimiter:
    def __init__(self, per_minute=120):
        self.per_minute = int(per_minute)
        self.window = {}

    def allow(self, key="default"):
        now = int(time.time() // 60)
        n = self.window.get((key, now), 0)
        if n >= self.per_minute:
            return False
        self.window[(key, now)] = n + 1
        old = [k for k in self.window if k[1] < now - 1]
        for k in old:
            del self.window[k]
        return True


# --- бэкап и синхронизация ---
def backup_verify(path):
    p = Path(path)
    if not p.is_file():
        return {"ok": False, "error": "бэкапа нет"}
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        return {"ok": True, "keys": sorted(data)[:10]}
    except (OSError, ValueError) as e:
        return {"ok": False, "error": str(e)[:120]}


def sync_speed_test(payload_kb=64):
    payload = os.urandom(int(payload_kb or 64) * 1024)
    t0 = time.time()
    content_hash(payload.hex())
    ms = (time.time() - t0) * 1000
    return {"payload_kb": len(payload) // 1024, "hash_ms": round(ms, 2),
            "throughput_kbps": round(len(payload) / 1024 / max(ms / 1000, 0.001), 1)}


# --- интерфейс и доступность ---
I18N = {
    "settings": {"ru": "Настройки", "en": "Settings"},
    "projects": {"ru": "Проекты", "en": "Projects"},
    "tasks": {"ru": "Задачи", "en": "Tasks"},
    "health": {"ru": "Здоровье проекта", "en": "Project health"},
}


def i18n_keys():
    return {k: list(v.keys()) for k, v in I18N.items()}


def a11y_prefs(path, **prefs):
    data = _pc_load(path, {})
    for k in ("fontScale", "highContrast", "reduceMotion"):
        if k in prefs and prefs[k] is not None:
            data[k] = prefs[k]
    _pc_save(path, data)
    return {"ok": True, "prefs": data}


def focus_mode(path, on):
    data = _pc_load(path, {})
    data["focus"] = bool(on)
    _pc_save(path, data)
    return {"ok": True, "focus": bool(on)}


def focus_active(path):
    return {"focus": bool(_pc_load(path, {}).get("focus"))}


def shortcuts_help():
    return {"shortcuts": [
        {"cmd": "/api/palette", "what": "палитра команд"},
        {"cmd": "/api/todo/board", "what": "доска TODO/FIXME"},
        {"cmd": "/api/stats/sessions", "what": "аналитика сессий"},
        {"cmd": "/api/health/report", "what": "здоровье проекта"},
        {"cmd": "/api/export/chat/md", "what": "чат в Markdown"},
    ]}
# ================= Батч-11: навигация, онбординг, навыки, галерея, pc://, эконом =================

BOOKMARKS_MAX = 200
RECENT_MAX = 30
ONBOARD_STEPS = ("key", "model", "project", "task", "test")


def breadcrumbs(root, rel):
    """103. Хлебные крошки: цепочка «куда кликать» для файла внутри проекта."""
    root = Path(root)
    rel = str(rel or "").strip("/")
    parts = [p for p in rel.split("/") if p and p not in (".", "..")]
    cur = ""
    crumbs = []
    for i, p in enumerate(parts):
        cur = (cur + "/" + p) if cur else p
        full = root / cur
        crumbs.append({
            "name": p,
            "rel": cur,
            "is_dir": full.is_dir() if full.exists() else (i < len(parts) - 1),
        })
    return {"root": root.name, "crumbs": crumbs}


def bookmark_add(project, rel, line=None, note=""):
    """104. Закладка на файл (и строку)."""
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    marks = _pc_load(_pc_json(project, "bookmarks.json"), [])
    if len(marks) >= BOOKMARKS_MAX:
        return {"error": "слишком много закладок (максимум %d)" % BOOKMARKS_MAX}
    mark = {"id": "bm%d" % (time.time() * 1000), "rel": str(safe), "line": int(line) if line else None,
            "note": str(note or "")[:200], "created": time.time()}
    marks.append(mark)
    _pc_save(_pc_json(project, "bookmarks.json"), marks)
    return {"ok": True, "bookmark": mark}


def bookmark_list(project):
    return _pc_load(_pc_json(project, "bookmarks.json"), [])


def bookmark_remove(project, bid):
    marks = _pc_load(_pc_json(project, "bookmarks.json"), [])
    left = [m for m in marks if m.get("id") != bid]
    if len(left) == len(marks):
        return {"error": "закладка не найдена"}
    _pc_save(_pc_json(project, "bookmarks.json"), left)
    return {"ok": True}


def recent_touch(project, rel, now=None):
    """105. Недавние файлы: отметить открытие файла."""
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    data = _pc_load(_pc_json(project, "recent.json"), {})
    files = [f for f in data.get("files", []) if f.get("rel") != str(safe)]
    files.insert(0, {"rel": str(safe), "ts": (now or time.time())})
    _pc_save(_pc_json(project, "recent.json"), {"files": files[:RECENT_MAX]})
    return {"ok": True}


def recent_files(project, cap=10):
    files = _pc_load(_pc_json(project, "recent.json"), {}).get("files", [])
    return {"files": files[:max(1, min(int(cap or 10), RECENT_MAX))]}


def onboarding_mark(path, step, done=True):
    """135. Чек-лист первого запуска: отметить шаг."""
    if step not in ONBOARD_STEPS:
        return {"error": "нет такого шага (%s)" % ", ".join(ONBOARD_STEPS)}
    data = _pc_load(path, {})
    done_set = set(data.get("done", []))
    if done:
        done_set.add(step)
    else:
        done_set.discard(step)
    data["done"] = sorted(done_set)
    _pc_save(path, data)
    left = [s for s in ONBOARD_STEPS if s not in done_set]
    return {"ok": True, "done": data["done"], "left": left, "complete": not left}


def onboarding_state(path):
    done = set(_pc_load(path, {}).get("done", []))
    return {"steps": [{"step": s, "done": s in done} for s in ONBOARD_STEPS],
            "left": [s for s in ONBOARD_STEPS if s not in done],
            "complete": done >= set(ONBOARD_STEPS)}


def expert_mode(path, on):
    """140. Режим эксперта: спрятать подсказки, вернуть всё одной командой."""
    data = _pc_load(path, {})
    data["expert"] = bool(on)
    _pc_save(path, data)
    return {"ok": True, "expert": bool(on)}


def expert_active(path):
    return {"expert": bool(_pc_load(path, {}).get("expert"))}


SKILL_LIB = [
    {"name": "test-writer", "title": "Писатель тестов",
     "body": "Пиши юнит-тесты на изменённые файлы. Один тест — одно поведение; имя отражает сценарий.\n\ntest: python3 -m py_compile {file}"},
    {"name": "refactor-helper", "title": "Помощник рефакторинга",
     "body": "Рефакторь малыми шагами: сначала тест, потом перенос, потом проверка. Не меняй поведение.\n\ntest: git diff --stat"},
    {"name": "docs-updater", "title": "Обновлятор документации",
     "body": "Сверяй README с кодом: команды, примеры, имена файлов. Правь только то, что расходится.\n\ntest: grep -c README.md"},
]


def builtin_skills():
    """142. Каталог готовых навыков."""
    return {"skills": [{"name": s["name"], "title": s["title"]} for s in SKILL_LIB]}


def skill_lib_install(name, skills_dir):
    for s in SKILL_LIB:
        if s["name"] == str(name or ""):
            d = Path(skills_dir)
            d.mkdir(parents=True, exist_ok=True)
            dst = d / (s["name"] + ".md")
            dst.write_text("# %s\n\n%s\n" % (s["title"], s["body"]), encoding="utf-8")
            return {"ok": True, "path": str(dst)}
    return {"error": "нет такого навыка в каталоге"}


def skill_edit(skills_dir, name, body):
    """143. Редактор навыков: сохранить с бэкапом предыдущей версии."""
    safe = SKILL_NAME_RE.sub("-", str(name or "")).strip("-")
    if not safe:
        return {"error": "пустое имя"}
    d = Path(skills_dir)
    d.mkdir(parents=True, exist_ok=True)
    dst = d / (safe + ".md")
    if dst.exists():
        vd = d / ".versions"
        vd.mkdir(exist_ok=True)
        shutil.copy2(str(dst), str(vd / ("%s.v%d.md" % (safe, int(time.time())))))
    dst.write_text(str(body or ""), encoding="utf-8")
    return {"ok": True, "path": str(dst), "chars": len(str(body or ""))}


def skill_test(skills_dir, name, file=None, workdir=None):
    """143. Тестовый прогон навыка: выполняет его строку «test: …» в песочнице."""
    safe = SKILL_NAME_RE.sub("-", str(name or "")).strip("-")
    path = Path(skills_dir) / (safe + ".md")
    if not path.exists():
        return {"error": "навык не найден"}
    m = re.search(r"^test:\s*(.+)$", path.read_text(encoding="utf-8", errors="replace"), re.M)
    if not m:
        return {"error": "в навыке нет строки «test: …»"}
    argv = shlex_split_or_err(m.group(1).strip().replace("{file}", str(file or "README.md")))
    run = run_sandboxed(argv, workdir or ".", timeout=30)
    return {"ok": run.get("rc") == 0, "cmd": m.group(1).strip(), **run}


def skill_versions(skills_dir, name):
    """144. Версии навыка с возможностью отката."""
    safe = SKILL_NAME_RE.sub("-", str(name or "")).strip("-")
    vd = Path(skills_dir) / ".versions"
    if not vd.exists():
        return {"versions": []}
    vs = sorted(vd.glob(safe + ".v*.md"))
    return {"versions": [{"n": i + 1, "file": p.name, "ts": p.stat().st_mtime} for i, p in enumerate(vs)]}


def skill_rollback(skills_dir, name, n):
    safe = SKILL_NAME_RE.sub("-", str(name or "")).strip("-")
    vs = sorted(Path(skills_dir).glob(".versions/%s.v*.md" % safe))
    if not (1 <= int(n or 0) <= len(vs)):
        return {"error": "нет такой версии"}
    dst = Path(skills_dir) / (safe + ".md")
    shutil.copy2(str(vs[int(n) - 1]), str(dst))
    return {"ok": True, "restored": vs[int(n) - 1].name}


def template_publish(project, name):
    """186. Галерея шаблонов: опубликовать шаблон проекта в общий список."""
    safe = slug(name)
    if not (Path(project) / ".pocketcode").exists():
        return {"error": "у проекта нет .pocketcode"}
    gal_path = Path(project).parent / ".pocketcode-gallery.json"
    gal = _pc_load(gal_path, [])
    entry = {"name": safe, "title": str(name or "")[:80], "project": Path(project).name,
             "published": time.time()}
    gal = [g for g in gal if not (g["name"] == safe and g["project"] == entry["project"])]
    gal.append(entry)
    _pc_save(gal_path, gal)
    return {"ok": True, "gallery": gal_path.name, "entry": entry}


def template_gallery_list(base):
    return {"items": _pc_load(Path(base) / ".pocketcode-gallery.json", [])}


APP_CHANGELOG = [
    {"version": "0.11", "date": "2026-11-25", "items": [
        "Хлебные крошки и закладки на строки",
        "Библиотека готовых навыков с тестовым прогоном",
        "pc:// умные ссылки",
        "Эконом-режим одним тумблером",
    ]},
    {"version": "0.10", "date": "2026-11-24", "items": [
        "Синхронизация командных ролей",
        "Жесты чата: ответить и черновик",
    ]},
]


def app_changelog(path=None, limit=20):
    """191. Ченджлог приложения: пользовательский файл поверх встроенного списка."""
    if path and Path(path).exists():
        try:
            raw = Path(path).read_text(encoding="utf-8", errors="replace")[:20000]
            return {"source": "file", "changelog": raw}
        except OSError as e:
            return {"source": "builtin", "error": str(e), "changelog": APP_CHANGELOG[:limit]}
    return {"source": "builtin", "changelog": APP_CHANGELOG[:limit]}


def feedback_add(home, text, kind="idea", meta=None):
    """192. Обратная связь одной кнопкой."""
    text = str(text or "").strip()
    if not text:
        return {"error": "пустое сообщение"}
    entry = {"kind": str(kind)[:20], "text": text[:2000], "meta": meta or {}, "ts": time.time()}
    p = Path(home) / "feedback.jsonl"
    with p.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return {"ok": True, "id": int(entry["ts"] * 1000)}


def feedback_list(home, limit=100):
    p = Path(home) / "feedback.jsonl"
    if not p.exists():
        return {"items": []}
    items = []
    with p.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                items.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return {"items": items[-limit:]}


PC_LINK_KINDS = ("project", "task", "file", "settings", "skill")


def pc_link_make(kind, **params):
    """198. Умные ссылки pc://…: обмен задачами и файлами между телефоном и ПК."""
    if kind not in PC_LINK_KINDS:
        return {"error": "тип ссылки: %s" % ", ".join(PC_LINK_KINDS)}
    qs = "&".join("%s=%s" % (k, urllib.parse.quote_plus(str(v)))
                  for k, v in params.items() if k != "name" and v not in (None, ""))
    return {"url": "pc://%s/%s%s" % (kind, slug(str(params.get("name") or "id")), ("?" + qs) if qs else "")}


def pc_link_parse(url):
    m = re.match(r"^pc://([a-z]+)/([^/?]*)(?:\?(.*))?$", str(url or "").strip())
    if not m:
        return {"error": "не pc://-ссылка"}
    kind, tail, qs = m.groups()
    params = {}
    for pair in (qs or "").split("&"):
        if "=" in pair:
            k, v = pair.split("=", 1)
            params[k] = urllib.parse.unquote_plus(v)
    return {"kind": kind, "name": urllib.parse.unquote_plus(tail), "params": params}


def low_power_mode(path, on):
    """200. Эконом-режим: меньше контекст, короче ответы, фоновые задачи приостановлены."""
    data = _pc_load(path, {})
    data["lowPower"] = bool(on)
    data["lowPowerAt"] = time.time() if on else None
    _pc_save(path, data)
    return {"ok": True, "lowPower": bool(on),
            "effects": ["контекст меньше", "ответы короче", "фоновые задачи приостановлены"] if on else []}


def low_power_active(path):
    return {"lowPower": bool(_pc_load(path, {}).get("lowPower"))}


# ================= Батч-12: снапшоты, поиск по чату, заметки, Telegram, MCP, вложения =================

SNAPSHOT_MAX = 30
REMINDER_MAX = 100


def snapshot_save(project, rel):
    """Снапшоты файла: сохранить текущую версию перед опасной правкой."""
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    src = Path(project) / safe
    if not src.is_file():
        return {"error": "файл не найден"}
    d = _pc_json(project, "snapshots")
    d.mkdir(parents=True, exist_ok=True)
    base = re.sub(r"[^0-9A-Za-z._-]", "_", str(safe))
    stamp = int(time.time() * 1000)
    dst = d / ("%s.%d.bak" % (base, stamp))
    shutil.copy2(str(src), str(dst))
    old = sorted(d.glob(base + ".*.bak"))
    for extra in old[:-SNAPSHOT_MAX]:
        extra.unlink()
    return {"ok": True, "snapshot": dst.name, "kept": min(len(old), SNAPSHOT_MAX)}


def snapshot_list(project, rel):
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    base = re.sub(r"[^0-9A-Za-z._-]", "_", str(safe))
    d = _pc_json(project, "snapshots")
    if not d.exists():
        return {"snapshots": []}
    out = [{"file": p.name, "ts": p.stat().st_mtime, "size": p.stat().st_size}
           for p in sorted(d.glob(base + ".*.bak"), reverse=True)]
    return {"snapshots": out}


def snapshot_restore(project, rel, name):
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    if "/" in str(name) or ".." in str(name) or not str(name).endswith(".bak"):
        return {"error": "плохое имя снапшота"}
    src = _pc_json(project, "snapshots") / str(name)
    if not src.is_file():
        return {"error": "снапшот не найден"}
    shutil.copy2(str(src), str(Path(project) / safe))
    return {"ok": True, "restored": str(name)}


def chat_search(messages, query, limit=50):
    """Полнотекстовый поиск по истории чата (регистронезависимый)."""
    q = str(query or "").strip().lower()
    if not q:
        return {"error": "пустой запрос"}
    hits = []
    for m in messages:
        text = str(m.get("text") or m.get("content") or "") if isinstance(m, dict) else str(m)
        if q in text.lower():
            hits.append(m)
            if len(hits) >= max(1, int(limit or 50)):
                break
    return {"query": q, "hits": hits, "count": len(hits)}


def line_note_add(project, rel, line, text):
    """Комментарии к строкам файла («заметки на полях»)."""
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    try:
        ln = int(line)
    except (TypeError, ValueError):
        return {"error": "номер строки — целое число"}
    if ln < 1:
        return {"error": "номер строки >= 1"}
    notes = _pc_load(_pc_json(project, "line_notes.json"), {})
    lst = notes.setdefault(str(safe), [])
    if len(lst) >= 500:
        return {"error": "слишком много заметок у файла"}
    item = {"line": ln, "text": str(text or "")[:500], "ts": time.time()}
    lst.append(item)
    _pc_save(_pc_json(project, "line_notes.json"), notes)
    return {"ok": True, "note": item}


def line_note_list(project, rel):
    safe = _safe_join(project, rel)
    if safe is None:
        return {"error": "путь вне проекта"}
    notes = _pc_load(_pc_json(project, "line_notes.json"), {})
    return {"notes": notes.get(str(safe), [])}


HOTKEYS_DEFAULT = {"run": "Ctrl+R", "stop": "Ctrl+.", "chat": "Ctrl+K", "save": "Ctrl+S"}


def hotkey_check(user_hotkeys):
    """Реестр горячих клавиш: найти конфликты (одна комбинация на два действия)."""
    seen = {}
    conflicts = []
    merged = dict(HOTKEYS_DEFAULT)
    merged.update({str(k): str(v) for k, v in (user_hotkeys or {}).items()})
    for action, combo in merged.items():
        c = combo.strip()
        if c in seen:
            conflicts.append({"combo": c, "actions": [seen[c], action]})
        else:
            seen[c] = action
    return conflicts


TG_ALLOWED_UPDATES = ("message", "edited_message", "callback_query")


def tg_parse_update(update):
    """Telegram-бот: разобрать update (логика тестируется без сети)."""
    if not isinstance(update, dict):
        return {"error": "update должен быть объектом"}
    kind = next((k for k in TG_ALLOWED_UPDATES if k in update), None)
    if kind is None:
        return {"skip": True}
    if kind == "message":
        msg = update["message"]
        text = str(msg.get("text") or "")
        cmd = text.split()[0] if text.startswith("/") and text.split() else None
        return {"kind": kind, "chat_id": msg.get("chat", {}).get("id"), "text": text, "command": cmd}
    if kind == "edited_message":
        msg = update["edited_message"]
        return {"kind": kind, "chat_id": msg.get("chat", {}).get("id"),
                "text": str(msg.get("text") or ""), "command": None}
    q = update["callback_query"]
    return {"kind": kind, "chat_id": (q.get("message") or {}).get("chat", {}).get("id"),
            "data": str(q.get("data") or "")}


TG_COMMANDS = {
    "/status": lambda project: "Проект: %s. Агент на связи." % (project or "по умолчанию"),
    "/tasks": lambda project: "Задачи: см. приложение (список синхронизируется).",
    "/help": lambda project: "Команды: /status, /tasks, /help",
}


def tg_format_reply(parsed, project=None):
    """Telegram-бот: ответ на распознанную команду (таблица команд TG_COMMANDS)."""
    if not parsed or parsed.get("skip"):
        return None
    cmd = parsed.get("command")
    if cmd is None:
        return TG_COMMANDS["/help"](project)
    handler = TG_COMMANDS.get(cmd)
    if handler is None:
        return "Не знаю команду %s. /help — список." % cmd
    return handler(project)


MCP_PROTOCOL = "2024-11-05"


def _mcp_initialize(tools, params, result, err):
    return result({"protocolVersion": MCP_PROTOCOL, "capabilities": {"tools": {}},
                   "serverInfo": {"name": "pocketcode", "version": "0.11"}})


def _mcp_tools_list(tools, params, result, err):
    return result({"tools": [{"name": n, "description": (t or {}).get("description", "")}
                             for n, t in sorted((tools or {}).items())]})


def _mcp_tools_call(tools, params, result, err):
    name = str((params or {}).get("name") or "")
    if name not in (tools or {}):
        return err(-32602, "нет такого инструмента: %s" % name)
    args = (params or {}).get("arguments") or {}
    try:
        value = (tools[name]["call"])(**args) if isinstance(args, dict) else (tools[name]["call"])(args)
    except TypeError as e:
        return err(-32602, "плохие аргументы: %s" % e)
    return result({"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]})


def _mcp_ping(tools, params, result, err):
    return result({})


MCP_METHODS = {
    "initialize": _mcp_initialize,
    "tools/list": _mcp_tools_list,
    "tools/call": _mcp_tools_call,
    "ping": _mcp_ping,
}


def mcp_handle(tools, method, params=None, request_id=None):
    """MCP-сервер: JSON-RPC 2.0, методы — в таблице MCP_METHODS."""

    def result(res):
        return {"jsonrpc": "2.0", "id": request_id, "result": res}

    def err(code, message):
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    handler = MCP_METHODS.get(str(method or ""))
    if handler is None:
        return err(-32601, "метод не найден: %s" % method)
    return handler(tools, params, result, err)


MAGIC = [(b"\x89PNG\r\n\x1a\n", "image/png"), (b"\xff\xd8\xff", "image/jpeg"),
         (b"GIF8", "image/gif"), (b"%PDF", "application/pdf"), (b"PK\x03\x04", "application/zip"),
         (b"ID3", "audio/mpeg"), (b"\x1aE\xdf\xa3", "video/webm")]


def attachment_register(project, name, data):
    """Вложения чата (аудио/видео/картинки): сохранить с определением типа по магическим байтам."""
    if not isinstance(data, (bytes, bytearray)) or not data:
        return {"error": "пустое вложение"}
    data = bytes(data)
    if len(data) > 50 * 1024 * 1024:
        return {"error": "вложение больше 50 МБ"}
    mime = next((m for magic, m in MAGIC if data.startswith(magic)), "application/octet-stream")
    safe_name = re.sub(r"[^\w.\- ]", "_", str(name or "file"))[:120]
    d = _pc_json(project, "attachments")
    d.mkdir(parents=True, exist_ok=True)
    dst = d / ("%d_%s" % (int(time.time() * 1000), safe_name))
    dst.write_bytes(data)
    return {"ok": True, "file": dst.name, "mime": mime, "bytes": len(data)}


def attachment_list(project):
    d = _pc_json(project, "attachments")
    if not d.exists():
        return {"items": []}
    return {"items": [{"file": p.name, "size": p.stat().st_size} for p in sorted(d.iterdir())]}


def usage_incr(path, kind, n=1):
    """Счётчики использования фич: какие функции реально нужны."""
    k = str(kind or "")[:60]
    if not k:
        return {"error": "пустое имя фичи"}
    data = _pc_load(path, {})
    counts = data.get("counts", {})
    counts[k] = int(counts.get(k, 0)) + int(n or 1)
    data["counts"] = counts
    _pc_save(path, data)
    return {"ok": True, "count": counts[k]}


def usage_top(path, limit=10):
    counts = _pc_load(path, {}).get("counts", {})
    ranked = sorted(counts.items(), key=lambda kv: -kv[1])[:max(1, int(limit or 10))]
    return {"top": [{"feature": k, "count": v} for k, v in ranked]}


def reminder_add(path, text, due_ts):
    """Напоминания: текст + время срабатывания."""
    text = str(text or "").strip()
    if not text:
        return {"error": "пустой текст"}
    try:
        due = float(due_ts)
    except (TypeError, ValueError):
        return {"error": "due_ts — число (unix-время)"}
    items = _pc_load(path, {}).get("items", [])
    if len(items) >= REMINDER_MAX:
        return {"error": "слишком много напоминаний (максимум %d)" % REMINDER_MAX}
    item = {"id": "r%d" % int(time.time() * 1000), "text": text[:300], "due": due}
    items.append(item)
    _pc_save(path, {"items": items})
    return {"ok": True, "reminder": item}


def reminder_due(path, now=None):
    now = now or time.time()
    items = _pc_load(path, {}).get("items", [])
    due = [r for r in items if r["due"] <= now]
    left = [r for r in items if r["due"] > now]
    _pc_save(path, {"items": left})
    return {"due": due, "left": left}


def export_notes_md(project):
    """Экспорт закладок и заметок в Markdown-файл рядом с проектом."""
    def display(rel):
        p = Path(rel)
        try:
            return p.relative_to(Path(project).resolve()).as_posix()
        except ValueError:
            return p.as_posix()

    marks = _pc_load(_pc_json(project, "bookmarks.json"), [])
    notes = _pc_load(_pc_json(project, "line_notes.json"), {})
    lines = ["# Заметки проекта %s" % Path(project).name, "", "## Закладки", ""]
    for m in marks:
        lines.append("- `%s%s` — %s" % (display(m.get("rel")),
                                        (":" + str(m["line"])) if m.get("line") else "",
                                        m.get("note") or ""))
    lines += ["", "## Заметки на полях", ""]
    for rel, items in sorted(notes.items()):
        for it in items:
            lines.append("- `%s:%s` — %s" % (display(rel), it["line"], it["text"]))
    out = Path(project) / "notes-export.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {"ok": True, "file": str(out), "bookmarks": len(marks),
            "notes": sum(len(v) for v in notes.values())}


URL_RE = re.compile(r"https?://[^\s\)\]>\"']+")


def readme_link_check(project, max_checks=50):
    """Проверка внешних ссылок в README: HEAD-запрос, битые помечаем."""
    md = Path(project) / "README.md"
    if not md.is_file():
        return {"error": "README.md не найден"}
    urls = []
    seen = set()
    for line in md.read_text(encoding="utf-8", errors="replace").splitlines():
        for u in URL_RE.findall(line.replace("](", " ").replace(")", " ")):
            if u not in seen:
                seen.add(u)
                urls.append(u)
    import urllib.request
    results = []
    for u in urls[:max(1, int(max_checks or 50))]:
        try:
            req = urllib.request.Request(u, method="HEAD", headers={"User-Agent": "pocketcode-linkcheck"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                ok = resp.status < 400
        except Exception:
            ok = False
        results.append({"url": u, "ok": ok})
    return {"checked": len(results), "broken": [r["url"] for r in results if not r["ok"]],
            "results": results}


def reading_time(text, wpm=200):
    """Оценка времени чтения документа."""
    words = len(re.findall(r"\S+", str(text or "")))
    minutes = words / max(20, int(wpm or 200))
    return {"words": words, "minutes": round(minutes, 1),
            "label": ("%d мин" % round(minutes)) if minutes >= 1 else "< 1 мин"}


# ---------- batch 13. релизы, планирование, гигиена репозитория ----------

SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")

COMMIT_TYPES = ("feat", "fix", "docs", "style", "refactor", "test",
                "chore", "build", "ci", "perf", "revert")

RELEASE_LABELS = {"feat": "Новое", "fix": "Исправления", "perf": "Производительность",
                  "docs": "Документация", "revert": "Откаты", "other": "Прочее"}

LICENSE_MARKS = (
    ("MIT", "permission is hereby granted, free of charge"),
    ("Apache-2.0", "apache license"),
    ("GPL-3.0", "gnu general public license"),
    ("BSD-3-Clause", "redistribution and use in source and binary forms"),
    ("MPL-2.0", "mozilla public license"),
)


def version_bump(version, kind="patch"):
    """Semver-инкремент версии: major/minor/patch."""
    m = SEMVER_RE.match(str(version or "").strip())
    if not m:
        return None
    kind = str(kind or "patch")
    if kind not in ("major", "minor", "patch"):
        return None
    maj, mi, pa = (int(x) for x in m.groups())
    if kind == "major":
        maj, mi, pa = maj + 1, 0, 0
    elif kind == "minor":
        mi, pa = mi + 1, 0
    else:
        pa += 1
    return "%d.%d.%d" % (maj, mi, pa)


def semver_latest(versions):
    """Самая свежая версия из списка строк вида 1.2.3."""
    best = None
    for v in versions or []:
        m = SEMVER_RE.match(str(v).strip())
        if not m:
            return None
        key = tuple(int(x) for x in m.groups())
        if best is None or key > best[0]:
            best = (key, str(v).strip())
    return best[1] if best else None


def commit_msg_check(msg):
    """Проверка сообщения коммита: conventional commits, первая строка до 72."""
    head = str(msg or "").strip().splitlines()[0] if str(msg or "").strip() else ""
    if not head:
        return {"ok": False, "problems": ["пустое сообщение"]}
    problems = []
    if len(head) > 72:
        problems.append("первая строка длиннее 72 символов (%d)" % len(head))
    m = re.match(r"^(\w+)(\([^)]*\))?!?: (.+)$", head)
    if not m:
        problems.append("нет вида 'тип: тема' (feat/fix/docs/...)")
    else:
        if m.group(1) not in COMMIT_TYPES:
            problems.append("неизвестный тип: %s" % m.group(1))
        if m.group(3)[:1].isupper():
            problems.append("тема начинается с большой буквы")
        if m.group(3).endswith("."):
            problems.append("точка в конце темы")
    return {"ok": not problems, "problems": problems}


def branch_name_from(task):
    """Имя ветки из текста задачи: fix/... для багов, docs/..., иначе feat/..."""
    t = str(task or "").strip()
    if not t:
        return {"error": "пустая задача"}
    low = t.lower()
    if any(w in low for w in ("почини", "исправ", "баг", "регресс", "fix", "bug", "regress")):
        prefix = "fix"
    elif any(w in low for w in ("докум", "readme", "инструкц", "docs")):
        prefix = "docs"
    else:
        prefix = "feat"
    name = re.sub(r"[^\w.-]+", "-", low)[:40].strip("-") or "task"
    return {"ok": True, "branch": "%s/%s" % (prefix, name)}


def release_notes(commits):
    """Черновик заметок о релизе: коммиты 'тип: тема' группируются по типу."""
    groups = {}
    for c in commits or []:
        line = str(c).strip()
        m = re.match(r"^(\w+)(\([^)]*\))?: (.+)$", line)
        if m:
            groups.setdefault(m.group(1), []).append(m.group(3))
        else:
            groups.setdefault("other", []).append(line)
    out = [{"type": t, "label": RELEASE_LABELS.get(t, t), "items": items}
           for t, items in sorted(groups.items())]
    return {"groups": out}


def diff_stat(diff_text):
    """Короткая сводка unified-diff: файлы, добавлено/удалено, по файлам."""
    files = added = removed = fa = fr = 0
    cur = None
    per = []
    for line in str(diff_text or "").splitlines():
        if line.startswith(("---", "+++")):
            continue
        if line.startswith("diff --git"):
            if cur is not None:
                per.append({"file": cur, "added": fa, "removed": fr})
            cur = line.split(" b/", 1)[-1]
            files += 1
            fa = fr = 0
        elif line.startswith("+"):
            fa += 1
            added += 1
        elif line.startswith("-"):
            fr += 1
            removed += 1
    if cur is None:
        return {"error": "в diff нет файлов"}
    per.append({"file": cur, "added": fa, "removed": fr})
    return {"files": files, "added": added, "removed": removed, "per_file": per}


def license_detect(project):
    """Определение лицензии проекта по тексту файла LICENSE/COPYING."""
    for name in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING"):
        p = Path(project) / name
        if p.is_file():
            head = p.read_text(encoding="utf-8", errors="replace")[:2000].lower()
            for lic, mark in LICENSE_MARKS:
                if mark in head:
                    return {"license": lic, "file": name}
            return {"license": "unknown", "file": name}
    return {"error": "файл лицензии не найден"}


def backlog_aging(tasks, now=None, stale_days=14):
    """Возраст задач в бэклоге: что висит дольше stale_days — stale."""
    now = now or time.time()
    try:
        stale_days = float(stale_days)
    except (TypeError, ValueError):
        return {"error": "stale_days — число"}
    if stale_days <= 0:
        return {"error": "stale_days должен быть > 0"}
    out = []
    for t in tasks or []:
        if not isinstance(t, dict) or "created" not in t:
            return {"error": "каждая задача — словарь с полем created"}
        age = max(0.0, (float(now) - float(t["created"])) / 86400.0)
        out.append({"id": t.get("id"), "age_days": round(age, 1),
                    "stale": age > stale_days})
    return {"items": out, "stale": sum(1 for i in out if i["stale"])}


def velocity_burn(remaining, done_per_day, done=0):
    """Оценка завершения: при текущей скорости — дней и дата финиша."""
    try:
        remaining = float(remaining)
        done = float(done)
        speed = float(done_per_day)
    except (TypeError, ValueError):
        return {"error": "аргументы — числа"}
    if remaining < 0 or done < 0:
        return {"error": "отрицательные значения"}
    if speed <= 0:
        return {"error": "скорость должна быть > 0"}
    days = int(-(-remaining // speed))
    total = done + remaining
    return {"days_left": days, "eta_date": time.strftime(
        "%Y-%m-%d", time.localtime(time.time() + days * 86400)),
        "percent": round(100.0 * done / total, 1) if total else 100.0}


def backup_rotate(directory, keep=5):
    """Ротация бэкапов в папке: остаются keep самых свежих по времени изменения."""
    d = Path(directory)
    if not d.is_dir():
        return {"error": "папка не найдена"}
    try:
        keep = max(1, int(keep))
    except (TypeError, ValueError):
        return {"error": "keep — число"}
    files = sorted((p for p in d.iterdir() if p.is_file()),
                   key=lambda p: p.stat().st_mtime, reverse=True)
    dropped = []
    for p in files[keep:]:
        p.unlink()
        dropped.append(p.name)
    return {"ok": True, "kept": len(files) - len(dropped), "dropped": dropped}


# ================= Батч-14: hotspots, зависимости, .gitignore, секреты в файлах,
# длинные файлы, issue из краша, стендап-отчёт, дубликаты файлов =================

# ---------- 201. hotspots: файлы, которые чаще всего меняются ----------
def churn_hotspots(project, top=10):
    if not Path(project).is_dir():
        return {"error": "проект не найден"}
    rc, out, err = _git(project, "log", "--name-only", "--pretty=format:")
    if rc != 0:
        return {"error": (err or "нет истории git").strip()[:200]}
    counts = {}
    for l in out.splitlines():
        l = l.strip()
        if l:
            counts[l] = counts.get(l, 0) + 1
    try:
        top = int(top)
    except (TypeError, ValueError):
        return {"error": "top — число"}
    if top <= 0:
        return {"error": "top должен быть > 0"}
    items = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:top]
    return {"commits_files": sum(counts.values()),
            "hotspots": [{"file": f, "touches": n} for f, n in items]}


# ---------- 202. проверка requirements.txt: дубликаты и непиннутые ----------
def deps_check(project):
    req = Path(project) / "requirements.txt"
    if not req.is_file():
        return {"error": "requirements.txt не найден"}
    seen, dups, unpinned = {}, [], []
    for i, raw in enumerate(req.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line or line.startswith(("-r", "--", "git+", "http", "-e")):
            continue
        name = re.split(r"[\[\]=<>!~; ]", line, 1)[0].strip().lower().replace("_", "-")
        if not name:
            continue
        if name in seen:
            dups.append({"name": name, "first_line": seen[name], "line": i})
        else:
            seen[name] = i
        if not re.search(r"[=<>!~]", line):
            unpinned.append(name)
    return {"ok": not dups, "duplicates": dups,
            "unpinned": sorted(set(unpinned)), "total": len(seen)}


# ---------- 203. артефакты без покрытия .gitignore ----------
_GI_ARTIFACTS = (("__pycache__", "dir"), ("node_modules", "dir"), (".gradle", "dir"),
                 (".idea", "dir"), (".venv", "dir"), ("build", "dir"), ("dist", "dir"),
                 (".pyc", "file"), (".log", "file"), (".env", "file"))


def gitignore_check(project):
    root = Path(project)
    if not root.is_dir():
        return {"error": "проект не найден"}
    gi = root / ".gitignore"
    text = ""
    if gi.is_file():
        text = gi.read_text(encoding="utf-8", errors="replace")
    rules = [l.strip() for l in text.splitlines() if l.strip() and not l.strip().startswith("#")]
    seen = []
    for p in list(root.rglob("*"))[:4000]:
        if ".git" in p.parts or any(part in _SKIP_DIRS for part in p.parts[:-1]):
            continue
        for key, kind in _GI_ARTIFACTS:
            hit = ((kind == "dir" and p.is_dir() and p.name == key)
                   or (kind == "file" and p.is_file() and p.name.endswith(key)))
            if hit and key not in [s[0] for s in seen]:
                seen.append((key, kind))
    items = [{"name": k, "kind": v, "ignored": any(k in r for r in rules)}
             for k, v in sorted(seen)]
    return {"items": items, "missing": [i["name"] for i in items if not i["ignored"]]}


# ---------- 204. скан секретов по всем файлам проекта ----------
def secret_scan_project(project, max_files=200):
    root = Path(project)
    if not root.is_dir():
        return {"error": "проект не найден"}
    findings, scanned = [], 0
    for p in sorted(root.rglob("*")):
        if scanned >= int(max_files or 200):
            break
        if not p.is_file() or ".git" in p.parts or any(x in _SKIP_DIRS for x in p.parts):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "\0" in text[:2000]:
            continue
        scanned += 1
        for f in scan_secrets(text):
            findings.append({"file": str(p.relative_to(root)), "line": f["line"],
                             "kind": f["kind"]})
    return {"scanned": scanned, "findings": findings, "clean": not findings}


# ---------- 205. файлы длиннее max_lines ----------
def long_file_check(project, max_lines=400, top=20):
    root = Path(project)
    if not root.is_dir():
        return {"error": "проект не найден"}
    try:
        max_lines = int(max_lines)
        top = max(1, int(top))
    except (TypeError, ValueError):
        return {"error": "max_lines и top — числа"}
    if max_lines <= 0:
        return {"error": "max_lines должен быть > 0"}
    items = []
    for p in list(root.rglob("*"))[:4000]:
        if not p.is_file() or ".git" in p.parts or any(x in _SKIP_DIRS for x in p.parts):
            continue
        try:
            n = sum(1 for _ in p.open(encoding="utf-8", errors="replace"))
        except OSError:
            continue
        if n > max_lines:
            items.append({"file": str(p.relative_to(root)), "lines": n})
    items.sort(key=lambda i: (-i["lines"], i["file"]))
    return {"max_lines": max_lines, "over": len(items), "items": items[:top]}


# ---------- 206. черновик issue из краша ----------
def issue_from_crash(title, log, env=None, max_log_lines=50):
    title = str(title or "").strip()
    if not title:
        return {"error": "заголовок пуст"}
    try:
        max_log_lines = int(max_log_lines)
    except (TypeError, ValueError):
        return {"error": "max_log_lines — число"}
    if max_log_lines <= 0:
        return {"error": "max_log_lines должен быть > 0"}
    tail = [l for l in str(log or "").splitlines() if l.strip()][-max_log_lines:]
    body = ["## Что случилось", title, "", "## Лог", "```", *tail, "```"]
    if isinstance(env, dict) and env:
        body += ["", "## Окружение"] + ["- %s: %s" % (k, v) for k, v in sorted(env.items())]
    text = "\n".join(body)
    label = "bug" if re.search(r"Traceback|Exception|FATAL|CRASH", text) else ""
    return {"title": title, "body": text, "label": label}


# ---------- 207. стендап-отчёт: что поменялось за N дней ----------
def standup_report(project, days=1):
    if not Path(project).is_dir():
        return {"error": "проект не найден"}
    try:
        days = int(days)
    except (TypeError, ValueError):
        return {"error": "days — число"}
    if days <= 0:
        return {"error": "days должен быть > 0"}
    rc, out, err = _git(project, "log", "--pretty=%h|%s|%an|%ad", "--date=short",
                        "--since=%d day ago" % days)
    if rc != 0:
        return {"error": (err or "нет истории git").strip()[:200]}
    rows = [l for l in out.splitlines() if l.strip()]
    commits = []
    for l in rows:
        h, subj, author, date = (l.split("|", 3) + ["", "", ""])[:4]
        commits.append({"hash": h, "subject": subj, "author": author, "date": date})
    authors = sorted({c["author"] for c in commits if c["author"]})
    return {"days": days, "count": len(commits), "authors": authors, "commits": commits}


# ---------- 208. дубликаты файлов по содержимому ----------
def dup_detect(project, top=10, max_size=5 * 1024 * 1024):
    root = Path(project)
    if not root.is_dir():
        return {"error": "проект не найден"}
    try:
        top = max(1, int(top))
        max_size = max(1, int(max_size))
    except (TypeError, ValueError):
        return {"error": "top и max_size — числа"}
    by_hash = {}
    for p in list(root.rglob("*"))[:4000]:
        if not p.is_file() or ".git" in p.parts or any(x in _SKIP_DIRS for x in p.parts):
            continue
        try:
            if p.stat().st_size > max_size:
                continue
            h = hashlib.sha256(p.read_bytes()).hexdigest()
        except OSError:
            continue
        by_hash.setdefault((p.stat().st_size, h), []).append(str(p.relative_to(root)))
    dups = [{"hash": k[1][:12], "size": k[0], "files": v}
            for k, v in sorted(by_hash.items()) if len(v) > 1]
    wasted = sum(d["size"] * (len(d["files"]) - 1) for d in dups)
    return {"groups": len(dups), "wasted_bytes": wasted, "duplicates": dups[:top]}
