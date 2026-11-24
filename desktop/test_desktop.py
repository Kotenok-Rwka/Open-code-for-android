#!/usr/bin/env python3
"""Юнит-тесты PocketCode Desktop: только стандартная библиотека.

Запуск: python3 -m unittest discover -s desktop -p 'test_*.py'
"""

import importlib.util
import json
import os
import tempfile
import unittest
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
        try:
            import cryptography  # noqa: F401
        except ImportError:
            self.skipTest("cryptography не установлен")
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
