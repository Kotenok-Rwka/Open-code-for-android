#!/usr/bin/env python3
"""PocketCode Desktop — лёгкий компаньон для Linux.

Не порт Compose-интерфейса, а рабочий мост: чат с теми же OpenAI-совместимыми
провайдерами, проекты, а также импорт/экспорт задач с телефона.

Запуск: python3 pocketcode_desktop.py  (открой http://127.0.0.1:8765)
Зависимостей нет: только стандартная библиотека Python 3.
"""

import argparse
import json
import os
import sys
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

PAGE = """<!doctype html><html lang="ru"><meta charset="utf-8"><title>PocketCode Desktop</title>
<style>
 body{background:#111318;color:#e8eaf0;font:15px system-ui;margin:0;padding:18px;max-width:900px;margin:auto}
 h1{font-size:19px} textarea,input,select,button{font:14px system-ui;background:#1d2027;color:#e8eaf0;border:1px solid #343945;border-radius:8px;padding:9px}
 textarea{width:100%;min-height:120px;box-sizing:border-box} button{background:#395bd6;border:0;cursor:pointer;padding:10px 16px}
 .row{display:flex;gap:8px;margin:8px 0} .row>*{flex:1} .msg{padding:10px;border-radius:9px;margin:6px 0;background:#1b1e24;white-space:pre-wrap}
 small{color:#9aa3b2} nav button{background:#262b36}
</style><h1>PocketCode Desktop · Linux</h1>
<nav class="row"><button onclick="tab('chat')">Чат</button><button onclick="tab('profiles')">Провайдеры</button><button onclick="tab('sync')">Обмен с телефоном</button><button onclick="tab('agents')">Агенты и роли</button><button onclick="tab('autosync')">Автосинхронизация</button></nav>
<div id="chat"><div id="log"></div><textarea id="q" placeholder="Задача или сообщение"></textarea><div class="row"><button onclick="send()">Отправить</button><button onclick="clearChat()">Очистить чат</button></div></div>
<div id="profiles" hidden><div class="row"><input id="pname" placeholder="Имя"><input id="purl" placeholder="http://localhost:1234/v1"><input id="pmodel" placeholder="local-model"></div>
<div class="row"><input id="pkey" placeholder="Ключ (для локального — любой)"><button onclick="saveProfile()">Сохранить</button></div><div id="plist"></div></div>
<div id="sync" hidden><p>Экспортируй задачи с телефона в JSON и вставь сюда, чтобы продолжить на ПК — и наоборот.</p>
<textarea id="imp" placeholder="JSON задач"></textarea><div class="row"><button onclick="importTasks()">Импортировать</button><button onclick="exportTasks()">Экспортировать</button></div><pre id="tasks"></pre></div>
<div id="agents" hidden><h2>Агенты Chat</h2><div id="agentsList"></div><div class="row"><input id="aname" placeholder="Имя"><input id="agoal" placeholder="Цель"></div><button onclick="saveAgent()">Создать агента</button>
<h2>Роли моделей</h2><div class="row"><input id="aplan" placeholder="Модель планирования"><input id="aexec" placeholder="Модель выполнения"></div><button onclick="saveRoles()">Сохранить роли</button>
<h2>Локальные виджеты</h2><div id="widgets"></div></div>
<div id="autosync" hidden><h2>Автосинхронизация с телефоном</h2>
<p>Телефон отправляет события и снимки на этот адрес: <code>http://&lt;IP ПК&gt;:8765/api/sync/push</code>. Запустите компаньон с <code>--host 0.0.0.0</code>, чтобы телефон в той же сети мог подключиться.</p>
<div class="row"><label>Интервал (мин) <input id="syncInterval" type="number" min="1" value="15"></label><button onclick="saveSyncConfig()">Сохранить</button></div>
<div class="row"><button onclick="loadSyncStatus()">Обновить статус</button><button onclick="syncNow()">Отправить сейчас</button></div>
<pre id="syncStatus"></pre></div>
<script>
let profiles={};
function tab(t){for(const x of ['chat','profiles','sync'])document.getElementById(x).hidden=x!==t;if(t==='profiles')loadProfiles();if(t==='sync')loadTasks();if(t==='agents')loadAgents()}
async function get(u){return (await fetch(u)).json()}
async function post(u,b){return (await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)})).json()}
async function load(){const m=await get('/api/messages');document.getElementById('log').innerHTML=m.map(x=>'<div class="msg"><b>'+x.role+'</b>: '+esc(x.text)+'</div>').join('')}
function esc(s){return s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
async function send(){const q=document.getElementById('q');if(!q.value.trim())return;const r=await post('/api/chat',{text:q.value});q.value='';await load();if(r.error)alert(r.error)}
async function clearChat(){await post('/api/clear',{});await load()}
async function loadProfiles(){profiles=await get('/api/profiles');document.getElementById('plist').innerHTML=Object.entries(profiles).map(([k,v])=>'<div class="msg">'+esc(k)+' · '+esc(v.base_url)+' · '+esc(v.model)+' <button onclick="useProfile(\\''+k+'\\')">Выбрать</button></div>').join('')}
async function saveProfile(){const n=document.getElementById('pname').value.trim();if(!n)return;await post('/api/profiles',{name:n,base_url:document.getElementById('purl').value.trim(),model:document.getElementById('pmodel').value.trim(),api_key:document.getElementById('pkey').value.trim()});loadProfiles()}
async function useProfile(n){await post('/api/use',{name:n});alert('Выбран профиль '+n)}
async function loadTasks(){document.getElementById('tasks').textContent=JSON.stringify(await get('/api/tasks'),null,2)}
async function importTasks(){await post('/api/tasks/import',JSON.parse(document.getElementById('imp').value));loadTasks()}
async function exportTasks(){document.getElementById('imp').value=JSON.stringify(await get('/api/tasks'))}
async function loadAgents(){const a=await get('/api/agents');document.getElementById('agentsList').innerHTML=a.map(x=>'<div class="msg"><b>'+esc(x.name)+'</b> '+esc(x.goal)+'</div>').join('');const r=await get('/api/roles');document.getElementById('aplan').value=r.planning||'';document.getElementById('aexec').value=r.execution||'';const w=await get('/api/widgets');document.getElementById('widgets').innerHTML=w.map(x=>'<div class="msg"><a href="'+esc(x.url)+'" target="_blank">'+esc(x.name)+'</a> <small>'+esc(x.note)+'</small></div>').join('')}
async function saveAgent(){await post('/api/agents',{name:document.getElementById('aname').value,goal:document.getElementById('agoal').value});loadAgents()}
async function saveRoles(){await post('/api/roles',{planning:document.getElementById('aplan').value,execution:document.getElementById('aexec').value});alert('Роли сохранены')}
async function syncStatus(){return await get('/api/sync/status')}
async function loadSyncStatus(){const x=await syncStatus();document.getElementById('syncStatus').textContent=JSON.stringify(x,null,2)}
async function saveSyncConfig(){const n=document.getElementById('syncInterval').value;await post('/api/sync/config',{interval_min:parseInt(n||'15')});loadSyncStatus()}
async function syncNow(){await post('/api/sync/push',{v:2,type:'status',seq:0,device:'desktop',project:'',ts:Date.now(),kind:'status',data:{text:'sync requested'}});loadSyncStatus()}
load();loadSyncStatus();
</script></html>"""


def load_json(path, default):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))


def call_llm(profile, messages):
    url = profile["base_url"].rstrip("/") + "/chat/completions"
    body = json.dumps({
        "model": profile["model"],
        "messages": [{"role": m["role"], "content": m["text"]} for m in messages],
        "temperature": 0.4,
    }).encode()
    req = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + profile.get("api_key", "lm-studio"),
    })
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode())
    return data["choices"][0]["message"]["content"]


TOKEN_FILE = SYNC / "token.txt"


def access_token():
    """Токен для удалённого доступа: создаётся автоматически при первом запуске."""
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(__import__("secrets").token_urlsafe(24))
    return TOKEN_FILE.read_text().strip()



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
        return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode() or "{}")

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/index"):
            raw = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        elif self.path == "/api/messages":
            self.send([json.loads(x) for x in CHAT.read_text().splitlines()] if CHAT.exists() else [])
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
            self.send({**state, "pending": len(SYNC_EVENTS.read_text().splitlines()) if SYNC_EVENTS.exists() else 0})
        elif self.path.startswith("/api/sync/pull"):
            if not self.token_ok():
                self.send({"error": "token required"}, 401); return
            from urllib.parse import parse_qs, urlparse
            q = parse_qs(urlparse(self.path).query)
            since = int(q.get("since", ["0"])[0])
            lines = SYNC_EVENTS.read_text().splitlines() if SYNC_EVENTS.exists() else []
            self.send([json.loads(x) for x in lines if json.loads(x).get("seq", 0) > since][-200:])
        elif self.path == "/api/messages/full":
            msgs = [json.loads(x) for x in CHAT.read_text().splitlines()] if CHAT.exists() else []
            self.send({"version": 1, "items": msgs, "project": "desktop"})
        elif self.path == "/api/sync/pack":
            msgs = [json.loads(x) for x in CHAT.read_text().splitlines()] if CHAT.exists() else []
            self.send({"version": 1, "items": msgs, "project": load_json(HOME / "roles.json", {}).get("project", "")})
        else:
            self.send({"error": "not found"}, 404)

    def do_POST(self):
        try:
            b = self.body()
            if self.path == "/api/chat":
                profiles = load_json(PROFILES, {})
                name = profiles.get("_active", "")
                if name not in profiles:
                    self.send({"error": "Сначала сохрани и выбери провайдера"}, 400)
                    return
                history = [json.loads(x) for x in CHAT.read_text().splitlines()] if CHAT.exists() else []
                history.append({"role": "user", "text": b["text"]})
                answer = call_llm(profiles[name], history[-20:])
                history.append({"role": "assistant", "text": answer})
                CHAT.write_text("\n".join(json.dumps(m, ensure_ascii=False) for m in history[-200:]))
                self.send({"ok": True})
            elif self.path == "/api/clear":
                CHAT.write_text("")
                self.send({"ok": True})
            elif self.path == "/api/profiles":
                profiles = load_json(PROFILES, {})
                profiles[b["name"]] = {"base_url": b["base_url"], "model": b["model"], "api_key": b["api_key"]}
                profiles.setdefault("_active", b["name"])
                save_json(PROFILES, profiles)
                self.send({"ok": True})
            elif self.path == "/api/use":
                profiles = load_json(PROFILES, {})
                profiles["_active"] = b["name"]
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
                with CHAT.open("a") as f:
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
                seq = int(b.get("seq", 0) or 0)
                typ = b.get("type", "event")
                with SYNC_EVENTS.open("a") as f:
                    f.write(json.dumps(b, ensure_ascii=False) + "\n")
                state = load_json(SYNC_STATE, {"last_seq": 0, "last_ts": 0, "events": 0, "fulls": 0})
                state["last_seq"] = max(state.get("last_seq", 0), seq)
                state["last_ts"] = b.get("ts", 0)
                state["events"] = state.get("events", 0) + 1
                if typ == "full": state["fulls"] = state.get("fulls", 0) + 1
                save_json(SYNC_STATE, state)
                if typ == "full":
                    for fp in b.get("files", []):
                        path = (SYNC_FILES / str(fp.get("path", "")).replace("..", "_")).resolve()
                        if str(path).startswith(str(SYNC_FILES.resolve())):
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_text(str(fp.get("text", "")))
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
    a = ap.parse_args()
    print(f"PocketCode Desktop: http://{a.host}:{a.port}/  (Ctrl+C — выход)")
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
