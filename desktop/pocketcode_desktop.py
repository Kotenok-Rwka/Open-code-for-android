#!/usr/bin/env python3
"""PocketCode Desktop — лёгкий компаньон для Linux и Windows.

Не порт Compose-интерфейса, а рабочий мост: чат с теми же OpenAI-совместимыми
провайдерами, проекты, а также импорт/экспорт задач с телефона.

Запуск: python3 pocketcode_desktop.py  (браузер откроется сам: http://127.0.0.1:8765)
Зависимостей нет: только стандартная библиотека Python 3.
"""

import argparse
import json
import os
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

HOME = Path.home() / ".pocketcode-desktop"
HOME.mkdir(exist_ok=True)
PROFILES = HOME / "profiles.json"
TASKS = HOME / "tasks.json"
CHAT = HOME / "chat.jsonl"
SYNC = HOME / "sync"
SYNC.mkdir(exist_ok=True)
SYNC_EVENTS = SYNC / "events.jsonl"
SYNC_STATE = SYNC / "state.json"
SYNC_FILES = SYNC / "files"

PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PocketCode Desktop · Aurora</title>
<style>
:root{--bg:#0b1620;--bg2:#0f2027;--panel:#122532;--panel2:#17303f;--line:rgba(147,220,255,.13);--line2:rgba(147,220,255,.24);--txt:#eaf6ff;--dim:#8fb3c7;--accent:#4fd1c5;--accent2:#6aa8ff;--glow:rgba(79,209,197,.16);--radius:16px}
*{box-sizing:border-box}
html,body{height:100%}
body{margin:0;color:var(--txt);font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;background:
 radial-gradient(1000px 600px at 8% -10%,rgba(79,209,197,.16),transparent 60%),
 radial-gradient(900px 700px at 105% 10%,rgba(106,168,255,.15),transparent 55%),
 linear-gradient(160deg,var(--bg),var(--bg2) 55%,#0a141d);background-attachment:fixed}
#app{display:grid;grid-template-columns:236px 1fr;grid-template-rows:auto 1fr;gap:18px;max-width:1500px;min-height:100vh;margin:auto;padding:20px}
header{grid-column:1/-1;display:flex;align-items:center;gap:14px;padding:12px 18px;border:1px solid var(--line);border-radius:var(--radius);background:linear-gradient(90deg,rgba(18,37,50,.92),rgba(16,31,42,.7));backdrop-filter:blur(14px);box-shadow:0 12px 40px rgba(0,0,0,.25)}
.logo{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;font-weight:800;font-size:17px;color:#06222a;background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 0 24px var(--glow)}
header h1{font-size:17px;margin:0;letter-spacing:.2px}
header .sub{color:var(--dim);font-size:12px}
header .status{margin-left:auto;display:flex;align-items:center;gap:8px;color:var(--dim);font-size:12px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 12px var(--accent)}
nav{display:flex;flex-direction:column;gap:6px;position:sticky;top:20px;align-self:start}
nav button{display:flex;align-items:center;gap:10px;text-align:left;padding:11px 14px;border-radius:12px;border:1px solid transparent;background:transparent;color:var(--dim);cursor:pointer;font:inherit;transition:.18s}
nav button:hover{background:rgba(79,209,197,.07);color:var(--txt)}
nav button.on{background:linear-gradient(90deg,rgba(79,209,197,.16),rgba(106,168,255,.08));border-color:var(--line2);color:var(--txt);box-shadow:inset 3px 0 0 var(--accent)}
main{border:1px solid var(--line);border-radius:var(--radius);background:linear-gradient(180deg,rgba(18,37,50,.94),rgba(13,27,37,.92));backdrop-filter:blur(10px);padding:20px;min-width:0;box-shadow:0 20px 60px rgba(0,0,0,.28)}
section[hidden]{display:none!important}
h2{font-size:19px;margin:0 0 14px}h3{font-size:15px;margin:22px 0 10px;color:var(--accent)}
p,small,.hint{color:var(--dim)}
.hint{font-size:12px;margin:6px 0 14px}
input,select,textarea{width:100%;padding:10px 12px;border-radius:10px;border:1px solid var(--line2);background:rgba(8,20,28,.75);color:var(--txt);font:inherit;outline:none;transition:.15s}
input:focus,textarea:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--glow)}
textarea{min-height:110px;resize:vertical}
button.act,button.ghost{border-radius:10px;padding:10px 16px;font:inherit;cursor:pointer;transition:.15s}
button.act{border:1px solid transparent;color:#06222a;font-weight:700;background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 6px 22px var(--glow)}
button.act:hover{filter:brightness(1.1);transform:translateY(-1px)}
button.ghost{background:rgba(79,209,197,.07);border:1px solid var(--line2);color:var(--txt)}
button.ghost:hover{background:rgba(79,209,197,.14)}
.row{display:flex;gap:10px;margin:10px 0;align-items:center}.row>*{flex:1}.row button{flex:0 0 auto}
.card{border:1px solid var(--line);border-radius:13px;background:linear-gradient(160deg,rgba(23,48,63,.8),rgba(16,34,45,.55));padding:12px 14px;margin:9px 0}
.msg{white-space:pre-wrap;overflow-wrap:anywhere}
.msg b{color:var(--accent)}
.msg.user{border-left:3px solid var(--accent2)}.msg.assistant{border-left:3px solid var(--accent)}
#log{max-height:56vh;overflow:auto;padding-right:6px;margin-bottom:14px}
#log::-webkit-scrollbar{width:8px}#log::-webkit-scrollbar-thumb{background:var(--line2);border-radius:8px}
pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;border-radius:10px;background:rgba(6,16,23,.75);border:1px solid var(--line);color:#c9e6f2;max-height:45vh;overflow:auto}
code{color:var(--accent)}
.pill{display:inline-block;padding:3px 9px;border-radius:99px;border:1px solid var(--line2);font-size:11px;color:var(--dim);margin-left:6px}
@media(max-width:850px){#app{grid-template-columns:1fr;padding:12px;gap:12px}nav{flex-direction:row;overflow:auto;position:static}nav button{white-space:nowrap}main{padding:14px}}
</style></head><body><div id="app">
<header><div class="logo">PC</div><div><h1>PocketCode Desktop <span class="pill">Aurora</span></h1><div class="sub">Мост между телефоном, моделями и проектами</div></div><div class="status"><span class="dot"></span><span id="clock">локально</span></div></header>
<nav id="nav">
<button class="on" data-tab="chat" onclick="tab('chat')">◉ Чат</button>
<button data-tab="profiles" onclick="tab('profiles')">◇ Провайдеры</button>
<button data-tab="sync" onclick="tab('sync')">⇄ Обмен с телефоном</button>
<button data-tab="agents" onclick="tab('agents')">✦ Агенты и роли</button>
<button data-tab="autosync" onclick="tab('autosync')">↻ Автосинхронизация</button>
</nav>
<main>
<section id="chat"><h2>Чат</h2><p class="hint">Полные ответы без обрезки. Сообщения остаются на этом ПК.</p><div id="log"></div><textarea id="q" placeholder="Задача или сообщение"></textarea><div class="row"><button class="act" onclick="send()">Отправить</button><button class="ghost" onclick="clearChat()">Очистить чат</button></div></section>
<section id="profiles" hidden><h2>Провайдеры</h2><p class="hint">Локальная модель: base_url вида http://localhost:1234/v1, ключ — любой.</p>
<div class="row"><input id="pname" placeholder="Имя"><input id="purl" placeholder="http://localhost:1234/v1"><input id="pmodel" placeholder="local-model"></div>
<div class="row"><input id="pkey" placeholder="Ключ (для локального — любой)"><button class="act" onclick="saveProfile()">Сохранить</button></div><div id="plist"></div></section>
<section id="sync" hidden><h2>Обмен с телефоном</h2><p>Экспортируй задачи с телефона в JSON и вставь сюда, чтобы продолжить на ПК — и наоборот.</p>
<textarea id="imp" placeholder="JSON задач"></textarea><div class="row"><button class="act" onclick="importTasks()">Импортировать</button><button class="ghost" onclick="exportTasks()">Экспортировать</button></div><pre id="tasks"></pre></section>
<section id="agents" hidden><h2>Агенты Chat</h2><div id="agentsList"></div><div class="row"><input id="aname" placeholder="Имя"><input id="agoal" placeholder="Цель"></div><div class="row"><button class="act" onclick="saveAgent()">Создать агента</button></div>
<h3>Роли моделей</h3><div class="row"><input id="aplan" placeholder="Модель планирования"><input id="aexec" placeholder="Модель выполнения"></div><div class="row"><button class="ghost" onclick="saveRoles()">Сохранить роли</button></div>
<h3>Локальные виджеты</h3><div id="widgets"></div></section>
<section id="autosync" hidden><h2>Автосинхронизация с телефоном</h2>
<p>Телефон отправляет события и снимки на адрес <code>http://&lt;IP ПК&gt;:8765/api/sync/push</code>. Запусти компаньон с <code>--host 0.0.0.0</code>, чтобы телефон в той же сети мог подключиться.</p>
<div class="row"><label>Интервал (мин) <input id="syncInterval" type="number" min="1" value="15"></label><button class="ghost" onclick="saveSyncConfig()">Сохранить</button></div>
<div class="row"><button class="ghost" onclick="loadSyncStatus()">Обновить статус</button><button class="ghost" onclick="syncNow()">Отправить сейчас</button><button class="ghost" onclick="showToken()">Показать токен</button></div>
<pre id="tokenBox"></pre><pre id="syncStatus"></pre></section>
</main></div>
<script>
"use strict";
let profiles={};
const byId=id=>document.getElementById(id);
function tab(t){for(const x of ['chat','profiles','sync','agents','autosync'])byId(x).hidden=x!==t;document.querySelectorAll('#nav button').forEach(b=>b.classList.toggle('on',b.dataset.tab===t));if(t==='chat')load();if(t==='profiles')loadProfiles();if(t==='sync')loadTasks();if(t==='agents')loadAgents();if(t==='autosync')loadSyncStatus()}
async function get(u){const r=await fetch(u);if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}
async function post(u,b){const r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}
function el(tag,cls,text){const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e}
function card(){return el('div','card')}
async function load(){try{const m=await get('/api/messages');const log=byId('log');log.replaceChildren();m.forEach(x=>{const c=card();c.classList.add('msg',x.role==='user'?'user':'assistant');c.append(el('b','',x.role+': '),document.createTextNode(x.text));log.append(c)});log.scrollTop=log.scrollHeight}catch(e){alert('Не удалось загрузить чат: '+e.message)}}
async function send(){const q=byId('q');if(!q.value.trim())return;const text=q.value;q.value='';try{const r=await post('/api/chat',{text});await load();if(r.error)alert(r.error)}catch(e){q.value=text;alert('Ошибка отправки: '+e.message)}}
async function clearChat(){await post('/api/clear',{});await load()}
async function loadProfiles(){profiles=await get('/api/profiles');const box=byId('plist');box.replaceChildren();Object.entries(profiles).filter(([k,v])=>k!=='_active'&&v&&typeof v==='object').forEach(([k,v])=>{const c=card();c.append(el('b','',k),document.createTextNode(' · '+v.base_url+' · '+v.model));const b=el('button','ghost',k===profiles._active?'Выбран':'Выбрать');b.onclick=()=>useProfile(k);c.append(b);box.append(c)})}
async function saveProfile(){const n=byId('pname').value.trim();if(!n){alert('Введи имя профиля');return}await post('/api/profiles',{name:n,base_url:byId('purl').value.trim(),model:byId('pmodel').value.trim(),api_key:byId('pkey').value.trim()});loadProfiles()}
async function useProfile(n){await post('/api/use',{name:n});alert('Выбран профиль '+n)}
async function loadTasks(){byId('tasks').textContent=JSON.stringify(await get('/api/tasks'),null,2)}
async function importTasks(){let v;try{v=JSON.parse(byId('imp').value)}catch(e){alert('Это не JSON: '+e.message);return}const r=await post('/api/tasks/import',v);if(r.error)alert(r.error);loadTasks()}
async function exportTasks(){byId('imp').value=JSON.stringify(await get('/api/tasks'))}
async function loadAgents(){const a=await get('/api/agents');const list=byId('agentsList');list.replaceChildren();a.forEach(x=>{const c=card();c.append(el('b','',x.name),document.createTextNode(' '+x.goal));list.append(c)});const r=await get('/api/roles');byId('aplan').value=r.planning||'';byId('aexec').value=r.execution||'';const w=await get('/api/widgets');const wb=byId('widgets');wb.replaceChildren();w.forEach(x=>{const c=card();const a2=el('a','',x.name);a2.href=x.url;a2.target='_blank';a2.rel='noopener noreferrer';c.append(a2,el('small','',' '+x.note));wb.append(c)})}
async function saveAgent(){await post('/api/agents',{name:byId('aname').value,goal:byId('agoal').value});loadAgents()}
async function saveRoles(){await post('/api/roles',{planning:byId('aplan').value,execution:byId('aexec').value});alert('Роли сохранены')}
async function loadSyncStatus(){byId('syncStatus').textContent=JSON.stringify(await get('/api/sync/status'),null,2)}
async function saveSyncConfig(){const n=byId('syncInterval').value;await post('/api/sync/config',{interval_min:parseInt(n||'15')});loadSyncStatus()}
async function syncNow(){await post('/api/sync/push',{v:2,type:'status',seq:0,device:'desktop',project:'',ts:Date.now(),kind:'status',data:{text:'sync requested'}});loadSyncStatus()}
async function showToken(){const r=await get('/api/sync/token');byId('tokenBox').textContent=r.token?('Токен для телефона (X-PocketCode-Token): '+r.token):('Недоступно: '+(r.error||'только с этого ПК'))}
byId('clock').textContent=new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
load().catch(()=>{});loadSyncStatus().catch(()=>{});
</script></html>
"""


# Один сервер обслуживает несколько потоков: записи в файлы идут под замком,
# иначе одновременные запросы теряют сообщения и портят JSON.
LOCK = threading.RLock()


def read_lines(path):
    """Непустые строки файла (UTF-8), без пустых хвостов."""
    try:
        return [x for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]
    except (OSError, UnicodeDecodeError):
        return []


def read_jsonl(path):
    """Строки JSONL; битая или обрезанная строка пропускается, а не роняет ответ."""
    out = []
    for line in read_lines(path):
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if isinstance(value, dict):
            out.append(value)
    return out


def load_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeDecodeError):
        return default


def save_json(path, value):
    """Атомарная запись: обрыв процесса не оставит половину файла настроек."""
    data = json.dumps(value, ensure_ascii=False, indent=2)
    with LOCK:
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(data, encoding="utf-8")
        os.replace(tmp, path)


def call_llm(profile, messages):
    base = str(profile.get("base_url") or "").rstrip("/")
    model = str(profile.get("model") or "")
    if not base or not model:
        raise ValueError("в профиле не заданы адрес сервера или модель")
    url = base + "/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": [{"role": m["role"], "content": m["text"]} for m in messages],
        "temperature": 0.4,
    }).encode()
    req = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + (profile.get("api_key") or "lm-studio"),
    })
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode())
    return data["choices"][0]["message"]["content"]


TOKEN_FILE = SYNC / "token.txt"


def access_token():
    """Токен для удалённого доступа: создаётся автоматически при первом запуске."""
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(__import__("secrets").token_urlsafe(24), encoding="utf-8")
        try:
            os.chmod(TOKEN_FILE, 0o600)  # токен не должен быть читаем другим пользователям
        except OSError:
            pass
    return TOKEN_FILE.read_text(encoding="utf-8").strip()



def decrypt_payload(value, password=""):
    """Расшифровать PCENC1:<iv>:<data> (AES-256-GCM, ключ SHA-256(password) x2000)."""
    if not isinstance(value, str) or not value.startswith("PCENC1:"):
        return value, False
    if not password:
        return value, True
    import base64, hashlib
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        _, iv, data = value.split(":", 2)
        key = hashlib.sha256((password * 2000).encode()).digest()
        plain = AESGCM(key).decrypt(base64.b64decode(iv), base64.b64decode(data), None)
        return json.loads(plain.decode()), True
    except Exception:
        return value, True

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def token_ok(self):
        expected = access_token()
        # Без токена разрешаем только локальные подключения (для обратной совместимости).
        supplied = self.headers.get("X-PocketCode-Token", "")
        if supplied == expected:
            return True
        return self.client_address[0] in ("127.0.0.1", "::1")

    def send(self, value, code=200):
        raw = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        if len(raw) > 32 * 1024 * 1024:
            raise ValueError("слишком большое тело запроса")
        return json.loads(raw.decode("utf-8", "replace") or "{}")

    def local_or_token(self):
        """Всё, кроме синхронизации, доступно только с этого ПК (или с токеном)."""
        if self.token_ok():
            return True
        self.send({"error": "token required"}, 401)
        return False

    def do_GET(self):
        if not self.local_or_token():
            return
        if self.path == "/" or self.path.startswith("/index"):
            raw = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        elif self.path == "/api/messages":
            self.send(read_jsonl(CHAT))
        elif self.path == "/api/profiles":
            self.send(load_json(PROFILES, {}))
        elif self.path == "/api/tasks":
            self.send(load_json(TASKS, []))
        elif self.path == "/api/agents":
            self.send(load_json(HOME / "agents.json", []))
        elif self.path == "/api/roles":
            self.send(load_json(HOME / "roles.json", {}))
        elif self.path == "/api/widgets":
            self.send(load_json(HOME / "widgets.json", []))
        elif self.path == "/api/sync/token":
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                self.send({"error": "local only"}, 403); return
            self.send({"token": access_token()})
        elif self.path.startswith("/api/sync/status"):
            if not self.token_ok():
                self.send({"error": "token required"}, 401); return
            state = load_json(SYNC_STATE, {"last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
            self.send({**state, "pending": len(read_lines(SYNC_EVENTS)) if SYNC_EVENTS.exists() else 0})
        elif self.path.startswith("/api/sync/pull"):
            if not self.token_ok():
                self.send({"error": "token required"}, 401); return
            from urllib.parse import parse_qs, urlparse
            q = parse_qs(urlparse(self.path).query)
            try:
                since = int(q.get("since", ["0"])[0])
            except (TypeError, ValueError):
                since = 0
            events = read_jsonl(SYNC_EVENTS)
            self.send([x for x in events if x.get("seq", 0) > since][-200:])
        elif self.path == "/api/messages/full":
            self.send({"version": 1, "items": read_jsonl(CHAT), "project": "desktop"})
        elif self.path == "/api/sync/pack":
            msgs = read_jsonl(CHAT)
            self.send({"version": 1, "items": msgs, "project": load_json(HOME / "roles.json", {}).get("project", "")})
        else:
            self.send({"error": "not found"}, 404)

    def do_POST(self):
        if not self.local_or_token():
            return
        try:
            b = self.body()
            if self.path == "/api/chat":
                text = (b.get("text") or "").strip() if isinstance(b, dict) else ""
                if not text:
                    self.send({"error": "Пустое сообщение"}, 400)
                    return
                profiles = load_json(PROFILES, {})
                profile = profiles.get(profiles.get("_active", ""))
                if not isinstance(profile, dict):
                    self.send({"error": "Сначала сохрани и выбери провайдера"}, 400)
                    return
                history = read_jsonl(CHAT)
                history.append({"role": "user", "text": text})
                try:
                    answer = call_llm(profile, history[-20:])
                except Exception as e:
                    # Причину показываем в чате, а не молчаливой ошибкой сервера.
                    self.send({"error": "Модель не ответила: " + str(e)}, 502)
                    return
                history.append({"role": "assistant", "text": answer})
                with LOCK:
                    CHAT.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in history[-200:]), encoding="utf-8")
                self.send({"ok": True, "text": answer})
            elif self.path == "/api/clear":
                with LOCK:
                    CHAT.write_text("", encoding="utf-8")
                self.send({"ok": True})
            elif self.path == "/api/profiles":
                name = (b.get("name") or "").strip() if isinstance(b, dict) else ""
                base_url = (b.get("base_url") or "").strip() if isinstance(b, dict) else ""
                model = (b.get("model") or "").strip() if isinstance(b, dict) else ""
                if not name or not base_url or not model:
                    self.send({"error": "Нужны имя, адрес сервера и модель"}, 400)
                    return
                if not base_url.startswith(("http://", "https://")):
                    self.send({"error": "Адрес должен начинаться с http:// или https://"}, 400)
                    return
                profiles = load_json(PROFILES, {})
                profiles[name] = {"base_url": base_url, "model": model, "api_key": b.get("api_key", "")}
                profiles.setdefault("_active", name)
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif self.path == "/api/use":
                name = b.get("name") if isinstance(b, dict) else None
                profiles = load_json(PROFILES, {})
                if not isinstance(profiles.get(name), dict):
                    self.send({"error": "Нет такого профиля"}, 404)
                    return
                profiles["_active"] = name
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif self.path == "/api/agents":
                agents = load_json(HOME / "agents.json", [])
                agents.append({"name": b.get("name", ""), "goal": b.get("goal", "")})
                save_json(HOME / "agents.json", agents)
                self.send({"ok": True})
            elif self.path == "/api/roles":
                save_json(HOME / "roles.json", b)
                self.send({"ok": True})
            elif self.path == "/api/sync/import":
                items = b.get("items", [])
                with CHAT.open("a", encoding="utf-8") as f:
                    for m in items:
                        f.write(json.dumps({"role": m.get("role", "user"), "text": m.get("text", "")}, ensure_ascii=False) + "\n")
                self.send({"ok": True, "imported": len(items)})
            elif self.path == "/api/sync/config":
                save_json(SYNC / "config.json", b)
                self.send({"ok": True})
            elif self.path == "/api/sync/push":
                if not self.token_ok():
                    self.send({"error": "token required"}, 401); return
                cfg = load_json(SYNC / "config.json", {})
                if isinstance(b, str):
                    b, encrypted = decrypt_payload(b, cfg.get("password", ""))
                    if not isinstance(b, dict):
                        self.send({"error": "bad encrypted payload"}, 400); return
                if not isinstance(b, dict):
                    self.send({"error": "bad payload"}, 400); return
                try:
                    seq = int(b.get("seq", 0) or 0)
                except (TypeError, ValueError):
                    self.send({"error": "bad seq"}, 400); return
                typ = b.get("type", "event")
                with LOCK:
                    with SYNC_EVENTS.open("a", encoding="utf-8") as f:
                        f.write(json.dumps(b, ensure_ascii=False) + "\n")
                    state = load_json(SYNC_STATE, {"last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
                    state["last_seq"] = max(state.get("last_seq", 0), seq)
                    state["last_ts"] = b.get("ts", 0)
                    state["events"] = state.get("events", 0) + 1
                    if typ == "full": state["fulls"] = state.get("fulls", 0) + 1
                    save_json(SYNC_STATE, state)
                if typ == "full":
                    files = b.get("files", [])
                    for fp in files if isinstance(files, list) else []:
                        if not isinstance(fp, dict):
                            continue
                        root = SYNC_FILES.resolve()
                        path = (root / str(fp.get("path", "")).lstrip("/\\").replace("..", "_")).resolve()
                        if root in path.parents:
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_text(str(fp.get("text", "")), encoding="utf-8")
                self.send({"ok": True, "seq": seq})
            elif self.path == "/api/tasks/import":
                incoming = b if isinstance(b, list) else [b]
                tasks = load_json(TASKS, [])
                save_json(TASKS, tasks + incoming)
                self.send({"ok": True})
            else:
                self.send({"error": "not found"}, 404)
        except Exception as e:
            self.send({"error": str(e)}, 500)


def main():
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
    url = f"http://127.0.0.1:{a.port}/"
    print(f"PocketCode Desktop: http://{a.host}:{a.port}/  (Ctrl+C — выход)")
    if not a.no_browser:
        import threading, webbrowser
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
