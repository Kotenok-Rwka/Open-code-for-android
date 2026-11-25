#!/usr/bin/env python3
"""Юнит-тесты PocketCode Desktop: стандартная библиотека и пакет cryptography
(нужен тесту шифрования синхронизации).

Запуск: python3 -m unittest discover -s desktop -p 'test_*.py'
"""

import http.client
import importlib.util
import json
import os
import subprocess
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path


def load_module():
    path = Path(__file__).resolve().parent / "pocketcode_desktop.py"
    spec = importlib.util.spec_from_file_location("pocketcode_desktop_test", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class DesktopLogicTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.m.HOME = Path(self.tmp.name)
        self.m.SYNC = self.m.HOME / "sync"
        self.m.SYNC.mkdir(exist_ok=True)
        self.m.SYNC_EVENTS = self.m.SYNC / "events.jsonl"
        self.m.SYNC_STATE = self.m.SYNC / "state.json"
        self.m.SYNC_OUTBOX = self.m.SYNC / "outbox.jsonl"
        self.m.SYNC_CONFIG = self.m.SYNC / "config.json"
        self.m.CHAT = self.m.HOME / "chat.jsonl"
        self.m.PROFILES = self.m.HOME / "profiles.json"
        self.m.PROJECTS = self.m.HOME / "projects"
        self.m.PROJECTS.mkdir(exist_ok=True)
        self.m.QUEUE = self.m.HOME / "queue.json"
        self.m.SKILLS = self.m.HOME / "skills.json"
        self.m.AGENTS = self.m.HOME / "agents.json"
        self.m.ROLES = self.m.HOME / "roles.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_safe_path_blocks_traversal_and_absolute(self):
        self.assertIsNone(self.m.safe_path(self.m.PROJECTS, "../../etc/passwd"))
        self.assertIsNone(self.m.safe_path(self.m.PROJECTS, "/etc/passwd"))
        self.assertIsNone(self.m.safe_path(self.m.PROJECTS, "a/../b"))
        self.assertIsNotNone(self.m.safe_path(self.m.PROJECTS, "project/src/Main.kt"))

    def test_no_secrets_in_public_config(self):
        self.m.save_json(self.m.SYNC_CONFIG, {"phoneUrl": "https://phone", "password": "super-secret", "token": "abc123"})
        cfg = self.m.public_sync_cfg()
        self.assertEqual(cfg["phoneUrl"], "https://phone")
        self.assertNotIn("password", cfg)
        self.assertNotIn("token", cfg)

    def test_sync_link_has_host_token_password(self):
        token = self.m.access_token()
        self.m.save_json(self.m.SYNC_CONFIG, {"password": "пароль 123&=x"})
        link = self.m.sync_link(port=8765, ip="192.168.1.5")
        self.assertTrue(link.startswith("http://192.168.1.5:8765?"))
        self.assertIn("token=" + token, link)
        self.assertIn("&password=", link)
        # без пароля параметра нет
        self.m.save_json(self.m.SYNC_CONFIG, {})
        self.assertNotIn("password=", self.m.sync_link(ip="192.168.1.5"))
        # телефон парсит ссылку обратно (urllib.parse как в QR-обработчике)
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(link).query)
        self.assertEqual(q["token"][0], token)
        self.assertEqual(q["password"][0], "пароль 123&=x")

    def test_mask_hides_secrets(self):
        text = "ошибка token=abcdef123456 и api_key: zzzzzzzz"
        masked = self.m.mask(text)
        self.assertNotIn("abcdef123456", masked)
        self.assertNotIn("zzzzzzzz", masked)

    def test_sync_events_seq_and_dedup(self):
        evt = self.m.make_event("message", {"text": "привет"})
        self.assertEqual(evt["v"], 2)
        self.assertEqual(evt["type"], "event")
        self.assertEqual(evt["seq"], 1)
        self.m.append_jsonl(self.m.SYNC_EVENTS, evt)
        self.m.append_jsonl(self.m.SYNC_EVENTS, evt)
        events = self.m.read_jsonl(self.m.SYNC_EVENTS)
        self.assertEqual(len(events), 2)  # запись дублируется на уровне файла
        seen = set()
        unique = [e for e in events if (e["seq"], e["device"]) not in seen and not seen.add((e["seq"], e["device"]))]
        self.assertEqual(len(unique), 1)

    def test_full_packet_skips_secrets_and_big_files(self):
        p = self.m.safe_path(self.m.PROJECTS, "main/src/App.kt")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("fun main() {}", encoding="utf-8")
        secret = self.m.safe_path(self.m.PROJECTS, "main/.env")
        secret.write_text("API_KEY=hunter2", encoding="utf-8")
        packet = self.m.full_packet()
        paths = [f["path"] for f in packet["files"]]
        self.assertIn("main/src/App.kt", paths)
        self.assertNotIn("main/.env", paths)
        self.assertEqual(packet["type"], "full")

    def test_pcenc_roundtrip(self):
        payload = {"seq": 7, "type": "event", "kind": "ping", "data": {"text": "привет"}}
        enc = self.m.pcenc_encrypt(payload, "password123")
        self.assertTrue(enc.startswith("PCENC1:"))
        self.assertEqual(self.m.pcenc_decrypt(enc, "password123"), payload)
        self.assertIsNone(self.m.pcenc_decrypt(enc, "wrong"))

    def test_plain_json_payload(self):
        payload = {"seq": 1, "type": "event"}
        value, valid = self.m.decrypt_payload(payload, "")
        self.assertTrue(valid)
        self.assertEqual(value, payload)

    def test_translate_query_ru_en(self):
        query, changed = self.m.translate_query("современные сайты и темы")
        self.assertTrue(changed)
        self.assertIn("modern", query)
        self.assertIn("websites", query)
        self.assertIn("themes", query)

    def test_trim_history_limits_messages(self):
        messages = [{"role": "user", "text": "x" * 500} for _ in range(40)]
        out = self.m.trim_history(messages, 5, 1800)
        self.assertLessEqual(len(out), 5)
        self.assertTrue(all(isinstance(m.get("content"), str) for m in out))

    def test_builtin_skills_cover_modern_ui_and_themes(self):
        names = " ".join(s["name"] for s in self.m.load_skills())
        self.assertIn("сайты", names)
        self.assertIn("Аврора", names)
        self.assertIn("Темы", names)

    def test_tools_notes_only_enabled(self):
        self.m.save_json(self.m.SKILLS, [{"name": "a", "enabled": True}, {"name": "b", "enabled": False}])
        self.m.save_json(self.m.AGENTS, [{"name": "c", "enabled": True}, {"name": "d", "enabled": False}])
        notes = self.m.tools_notes()
        self.assertIn("a", notes)
        self.assertIn("c", notes)
        self.assertNotIn("b", notes)
        self.assertNotIn("d", notes)

    def test_request_is_local_needs_loopback_ip_host_and_origin(self):
        local = self.m.request_is_local
        self.assertTrue(local("127.0.0.1", {"Host": "127.0.0.1:8765"}))
        self.assertTrue(local("::1", {"Host": "[::1]:8765"}))
        self.assertTrue(local("127.0.0.1", {"Host": "localhost:8765", "Origin": "http://localhost:8765"}))
        self.assertFalse(local("192.168.1.20", {"Host": "127.0.0.1:8765"}))
        self.assertFalse(local("127.0.0.1", {"Host": "xxx.trycloudflare.com"}))
        self.assertFalse(local("127.0.0.1", {"Host": "127.0.0.1:8765", "Origin": "https://evil.example"}))
        self.assertFalse(local("127.0.0.1", {"Host": "127.0.0.1:8765", "Origin": "null"}))
        self.assertFalse(local("127.0.0.1", {}))


class ServerAccessTest(unittest.TestCase):
    """Сервер синхронизации: без токена отвечает только этому же компьютеру."""

    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.m.HOME = Path(self.tmp.name)
        self.m.SYNC = self.m.HOME / "sync"
        self.m.SYNC.mkdir(exist_ok=True)
        self.m.SYNC_CONFIG = self.m.SYNC / "config.json"
        self.m.TOKEN_FILE = self.m.SYNC / "token.txt"
        self.m.PROJECTS = self.m.HOME / "projects"
        self.m.PROJECTS.mkdir(exist_ok=True)
        self.server = self.m.ThreadingHTTPServer(("127.0.0.1", 0), self.m.Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def post(self, path, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            conn.request("POST", path, body=b"{}", headers={"Content-Type": "application/json", **(headers or {})})
            response = conn.getresponse()
            response.read()
            return response.status
        finally:
            conn.close()

    def test_terminal_refuses_foreign_host_and_origin_without_token(self):
        self.assertEqual(self.post("/api/terminal", {"Host": "xxx.trycloudflare.com"}), 401)
        self.assertEqual(self.post("/api/terminal", {"Origin": "https://evil.example"}), 401)
        self.assertEqual(self.post("/api/chat", {"Host": "192.168.1.5:8765"}), 401)

    def test_terminal_reaches_handler_locally_or_with_token(self):
        # 400 «Нужны проект и команда» означает, что запрос прошёл проверку доступа.
        self.assertEqual(self.post("/api/terminal"), 400)
        token = self.m.access_token()
        self.assertEqual(self.post("/api/terminal", {"Host": "xxx.trycloudflare.com", "X-PocketCode-Token": token}), 400)
        self.assertEqual(self.post("/api/terminal", {"Host": "xxx.trycloudflare.com", "X-PocketCode-Token": "wrong"}), 401)


if __name__ == "__main__":
    unittest.main()


class NativeAppTest(unittest.TestCase):
    """Нативное окно: все разделы навигации имеют построители, браузер не запускается."""

    def test_nav_sections_have_builders(self):
        import importlib.util as ilu
        path = Path(__file__).resolve().parent / "pocketcode_app.py"
        spec = ilu.spec_from_file_location("pocketcode_app_test", path)
        mod = ilu.module_from_spec(spec)
        spec.loader.exec_module(mod)
        keys = [k for _, items in mod.NAV for k, _ in items]
        self.assertIn("chat", keys)
        self.assertEqual(len(keys), len(set(keys)), "разделы навигации не должны повторяться")
        for key in keys:
            self.assertTrue(hasattr(mod.App, "build_" + key), "нет построителя раздела " + key)

    def test_no_browser_launch(self):
        text = (Path(__file__).resolve().parent / "pocketcode_desktop.py").read_text(encoding="utf-8")
        self.assertNotIn("webbrowser", text)
        self.assertEqual(hasattr(load_module(), "start_server_async"), True)


class TestFeatures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_scan_secrets(self):
        text = "x=1\nkey = 'AKIA" + "IOSFODNN7EXAMPL" + "E'\napi_key: 'supersecret123'\nexcept: pass\n"
        kinds = [f["kind"] for f in self.fx.scan_secrets(text)]
        self.assertIn("aws-key", kinds)
        self.assertIn("credential", kinds)
        self.assertIn("empty-except", kinds)
        self.assertEqual(self.fx.scan_secrets("чистый код"), [])

    def test_trim_history_keeps_head_and_tail(self):
        msgs = [{"role": "system", "text": "S"}] + [{"role": "user", "text": "m%d" % i} for i in range(100)]
        kept, dropped = self.fx.trim_history(msgs, max_chars=300)
        self.assertEqual(kept[0]["text"], "S")
        self.assertGreater(len(kept), 1)
        self.assertEqual(dropped, 100 - (len(kept) - 1))
        # суммарный размер в пределах лимита
        self.assertLessEqual(sum(len(str(m)) for m in kept), 300 + len(str(msgs[0])))

    def test_route_step(self):
        self.assertEqual(self.fx.route_step("переименуй переменную x"), "cheap")
        self.assertEqual(self.fx.route_step("x" * 50), "cheap")
        self.assertEqual(self.fx.route_step("Спроектируй архитектуру плагинов с версионированием, миграциями и обратной совместимостью API всех клиентов"), "strong")

    def test_resolve_conflict(self):
        fx = self.fx
        self.assertEqual(fx.resolve_conflict("a", "a", "b"), ("theirs", "b"))
        self.assertEqual(fx.resolve_conflict("a", "b", "a"), ("mine", "b"))
        self.assertEqual(fx.resolve_conflict("a", "b", "b"), ("same", "b"))
        v, merged = fx.resolve_conflict("a", "b", "c")
        self.assertEqual(v, "both")
        self.assertIn("<<<<<<< mine", merged)
        self.assertIn(">>>>>>> theirs", merged)

    def test_key_tests(self):
        out = self.fx.key_tests(["b_test.py", "a_fail_test.py", "c_test.py", "d_failed_spec.py"], max_n=2)
        self.assertEqual(len(out), 2)
        self.assertIn("a_fail_test.py", out)

    def test_suggest_mode(self):
        self.assertEqual(self.fx.suggest_mode(3, "focused"), "scaffold")
        self.assertEqual(self.fx.suggest_mode(1, "auto"), "focused")
        self.assertEqual(self.fx.suggest_mode(0, "auto"), "auto")

    def test_memory_roundtrip(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertTrue(self.fx.update_memory(d, "# Решения\n- SQLite"))
            self.assertEqual(self.fx.read_memory(d), "# Решения\n- SQLite")
            self.assertTrue(self.fx.update_rules(d, "без any"))
            block = self.fx.memory_and_rules_block(d)
            self.assertIn("SQLite", block)
            self.assertIn("без any", block)

    def test_templates_create(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            ok = self.fx.create_from_template(d, "landing")
            self.assertTrue(ok)
            self.assertTrue((Path(d) / "index.html").exists())
            self.assertFalse(self.fx.create_from_template(d, "нету"))

    def test_fallback(self):
        self.assertEqual(self.fx.fallback_provider({"localModel": "qwen2"}, "Connection refused"), "qwen2")
        self.assertIsNone(self.fx.fallback_provider({"localModel": "qwen2"}, "bad api key"))
        self.assertIsNone(self.fx.fallback_provider({"localModel": ""}, "Connection refused"))

    def test_snapshot_roundtrip(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.fx.SNAPSHOT_BASE = Path(d) / "snaps"
            p = Path(d) / "proj"
            p.mkdir()
            (p / "app.py").write_text("print(1)", encoding="utf-8")
            r = self.fx.make_snapshot(str(p))
            self.assertTrue(r.get("ok"))
            (p / "app.py").write_text("print(2)", encoding="utf-8")
            snaps = self.fx.list_snapshots(str(p))
            self.assertEqual(len(snaps), 1)
            self.fx.restore_snapshot(str(p), snaps[0]["n"])
            self.assertEqual((p / "app.py").read_text(encoding="utf-8"), "print(1)")

    def test_devices_online(self):
        self.fx.DEVICES.clear()
        import time as t
        now = t.time()
        self.fx.touch_device("phone", now)
        self.fx.touch_device("pc", now - 600)
        st = {d["name"]: d["online"] for d in self.fx.devices_status(now=now)}
        self.assertTrue(st["phone"])
        self.assertFalse(st["pc"])

    def test_server_endpoints_smoke(self):
        mod = load_module()
        sock = __import__("socket")
        s = sock.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        mod.start_server_async("127.0.0.1", port)
        import time as t
        t.sleep(0.4)
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        body = json.dumps({"text": "token = 'AKIA" + "IOSFODNN7EXAMPL" + "E'"}).encode()
        conn.request("POST", "/api/scan/secrets", body=body,
                     headers={"Content-Type": "application/json"})
        resp = json.loads(conn.getresponse().read())
        self.assertEqual(resp["findings"][0]["kind"], "aws-key")
        conn.request("GET", "/api/sync/status")
        resp = json.loads(conn.getresponse().read())
        self.assertIn("devices", resp)
        conn.close()


class TestBatch3(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test3", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_git_status_and_log(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            fx = self.fx
            fx._git(d, "init", "-q")
            (Path(d) / "a.txt").write_text("hi", encoding="utf-8")
            st = fx.git_status_brief(d)
            self.assertEqual(st["changed"], 1)
            fx.git_commit_all(d, "первый")
            st2 = fx.git_status_brief(d)
            self.assertEqual(st2["changed"], 0)
            log = fx.git_log(d, 5)
            self.assertEqual(len(log["commits"]), 1)
            br = fx.git_branches(d)
            self.assertTrue(br["current"])
            hist = fx.git_file_history(d, "a.txt")
            self.assertEqual(len(hist["commits"]), 1)

    def test_git_commit_nothing(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.fx._git(d, "init", "-q")
            r = self.fx.git_commit_all(d, "пусто")
            self.assertTrue(r.get("ok"))
            self.assertFalse(r.get("committed"))

    def test_risk_metrics(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "bad.py").write_text("except:\n    pass\neval('x')\nexcept:\n    pass\n", encoding="utf-8")
            (Path(d) / "good.py").write_text("print(1)\n", encoding="utf-8")
            rows = self.fx.file_risk_metrics(d)
            self.assertEqual(rows[0]["file"], "bad.py")
            self.assertGreater(rows[0]["risk"], rows[1]["risk"])

    def test_palette(self):
        all_cmds = self.fx.palette_commands()
        self.assertGreaterEqual(len(all_cmds), 8)
        hit = self.fx.palette_filter(all_cmds, "секрет")
        self.assertEqual(len(hit), 1)
        self.assertEqual(len(self.fx.palette_filter(all_cmds, "")), len(all_cmds))

    def test_ci_and_issue(self):
        fx = self.fx
        jobs = '{"jobs":[{"name":"build","conclusion":"success"},{"name":"test","conclusion":"failure","html_url":"u"}]}'
        f = fx.ci_failure_text(jobs)
        self.assertEqual(f[0]["name"], "test")
        self.assertEqual(fx.ci_failure_text("мусор"), [])
        t = fx.issue_to_task('{"number":42,"title":"Падает","body":"лог"}')
        self.assertIn("#42", t["task"])
        self.assertIsNone(fx.issue_to_task('{"title":""}'))

    def test_health_and_integrity(self):
        fx = self.fx
        self.assertTrue(fx.health_report(5, True, 2)["ok"])
        h = fx.health_report(0, False, 0)
        self.assertFalse(h["ok"])
        packets = [{"a": 1}, {"b": 2}]
        hashes = [fx.content_hash(json.dumps(p, sort_keys=True)) for p in packets]
        r = fx.verify_packets_integrity(packets, hashes)
        self.assertEqual(r["broken"], [])
        r2 = fx.verify_packets_integrity(packets, ["bad", hashes[1]])
        self.assertEqual(r2["broken"], [0])

    def test_templates_and_pins(self):
        fx = self.fx
        t = fx.template_save({}, "тесты", "прогони")
        self.assertIn("тесты", t)
        t = fx.template_save(t, "тесты", "другое")
        self.assertEqual(t["тесты"], "другое")
        self.assertNotIn("тесты", fx.template_delete(t, "тесты"))
        p = fx.toggle_pin([], "a.py")
        self.assertIn("a.py", p)
        self.assertNotIn("a.py", fx.toggle_pin(p, "a.py"))

    def test_profile(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            r = self.fx.profile_cmd(["echo", "ok"], d)
            self.assertEqual(r["stdout"].strip(), "ok")
            self.assertGreaterEqual(r["seconds"], 0)


class TestBatch4(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test4", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_macros_record_run_delete(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(fx.macro_record(d, "build", "ls"), {"ok": True, "steps": 1})
            fx.macro_record(d, "build", "make")
            self.assertEqual(fx.macro_steps(d, "build"), ["ls", "make"])
            self.assertEqual(fx.macro_record(d, "build", "  "), {"error": "пустая команда"})
            self.assertEqual(fx.macro_list(d), [{"name": "build", "steps": 2}])
            self.assertTrue(fx.macro_delete(d, "build").get("ok"))
            self.assertIsNone(fx.macro_steps(d, "build"))
            self.assertTrue(fx.macro_delete(d, "build").get("error"))

    def test_macro_step_cap(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            for i in range(fx.MAX_MACRO_STEPS):
                fx.macro_record(d, "big", "cmd%d" % i)
            self.assertIn("error", fx.macro_record(d, "big", "cmd_over"))

    def test_triggers_fire_on_change(self):
        fx = self.fx
        import tempfile
        import time as _t
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "src").mkdir()
            f = Path(d) / "src" / "a.py"
            f.write_text("x", encoding="utf-8")
            add = fx.trigger_add(d, "src/*.py", "pytest")
            self.assertTrue(add.get("ok"))
            r1 = fx.trigger_check(d, d)  # первый проход — снимок
            self.assertEqual(r1["fired"], [])
            _t.sleep(0.05)
            f.write_text("y", encoding="utf-8")
            r2 = fx.trigger_check(d, d)
            self.assertEqual(len(r2["fired"]), 1)
            self.assertEqual(r2["fired"][0]["cmd"], "pytest")
            self.assertEqual(r2["fired"][0]["files"], [str(f)])
            r3 = fx.trigger_check(d, d)  # ничего не менялось
            self.assertEqual(r3["fired"], [])
            self.assertTrue(fx.trigger_remove(d, add["id"]).get("ok"))
            self.assertTrue(fx.trigger_remove(d, "нет-такого").get("error"))

    def test_webhooks_validation_and_fire(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("error", fx.webhook_add(d, "ftp://bad"))
            self.assertIn("error", fx.webhook_add(d, "http://u:p@host/hook"))
            self.assertTrue(fx.webhook_add(d, "https://example.com/hook").get("ok"))
            got = {}
            sent = fx.webhook_fire(d, "task_done", {"id": 7},
                                   poster=lambda url, body: (got.update(url=url, body=body), (True, ""))[1])
            self.assertEqual(sent["sent"][0]["ok"], True)
            self.assertEqual(got["url"], "https://example.com/hook")
            self.assertIn(b"task_done", got["body"])
            wid = fx.webhook_list(d)[0]["id"]
            self.assertTrue(fx.webhook_remove(d, wid).get("ok"))
            self.assertEqual(fx.webhook_fire(d, "task_done", {}, poster=lambda u, b: (True, ""))["sent"], [])

    def test_sql_readonly_and_error(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("error", fx.sql_query(d, "DROP TABLE x"))
            self.assertIn("error", fx.sql_query(d, "INSERT INTO t VALUES (1)"))
            self.assertIn("note", fx.sql_query(d, "select 1"))
            import sqlite3
            db = Path(d) / ".pocketcode"
            db.mkdir(exist_ok=True)
            con = sqlite3.connect(str(db / "data.sqlite"))
            con.execute("create table t (id integer, name text)")
            con.execute("insert into t values (1, 'a')")
            con.commit(); con.close()
            r = fx.sql_query(d, "select id, name from t")
            self.assertEqual(r["columns"], ["id", "name"])
            self.assertEqual(r["rows"], [[1, "a"]])

    def test_mermaid_tree(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "src").mkdir()
            (Path(d) / "src" / "m.py").write_text("1", encoding="utf-8")
            (Path(d) / "node_modules").mkdir()  # мусор не попадает
            (Path(d) / "node_modules" / "junk.js").write_text("x", encoding="utf-8")
            r = fx.mermaid_tree(d)
            self.assertTrue(r["mermaid"].startswith("graph TD"))
            self.assertIn("m.py", r["mermaid"])
            self.assertNotIn("junk.js", r["mermaid"])
            self.assertEqual(r["nodes"], 2)  # папка src + файл m.py
            self.assertIn("error", fx.mermaid_tree(str(Path(d) / "нет")))

    def test_mermaid_empty(self):
        fx = self.fx
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            r = fx.mermaid_tree(d)
            self.assertIn("пусто", r["mermaid"])


class TestBatch5(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test5", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_cron_every_and_daily(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("error", fx.cron_add(d, "hourly", "дело"))
            self.assertIn("error", fx.cron_add(d, "every 30m", ""))
            self.assertIn("error", fx.cron_add(d, "every 2000m", "дело"))
            ok = fx.cron_add(d, "every 30m", "прогнать тесты", "тесты")
            self.assertTrue(ok.get("ok"))
            daily = fx.cron_add(d, "daily 09:30", "бэкап")
            self.assertTrue(daily.get("ok"))
            # интервал ещё не вышел
            self.assertEqual(fx.cron_due(d, now=100)["due"], [])
            r = fx.cron_due(d, now=60 * 31)
            self.assertEqual([x["id"] for x in r["due"]], [ok["id"]])
            # daily: сегодня 10:00 — срок вышел
            ten = time.time()  # base для mktime из localtime
            lt = time.localtime(ten)
            target = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 9, 30, 0, 0, 0, -1))
            r2 = fx.cron_due(d, now=target + 1)
            self.assertEqual(sorted(x["id"] for x in r2["due"]), sorted([daily["id"], ok["id"]]))
            self.assertEqual(fx.cron_due(d, now=target + 1)["due"], [])  # обе уже отмечены
            self.assertEqual(len(fx.cron_list(d)), 2)
            self.assertTrue(fx.cron_remove(d, ok["id"]).get("ok"))
            self.assertTrue(fx.cron_remove(d, ok["id"]).get("error"))

    def test_conflict_resolve(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "a.txt"
            f.write_text("head\n<<<<<<< HEAD\nours line\n=======\ntheirs line\n>>>>>>> main\ntail\n",
                         encoding="utf-8")
            self.assertIn("error", fx.conflict_resolve(d, "a.txt", "random"))
            r = fx.conflict_resolve(d, "a.txt", "theirs")
            self.assertEqual(r, {"ok": True, "resolved": 1})
            self.assertEqual(f.read_text(encoding="utf-8"), "head\ntheirs line\ntail\n")
            f.write_text("<<<<<<< HEAD\nA\n=======\nB\n>>>>>>> x\n", encoding="utf-8")
            fx.conflict_resolve(d, "a.txt", "ours")
            self.assertEqual(f.read_text(encoding="utf-8"), "A\n")
            f.write_text("<<<<<<< HEAD\nA\n=======\nB\n>>>>>>> x\n", encoding="utf-8")
            fx.conflict_resolve(d, "a.txt", "both")
            self.assertEqual(f.read_text(encoding="utf-8"), "A\nB\n")
            self.assertIn("error", fx.conflict_resolve(d, "нет.txt", "ours"))

    def test_dead_code(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "mod.py").write_text(
                "def used():\n    return 1\n\ndef unused_one():\n    return 2\n", encoding="utf-8")
            (Path(d) / "main.py").write_text("from mod import used\nused()\n", encoding="utf-8")
            r = fx.find_dead_code(d)
            names = [x["name"] for x in r["dead"]]
            self.assertEqual(names, ["unused_one"])
            self.assertEqual(r["scanned"], 2)
            self.assertIn("error", fx.find_dead_code(str(Path(d) / "нет")))

    def test_log_overview(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "app.log"
            f.write_text("2026-01-01 INFO start\n2026-01-01 ERROR boom\n"
                         "2026-01-01 WARNING meh\n2026-01-01 ERROR second\nno level line\n",
                         encoding="utf-8")
            r = fx.log_overview(f)
            self.assertEqual(r["counts"], {"INFO": 1, "ERROR": 2, "WARNING": 1})
            self.assertEqual(len(r["errors"]), 2)
            self.assertIn("ERROR boom", r["errors"][0])
            self.assertEqual(r["total_lines"], 5)
            self.assertIn("error", fx.log_overview(Path(d) / "нет"))

    def test_table_summary_csv_json(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            c = Path(d) / "t.csv"
            c.write_text("id,price\n1,10\n2,20\n3,\n", encoding="utf-8")
            r = fx.table_summary(c)
            self.assertEqual(r["columns"], ["id", "price"])
            self.assertEqual(r["rows"], 3)
            self.assertEqual(r["stats"]["price"]["min"], 10.0)
            self.assertEqual(r["stats"]["price"]["missing"], 1)
            j = Path(d) / "t.json"
            j.write_text('[{"name": "a", "v": 1}, {"name": "b", "v": 3}]', encoding="utf-8")
            rj = fx.table_summary(j)
            self.assertEqual(rj["rows"], 2)
            self.assertEqual(rj["stats"]["v"]["max"], 3.0)
            self.assertIn("error", fx.table_summary(Path(d) / "нет"))

    def test_csv_diff(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            a = Path(d) / "a.csv"
            b = Path(d) / "b.csv"
            a.write_text("id,v\n1,10\n2,20\n", encoding="utf-8")
            b.write_text("id,v\n1,10\n2,21\n3,30\n", encoding="utf-8")
            r = fx.csv_diff(a, b)
            self.assertEqual(r["counts"], {"added": 1, "removed": 0, "changed": 1})
            self.assertEqual(r["changed"], [[["2", "20"], ["2", "21"]]])
            self.assertFalse(r["header_changed"])
            self.assertIn("error", fx.csv_diff(a, Path(d) / "нет"))

    def test_import_prompts(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            cmd = Path(d) / ".claude" / "commands"
            cmd.mkdir(parents=True)
            (cmd / "deploy.md").write_text("собери и задеплой", encoding="utf-8")
            r = fx.import_prompts(d)
            self.assertEqual(len(r["prompts"]), 1)
            self.assertEqual(r["prompts"][0]["name"], "deploy")
            self.assertEqual(r["prompts"][0]["text"], "собери и задеплой")

    def test_quarantine_packet(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            good = {"device": "phone", "seq": 1}
            self.assertTrue(fx.quarantine_packet(d, good)["ok"])
            bad = fx.quarantine_packet(d, "не json")
            self.assertTrue(bad["quarantined"])
            self.assertEqual(bad["reason"], "не JSON")
            no_dev = fx.quarantine_packet(d, {"seq": 2})
            self.assertEqual(no_dev["reason"], "нет отправителя (device)")
            wrong = fx.quarantine_packet(d, good, expected_hash="deadbeef")
            self.assertEqual(wrong["reason"], "хеш не совпал")
            items = fx.quarantine_list(d)
            self.assertEqual(len(items["items"]), 3)
            self.assertIn("phone", {i["device"] for i in items["items"]})


class TestBatch6(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test6", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_queue_priority(self):
        fx = self.fx
        items = [{"text": "обычная", "ts": 10}, {"text": "срочная", "ts": 20, "prio": 0}]
        self.assertEqual(fx.queue_pick(items)["text"], "срочная")
        self.assertEqual(fx.queue_pick([{"text": "a", "ts": 1}, {"text": "b", "ts": 0}])["text"], "b")
        self.assertEqual(fx.queue_pick([]), None)
        self.assertEqual(fx.queue_pick([{"text": "x"}, {"text": None}])["text"], "x")
        # без приоритета — самая старая
        self.assertEqual(fx.queue_pick([{"text": "a", "ts": 1, "prio": 5},
                                        {"text": "b", "ts": 0}])["text"], "b")

    def test_crash_journal(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            fx.crash_report(d, "Exception", "boom", "agent.py:10")
            fx.crash_report(d, "Timeout", "вышло время")
            r = fx.crash_list(d)
            self.assertEqual(r["total"], 2)
            self.assertEqual(r["by_kind"], {"Exception": 1, "Timeout": 1})
            self.assertEqual(r["items"][0]["kind"], "Timeout")  # свежие сверху
            self.assertTrue(fx.crash_report(d, "Exception", "ещё раз")["ok"])
            self.assertEqual(fx.crash_list(d, limit=2)["total"], 3)
            self.assertEqual(len(fx.crash_list(d, limit=2)["items"]), 2)

    def test_notes_cms(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("error", fx.note_add(d, "", ""))
            ok = fx.note_add(d, "Деплой", "сначала тесты", "devops, важно")
            self.assertTrue(ok.get("ok"))
            fx.note_add(d, "Идея", "кэшировать ответы", "devops")
            r = fx.note_list(d, query="тесты")
            self.assertEqual(r["total"], 1)
            self.assertEqual(r["items"][0]["title"], "Деплой")
            self.assertEqual(fx.note_list(d, tag="devops")["total"], 2)
            self.assertEqual(fx.note_list(d, tag="нет")["total"], 0)
            # свежие сверху
            self.assertEqual(fx.note_list(d)["items"][0]["title"], "Идея")
            self.assertTrue(fx.note_delete(d, ok["id"]).get("ok"))
            self.assertIn("error", fx.note_delete(d, ok["id"]))

    def test_guest_view(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(fx.guest_view(d, "плохой")["status"], 403)
            tok = fx.guest_create(d, ttl_hours=1)["token"]
            (Path(d) / "a.py").write_text("print(1)\n", encoding="utf-8")
            (Path(d) / ".pocketcode").mkdir(exist_ok=True)
            (Path(d) / ".pocketcode" / "memory.md").write_text(
                "решение: токен ghp_abcdef0123456789abcdef0123\nструктура готова", encoding="utf-8")
            r = fx.guest_view(d, tok)
            self.assertEqual(r["status"], 200)
            self.assertEqual(r["files"], ["a.py"])  # скрытые каталоги не попадают
            self.assertIn("***", r["memory_redacted"])
            self.assertNotIn("ghp_abcdef0123456789abcdef0123", r["memory_redacted"])
            self.assertIn("структура готова", r["memory_redacted"])
            self.assertEqual(r["secrets_blocked"], 1)
            # просроченный токен
            fx._pc_save(fx._pc_json(d, "guest.json"),
                        {"token": tok, "ts": int(time.time()) - 7200, "ttl": 1})
            self.assertEqual(fx.guest_view(d, tok)["status"], 403)

    def test_integrity_check(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            r = fx.integrity_check(d)
            self.assertTrue(r["ok"])  # пустой проект — без проблем
            self.assertEqual(r["checked"], 0)
            fx.note_add(d, "заметка", "тело")
            fx.crash_report(d, "Exception", "x")
            r2 = fx.integrity_check(d)
            self.assertTrue(r2["ok"])
            self.assertGreaterEqual(r2["checked"], 2)
            (fx._pc_json(d, "broken.json")).write_text("{битый", encoding="utf-8")
            r3 = fx.integrity_check(d)
            self.assertFalse(r3["ok"])
            self.assertIn("битый JSON", r3["problems"][0]["problem"])


class TestBatch7(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test7", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_plugins(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            pdir = Path(d) / "plugins"
            pdir.mkdir()
            (pdir / "hello.py").write_text("print('плагин работает')", encoding="utf-8")
            state = Path(d) / "state.json"
            r = fx.plugin_list(pdir, state)
            self.assertEqual([i["name"] for i in r["items"]], ["hello"])
            self.assertTrue(r["items"][0]["enabled"])
            self.assertTrue(fx.plugin_toggle(state, "hello", False)["ok"])
            self.assertFalse(fx.plugin_list(pdir, state)["items"][0]["enabled"])
            off = fx.plugin_run(pdir, state, "hello")
            self.assertIn("выключен", off.get("error", ""))
            fx.plugin_toggle(state, "hello", True)
            run = fx.plugin_run(pdir, state, "hello", timeout=30)
            self.assertEqual(run.get("rc"), 0)
            self.assertIn("плагин работает", run.get("stdout", ""))
            self.assertIn("error", fx.plugin_run(pdir, state, "нет_такого"))
            self.assertIn("error", fx.plugin_run(pdir, state, "../etc"))
            self.assertIn("error", fx.plugin_toggle(state, "плохое имя!", True))

    def test_session_stats(self):
        fx = self.fx
        sessions = {"active": "a", "items": [{"id": "a"}, {"id": "b"}]}
        msgs = [
            {"role": "user", "text": "привет", "ts": 1795580000000, "session": "a"},
            {"role": "assistant", "text": "ответ", "ts": 1795580000000, "session": "a"},
            {"role": "user", "text": "ещё", "ts": 1795580000000, "session": "b"},
        ]
        r = fx.session_stats(sessions, msgs)
        self.assertEqual(r["sessions"], 2)
        self.assertEqual(r["messages"], 3)
        self.assertEqual(r["by_role"], {"user": 2, "assistant": 1})
        self.assertEqual(r["by_session"], {"a": 2, "b": 1})
        self.assertGreaterEqual(r["active_days"], 1)
        self.assertEqual(r["busiest"][0], ("a", 2))
        self.assertEqual(fx.session_stats(None, [])["messages"], 0)

    def test_media_list(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            png = bytes(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
                        + (30).to_bytes(4, "big") + (20).to_bytes(4, "big") + b"\x08\x02" + b"\x00" * 8)
            (Path(d) / "a.png").write_bytes(png)
            (Path(d) / "b.txt").write_text("не медиа", encoding="utf-8")
            (Path(d) / "build").mkdir()
            (Path(d) / "build" / "c.png").write_bytes(png)  # мусорный каталог — пропустить
            r = fx.media_list(d)
            self.assertEqual([i["file"] for i in r["items"]], ["a.png"])
            self.assertEqual(r["items"][0]["width"], 30)
            self.assertEqual(r["items"][0]["height"], 20)
            self.assertIn("error", fx.media_list(str(Path(d) / "нет")))

    def test_ratings(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "ratings.json"
            self.assertIn("error", fx.rate_item(f, "skill", "", 1))
            self.assertIn("error", fx.rate_item(f, "music", "x", 1))
            self.assertIn("error", fx.rate_item(f, "skill", "x", 5))
            fx.rate_item(f, "skill", "сайты", 1)
            fx.rate_item(f, "skill", "сайты", 1)
            fx.rate_item(f, "skill", "сайты", -1)
            fx.rate_item(f, "skill", "боты", 1)
            top = fx.ratings_top(f, "skill")
            self.assertEqual(top["items"][0]["name"], "сайты")
            self.assertEqual(top["items"][0]["score"], 1)
            self.assertEqual(top["items"][1]["name"], "боты")
            self.assertEqual(fx.ratings_top(f, "template")["items"], [])


class TestBatch8(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test8", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_git_cache(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q"], cwd=d, check=True)
            r1 = fx.git_status_cached(d)
            self.assertFalse(r1["cached"])
            r2 = fx.git_status_cached(d)
            self.assertTrue(r2["cached"])  # из кэша
            self.assertEqual({k: v for k, v in r1.items() if k != "cached"},
                             {k: v for k, v in r2.items() if k != "cached"})
            fx.git_cache_invalidate(d)
            self.assertFalse(fx.git_status_cached(d)["cached"])
            r3 = fx.git_status_cached(d, ttl=-1)  # TTL истёк
            self.assertFalse(r3["cached"])

    def test_json_validate(self):
        fx = self.fx
        ok = fx.json_validate('{"a": 1}')
        self.assertTrue(ok["ok"])
        bad = fx.json_validate('{"a": 1,,}')
        self.assertFalse(bad["ok"])
        self.assertGreaterEqual(bad["line"], 1)
        fixed = fx.json_validate('{"a": [1, 2,], "b": "x",}', autofix=True)
        self.assertTrue(fixed["ok"])
        self.assertEqual(fixed["data"], {"a": [1, 2], "b": "x"})
        self.assertTrue(any("запятые" in n for n in fixed["notes"]))
        self.assertFalse(fx.json_validate("")["ok"])

    def test_disk_readonly(self):
        fx = self.fx
        r = fx.disk_status(tempfile.gettempdir(), min_free_mb=1)
        self.assertFalse(r["readonly"])
        self.assertGreaterEqual(r["free_mb"], 0)
        # нереалистично большой порог -> режим чтения
        self.assertTrue(fx.disk_low(tempfile.gettempdir(), min_free_mb=10**7))

    def test_team_roles(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            fx.team_set(d, "Аня", "owner")
            fx.team_set(d, "Борис", "editor")
            fx.team_set(d, "Вера", "reader")
            r = fx.team_get(d)
            self.assertEqual(r["owner"], "Аня")
            self.assertEqual(r["members"]["Борис"], "editor")
            self.assertTrue(fx.team_check(d, "Борис", "task_run")["allowed"])
            self.assertTrue(fx.team_check(d, "Аня", "settings")["allowed"])
            self.assertFalse(fx.team_check(d, "Борис", "settings")["allowed"])  # редактору нельзя настройки
            self.assertFalse(fx.team_check(d, "Вера", "file_write")["allowed"])
            self.assertFalse(fx.team_check(d, "Вера", "task_run")["allowed"])  # читателю нельзя запускать задачи
            self.assertIn("error", fx.team_set(d, "", "owner"))
            self.assertIn("error", fx.team_set(d, "Кто", "босс"))

    def test_repo_export(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "proj"
            src.mkdir()
            (src / "app.py").write_text("print(1)", encoding="utf-8")
            (src / ".pocketcode").mkdir()
            (src / "build").mkdir()
            (src / "build" / "x.o").write_text("junk", encoding="utf-8")
            r = fx.export_project_repo(src, run_cmd="python3 app.py")
            dest = Path(r["dest"])
            self.assertTrue(r["ok"])
            self.assertTrue((dest / "README.md").is_file())
            self.assertTrue((dest / ".github" / "workflows" / "ci.yml").is_file())
            self.assertTrue((dest / "LICENSE").is_file())
            self.assertTrue((dest / "app.py").is_file())
            self.assertFalse((dest / "build" / "x.o").exists())  # мусор не копируем
            self.assertIn("python3 app.py", (dest / "README.md").read_text(encoding="utf-8"))
            self.assertIn("error", fx.export_project_repo(str(src / "нет")))

    def test_health_report(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            r = fx.project_health(d)
            self.assertIn("score", r)
            self.assertIn("readonly", r)
            self.assertGreaterEqual(r["score"], 0)
            self.assertLessEqual(r["score"], 100)

    def test_full_import(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            zsrc = Path(d) / "export.zip"
            with zipfile.ZipFile(zsrc, "w") as zf:
                zf.writestr("settings.json", json.dumps({"leanCalls": True}))
                zf.writestr("projects/demo/.pocketcode/notes.json", "[]")
                zf.writestr("projects/demo/app.py", "print(1)")
                zf.writestr("../злая", "нет")  # обход пути — пропустить
            home = Path(d) / "home"
            projects = home / "projects"
            r = fx.full_import(zsrc, projects, home, app_version="1.2.3")
            self.assertEqual(r["ok"], True)
            self.assertEqual(r["imported"], 3)
            self.assertEqual(r["skipped"], 1)
            self.assertTrue((home / "settings.json").is_file())
            self.assertTrue((projects / "demo" / "app.py").is_file())
            marker = projects / "demo" / ".pocketcode" / "import.json"
            self.assertIn("1.2.3", marker.read_text(encoding="utf-8"))
            self.assertEqual(r["migrated"], 1)
            # повторный импорт — маркер не перезаписывается
            r2 = fx.full_import(zsrc, projects, home)
            self.assertEqual(r2["migrated"], 0)
            self.assertIn("error", fx.full_import(d, projects, home))


class TestBatch9(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test9", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def _proj(self, d):
        p = Path(d) / "proj"
        p.mkdir()
        (p / "a.py").write_text("x = 1\n# TODO сделать\n", encoding="utf-8")
        (p / "b.js").write_text("var y = 2;\n", encoding="utf-8")
        (p / "readme.md").write_text("# Заголовок\n[битая](нет.md)\n[ок](a.py)\n", encoding="utf-8")
        (p / "copy1.txt").write_text("одинаковый", encoding="utf-8")
        (p / "copy2.txt").write_text("одинаковый", encoding="utf-8")
        return p

    def test_code_metrics(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.cloc(p)
            self.assertEqual(r["languages"]["Python"]["files"], 1)
            self.assertEqual(r["languages"]["JavaScript"]["code"], 1)
            big = fx.biggest_files(p, limit=1)
            self.assertEqual(len(big["items"]), 1)
            self.assertIn("file", big["items"][0])
            self.assertEqual(fx.dup_files(p)["items"][0]["files"],
                             sorted(["copy1.txt", "copy2.txt"]))
            self.assertIn("readme.md", [x["file"] for x in fx.stale_files(p, days=0)["items"]])
            self.assertEqual(fx.empty_dirs(p)["items"], [])

    def test_markdown_and_generators(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.md_broken_links(p)
            self.assertEqual(r["broken"][0]["link"], "нет.md")
            self.assertEqual(r["checked"], 2)
            toc = fx.md_toc("# A\n```py\n# не заголовок\n```\n## B")
            self.assertEqual(len(toc["toc"].splitlines()), 2)
            self.assertIn("node_modules/", fx.gitignore_gen("node")["gitignore"])
            self.assertIn("error", fx.gitignore_gen("brainfuck"))
            f = p / "a.py"
            fx.license_header(f, "mit")
            self.assertTrue(f.read_text(encoding="utf-8").startswith("# SPDX-License-Identifier: MIT"))
            self.assertFalse(fx.license_header(f, "mit")["changed"])  # уже есть
            subprocess.run(["git", "init", "-q"], cwd=p, check=True)
            subprocess.run(["git", "config", "user.email", "t@t"], cwd=p, check=True)
            subprocess.run(["git", "config", "user.name", "t"], cwd=p, check=True)
            subprocess.run(["git", "add", "."], cwd=p, check=True)
            subprocess.run(["git", "commit", "-qm", "первый коммит"], cwd=p, check=True)
            ch = fx.changelog_from_git(p)
            self.assertIn("первый коммит", ch["changelog"])

    def test_snippets_drafts_clipboard_pins_tags(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            sn = Path(d) / "snippets.json"
            self.assertIn("error", fx.snippet_add(sn, "", "код"))
            fx.snippet_add(sn, "fetch", "async def fetch(): ...", "python")
            fx.snippet_add(sn, "parse", "json.loads(x)")
            self.assertEqual(fx.snippet_search(sn, "fetch")["items"][0]["name"], "fetch")
            self.assertEqual(len(fx.snippet_search(sn)["items"]), 2)
            dr = Path(d) / "drafts.json"
            fx.drafts_save(dr, "sess1", "черновик")
            self.assertEqual(fx.drafts_load(dr, "sess1")["text"], "черновик")
            cl = Path(d) / "clip.json"
            fx.clipboard_push(cl, "первый")
            fx.clipboard_push(cl, "второй")
            hist = fx.clipboard_history(cl)
            self.assertEqual([i["text"] for i in hist["items"]], ["второй", "первый"])
            p = self._proj(d)
            self.assertTrue(fx.pin_file(p, "a.py")["ok"])
            self.assertIn("error", fx.pin_file(p, "../нет"))
            self.assertTrue(fx.unpin_file(p, "a.py")["ok"])
            self.assertEqual(fx.tag_project(p, "web,  dev")["tags"], ["web", "dev"])

    def test_todo_board_heatmap_streak(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            board = fx.todo_board(p)
            self.assertEqual(board["total"], 1)
            self.assertEqual(board["board"]["TODO"][0]["text"], "сделать")
            hm = fx.heatmap(p, days=7)
            self.assertGreaterEqual(sum(hm["per_day"].values()), 5)
            sg = fx.streak_and_goal(p, goal=1)
            self.assertGreaterEqual(sg["streak"], 1)
            self.assertTrue(sg["goal_met"])

    def test_exports_and_doctor(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "chat.md"
            r = fx.export_chat_md([{"role": "user", "text": "привет"},
                                   {"role": "assistant", "text": "ответ"}], out)
            self.assertTrue(r["ok"])
            self.assertIn("**Ты**: привет", out.read_text(encoding="utf-8"))
            csv_out = Path(d) / "m.csv"
            fx.metrics_csv([{"a": 1, "b": 2}, {"a": 3, "b": 4}], csv_out)
            self.assertEqual(csv_out.read_text(encoding="utf-8").splitlines()[0], "a,b")
            doc = fx.env_doctor(d)
            self.assertTrue(doc["ok"])
            self.assertEqual({"python", "git", "disk", "network-stack"}, {c["name"] for c in doc["checks"]})
            a = fx.dep_audit(self._proj(d))
            self.assertEqual(a["findings"], [])

    def test_trash_lock_aliases_timer(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.trash_delete(p, "copy1.txt")
            self.assertTrue(r["ok"])
            self.assertFalse((p / "copy1.txt").exists())
            fx.trash_restore(p, r["trash_file"])
            self.assertTrue((p / "copy1.txt").exists())
            self.assertIn("error", fx.trash_restore(p, "нет"))
            self.assertTrue(fx.lock_project(p, "секрет123")["ok"])
            self.assertTrue(fx.unlock_check(p, "секрет123")["ok"])
            self.assertFalse(fx.unlock_check(p, "не то")["ok"])
            unlocked = Path(d) / "unlocked"  # без lock.json
            unlocked.mkdir()
            self.assertFalse(fx.unlock_check(unlocked, "")["locked"])
            al = Path(d) / "al.json"
            fx.alias_set(al, "т", "прогнать тесты")
            self.assertEqual(fx.alias_get(al, "т")["command"], "прогнать тесты")
            t = Path(d) / "timers.json"
            self.assertIn("error", fx.session_timer(t, "s", "stop"))
            fx.session_timer(t, "s", "start")
            self.assertIn("error", fx.session_timer(t, "s", "start"))
            r2 = fx.session_timer(t, "s", "stop")
            self.assertFalse(r2["running"])

    def test_metrics_ratelimit_backup_sync(self):
        fx = self.fx
        import threading as th
        key = f"metrics-{time.time()}"
        for i in range(3):
            fx.api_metrics_record(key, "/api/chat", 10.5 + i, 200 if i < 2 else 500)
        r = fx.api_metrics_report(key)
        self.assertEqual(r["total"], 3)
        self.assertEqual(r["slowest"][0]["calls"], 3)
        self.assertAlmostEqual(r["error_rate"], 1 / 3, places=2)
        limiter = fx.RateLimiter(per_minute=2)
        self.assertTrue(limiter.allow("ip1"))
        self.assertTrue(limiter.allow("ip1"))
        self.assertFalse(limiter.allow("ip1"))
        self.assertTrue(limiter.allow("ip2"))  # другой ключ не заблокирован
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "backup.json"
            f.write_text('{"a": 1}', encoding="utf-8")
            self.assertTrue(fx.backup_verify(f)["ok"])
            self.assertFalse(fx.backup_verify(Path(d) / "нет")["ok"])
            sp = fx.sync_speed_test(payload_kb=16)
            self.assertEqual(sp["payload_kb"], 16)
            self.assertGreater(sp["throughput_kbps"], 0)

    def test_i18n_a11y_focus_shortcuts(self):
        fx = self.fx
        self.assertIn("ru", fx.i18n_keys()["settings"])
        with tempfile.TemporaryDirectory() as d:
            a = Path(d) / "a11y.json"
            r = fx.a11y_prefs(a, fontScale=1.2, highContrast=True, reduceMotion=None)
            self.assertEqual(r["prefs"]["fontScale"], 1.2)
            self.assertNotIn("reduceMotion", r["prefs"])
            f = Path(d) / "focus.json"
            fx.focus_mode(f, True)
            self.assertTrue(fx.focus_active(f)["focus"])
            fx.focus_mode(f, False)
            self.assertFalse(fx.focus_active(f)["focus"])
            self.assertEqual(len(fx.shortcuts_help()["shortcuts"]), 5)


class TestBatch11(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test11", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def _proj(self, d):
        p = Path(d) / "proj"
        (p / "src").mkdir(parents=True)
        (p / "src" / "main.py").write_text("print(1)\n", encoding="utf-8")
        (p / ".pocketcode").mkdir()
        return p

    def test_breadcrumbs(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.breadcrumbs(p, "src/main.py")
            names = [c["name"] for c in r["crumbs"]]
            self.assertEqual(names, ["src", "main.py"])
            self.assertTrue(r["crumbs"][0]["is_dir"])
            self.assertFalse(r["crumbs"][1]["is_dir"])

    def test_bookmarks(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.bookmark_add(p, "src/main.py", line=12, note="починить")
            self.assertTrue(r["ok"])
            lst = fx.bookmark_list(p)
            self.assertEqual(len(lst), 1)
            self.assertEqual(lst[0]["line"], 12)
            bid = lst[0]["id"]
            self.assertTrue(fx.bookmark_remove(p, bid)["ok"])
            self.assertEqual(fx.bookmark_remove(p, bid).get("error"), "закладка не найдена")
            bad = fx.bookmark_add(p, "../etc/passwd")
            self.assertIn("error", bad)

    def test_recent_files(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            fx.recent_touch(p, "src/main.py", now=100.0)
            fx.recent_touch(p, "src/main.py", now=200.0)  # дубль не размножается
            r = fx.recent_files(p)
            self.assertEqual(len(r["files"]), 1)
            self.assertEqual(r["files"][0]["ts"], 200.0)
            self.assertIn("error", fx.recent_touch(p, "../../x"))

    def test_onboarding(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "onb.json"
            self.assertFalse(fx.onboarding_state(f)["complete"])
            r = fx.onboarding_mark(f, "key")
            self.assertEqual(r["left"], ["model", "project", "task", "test"])
            self.assertIn("error", fx.onboarding_mark(f, "нет такого"))
            for s in ("model", "project", "task", "test"):
                fx.onboarding_mark(f, s)
            self.assertTrue(fx.onboarding_state(f)["complete"])

    def test_expert_mode(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "exp.json"
            self.assertFalse(fx.expert_active(f)["expert"])
            fx.expert_mode(f, True)
            self.assertTrue(fx.expert_active(f)["expert"])

    def test_skill_library_and_editor(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            lib = fx.builtin_skills()
            self.assertEqual(len(lib["skills"]), 3)
            r = fx.skill_lib_install("test-writer", d)
            self.assertTrue(r["ok"])
            self.assertIn("error", fx.skill_lib_install("нету", d))
            # редактор: правка создаёт версию
            fx.skill_edit(d, "test-writer", "# v2\n\ntest: echo ok\n")
            vs = fx.skill_versions(d, "test-writer")
            self.assertEqual(len(vs["versions"]), 1)
            # тестовый прогон: команда из навыка выполняется в песочнице
            t = fx.skill_test(d, "test-writer", workdir=d)
            self.assertTrue(t["ok"], t)
            self.assertEqual(t["cmd"], "echo ok")
            # откат версии возвращает прежний текст
            rb = fx.skill_rollback(d, "test-writer", 1)
            self.assertTrue(rb["ok"])
            back = (Path(d) / "test-writer.md").read_text(encoding="utf-8")
            self.assertIn("test: python3 -m py_compile {file}", back)
            self.assertIn("error", fx.skill_test(d, "нету"))

    def test_template_gallery(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.template_publish(p, "Мой шаблон")
            self.assertTrue(r["ok"])
            items = fx.template_gallery_list(d)["items"]
            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["title"], "Мой шаблон")

    def test_app_changelog(self):
        fx = self.fx
        r = fx.app_changelog()
        self.assertEqual(r["source"], "builtin")
        self.assertEqual(r["changelog"][0]["version"], "0.11")

    def test_feedback(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            self.assertTrue(fx.feedback_add(d, "Добавьте тёмную тему", kind="idea")["ok"])
            self.assertIn("error", fx.feedback_add(d, "  "))
            items = fx.feedback_list(d)["items"]
            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["kind"], "idea")

    def test_pc_links(self):
        fx = self.fx
        r = fx.pc_link_make("task", name="fix bug", id=42)
        self.assertEqual(r["url"], "pc://task/fix-bug?id=42")
        parsed = fx.pc_link_parse(r["url"])
        self.assertEqual(parsed["kind"], "task")
        self.assertEqual(parsed["name"], "fix-bug")
        self.assertEqual(parsed["params"]["id"], "42")
        self.assertIn("error", fx.pc_link_make("нету"))
        self.assertIn("error", fx.pc_link_parse("https://example.com"))

    def test_low_power(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "lp.json"
            self.assertFalse(fx.low_power_active(f)["lowPower"])
            r = fx.low_power_mode(f, True)
            self.assertTrue(r["lowPower"])
            self.assertEqual(len(r["effects"]), 3)
            self.assertTrue(fx.low_power_active(f)["lowPower"])


class TestBatch12(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test12", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def _proj(self, d):
        p = Path(d) / "proj"
        p.mkdir(parents=True)
        (p / "src").mkdir()
        (p / "src" / "main.py").write_text("print(1)\n", encoding="utf-8")
        return p

    def test_snapshots(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            r = fx.snapshot_save(p, "src/main.py")
            self.assertTrue(r["ok"])
            lst = fx.snapshot_list(p, "src/main.py")["snapshots"]
            self.assertEqual(len(lst), 1)
            (p / "src" / "main.py").write_text("print(2)\n", encoding="utf-8")
            rs = fx.snapshot_restore(p, "src/main.py", lst[0]["file"])
            self.assertTrue(rs["ok"])
            self.assertEqual((p / "src" / "main.py").read_text(encoding="utf-8"), "print(1)\n")
            self.assertIn("error", fx.snapshot_restore(p, "src/main.py", "../x.bak"))

    def test_chat_search(self):
        fx = self.fx
        msgs = [{"text": "Починить баг"}, {"content": "Добавить Тесты"}, {"text": "ничего"}]
        r = fx.chat_search(msgs, "тест")
        self.assertEqual(r["count"], 1)
        self.assertEqual(r["hits"][0]["content"], "Добавить Тесты")
        self.assertIn("error", fx.chat_search(msgs, "  "))

    def test_line_notes(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            self.assertTrue(fx.line_note_add(p, "src/main.py", 1, "проверить")["ok"])
            notes = fx.line_note_list(p, "src/main.py")["notes"]
            self.assertEqual(len(notes), 1)
            self.assertEqual(notes[0]["line"], 1)
            self.assertIn("error", fx.line_note_add(p, "src/main.py", "abc", "x"))
            self.assertIn("error", fx.line_note_add(p, "../x", 1, "x"))

    def test_hotkey_check(self):
        fx = self.fx
        r = fx.hotkey_check({"run": "Ctrl+K"})
        self.assertEqual(len(r), 1)
        self.assertEqual(r[0]["actions"], ["run", "chat"])
        self.assertFalse(fx.hotkey_check({"build": "Ctrl+B"}))

    def test_telegram(self):
        fx = self.fx
        p = fx.tg_parse_update({"message": {"chat": {"id": 7}, "text": "/status"}})
        self.assertEqual(p["command"], "/status")
        self.assertEqual(p["chat_id"], 7)
        reply = fx.tg_format_reply(p, "demo")
        self.assertIn("demo", reply)
        self.assertIsNone(fx.tg_format_reply(fx.tg_parse_update({"foo": 1})))
        bad = fx.tg_parse_update({"message": {"chat": {"id": 7}, "text": "/wat"}})
        self.assertIn("/help", fx.tg_format_reply(bad))
        self.assertIn("error", fx.tg_parse_update("не словарь"))

    def test_mcp_handle(self):
        fx = self.fx
        tools = {"status": {"description": "статус", "call": lambda: {"ok": True}},
                 "echo": {"description": "эхо", "call": lambda x: {"x": x}}}
        init = fx.mcp_handle(tools, "initialize", request_id=1)
        self.assertEqual(init["result"]["protocolVersion"], fx.MCP_PROTOCOL)
        lst = fx.mcp_handle(tools, "tools/list", request_id=2)
        self.assertEqual([t["name"] for t in lst["result"]["tools"]], ["echo", "status"])
        call = fx.mcp_handle(tools, "tools/call", {"name": "echo", "arguments": {"x": 5}}, 3)
        self.assertEqual(json.loads(call["result"]["content"][0]["text"]), {"x": 5})
        self.assertEqual(fx.mcp_handle(tools, "tools/call", {"name": "нет"}, 4)["error"]["code"], -32602)
        self.assertEqual(fx.mcp_handle(tools, "нет")["error"]["code"], -32601)
        self.assertEqual(fx.mcp_handle(tools, "ping", request_id=9)["result"], {})

    def test_attachments(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            png = b"\x89PNG\r\n\x1a\n" + b"tail"
            r = fx.attachment_register(p, "скрин.png", png)
            self.assertTrue(r["ok"])
            self.assertEqual(r["mime"], "image/png")
            self.assertEqual(r["bytes"], len(png))
            items = fx.attachment_list(p)["items"]
            self.assertEqual(len(items), 1)
            self.assertIn("error", fx.attachment_register(p, "empty", b""))

    def test_usage(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "usage.json"
            fx.usage_incr(f, "chat")
            fx.usage_incr(f, "chat")
            fx.usage_incr(f, "run")
            top = fx.usage_top(f)["top"]
            self.assertEqual(top[0]["feature"], "chat")
            self.assertEqual(top[0]["count"], 2)
            self.assertIn("error", fx.usage_incr(f, ""))

    def test_reminders(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "rem.json"
            self.assertTrue(fx.reminder_add(f, "позвонить", 100.0)["ok"])
            fx.reminder_add(f, "позже", 200.0)
            r = fx.reminder_due(f, now=150.0)
            self.assertEqual(len(r["due"]), 1)
            self.assertEqual(r["due"][0]["text"], "позвонить")
            self.assertEqual(len(r["left"]), 1)
            self.assertIn("error", fx.reminder_add(f, "", 10.0))
            self.assertIn("error", fx.reminder_add(f, "x", "не время"))

    def test_export_notes_md(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            fx.bookmark_add(p, "src/main.py", line=1, note="важно")
            fx.line_note_add(p, "src/main.py", 1, "проверить")
            r = fx.export_notes_md(p)
            self.assertTrue(r["ok"])
            self.assertEqual(r["bookmarks"], 1)
            self.assertEqual(r["notes"], 1)
            body = (p / "notes-export.md").read_text(encoding="utf-8")
            self.assertIn("## Закладки", body)
            self.assertIn("src/main.py:1", body)

    def test_readme_link_check(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._proj(d)
            (p / "README.md").write_text(
                "сайт: https://127.0.0.1:1/nope и [x](https://127.0.0.1:2/never)\n",
                encoding="utf-8")
            r = fx.readme_link_check(p, max_checks=5)
            self.assertEqual(r["checked"], 2)
            self.assertEqual(len(r["broken"]), 2)
            empty = Path(d) / "empty_proj"
            empty.mkdir()  # проект без README
            self.assertIn("error", fx.readme_link_check(empty))

    def test_reading_time(self):
        fx = self.fx
        r = fx.reading_time(" ".join(["слово"] * 400))
        self.assertEqual(r["words"], 400)
        self.assertEqual(r["minutes"], 2.0)
        self.assertEqual(r["label"], "2 мин")
        short = fx.reading_time("одно слово")
        self.assertEqual(short["label"], "< 1 мин")


class TestBatch13(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test13", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    def test_versions(self):
        fx = self.fx
        self.assertEqual(fx.version_bump("1.2.3", "patch"), "1.2.4")
        self.assertEqual(fx.version_bump("1.2.3", "minor"), "1.3.0")
        self.assertEqual(fx.version_bump("1.2.3", "major"), "2.0.0")
        self.assertIsNone(fx.version_bump("1.2", "patch"))
        self.assertIsNone(fx.version_bump("1.2.3", "beta"))
        self.assertEqual(fx.semver_latest(["1.2.3", "1.10.0", "1.9.9"]), "1.10.0")
        self.assertIsNone(fx.semver_latest(["1.2.3", "два"]))
        self.assertIsNone(fx.semver_latest([]))

    def test_commit_check(self):
        fx = self.fx
        self.assertTrue(fx.commit_msg_check("feat(auth): вход по токену")["ok"])
        self.assertFalse(fx.commit_msg_check("починил что-то")["ok"])
        self.assertFalse(fx.commit_msg_check("feat: Тест.")["ok"])
        self.assertFalse(fx.commit_msg_check("wat: тема")["ok"])
        long = "fix: " + "х" * 80
        self.assertFalse(fx.commit_msg_check(long)["ok"])
        self.assertFalse(fx.commit_msg_check("   ")["ok"])

    def test_branch_name(self):
        fx = self.fx
        self.assertTrue(fx.branch_name_from("Починить баг входа")["branch"].startswith("fix/"))
        self.assertTrue(fx.branch_name_from("Документация по установке")["branch"].startswith("docs/"))
        self.assertTrue(fx.branch_name_from("Добавить тёмную тему")["branch"].startswith("feat/"))
        self.assertIn("error", fx.branch_name_from(""))

    def test_release_notes(self):
        fx = self.fx
        r = fx.release_notes(["feat: тема", "feat: виджет", "fix: краш", "мусор без типа"])
        kinds = {g["type"]: g for g in r["groups"]}
        self.assertEqual(kinds["feat"]["items"], ["тема", "виджет"])
        self.assertEqual(kinds["fix"]["items"], ["краш"])
        self.assertEqual(kinds["other"]["items"], ["мусор без типа"])
        self.assertEqual(fx.release_notes([])["groups"], [])

    def test_diff_stat(self):
        fx = self.fx
        diff = ("diff --git a/app.py b/app.py\n"
                "--- a/app.py\n+++ b/app.py\n"
                "+один\n+два\n-три\n"
                "diff --git a/lib/x.py b/lib/x.py\n"
                "--- a/lib/x.py\n+++ b/lib/x.py\n"
                "+четыре\n")
        r = fx.diff_stat(diff)
        self.assertEqual(r["files"], 2)
        self.assertEqual(r["added"], 3)
        self.assertEqual(r["removed"], 1)
        self.assertEqual(r["per_file"][1]["file"], "lib/x.py")
        self.assertIn("error", fx.diff_stat("ничего"))

    def test_license_detect(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "proj"
            p.mkdir()
            self.assertIn("error", fx.license_detect(p))
            (p / "LICENSE").write_text("MIT License\n\nPermission is hereby granted, free of charge...\n",
                                       encoding="utf-8")
            self.assertEqual(fx.license_detect(p)["license"], "MIT")
            (p / "LICENSE").write_text("Какой-то свой текст\n", encoding="utf-8")
            self.assertEqual(fx.license_detect(p)["license"], "unknown")

    def test_backlog_aging(self):
        fx = self.fx
        now = 1_000_000_000.0
        tasks = [{"id": "a", "created": now - 20 * 86400},
                 {"id": "b", "created": now - 2 * 86400}]
        r = fx.backlog_aging(tasks, now=now, stale_days=14)
        self.assertEqual(r["stale"], 1)
        self.assertTrue(r["items"][0]["stale"])
        self.assertFalse(r["items"][1]["stale"])
        self.assertIn("error", fx.backlog_aging([{"id": "x"}], now=now))
        self.assertIn("error", fx.backlog_aging(tasks, now=now, stale_days=0))

    def test_velocity(self):
        fx = self.fx
        r = fx.velocity_burn(remaining=7, done_per_day=2, done=3)
        self.assertEqual(r["days_left"], 4)
        self.assertAlmostEqual(r["percent"], 30.0)
        self.assertIn("error", fx.velocity_burn(5, 0))
        self.assertIn("error", fx.velocity_burn(-1, 2))
        self.assertIn("error", fx.velocity_burn("много", 2))

    def test_backup_rotate(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            old = Path(d) / "b_old.zip"
            new = Path(d) / "b_new.zip"
            old.write_text("o", encoding="utf-8")
            new.write_text("n", encoding="utf-8")
            os.utime(old, (1, 1))
            r = fx.backup_rotate(d, keep=1)
            self.assertTrue(r["ok"])
            self.assertEqual(r["kept"], 1)
            self.assertEqual(r["dropped"], ["b_old.zip"])
            self.assertFalse(old.exists())
            self.assertTrue(new.exists())
            self.assertIn("error", fx.backup_rotate(Path(d) / "нет", keep=1))


class TestBatch14(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "features_test14", Path(__file__).resolve().parent / "features.py")
        cls.fx = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fx)

    @staticmethod
    def _mkrepo(d):
        p = Path(d)
        (p / ".gitignore").write_text("*.log\n", encoding="utf-8")
        for args in (["init"], ["config", "user.email", "t@t"], ["config", "user.name", "t"]):
            subprocess.run(["git", *args], cwd=str(p), capture_output=True, check=True)
        return p

    @staticmethod
    def _commit(d, msg):
        subprocess.run(["git", "add", "-A"], cwd=str(d), capture_output=True, check=True)
        subprocess.run(["git", "commit", "-m", msg], cwd=str(d),
                       capture_output=True, check=True,
                       env={**os.environ, "GIT_AUTHOR_NAME": "t",
                            "GIT_COMMITTER_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                            "GIT_COMMITTER_EMAIL": "t@t"})

    def test_churn_hotspots(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._mkrepo(d)
            (p / "a.py").write_text("x = 1\n", encoding="utf-8")
            (p / "b.py").write_text("y = 2\n", encoding="utf-8")
            self._commit(p, "первый")
            (p / "a.py").write_text("x = 2\n", encoding="utf-8")
            self._commit(p, "второй")
            r = fx.churn_hotspots(p)
            self.assertTrue(r["hotspots"])
            self.assertEqual(r["hotspots"][0]["file"], "a.py")
            self.assertEqual(r["hotspots"][0]["touches"], 2)
            self.assertEqual(r["commits_files"], 4)
            self.assertIn("error", fx.churn_hotspots(Path(d) / "нет"))
            self.assertIn("error", fx.churn_hotspots(p, top=0))

    def test_deps_check(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            self.assertIn("error", fx.deps_check(p))
            (p / "requirements.txt").write_text(
                "flask\nrequests\nflask==2.0.0\nmy_pkg>=1\n# комментарий\n\n"
                "-r extras.txt\n", encoding="utf-8")
            r = fx.deps_check(p)
            self.assertFalse(r["ok"])
            self.assertEqual(r["duplicates"][0]["name"], "flask")
            self.assertEqual(r["duplicates"][0]["line"], 3)
            self.assertEqual(r["total"], 3)
            self.assertEqual(r["unpinned"], ["flask", "requests"])

    def test_gitignore_check(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / "__pycache__").mkdir()
            (p / "app.pyc").write_bytes(b"\x00")
            (p / ".env").write_text("K=v\n", encoding="utf-8")
            self.assertEqual(fx.gitignore_check(p)["missing"],
                             [".env", ".pyc", "__pycache__"])
            (p / ".gitignore").write_text("__pycache__\n*.pyc\n.env\n", encoding="utf-8")
            self.assertEqual(fx.gitignore_check(p)["missing"], [])
            self.assertIn("error", fx.gitignore_check(Path(d) / "нет"))

    def test_secret_scan_project(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / ".git").mkdir()
            (p / "cfg.py").write_text(
                'token = "ghp_' + "a" * 36 + '"\n', encoding="utf-8")
            (p / "__pycache__").mkdir()
            (p / "__pycache__" / "x.py").write_text('token = "ghp_' + "b" * 36 + '"\n',
                                                    encoding="utf-8")
            r = fx.secret_scan_project(p)
            self.assertEqual(r["scanned"], 1)
            self.assertEqual(len(r["findings"]), 1)
            self.assertEqual(r["findings"][0]["file"], "cfg.py")
            self.assertEqual(r["findings"][0]["line"], 1)
            self.assertNotIn("match", r["findings"][0])
            self.assertFalse(r["clean"])
            self.assertIn("error", fx.secret_scan_project(Path(d) / "нет"))

    def test_long_file_check(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / "короткий.py").write_text("1\n2\n3\n", encoding="utf-8")
            (p / "длинный.py").write_text("строка\n" * 6, encoding="utf-8")
            r = fx.long_file_check(p, max_lines=5)
            self.assertEqual(r["over"], 1)
            self.assertEqual(r["items"][0]["file"], "длинный.py")
            self.assertEqual(r["items"][0]["lines"], 6)
            self.assertIn("error", fx.long_file_check(p, max_lines=0))
            self.assertIn("error", fx.long_file_check(p, max_lines="много"))
            self.assertIn("error", fx.long_file_check(Path(d) / "нет"))

    def test_issue_from_crash(self):
        fx = self.fx
        r = fx.issue_from_crash("  Падение при старте  ", "шаг 1\nTraceback ...")
        self.assertEqual(r["title"], "Падение при старте")
        self.assertEqual(r["label"], "bug")
        self.assertIn("```", r["body"])
        self.assertIn("Traceback", r["body"])
        r2 = fx.issue_from_crash("Опечатка", "всё ок")
        self.assertEqual(r2["label"], "")
        r3 = fx.issue_from_crash("Краш", "лог", env={"os": "linux", "ver": "1.0"})
        self.assertIn("- os: linux", r3["body"])
        self.assertIn("- ver: 1.0", r3["body"])
        self.assertIn("error", fx.issue_from_crash("   ", "лог"))
        self.assertIn("error", fx.issue_from_crash("т", "лог", max_log_lines=0))

    def test_standup_report(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = self._mkrepo(d)
            (p / "a.py").write_text("x = 1\n", encoding="utf-8")
            self._commit(p, "fix: старт")
            r = fx.standup_report(p, days=7)
            self.assertEqual(r["count"], 1)
            self.assertEqual(r["authors"], ["t"])
            self.assertEqual(r["commits"][0]["subject"], "fix: старт")
            self.assertEqual(r["commits"][0]["hash"], subprocess.run(
                ["git", "rev-parse", "--short", "HEAD"], cwd=str(p),
                capture_output=True, text=True, check=True).stdout.strip())
            self.assertIn("error", fx.standup_report(p, days=0))
            self.assertIn("error", fx.standup_report(p, days="два"))
            self.assertIn("error", fx.standup_report(Path(d) / "нет"))

    def test_dup_detect(self):
        fx = self.fx
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / "a.txt").write_text("одно и то же\n", encoding="utf-8")
            (p / "b.txt").write_text("одно и то же\n", encoding="utf-8")
            (p / "c.txt").write_text("другое\n", encoding="utf-8")
            r = fx.dup_detect(p)
            self.assertEqual(r["groups"], 1)
            self.assertEqual(sorted(r["duplicates"][0]["files"]), ["a.txt", "b.txt"])
            self.assertEqual(r["wasted_bytes"], len("одно и то же\n".encode("utf-8")))
            self.assertEqual(fx.dup_detect(p, top=1)["duplicates"][:1], r["duplicates"][:1])
            self.assertIn("error", fx.dup_detect(Path(d) / "нет"))
            self.assertIn("error", fx.dup_detect(p, top="нет"))
