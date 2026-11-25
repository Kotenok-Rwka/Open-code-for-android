#!/usr/bin/env python3
"""PocketCode Desktop — нативное приложение на Tkinter, без браузера.

Тот же агент и те же возможности, что в Android-приложении, но интерфейс свой:
тема «Аврора», боковая навигация «Чат · Проект · Агент · Ещё», файлы, git,
терминал, модели и ключи, локальные модели, навыки и плагины, экономия токенов,
двусторонняя синхронизация с телефоном и другими устройствами.

Запуск: python3 pocketcode_app.py
Зависимостей нет, кроме стандартной библиотеки Python 3 (Tkinter входит в
установку python.org для Windows; в Debian/Ubuntu нужен пакет python3-tk).
"""

import json
import os
import shutil
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path

try:
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox, simpledialog
except ImportError:  # без Tkinter окно не построить — сообщим об этом в run()
    tk = None
    ttk = None
    filedialog = None
    messagebox = None
    simpledialog = None

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import pocketcode_desktop as core  # noqa: E402

BG = "#0a1120"
SIDEBAR = "#0d1729"
CARD = "#101c31"
CARD2 = "#16243c"
LINE = "#1e3050"
TEXT = "#e7eefc"
MUTED = "#8ea6c8"
DARK_TEXT = "#04121a"
FONT = "Segoe UI" if os.name == "nt" else "DejaVu Sans"
MONO = "Consolas" if os.name == "nt" else "DejaVu Sans Mono"

ACCENTS = {
    "teal": "#2dd4bf", "blue": "#60a5fa", "violet": "#a78bfa",
    "amber": "#fbbf24", "rose": "#fb7185", "green": "#4ade80",
}
ACCENT_NAMES = {
    "teal": "Бирюзовый", "blue": "Синий", "violet": "Фиолетовый",
    "amber": "Янтарный", "rose": "Розовый", "green": "Зелёный",
}

NAV = [
    ("Чат", [("chat", "Чат"), ("history", "История")]),
    ("Проект", [("files", "Файлы"), ("changes", "Правки и откат"), ("git", "Git"),
                ("terminal", "Терминал"), ("projects", "Проекты")]),
    ("Агент", [("models", "Модели и ключи"), ("local", "Локальные модели"),
               ("skills", "Навыки"), ("github", "Плагины с GitHub"),
               ("compare", "Сравнение моделей"), ("queue", "Очередь")]),
    ("Ещё", [("save", "Экономия"), ("usage", "Расход"), ("look", "Оформление"),
             ("sync", "Синхронизация"), ("backup", "Резервная копия"),
             ("diag", "Диагностика"), ("about", "О программе")]),
]

RUN_MODE = {"Агент": "agent", "Только ответ": "ask"}


class App:
    def __init__(self, root, server=None, host="0.0.0.0", port=8765):
        self.root = root
        self.server = server
        self.host = host
        self.port = port
        self.accent = ACCENTS.get(str(core.settings().get("accent") or "teal"), ACCENTS["teal"])
        self.section = "chat"
        self.pages = {}
        self.nav_buttons = {}
        self.reloads = {}
        self.job = None
        self.approved_id = None
        self.session_id = core.active_session_id()
        self.project = core.session_project(self.session_id)
        self.file_items = []
        self.skill_items = []
        self.gh_items = []
        self.gh_page = 1
        self.gh_query = ""
        self.catalog = list(core.LOCAL_CATALOG)
        self._steps_sig = ""
        self._chat_mtime = 0
        self._last_flush = 0.0
        self._last_sync_note = ""
        self.apply_style()
        self.build()
        self.show("chat")
        self.root.after(800, self.tick)

    # ---------- тема и общие элементы ----------

    def apply_style(self):
        st = ttk.Style(self.root)
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        self.root.option_add("*TCombobox*Listbox.background", CARD)
        self.root.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.root.option_add("*TCombobox*Listbox.selectBackground", self.accent)
        self.root.option_add("*TCombobox*Listbox.selectForeground", DARK_TEXT)
        st.configure(".", background=BG, foreground=TEXT, fieldbackground=CARD, font=(FONT, 10))
        st.configure("TFrame", background=BG)
        st.configure("TLabel", background=BG, foreground=TEXT, font=(FONT, 10))
        st.configure("TCheckbutton", background=BG, foreground=TEXT, font=(FONT, 10))
        st.map("TCheckbutton", background=[("active", BG)])
        st.configure("Treeview", background=CARD, fieldbackground=CARD, foreground=TEXT,
                     rowheight=26, borderwidth=0, font=(FONT, 10))
        st.map("Treeview", background=[("selected", self.accent)], foreground=[("selected", DARK_TEXT)])
        st.configure("Treeview.Heading", background=CARD2, foreground=MUTED, relief="flat",
                     font=(FONT, 9, "bold"))
        st.configure("TCombobox", fieldbackground=CARD, background=CARD, foreground=TEXT,
                     arrowcolor=MUTED, borderwidth=0)
        st.configure("TEntry", fieldbackground=CARD, foreground=TEXT, insertcolor=TEXT, borderwidth=0)
        st.configure("Horizontal.TProgressbar", background=self.accent, troughcolor=CARD2, borderwidth=0)
        st.configure("Vertical.TScrollbar", background=CARD2, troughcolor=BG, arrowcolor=MUTED,
                     borderwidth=0, relief="flat")

    def label(self, parent, text="", size=10, fg=TEXT, weight="normal", wrap=0, bg=None):
        try:
            bgc = bg or parent.cget("bg")
        except tk.TclError:
            bgc = bg or BG
        lbl = tk.Label(parent, text=text, font=(FONT, size, weight), fg=fg, bg=bgc,
                       justify="left", anchor="w")
        if wrap:
            lbl.configure(wraplength=wrap)
        return lbl

    def btn(self, parent, text, command, primary=False, small=False):
        fg = DARK_TEXT if primary else TEXT
        bg = self.accent if primary else CARD2
        b = tk.Button(parent, text=text, command=command, font=(FONT, 9 if small else 10),
                      bg=bg, fg=fg, activebackground=(self.accent if primary else LINE),
                      activeforeground=fg, relief="flat", bd=0, padx=10, pady=5,
                      cursor="hand2", highlightthickness=0)
        b.pack(side="left", padx=(0, 6), pady=2)
        return b

    def field(self, parent, value="", width=30, show=""):
        e = tk.Entry(parent, font=(FONT, 10), bg=CARD, fg=TEXT, insertbackground=TEXT,
                     relief="flat", bd=6, width=width, show=show, highlightthickness=1,
                     highlightbackground=LINE, highlightcolor=self.accent)
        e.insert(0, str(value or ""))
        e.pack(side="left", padx=(0, 6), pady=2)
        return e

    def tbox(self, parent, height=8, readonly=True):
        wrap = tk.Frame(parent, bg=BG)
        t = tk.Text(wrap, height=height, font=(MONO, 10), bg=CARD, fg=TEXT,
                    insertbackground=TEXT, relief="flat", bd=8, wrap="word", undo=True,
                    highlightthickness=1, highlightbackground=LINE, highlightcolor=self.accent)
        sb = ttk.Scrollbar(wrap, orient="vertical", command=t.yview)
        t.configure(yscrollcommand=sb.set)
        t.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        if readonly:
            t.configure(state="disabled")
        return wrap, t

    def table(self, parent, columns, height=10, selectmode="browse"):
        wrap = tk.Frame(parent, bg=BG)
        ids = ["c%d" % i for i in range(len(columns))]
        tv = ttk.Treeview(wrap, columns=ids, show="headings", height=height, selectmode=selectmode)
        for cid, (title, width) in zip(ids, columns):
            tv.heading(cid, text=title)
            tv.column(cid, width=width, anchor="w", stretch=True)
        sb = ttk.Scrollbar(wrap, orient="vertical", command=tv.yview)
        tv.configure(yscrollcommand=sb.set)
        tv.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        return wrap, tv

    def listbox(self, parent, height=10, multi=False):
        wrap = tk.Frame(parent, bg=BG)
        lb = tk.Listbox(wrap, height=height, bg=CARD, fg=TEXT, selectbackground=self.accent,
                        selectforeground=DARK_TEXT, relief="flat", bd=0, highlightthickness=1,
                        highlightbackground=LINE, activestyle="none", font=(FONT, 10),
                        exportselection=False,
                        selectmode=("extended" if multi else "browse"))
        sb = ttk.Scrollbar(wrap, orient="vertical", command=lb.yview)
        lb.configure(yscrollcommand=sb.set)
        lb.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        return wrap, lb

    def scroll_area(self, parent):
        wrap = tk.Frame(parent, bg=BG)
        canvas = tk.Canvas(wrap, bg=BG, highlightthickness=0, bd=0)
        bar = ttk.Scrollbar(wrap, orient="vertical", command=canvas.yview)
        inner = tk.Frame(canvas, bg=BG)
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=bar.set)
        canvas.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")

        def wheel(event):
            step = -1 if (getattr(event, "num", None) == 4 or getattr(event, "delta", 0) > 0) else 1
            x, y = canvas.winfo_pointerxy()
            left, top = canvas.winfo_rootx(), canvas.winfo_rooty()
            if left <= x <= left + canvas.winfo_width() and top <= y <= top + canvas.winfo_height():
                canvas.yview_scroll(step, "units")

        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            canvas.bind_all(seq, wheel, add="+")
        return wrap, inner, canvas

    def set_text(self, widget, text):
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", text)
        widget.configure(state="disabled")

    def get_text(self, widget):
        return widget.get("1.0", "end").strip()

    def set_status(self, text):
        self.status_var.set(text)

    def run_bg(self, fn, done=None):
        """Долгая работа — в фоне, результат — в потоке интерфейса."""
        def work():
            result, error = None, None
            try:
                result = fn()
            except Exception as e:  # noqa: BLE001 — показываем текст пользователю
                error = core.mask(e)
            self.root.after(0, lambda: self._bg_done(done, result, error))
        threading.Thread(target=work, daemon=True).start()

    def _bg_done(self, done, result, error):
        if error:
            self.set_status("Ошибка: " + str(error))
            messagebox.showerror("PocketCode", str(error))
        elif done:
            done(result)

    # ---------- каркас окна ----------

    def build(self):
        self.root.title("PocketCode Desktop %s — Аврора" % core.app_version())
        self.root.configure(bg=BG)
        self.root.option_add("*Font", (FONT, 10))

        shell = tk.Frame(self.root, bg=BG)
        shell.pack(fill="both", expand=True)

        side = tk.Frame(shell, bg=SIDEBAR, width=222)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)

        brand = tk.Frame(side, bg=SIDEBAR)
        brand.pack(fill="x", padx=16, pady=(18, 10))
        self.label(brand, "PocketCode", 15, TEXT, "bold").pack(anchor="w")
        self.label(brand, "Desktop · " + core.app_version(), 9, MUTED).pack(anchor="w")

        for group, items in NAV:
            self.label(side, group.upper(), 8, MUTED, "bold").pack(anchor="w", padx=18, pady=(12, 4))
            for key, title in items:
                b = tk.Button(side, text="  " + title, anchor="w", font=(FONT, 10),
                              bg=SIDEBAR, fg=TEXT, activebackground=CARD2, activeforeground=TEXT,
                              relief="flat", bd=0, padx=10, pady=6, cursor="hand2",
                              highlightthickness=0, command=lambda k=key: self.show(k))
                b.pack(fill="x", padx=10, pady=1)
                self.nav_buttons[key] = b

        self.label(side, "Синхронизация: " + ("вкл" if core.load_json(core.SYNC_CONFIG, {}).get("enabled") else "выкл"),
                   8, MUTED).pack(side="bottom", anchor="w", padx=18, pady=10)

        main = tk.Frame(shell, bg=BG)
        main.pack(side="left", fill="both", expand=True)

        head = tk.Frame(main, bg=BG)
        head.pack(fill="x", padx=18, pady=(14, 0))
        self.head_title = self.label(head, "Чат", 16, TEXT, "bold")
        self.head_title.pack(side="left")
        self.head_meta = self.label(head, "", 9, MUTED)
        self.head_meta.pack(side="left", padx=12)
        self.btn(head, "Обновить", self.refresh_current, small=True)

        self.content = tk.Frame(main, bg=BG)
        self.content.pack(fill="both", expand=True)

        bar = tk.Frame(main, bg=SIDEBAR, height=26)
        bar.pack(fill="x", side="bottom")
        bar.pack_propagate(False)
        self.status_var = tk.StringVar(value="Готово")
        tk.Label(bar, textvariable=self.status_var, bg=SIDEBAR, fg=MUTED, font=(FONT, 9),
                 anchor="w").pack(fill="x", padx=14, pady=4)

    def show(self, name):
        self.section = name
        for key, button in self.nav_buttons.items():
            active = key == name
            button.configure(bg=self.accent if active else SIDEBAR,
                             fg=DARK_TEXT if active else TEXT)
        titles = {k: t for _, items in NAV for k, t in items}
        self.head_title.configure(text=titles.get(name, name))
        if name not in self.pages:
            frame = tk.Frame(self.content, bg=BG)
            builder = getattr(self, "build_" + name, None)
            if builder is None:
                self.label(frame, "Раздел в разработке", 11, MUTED).pack(padx=18, pady=18)
            else:
                builder(frame)
            self.pages[name] = frame
        for frame in self.pages.values():
            frame.pack_forget()
        self.pages[name].pack(fill="both", expand=True, padx=18, pady=12)
        hook = self.reloads.get(name)
        if hook:
            try:
                hook()
            except Exception as e:  # noqa: BLE001
                self.set_status("Ошибка раздела: " + core.mask(e))

    def refresh_current(self):
        hook = self.reloads.get(self.section)
        if hook:
            hook()
            self.set_status("Обновлено")

    # ---------- чат ----------

    def build_chat(self, page):
        body = tk.Frame(page, bg=BG)
        body.pack(fill="both", expand=True)

        side = tk.Frame(body, bg=SIDEBAR, width=236)
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        self.label(side, "ЧАТЫ", 8, MUTED, "bold").pack(anchor="w", padx=14, pady=(10, 4))
        self.sessions_wrap, self.sessions_lb = self.listbox(side, height=20)
        self.sessions_wrap.pack(fill="both", expand=True, padx=8)
        self.sessions_lb.bind("<<ListboxSelect>>", lambda e: self.pick_session())
        row = tk.Frame(side, bg=SIDEBAR)
        row.pack(fill="x", padx=8, pady=8)
        self.btn(row, "+ Чат", self.new_chat, small=True)
        self.btn(row, "Имя", self.rename_chat, small=True)
        self.btn(row, "Удалить", self.delete_chat, small=True)

        right = tk.Frame(body, bg=BG)
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))

        top = tk.Frame(right, bg=BG)
        top.pack(fill="x")
        self.label(top, "Проект:", 9, MUTED).pack(side="left")
        self.chat_project = ttk.Combobox(top, state="readonly", width=22, font=(FONT, 9))
        self.chat_project.pack(side="left", padx=6)
        self.chat_project.bind("<<ComboboxSelected>>", lambda e: self.change_session_project())
        self.chat_provider = self.label(top, "", 9, MUTED)
        self.chat_provider.pack(side="left", padx=10)
        self.mode_var = tk.StringVar(value="Агент")
        self.mode_box = ttk.Combobox(top, state="readonly", width=13, textvariable=self.mode_var,
                                     values=list(RUN_MODE.keys()), font=(FONT, 9))
        self.mode_box.pack(side="right")

        self.chat_wrap, self.chat_inner, self.chat_canvas = self.scroll_area(right)
        self.chat_wrap.pack(fill="both", expand=True, pady=(8, 6))

        steps_head = tk.Frame(right, bg=BG)
        steps_head.pack(fill="x")
        self.label(steps_head, "Шаги агента", 9, MUTED, "bold").pack(side="left")
        self.steps_hint = self.label(steps_head, "пока пусто", 9, MUTED)
        self.steps_hint.pack(side="left", padx=8)
        steps_wrap, self.steps_box = self.tbox(right, height=6)
        steps_wrap.pack(fill="x")

        composer_box = tk.Frame(right, bg=BG)
        composer_box.pack(fill="x", pady=(8, 0))
        comp_wrap, self.composer = self.tbox(composer_box, height=3, readonly=False)
        comp_wrap.pack(fill="x")
        actions = tk.Frame(right, bg=BG)
        actions.pack(fill="x", pady=(6, 0))
        self.send_btn = self.btn(actions, "Отправить", self.send, primary=True)
        self.stop_btn = self.btn(actions, "Стоп", self.stop)
        self.stop_btn.configure(state="disabled")
        self.btn(actions, "Очистить чат", self.clear_chat)
        self.btn(actions, "Проверить обновления с устройств", self.reload_from_devices, small=True)
        self.label(actions, "Ctrl+Enter — отправить", 8, MUTED).pack(side="right")
        self.composer.bind("<Control-Return>", lambda e: (self.send(), "break")[1])

        self.reloads["chat"] = self.reload_chat

    def reload_chat(self):
        self.reload_sessions()
        self.render_chat()
        self.update_chat_header()

    def update_chat_header(self):
        names = [p["name"] for p in core.list_projects()]
        if self.project and self.project not in names:
            names.append(self.project)
        if hasattr(self, "chat_project"):
            self.chat_project.configure(values=names)
            self.chat_project.set(self.project)
        profile = core.active_profile() or {}
        text = (profile.get("model") or "провайдер не выбран") + " · " + (profile.get("base_url") or "")
        if hasattr(self, "chat_provider"):
            self.chat_provider.configure(text=text[:70])
        self.head_meta.configure(text="проект " + (self.project or "desktop"))

    def reload_sessions(self):
        if not hasattr(self, "sessions_lb"):
            return
        sessions = core.load_sessions()
        self.session_id = sessions["active"]
        self.project = core.session_project(self.session_id)
        self.sessions_lb.delete(0, "end")
        active_index = 0
        for i, s in enumerate(sessions["items"]):
            self.sessions_lb.insert("end", "  " + str(s.get("title") or "Чат")[:30])
            if s.get("id") == self.session_id:
                active_index = i
        if sessions["items"]:
            self.sessions_lb.selection_clear(0, "end")
            self.sessions_lb.selection_set(active_index)
            self.sessions_lb.see(active_index)

    def pick_session(self):
        if not hasattr(self, "sessions_lb"):
            return
        selection = self.sessions_lb.curselection()
        if not selection:
            return
        sessions = core.load_sessions()
        index = selection[0]
        if index >= len(sessions["items"]):
            return
        sid = sessions["items"][index]["id"]
        if sid == sessions["active"]:
            return
        sessions["active"] = sid
        core.save_json(core.SESSIONS, sessions)
        self.session_id = sid
        self.project = core.session_project(sid)
        self.render_chat()
        self.update_chat_header()

    def new_chat(self):
        item = core.new_session("Новый чат", self.project or "desktop")
        self.session_id = item["id"]
        self.reload_chat()
        self.set_status("Создан чат: " + item["title"])

    def rename_chat(self):
        if simpledialog is None:
            return
        sessions = core.load_sessions()
        current = next((s for s in sessions["items"] if s.get("id") == self.session_id), None)
        if current is None:
            return
        title = simpledialog.askstring("Переименовать чат", "Название:", initialvalue=current.get("title", ""))
        if not title:
            return
        current["title"] = title[:80]
        current["project"] = self.project or "desktop"
        core.save_json(core.SESSIONS, sessions)
        self.reload_chat()

    def delete_chat(self):
        if not messagebox.askyesno("Удалить чат", "История этого чата будет удалена. Продолжить?"):
            return
        core.delete_session(self.session_id)
        self.reload_chat()
        self.set_status("Чат удалён")

    def change_session_project(self):
        name = self.chat_project.get().strip()
        if not name:
            return
        core.create_project(name)
        sessions = core.load_sessions()
        for s in sessions["items"]:
            if s.get("id") == self.session_id:
                s["project"] = name
        core.save_json(core.SESSIONS, sessions)
        self.project = name
        self.update_chat_header()
        self.set_status("Проект чата: " + name)

    def render_chat(self):
        if not hasattr(self, "chat_inner"):
            return
        for child in self.chat_inner.winfo_children():
            child.destroy()
        messages = core.messages_for(self.session_id)
        if not messages:
            hint = ("Начни с задачи: «сделай лендинг в теме Аврора», «найди баг в main.py», "
                    "«поищи плагины для современных сайтов».")
            tk.Label(self.chat_inner, text=hint, bg=BG, fg=MUTED, font=(FONT, 10),
                     wraplength=620, justify="left").pack(anchor="w", padx=14, pady=24)
        for m in messages[-300:]:
            self.bubble(str(m.get("role") or "assistant"), str(m.get("text") or ""))
        self.chat_inner.update_idletasks()
        self.chat_canvas.configure(scrollregion=self.chat_canvas.bbox("all"))
        self.chat_canvas.yview_moveto(1.0)
        try:
            self._chat_mtime = core.CHAT.stat().st_mtime_ns
        except OSError:
            self._chat_mtime = 0

    def bubble(self, role, text):
        is_user = role == "user"
        outer = tk.Frame(self.chat_inner, bg=BG)
        outer.pack(fill="x", pady=4, padx=6)
        width = max(360, self.chat_canvas.winfo_width() - 120)
        card = tk.Frame(outer, bg=CARD2 if is_user else CARD, highlightthickness=1,
                        highlightbackground=self.accent if is_user else LINE)
        card.pack(anchor="e" if is_user else "w", fill="x",
                  padx=(140, 0) if is_user else (0, 140))
        tk.Label(card, text="Вы" if is_user else "PocketCode", font=(FONT, 8, "bold"),
                 fg=self.accent if is_user else MUTED, bg=card["bg"], anchor="w").pack(
            fill="x", padx=12, pady=(8, 0))
        tk.Label(card, text=text[:20000], font=(FONT, 10), fg=TEXT, bg=card["bg"],
                 justify="left", anchor="w", wraplength=width).pack(fill="x", padx=12, pady=(2, 10))

    def send(self):
        text = self.get_text(self.composer)
        if not text:
            return
        if self.job:
            self.set_status("Дождись завершения текущей задачи или нажми «Стоп»")
            return
        mode = RUN_MODE.get(self.mode_var.get(), "agent")
        try:
            job = core.start_run(self.session_id, text, mode)
        except ValueError as e:
            messagebox.showwarning("PocketCode", core.mask(e))
            return
        self.job = job
        self.composer.delete("1.0", "end")
        self.set_text(self.steps_box, "")
        self._steps_sig = ""
        self.steps_hint.configure(text="выполняется…")
        self.send_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.set_status("Агент работает…")
        self.render_chat()
        self.root.after(300, self.poll_run)

    def stop(self):
        if not self.job:
            return
        run = core.RUNS.get(self.job)
        if run:
            run["cancel"].set()
            run["approve_result"] = False
            run["approve"].set()
        self.set_status("Останавливаю задачу…")

    def poll_run(self):
        if not self.job:
            return
        state = core.run_state(self.job)
        if not state or state.get("error"):
            self.job = None
            self.send_btn.configure(state="normal")
            self.stop_btn.configure(state="disabled")
            self.reload_chat()
            return
        self.render_steps(state.get("events") or [])
        plan = state.get("plan") or []
        if plan:
            self.steps_hint.configure(text="план: %d задач" % len(plan))
        pending = state.get("pending")
        if pending and pending.get("id") != self.approved_id:
            self.approved_id = pending.get("id")
            ok = messagebox.askyesno(
                "Опасная команда",
                "Агент хочет выполнить:\n\n%s\n\nРазрешить?" % str(pending.get("command") or "")[:800])
            run = core.RUNS.get(self.job)
            if run:
                run["approve_result"] = bool(ok)
                run["approve"].set()
        if state.get("status") != "running":
            self.job = None
            self.approved_id = None
            self.send_btn.configure(state="normal")
            self.stop_btn.configure(state="disabled")
            note = {"done": "Готово", "cancelled": "Остановлено", "error": "Ошибка"}.get(
                state.get("status"), str(state.get("status")))
            if state.get("error"):
                note += ": " + str(state["error"])
            self.steps_hint.configure(text="завершено")
            self.set_status(note)
            self.reload_chat()
            return
        self.root.after(400, self.poll_run)

    def render_steps(self, events):
        lines = []
        for e in events[-80:]:
            kind = e.get("kind")
            if kind == "thought":
                lines.append("· " + str(e.get("text") or "")[:400])
            elif kind == "tool_start":
                lines.append("→ %s %s" % (e.get("name"), str(e.get("args") or "")[:220]))
            elif kind == "tool":
                lines.append("✓ %s: %s" % (e.get("name"), str(e.get("text") or "")[:300]))
            elif kind == "tool_cached":
                lines.append("↻ %s — из кеша" % e.get("name"))
            elif kind == "file":
                lines.append("✎ %s — %s" % (e.get("path"), e.get("text")))
            elif kind == "bash":
                lines.append("$ %s  (код %s)" % (e.get("command"), e.get("code")))
        text = "\n".join(lines) or "Агент думает…"
        if text != self._steps_sig:
            self._steps_sig = text
            self.set_text(self.steps_box, text[-12000:])
            self.steps_box.see("end")

    def clear_chat(self):
        if not messagebox.askyesno("Очистить чат", "Удалить сообщения этого чата?"):
            return
        keep = [m for m in core.read_jsonl(core.CHAT)
                if str(m.get("session") or "default") != str(self.session_id)]
        core.CHAT.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in keep),
                             encoding="utf-8")
        self.render_chat()

    def reload_from_devices(self):
        self.reload_chat()
        state = core.load_json(core.SYNC_STATE, {})
        self.set_status("Чаты обновлены. Входящих событий: %s, последний seq: %s"
                        % (state.get("events", 0), state.get("last_seq", 0)))

    # ---------- история ----------

    def build_history(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.history_info = self.label(top, "", 9, MUTED)
        self.history_info.pack(side="left")
        self.btn(top, "Обновить", self.reload_history)
        wrap, self.history_tv = self.table(page, [("Когда", 140), ("Сессия", 150),
                                                  ("Роль", 90), ("Текст", 620)], height=24)
        wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["history"] = self.reload_history

    def reload_history(self):
        all_msgs = core.read_jsonl(core.CHAT)
        titles = {s.get("id"): s.get("title") for s in core.load_sessions()["items"]}
        self.history_tv.delete(*self.history_tv.get_children())
        for m in all_msgs[-500:]:
            ts = int(m.get("ts") or 0) / 1000
            when = time.strftime("%d.%m %H:%M", time.localtime(ts)) if ts else "—"
            role = "Вы" if m.get("role") == "user" else "Агент"
            text = " ".join(str(m.get("text") or "").split())[:200]
            self.history_tv.insert("", "end", values=(when, titles.get(m.get("session"), "—"), role, text))
        self.history_info.configure(text="Сообщений всего: %d" % len(all_msgs))

    # ---------- файлы ----------

    def build_files(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.files_info = self.label(top, "", 9, MUTED)
        self.files_info.pack(side="left")
        self.btn(top, "Обновить", self.reload_files)
        self.btn(top, "Снимок проекта", lambda: self.run_bg(
            lambda: core.snapshot_project(self.project),
            lambda stamp: (self.set_status("Снимок: " + str(stamp)), self.reloads["changes"]() if "changes" in self.pages else None)))
        self.files_search = self.field(top, "", 22)
        self.files_search.bind("<KeyRelease>", lambda e: self.reload_files())
        self.label(top, "поиск по имени", 8, MUTED).pack(side="left")

        body = tk.Frame(page, bg=BG)
        body.pack(fill="both", expand=True, pady=(8, 0))
        left = tk.Frame(body, bg=BG, width=300)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        self.files_wrap, self.files_lb = self.listbox(left, height=24)
        self.files_wrap.pack(fill="both", expand=True)
        self.files_lb.bind("<<ListboxSelect>>", lambda e: self.open_file())

        right = tk.Frame(body, bg=BG)
        right.pack(side="left", fill="both", expand=True, padx=(12, 0))
        row = tk.Frame(right, bg=BG)
        row.pack(fill="x")
        self.file_path = self.field(row, "", 42)
        self.btn(row, "Сохранить", self.save_file, primary=True)
        self.btn(row, "Удалить", self.delete_file)
        self.btn(row, "Новый файл", self.new_file)
        self.editor_wrap, self.editor = self.tbox(right, height=26, readonly=False)
        self.editor_wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["files"] = self.reload_files

    def project_files(self):
        root = core.project_root(self.project)
        if root is None or not root.is_dir():
            return root, []
        out = []
        for p in sorted(root.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            if any(x in core.SKIP_DIRS for x in rel.split("/")[:-1]) or core.SKIP_FILE.match(p.name):
                continue
            out.append(rel)
            if len(out) >= core.MAX_FILES:
                break
        return root, out

    def reload_files(self):
        if not hasattr(self, "files_lb"):
            return
        root, items = self.project_files()
        needle = self.files_search.get().strip().lower() if hasattr(self, "files_search") else ""
        if needle:
            items = [x for x in items if needle in x.lower()]
        self.file_items = items
        self.files_lb.delete(0, "end")
        for item in items:
            self.files_lb.insert("end", "  " + item)
        self.files_info.configure(text="Проект: %s · файлов: %d%s" % (
            self.project or "desktop", len(items), "" if root else " (папка не найдена)"))

    def open_file(self):
        selection = self.files_lb.curselection()
        if not selection:
            return
        rel = self.file_items[selection[0]]
        root = core.project_root(self.project)
        path = core.safe_path(root, rel) if root else None
        if path is None or not path.is_file():
            self.set_status("Файл не найден")
            return
        if path.stat().st_size > core.MAX_FILE:
            self.set_status("Файл слишком большой для редактора")
            return
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            self.set_status("Это бинарный файл — редактор не откроет")
            return
        self.file_path.delete(0, "end")
        self.file_path.insert(0, rel)
        self.editor.delete("1.0", "end")
        self.editor.insert("1.0", text)
        self.set_status("Открыт " + rel)

    def save_file(self):
        rel = self.file_path.get().strip()
        root = core.project_root(self.project)
        path = core.safe_path(root, rel) if root else None
        if path is None or not rel:
            messagebox.showwarning("PocketCode", "Недопустимый путь")
            return
        text = self.editor.get("1.0", "end-1c")
        if len(text.encode("utf-8")) > core.MAX_FILE:
            messagebox.showwarning("PocketCode", "Файл слишком большой")
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        core.send_later("file", {"path": path.relative_to(root).as_posix(), "text": text})
        self.set_status("Сохранено: " + rel)
        self.reload_files()

    def delete_file(self):
        rel = self.file_path.get().strip()
        if not rel or not messagebox.askyesno("Удалить файл", "Удалить %s?" % rel):
            return
        root = core.project_root(self.project)
        path = core.safe_path(root, rel) if root else None
        if path and path.is_file():
            path.unlink()
        self.editor.delete("1.0", "end")
        self.file_path.delete(0, "end")
        self.reload_files()

    def new_file(self):
        if simpledialog is None:
            return
        rel = simpledialog.askstring("Новый файл", "Путь внутри проекта, например src/main.py:")
        if not rel:
            return
        root = core.project_root(self.project)
        path = core.safe_path(root, rel) if root else None
        if path is None:
            messagebox.showwarning("PocketCode", "Недопустимый путь")
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("", encoding="utf-8")
        self.file_path.delete(0, "end")
        self.file_path.insert(0, rel)
        self.editor.delete("1.0", "end")
        self.reload_files()

    # ---------- правки и откат ----------

    def build_changes(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.changes_info = self.label(top, "", 9, MUTED)
        self.changes_info.pack(side="left")
        self.btn(top, "Изменения", self.show_changes)
        self.btn(top, "Вернуть снимок", self.restore_changes, primary=True)
        self.btn(top, "Снимок сейчас", lambda: self.run_bg(
            lambda: core.snapshot_project(self.project),
            lambda s: (self.set_status("Снимок: " + str(s)), self.reload_changes())))
        wrap, self.snaps_tv = self.table(page, [("Снимок", 320), ("Файлов", 100)], height=10)
        wrap.pack(fill="x", pady=(8, 0))
        diff_wrap, self.diff_box = self.tbox(page, height=18)
        diff_wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["changes"] = self.reload_changes

    def reload_changes(self):
        snaps = core.list_snapshots(self.project)
        self.snaps_tv.delete(*self.snaps_tv.get_children())
        for s in snaps:
            self.snaps_tv.insert("", "end", values=(s["stamp"], s["files"]))
        self.changes_info.configure(text="Проект: %s · снимков: %d" % (self.project, len(snaps)))

    def selected_snapshot(self):
        sel = self.snaps_tv.selection()
        if not sel:
            return ""
        return str(self.snaps_tv.item(sel[0], "values")[0])

    def show_changes(self):
        stamp = self.selected_snapshot()
        if not stamp:
            self.set_status("Выбери снимок в списке")
            return
        changes = core.snapshot_changes(self.project, stamp)
        if not changes:
            self.set_text(self.diff_box, "Изменений относительно снимка нет.")
            return
        parts = []
        for c in changes:
            parts.append("=== %s (%s)\n%s" % (c["path"], c["status"], c.get("diff") or ""))
        self.set_text(self.diff_box, "\n".join(parts)[:40000])
        self.set_status("Изменённых файлов: %d" % len(changes))

    def restore_changes(self):
        stamp = self.selected_snapshot()
        if not stamp:
            self.set_status("Выбери снимок в списке")
            return
        if not messagebox.askyesno("Вернуть снимок", "Файлы проекта вернутся к снимку %s. Продолжить?" % stamp):
            return
        count = core.restore_snapshot(self.project, stamp)
        self.set_status("Восстановлено файлов: %d" % count)
        self.reload_files()
        self.show_changes()

    # ---------- git ----------

    def build_git(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        for title, action in (("Статус", "status"), ("Лог", "log"), ("Diff", "diff"),
                              ("Ветки", "branches"), ("Pull", "pull")):
            self.btn(top, title, lambda a=action: self.git_run(a))
        self.git_info = self.label(top, "", 9, MUTED)
        self.git_info.pack(side="left", padx=6)

        commit_row = tk.Frame(page, bg=BG)
        commit_row.pack(fill="x", pady=(8, 0))
        self.commit_msg = self.field(commit_row, "", 44)
        self.btn(commit_row, "Коммит", lambda: self.git_run("commit", {"message": self.commit_msg.get()}),
                 primary=True)

        branch_row = tk.Frame(page, bg=BG)
        branch_row.pack(fill="x", pady=(6, 0))
        self.branch_name = self.field(branch_row, "", 24)
        self.btn(branch_row, "Переключить ветку",
                 lambda: self.git_run("checkout", {"branch": self.branch_name.get()}))

        clone_row = tk.Frame(page, bg=BG)
        clone_row.pack(fill="x", pady=(6, 0))
        self.clone_url = self.field(clone_row, "https://github.com/owner/name", 44)
        self.btn(clone_row, "Клонировать", self.git_clone)

        out_wrap, self.git_box = self.tbox(page, height=20)
        out_wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["git"] = self.git_status

    def git_status(self):
        if not hasattr(self, "git_box"):
            return
        self.git_info.configure(text="Проект: %s" % self.project)

    def git_run(self, action, body=None):
        root = core.project_root(self.project)

        def work():
            return core.git_action(root, action, body or {})

        def done(res):
            text = res.get("text") if isinstance(res, dict) else str(res)
            error = res.get("error") if isinstance(res, dict) else ""
            self.set_text(self.git_box, (("Ошибка: " + error + "\n") if error else "") + (text or ""))
            self.set_status("git " + action + (": ошибка" if error else ": готово"))

        self.run_bg(work, done)

    def git_clone(self):
        url = self.clone_url.get().strip()
        name = Path(url.rstrip("/")).stem
        root = core.safe_path(core.PROJECTS, core.safe_name(name))
        if not url.startswith("https://github.com/") or root is None:
            messagebox.showwarning("PocketCode", "Нужен адрес https://github.com/owner/name")
            return
        if root.exists():
            messagebox.showwarning("PocketCode", "Проект с таким именем уже есть")
            return

        def work():
            proc = subprocess.run(["git", "clone", "--depth", "1", url, str(root)],
                                  capture_output=True, text=True, timeout=300,
                                  encoding="utf-8", errors="replace")
            return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

        def done(res):
            code, text = res
            self.set_text(self.git_box, text)
            if code == 0:
                self.set_status("Клонировано: " + root.name)
                self.project = root.name
                self.reloads["git"]()
            else:
                self.set_status("git clone не удался")

        self.run_bg(work, done)

    # ---------- терминал ----------

    def build_terminal(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.term_info = self.label(top, "", 9, MUTED)
        self.term_info.pack(side="left")
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(8, 0))
        self.term_cmd = self.field(row, "", 60)
        self.btn(row, "Выполнить", self.term_run, primary=True)
        self.term_cmd.bind("<Return>", lambda e: self.term_run())
        out_wrap, self.term_box = self.tbox(page, height=24)
        out_wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["terminal"] = lambda: self.term_info.configure(
            text="Команды выполняются в папке проекта: " + str(core.project_root(self.project)))

    def term_run(self):
        cmd = self.term_cmd.get().strip()
        root = core.project_root(self.project)
        if not cmd or root is None:
            return
        if core.DANGER.search(cmd) and not core.settings().get("autoApprove"):
            if not messagebox.askyesno("Опасная команда", "Выполнить?\n\n" + cmd):
                return

        def work():
            proc = subprocess.run(cmd, shell=True, cwd=str(root), capture_output=True, text=True,
                                  timeout=180, encoding="utf-8", errors="replace")
            return proc.returncode, (proc.stdout or "") + (("\nSTDERR:\n" + proc.stderr) if proc.stderr else "")

        def done(res):
            code, out = res
            self.set_text(self.term_box, "$ %s\n\n%s\n\nКод возврата: %d" % (cmd, out, code))
            self.set_status("Код возврата: %d" % code)
            self.reload_files()

        self.run_bg(work, done)
        self.set_status("Выполняю команду…")
    # ---------- проекты ----------

    def build_projects(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.projects_info = self.label(top, "", 9, MUTED)
        self.projects_info.pack(side="left")
        self.btn(top, "Обновить", self.reload_projects)
        wrap, self.projects_lb = self.listbox(page, height=16)
        wrap.pack(fill="both", expand=True, pady=(8, 0))
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(8, 0))
        self.new_project_name = self.field(row, "", 26)
        self.btn(row, "Создать проект", self.create_project, primary=True)
        self.btn(row, "Подключить папку", self.connect_project)
        self.btn(row, "Удалить проект", self.delete_project)
        self.btn(row, "Сделать текущим", self.use_project)
        self.reloads["projects"] = self.reload_projects

    def reload_projects(self):
        names = [p["name"] for p in core.list_projects()]
        self.projects_lb.delete(0, "end")
        for n in names:
            self.projects_lb.insert("end", "  " + n + ("   ← текущий" if n == self.project else ""))
        self.projects_info.configure(text="Проектов: %d · данные: %s" % (len(names), core.HOME))

    def selected_project(self):
        sel = self.projects_lb.curselection()
        if not sel:
            return ""
        return self.projects_lb.get(sel[0]).replace("← текущий", "").strip()

    def create_project(self):
        name = self.new_project_name.get().strip()
        if not name:
            return
        root = core.create_project(name)
        if root is None:
            messagebox.showwarning("PocketCode", "Недопустимое имя проекта")
            return
        self.project = root.name
        self.new_project_name.delete(0, "end")
        self.reload_projects()
        self.set_status("Проект создан: " + root.name)

    def connect_project(self):
        directory = filedialog.askdirectory(title="Папка проекта")
        if not directory:
            return
        root = core.open_project(Path(directory).name, directory)
        if root is None:
            messagebox.showwarning("PocketCode", "Папка не найдена")
            return
        self.project = root.name
        self.reload_projects()
        self.set_status("Подключена папка: " + str(root))

    def delete_project(self):
        name = self.selected_project()
        if not name or not messagebox.askyesno("Удалить проект", "Удалить проект %s?" % name):
            return
        root = core.project_root(name)
        if root and root.parent == core.PROJECTS.resolve() and root.is_dir():
            shutil.rmtree(root, ignore_errors=True)
        data = core.load_json(core.PROJECTS_MAP, {})
        data.pop(name, None)
        core.save_json(core.PROJECTS_MAP, data)
        if self.project == name:
            self.project = "desktop"
        self.reload_projects()

    def use_project(self):
        name = self.selected_project()
        if not name:
            return
        self.project = name
        sessions = core.load_sessions()
        for s in sessions["items"]:
            if s.get("id") == self.session_id:
                s["project"] = name
        core.save_json(core.SESSIONS, sessions)
        self.reload_projects()
        self.update_chat_header()
        self.set_status("Текущий проект: " + name)

    # ---------- модели и ключи ----------

    def build_models(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.models_info = self.label(top, "", 9, MUTED)
        self.models_info.pack(side="left")
        self.btn(top, "Использовать", self.use_profile, primary=True)
        self.btn(top, "Удалить", self.delete_profile)
        wrap, self.models_tv = self.table(page, [("Название", 160), ("Модель", 220),
                                                 ("Адрес", 320), ("Ключ", 90)], height=9)
        wrap.pack(fill="x", pady=(8, 0))

        self.label(page, "Провайдер (OpenAI-совместимый)", 10, TEXT, "bold").pack(anchor="w", pady=(10, 2))
        form = tk.Frame(page, bg=BG)
        form.pack(fill="x")
        self.label(form, "Имя", 9, MUTED).pack(side="left")
        self.prof_name = self.field(form, "", 14)
        self.label(form, "Адрес", 9, MUTED).pack(side="left")
        self.prof_url = self.field(form, "", 34)
        self.label(form, "Модель", 9, MUTED).pack(side="left")
        self.prof_model = self.field(form, "", 20)

        form2 = tk.Frame(page, bg=BG)
        form2.pack(fill="x", pady=(4, 0))
        self.label(form2, "Ключ", 9, MUTED).pack(side="left")
        self.prof_key = self.field(form2, "", 34, show="•")
        self.label(form2, "Макс. токенов", 9, MUTED).pack(side="left")
        self.prof_maxtok = self.field(form2, "0", 8)
        self.btn(form2, "Сохранить провайдера", self.save_profile, primary=True)

        hints = ("OpenAI: https://api.openai.com/v1 · OpenRouter: https://openrouter.ai/api/v1 · "
                 "Groq: https://api.groq.com/openai/v1 · DeepSeek: https://api.deepseek.com/v1 · "
                 "Gemini: https://generativelanguage.googleapis.com/v1beta/openai · "
                 "LM Studio: http://127.0.0.1:1234/v1 · Ollama: http://127.0.0.1:11434/v1")
        self.label(page, hints, 9, MUTED, wrap=900).pack(anchor="w", pady=(8, 0))
        self.reloads["models"] = self.reload_models

    def reload_models(self):
        profiles = core.public_profiles()
        active = profiles.pop("_active", "")
        self.models_tv.delete(*self.models_tv.get_children())
        for name, p in profiles.items():
            values = (name + ("  ← выбран" if name == active else ""), p.get("model", ""),
                      p.get("base_url", ""), "сохранён" if p.get("has_key") else "—")
            self.models_tv.insert("", "end", values=values)
        self.models_info.configure(text="Провайдеров: %d · активный: %s" % (len(profiles), active or "нет"))

    def selected_profile(self):
        sel = self.models_tv.selection()
        if not sel:
            return ""
        return str(self.models_tv.item(sel[0], "values")[0]).replace("  ← выбран", "").strip()

    def save_profile(self):
        name = self.prof_name.get().strip()
        base = self.prof_url.get().strip()
        model = self.prof_model.get().strip()
        if not name or not base or not model:
            messagebox.showwarning("PocketCode", "Нужны имя, адрес сервера и модель")
            return
        if not base.startswith(("http://", "https://")):
            messagebox.showwarning("PocketCode", "Адрес должен начинаться с http:// или https://")
            return
        profiles = core.load_json(core.PROFILES, {})
        old = profiles.get(name, {}) if isinstance(profiles.get(name), dict) else {}
        key = self.prof_key.get().strip()
        try:
            maxtok = int(self.prof_maxtok.get().strip() or 0)
        except ValueError:
            maxtok = 0
        profiles[name] = {
            "base_url": base, "model": model,
            "api_key": key if key else old.get("api_key", ""),
            "max_tokens": maxtok,
            "temperature": float(old.get("temperature", 0.4)),
        }
        profiles.setdefault("_active", name)
        core.save_json(core.PROFILES, profiles)
        self.prof_key.delete(0, "end")
        self.reload_models()
        self.update_chat_header()
        self.set_status("Провайдер сохранён: " + name)

    def use_profile(self):
        name = self.selected_profile() or self.prof_name.get().strip()
        profiles = core.load_json(core.PROFILES, {})
        if not name or not isinstance(profiles.get(name), dict):
            messagebox.showwarning("PocketCode", "Сначала сохрани провайдера")
            return
        profiles["_active"] = name
        core.save_json(core.PROFILES, profiles)
        self.reload_models()
        self.update_chat_header()
        self.set_status("Выбран провайдер: " + name)

    def delete_profile(self):
        name = self.selected_profile()
        if not name or not messagebox.askyesno("Удалить провайдера", "Удалить %s?" % name):
            return
        profiles = core.load_json(core.PROFILES, {})
        profiles.pop(name, None)
        if profiles.get("_active") == name:
            profiles["_active"] = next((k for k in profiles if k != "_active"), "")
        core.save_json(core.PROFILES, profiles)
        self.reload_models()
        self.update_chat_header()

    # ---------- локальные модели ----------

    def build_local(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.local_info = self.label(top, "", 9, MUTED)
        self.local_info.pack(side="left")
        self.btn(top, "Скачать", self.local_download, primary=True)
        self.btn(top, "Отмена", self.local_cancel)
        self.btn(top, "Использовать", self.local_use)
        self.btn(top, "Удалить", self.local_delete)
        self.btn(top, "Сервер: старт", lambda: self.local_server("start"))
        self.btn(top, "Сервер: стоп", lambda: self.local_server("stop"))

        wrap, self.local_tv = self.table(page, [("Модель", 340), ("Параметры", 110),
                                                ("ОЗУ, ГБ", 90), ("Описание", 420)], height=8)
        wrap.pack(fill="x", pady=(8, 0))
        self.label(page, "Скачанные модели", 10, TEXT, "bold").pack(anchor="w", pady=(10, 2))
        wrap2, self.local_dl_tv = self.table(page, [("Файл", 420), ("Размер, МБ", 120)], height=5)
        wrap2.pack(fill="x")
        self.local_bar = ttk.Progressbar(page, mode="determinate", length=100)
        self.local_bar.pack(fill="x", pady=(8, 0))
        dl_wrap, self.local_dl_box = self.tbox(page, height=4)
        dl_wrap.pack(fill="x", pady=(6, 0))
        log_wrap, self.local_log = self.tbox(page, height=7)
        log_wrap.pack(fill="both", expand=True, pady=(6, 0))
        self.reloads["local"] = self.reload_local

    def reload_local(self):
        self.local_tv.delete(*self.local_tv.get_children())
        self.catalog_rows = {}
        for index, item in enumerate(self.catalog):
            iid = "cat%d" % index
            self.catalog_rows[iid] = item
            self.local_tv.insert("", "end", iid=iid, values=(
                item.get("file", ""), "%sB · ctx %sK" % (item.get("params_b"), item.get("context_k")),
                item.get("min_ram_gb"), item.get("note", "")))
        self.local_dl_tv.delete(*self.local_dl_tv.get_children())
        downloaded = core.downloaded_models()
        for m in downloaded:
            self.local_dl_tv.insert("", "end", values=(m["name"], round(m["size"] / (1024 * 1024))))
        server = core.MODEL_PROC is not None and core.MODEL_PROC.poll() is None
        self.local_info.configure(text="Скачано моделей: %d · llama-server: %s · папка: %s" % (
            len(downloaded), "запущен" if server else "остановлен", core.DOWNLOADS_DIR))
        self.set_text(self.local_log, core.model_server_log() or "Лог llama-server пуст.")
        self.tick_local()

    def tick_local(self):
        if not hasattr(self, "local_bar") or self.section != "local":
            return
        with core.LOCK:
            infos = list(core.DOWNLOADS.values())
        lines = []
        for info in infos:
            total = int(info.get("total") or 0)
            done = int(info.get("done") or 0)
            percent = int(done * 100 / total) if total else 0
            lines.append("%s — %s, %d%% (%d / %d МБ)%s" % (
                info.get("name"), info.get("status"), percent, done // (1024 * 1024),
                total // (1024 * 1024), (" " + info["error"]) if info.get("error") else ""))
        self.set_text(self.local_dl_box, "\n".join(lines) or "Загрузок нет")
        active = next((i for i in infos if i.get("status") == "downloading"), None)
        if active and active.get("total"):
            self.local_bar.configure(maximum=100, value=int(active["done"] * 100 / active["total"]))
        else:
            self.local_bar.configure(value=0)

    def selected_catalog(self):
        sel = self.local_tv.selection()
        if not sel:
            return None
        return getattr(self, "catalog_rows", {}).get(sel[0])

    def local_download(self):
        item = self.selected_catalog()
        if item is None:
            self.set_status("Выбери модель в каталоге")
            return
        try:
            name = core.start_download(item["file"], item["url"], item["file"])
        except ValueError as e:
            messagebox.showwarning("PocketCode", core.mask(e))
            return
        self.set_status("Скачиваю " + name)
        self.tick_local()

    def local_cancel(self):
        with core.LOCK:
            infos = list(core.DOWNLOADS.values())
        for info in infos:
            if info.get("status") == "downloading":
                info["cancel"].set()
                info["status"] = "cancelled"
        self.set_status("Загрузка отменена")
        self.tick_local()

    def local_use(self):
        item = self.selected_catalog()
        name = item["file"] if item else self.selected_downloaded()
        if not name:
            self.set_status("Выбери модель")
            return
        profiles = core.load_json(core.PROFILES, {})
        profiles["local"] = {"base_url": "http://127.0.0.1:%d/v1" % int(core.settings().get("localPort") or 8080),
                             "model": name, "api_key": "local", "max_tokens": 2048}
        profiles["_active"] = "local"
        core.save_json(core.PROFILES, profiles)
        core.save_json(core.SETTINGS, {**core.settings(), "localModel": name})
        self.update_chat_header()
        self.set_status("Выбрана локальная модель: " + name)

    def selected_downloaded(self):
        sel = self.local_dl_tv.selection()
        if not sel:
            return ""
        return str(self.local_dl_tv.item(sel[0], "values")[0])

    def local_delete(self):
        name = self.selected_downloaded()
        if not name or not messagebox.askyesno("Удалить модель", "Удалить файл %s?" % name):
            return
        path = core.safe_path(core.DOWNLOADS_DIR, name)
        if path and path.is_file():
            path.unlink()
        self.reload_local()
        self.set_status("Модель удалена")

    def local_server(self, action):
        def work():
            return core.stop_model_server() if action == "stop" else core.start_model_server()

        def done(res):
            good, detail = res
            self.set_status(detail)
            self.reload_local()
            if not good:
                messagebox.showinfo("PocketCode", detail)

        self.run_bg(work, done)

    # ---------- навыки ----------

    def build_skills(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.skills_info = self.label(top, "", 9, MUTED)
        self.skills_info.pack(side="left")
        self.btn(top, "Включить/выключить", self.toggle_skill)
        self.btn(top, "Удалить", self.delete_skill)
        wrap, self.skills_tv = self.table(page, [("Навык", 260), ("Описание", 620), ("Статус", 90)], height=10)
        wrap.pack(fill="x", pady=(8, 0))

        self.label(page, "Свой навык", 10, TEXT, "bold").pack(anchor="w", pady=(10, 2))
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x")
        self.label(row, "Имя", 9, MUTED).pack(side="left")
        self.skill_name = self.field(row, "", 22)
        self.label(row, "Описание", 9, MUTED).pack(side="left")
        self.skill_desc = self.field(row, "", 44)
        body_wrap, self.skill_body = self.tbox(page, height=6, readonly=False)
        body_wrap.pack(fill="x", pady=(6, 0))
        actions = tk.Frame(page, bg=BG)
        actions.pack(fill="x", pady=(6, 0))
        self.btn(actions, "Сохранить навык", self.add_skill, primary=True)
        self.label(actions, "Из GitHub", 9, MUTED).pack(side="left", padx=(10, 4))
        self.install_repo = self.field(actions, "owner/name", 32)
        self.btn(actions, "Установить", self.install_skill)
        self.reloads["skills"] = self.reload_skills

    def reload_skills(self):
        self.skill_items = core.load_skills()
        self.skills_tv.delete(*self.skills_tv.get_children())
        for s in self.skill_items:
            self.skills_tv.insert("", "end", values=(
                s.get("name", ""), str(s.get("description", ""))[:160],
                "включён" if s.get("enabled", True) else "выключен"))
        enabled = len([s for s in self.skill_items if s.get("enabled", True)])
        self.skills_info.configure(text="Навыков: %d · включено: %d" % (len(self.skill_items), enabled))

    def selected_skill(self):
        sel = self.skills_tv.selection()
        if not sel:
            return None
        name = str(self.skills_tv.item(sel[0], "values")[0])
        return next((s for s in self.skill_items if s.get("name") == name), None)

    def toggle_skill(self):
        skill = self.selected_skill()
        if not skill:
            self.set_status("Выбери навык")
            return
        name = skill.get("name")
        enabled = not bool(skill.get("enabled", True))
        if any(b["name"] == name for b in core.BUILTIN_SKILLS):
            stored = [s for s in core.load_json(core.SKILLS, []) if s.get("name") != name]
            stored.append({"name": name, "enabled": enabled})
            core.save_json(core.SKILLS, stored)
        else:
            skills = core.load_json(core.SKILLS, [])
            for s in skills:
                if s.get("name") == name:
                    s["enabled"] = enabled
            core.save_json(core.SKILLS, skills)
        self.reload_skills()
        self.set_status("%s: %s" % (name, "включён" if enabled else "выключен"))

    def delete_skill(self):
        skill = self.selected_skill()
        if not skill:
            return
        if skill.get("builtin"):
            messagebox.showinfo("PocketCode", "Встроенный навык можно только выключить")
            return
        if not messagebox.askyesno("Удалить навык", "Удалить %s?" % skill.get("name")):
            return
        core.save_json(core.SKILLS, [s for s in core.load_json(core.SKILLS, [])
                                     if s.get("name") != skill.get("name")])
        self.reload_skills()

    def add_skill(self):
        name = self.skill_name.get().strip()
        body = self.get_text(self.skill_body)
        if not name or not body:
            messagebox.showwarning("PocketCode", "Нужны имя и текст навыка")
            return
        skills = [s for s in core.load_json(core.SKILLS, []) if s.get("name") != name]
        skills.append({"name": name, "description": self.skill_desc.get().strip(),
                       "body": body, "enabled": True})
        core.save_json(core.SKILLS, skills)
        self.skill_name.delete(0, "end")
        self.skill_desc.delete(0, "end")
        self.skill_body.delete("1.0", "end")
        self.reload_skills()
        self.set_status("Навык сохранён: " + name)

    def install_skill(self):
        repo = self.install_repo.get().strip().rstrip("/")
        if repo.startswith("https://github.com/"):
            repo = repo[len("https://github.com/"):]
        if not repo or "/" not in repo:
            messagebox.showwarning("PocketCode", "Нужен репозиторий вида owner/name")
            return

        def work():
            text, meta = core.fetch_repo_skill(repo)
            if not text:
                raise ValueError("Не удалось прочитать README/SKILL.md репозитория")
            skills = [s for s in core.load_json(core.SKILLS, []) if s.get("name") != meta["name"]]
            skills.append({**meta, "body": text})
            core.save_json(core.SKILLS, skills)
            return meta["name"]

        self.run_bg(work, lambda name: (self.reload_skills(), self.set_status("Навык установлен: " + name)))

    # ---------- плагины с GitHub ----------

    def build_github(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.gh_query = self.field(top, "", 34)
        self.gh_query.bind("<Return>", lambda e: self.gh_search())
        self.gh_category = ttk.Combobox(top, state="readonly", width=12,
                                        values=["Все", "Навыки", "Агенты", "MCP", "Плагины"],
                                        font=(FONT, 9))
        self.gh_category.set("Все")
        self.gh_category.pack(side="left", padx=(0, 6))
        self.gh_sort = ttk.Combobox(top, state="readonly", width=10,
                                    values=["stars", "forks", "updated"], font=(FONT, 9))
        self.gh_sort.set("stars")
        self.gh_sort.pack(side="left", padx=(0, 6))
        self.btn(top, "Искать", self.gh_search, primary=True)
        self.btn(top, "Показать ещё", lambda: self.gh_search(more=True))
        self.btn(top, "Установить навыком", self.gh_install)

        self.gh_info = self.label(page, "Русский запрос переводится на английский, описания — обратно.",
                                  9, MUTED)
        self.gh_info.pack(anchor="w", pady=(6, 0))
        wrap, self.gh_tv = self.table(page, [("Репозиторий", 300), ("★", 80), ("Описание", 620)], height=18)
        wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["github"] = lambda: self.gh_info.configure(
            text="GitHub: %d результатов на странице. Токен задаётся в разделе «Экономия»."
                 % len(self.gh_items))

    def gh_search(self, more=False):
        query = self.gh_query.get().strip()
        if not query:
            return
        page = (self.gh_page + 1) if more and query == getattr(self, "gh_query_text", "") else 1

        def work():
            s = core.settings()
            return core.github_search(query, self.gh_category.get(), self.gh_sort.get(), page,
                                      int(s.get("githubPerPage") or 40),
                                      bool(s.get("githubTranslate", True)),
                                      str(s.get("githubToken", "")))

        def done(res):
            items = res.get("items") or []
            self.gh_page = page
            self.gh_query_text = query
            if more:
                self.gh_items += items
            else:
                self.gh_items = items
            self.gh_tv.delete(*self.gh_tv.get_children())
            for it in self.gh_items:
                self.gh_tv.insert("", "end", iid=it["full_name"], values=(
                    it["full_name"], it["stars"],
                    (it.get("description_ru") or it.get("description") or "")[:200]))
            note = "Найдено: %d · страница %d" % (res.get("total", 0), page)
            if res.get("query_translated"):
                note += " · запрос: " + str(res.get("query"))
            self.gh_info.configure(text=note)
            self.set_status(note)

        self.run_bg(work, done)
        self.set_status("Ищу на GitHub…")

    def gh_install(self):
        sel = self.gh_tv.selection()
        if not sel:
            self.set_status("Выбери репозиторий")
            return
        self.install_repo.delete(0, "end")
        self.install_repo.insert(0, str(sel[0]))
        self.show("skills")
        self.install_skill()

    # ---------- сравнение моделей ----------

    def build_compare(self, page):
        self.label(page, "Один запрос — несколько провайдеров. Ответы придут параллельно.",
                   9, MUTED).pack(anchor="w")
        prompt_wrap, self.cmp_prompt = self.tbox(page, height=5, readonly=False)
        prompt_wrap.pack(fill="x", pady=(6, 0))
        body = tk.Frame(page, bg=BG)
        body.pack(fill="both", expand=True, pady=(6, 0))
        left = tk.Frame(body, bg=BG, width=220)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        self.label(left, "Провайдеры (Ctrl+клик)", 9, MUTED).pack(anchor="w")
        cmp_wrap, self.cmp_lb = self.listbox(left, height=8, multi=True)
        cmp_wrap.pack(fill="both", expand=True)
        self.btn(left, "Сравнить", self.compare_run, primary=True)
        out_wrap, self.cmp_out = self.tbox(body, height=16)
        out_wrap.pack(side="left", fill="both", expand=True, padx=(12, 0))
        self.reloads["compare"] = self.reload_compare

    def reload_compare(self):
        profiles = core.public_profiles()
        profiles.pop("_active", "")
        self.cmp_lb.delete(0, "end")
        for name in profiles:
            self.cmp_lb.insert("end", "  " + name)

    def compare_run(self):
        prompt = self.get_text(self.cmp_prompt)
        names = [self.cmp_lb.get(i).strip() for i in self.cmp_lb.curselection()]
        if not prompt or not names:
            messagebox.showwarning("PocketCode", "Нужны запрос и хотя бы один провайдер")
            return

        def work():
            return core.compare_models(prompt, names[:4])

        def done(res):
            parts = []
            for r in res:
                head = "%s · %s мс · %s токенов" % (r.get("name"), r.get("ms"), r.get("tokens"))
                parts.append("=== " + head + "\n" + (r.get("text") or ("Ошибка: " + str(r.get("error")))))
            self.set_text(self.cmp_out, "\n\n".join(parts))
            self.set_status("Сравнение готово: %d моделей" % len(res))

        self.run_bg(work, done)
        self.set_status("Спрашиваю модели…")

    # ---------- очередь ----------

    def build_queue(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.queue_info = self.label(top, "", 9, MUTED)
        self.queue_info.pack(side="left")
        self.btn(top, "Обновить", self.reload_queue)
        self.btn(top, "В чат", self.queue_to_chat, primary=True)
        self.btn(top, "Удалить", self.queue_remove)
        queue_wrap, self.queue_lb = self.listbox(page, height=12)
        queue_wrap.pack(fill="both", expand=True, pady=(8, 0))
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(8, 0))
        self.queue_text = self.field(row, "", 56)
        self.btn(row, "В очередь", self.queue_add)
        self.reloads["queue"] = self.reload_queue

    def reload_queue(self):
        self.queue_items = core.load_json(core.QUEUE, [])
        self.queue_lb.delete(0, "end")
        for item in self.queue_items:
            self.queue_lb.insert("end", "  " + str(item.get("text", ""))[:110])
        self.queue_info.configure(text="Задач в очереди: %d" % len(self.queue_items))

    def queue_add(self):
        text = self.queue_text.get().strip()
        if not text:
            return
        items = core.load_json(core.QUEUE, [])
        items.append({"id": core.secrets.token_hex(4), "text": text, "ts": core.now_ms()})
        core.save_json(core.QUEUE, items)
        self.queue_text.delete(0, "end")
        self.reload_queue()

    def queue_remove(self):
        sel = self.queue_lb.curselection()
        if not sel:
            return
        item = self.queue_items[sel[0]]
        core.save_json(core.QUEUE, [x for x in core.load_json(core.QUEUE, [])
                                    if x.get("id") != item.get("id")])
        self.reload_queue()

    def queue_to_chat(self):
        sel = self.queue_lb.curselection()
        if not sel:
            return
        text = str(self.queue_items[sel[0]].get("text", ""))
        self.show("chat")
        self.composer.delete("1.0", "end")
        self.composer.insert("1.0", text)
        self.set_status("Задача перенесена в чат")
    # ---------- экономия токенов ----------

    def build_save(self, page):
        s = core.settings()
        self.save_vars = {}
        self.label(page, "Меньше запросов и токенов при том же результате.", 10, TEXT, "bold").pack(anchor="w")
        for key, title in (("leanCalls", "Меньше вызовов модели (lean): история короче, шагов меньше"),
                           ("ecoMode", "Эко: минимум шагов и короткая история (для дешёвых задач)"),
                           ("featMemory", "Память проекта: агент помнит решения и структуру"),
                           ("featRules", "Правила проекта: агент соблюдает твои требования"),
                           ("featRouter", "Роутер сложности: простые шаги — без большого плана"),
                           ("featBudget", "Бюджет правок: лимит попыток починки с честным отказом"),
                           ("featAutoplan", "Автоплан: разбивать задачу на шаги с чек-листом"),
                           ("featSelfCheck", "Самопроверка: агент проверяет свой результат"),
                           ("featNotify", "Уведомление на телефон, когда задача завершена"),
                           ("featTerminal", "Терминал ПК с телефона (требует токен)"),
                           ("featAudit", "Аудит: журнал всех bash-команд агента"),
                           ("featGitCommit", "Git-действия из чата: коммиты и ветки"),
                           ("featMacros", "Макросы: запись и повтор команд"),
                           ("featTriggers", "Триггеры: команда при изменении файлов"),
                           ("featWebhooks", "Вебхуки: уведомления на свой URL"),
                           ("featSql", "SQL-консоль проекта (read-only)"),
                           ("featDiagrams", "Mermaid-диаграмма структуры проекта"),
                           ("featCron", "Cron-задачи: повторять задачу по расписанию"),
                           ("featMerge", "Помощник merge-конфликтов git"),
                           ("featDeadCode", "Поиск мёртвого кода (неиспользуемые функции)"),
                           ("featLogs", "Просмотр логов с уровнями и ошибками"),
                           ("featCsv", "Таблицы CSV/JSON: сводка и диф"),
                           ("featImportPrompts", "Импорт промптов из Claude Code / OpenCode"),
                           ("featQuarantine", "Карантин битых sync-пакетов"),
                           ("featPriority", "Приоритеты в очереди задач"),
                           ("featFullExport", "Полный экспорт всех данных в ZIP-архив"),
                           ("featCrash", "Журнал крашей с группировкой"),
                           ("featNotes", "Заметки проекта (мини-CMS)"),
                           ("featGuest", "Гостевой доступ без секретов"),
                           ("featIntegrity", "Проверка целостности данных проекта"),
                           ("featPlugins", "Плагины: локальные скрипты в песочнице"),
                           ("featMedia", "Просмотрщик изображений проекта"),
                           ("featRatings", "Рейтинги навыков и шаблонов"),
                           ("featGitCache", "Кэш git-статусов (быстрее на 15 с)"),
                           ("featJsonTools", "Валидатор JSON с автоисправлением"),
                           ("featFullImport", "Импорт полного архива на новом устройстве"),
                           ("featReadOnly", "Только чтение при нехватке места на диске"),
                           ("featTeam", "Командные роли проекта (владелец/редактор/читатель)"),
                           ("featRepoExport", "Экспорт проекта в готовый репозиторий"),
                           ("featHealthReport", "Отчёт «здоровье проекта»"),
                           ("featDups", "Поиск дубликатов файлов"),
                           ("featFileWrite", "Запись файлов ПК (шапки лицензий и др.)"),
                           ("featSnippets", "Сниппеты кода с поиском"),
                           ("featTrash", "Корзина удалённых файлов"),
                           ("featExports", "Экспорт чата и метрик (MD/CSV)"),
                           ("autoApprove", "Не спрашивать подтверждение опасных команд")):
            var = tk.BooleanVar(value=bool(s.get(key)))
            self.save_vars[key] = var
            ttk.Checkbutton(page, text=title, variable=var).pack(anchor="w", pady=2)

        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(10, 0))
        self.label(row, "Сообщений в истории", 9, MUTED).pack(side="left")
        self.max_history = tk.Spinbox(row, from_=4, to=60, width=5, bg=CARD, fg=TEXT,
                                      insertbackground=TEXT, relief="flat", bd=4,
                                      highlightthickness=1, highlightbackground=LINE)
        self.max_history.delete(0, "end")
        self.max_history.insert(0, str(int(s.get("maxHistory") or 20)))
        self.max_history.pack(side="left", padx=(6, 14))
        self.label(row, "Символов вывода инструмента", 9, MUTED).pack(side="left")
        self.max_tool_chars = tk.Spinbox(row, from_=500, to=20000, increment=500, width=8,
                                         bg=CARD, fg=TEXT, insertbackground=TEXT, relief="flat",
                                         bd=4, highlightthickness=1, highlightbackground=LINE)
        self.max_tool_chars.delete(0, "end")
        self.max_tool_chars.insert(0, str(int(s.get("maxToolChars") or 6000)))
        self.max_tool_chars.pack(side="left", padx=(6, 0))

        gh = tk.Frame(page, bg=BG)
        gh.pack(fill="x", pady=(14, 0))
        self.label(gh, "GitHub: токен (необязательно)", 9, MUTED).pack(side="left")
        self.gh_token = self.field(gh, "", 34, show="•")
        self.label(gh, "результатов на страницу", 9, MUTED).pack(side="left")
        self.gh_per_page = tk.Spinbox(gh, from_=10, to=100, increment=10, width=5, bg=CARD, fg=TEXT,
                                      insertbackground=TEXT, relief="flat", bd=4,
                                      highlightthickness=1, highlightbackground=LINE)
        self.gh_per_page.delete(0, "end")
        self.gh_per_page.insert(0, str(int(s.get("githubPerPage") or 40)))
        self.gh_per_page.pack(side="left", padx=(6, 0))
        self.label(page, "Пустое поле токена сохраняет прежний ключ.", 9, MUTED).pack(anchor="w", pady=(4, 0))

        actions = tk.Frame(page, bg=BG)
        actions.pack(fill="x", pady=(12, 0))
        self.btn(actions, "Сохранить", self.save_economy, primary=True)

        self.label(page, "Что уходит модели", 10, TEXT, "bold").pack(anchor="w", pady=(16, 2))
        ctx_wrap, self.ctx_box = self.tbox(page, height=10)
        ctx_wrap.pack(fill="both", expand=True)
        ctx_actions = tk.Frame(page, bg=BG)
        ctx_actions.pack(fill="x", pady=(6, 0))
        self.btn(ctx_actions, "Показать контекст", self.show_context, small=True)
        self.reloads["save"] = self.show_context

    def save_economy(self):
        s = core.settings()
        for key, var in self.save_vars.items():
            s[key] = bool(var.get())
        try:
            s["maxHistory"] = max(4, min(60, int(self.max_history.get())))
            s["maxToolChars"] = max(500, min(20000, int(self.max_tool_chars.get())))
            s["githubPerPage"] = max(10, min(100, int(self.gh_per_page.get())))
        except ValueError:
            pass
        token = self.gh_token.get().strip()
        if token:
            s["githubToken"] = token
            self.gh_token.delete(0, "end")
        core.save_json(core.SETTINGS, s)
        self.set_status("Настройки экономии сохранены")
        self.show_context()

    def show_context(self):
        sid = self.session_id
        messages = core.messages_for(sid)
        system = core.build_system_prompt(core.session_project(sid))
        text = system + "".join(str(m.get("text", "")) for m in messages)
        max_msgs, max_chars = core.history_budget()
        self.set_text(self.ctx_box, "Сообщений в чате: %d\nСимволов: %d\nОценка токенов: %d\n"
                                    "Бюджет истории: %d сообщений / %d символов\n"
                                    "Активных навыков: %d\n\n=== Системный промпт ===\n%s" % (
            len(messages), len(text), core.estimate_tokens(text), max_msgs, max_chars,
            len([x for x in core.load_skills() if x.get("enabled", True)]), system))

    # ---------- расход ----------

    def build_usage(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.usage_info = self.label(top, "", 11, TEXT, "bold")
        self.usage_info.pack(side="left")
        self.btn(top, "Обновить", self.reload_usage)
        self.label(page, "Счётчик считает запросы агента, токены промпта и ответа, "
                         "а также сэкономленные символы в режиме lean.", 9, MUTED, wrap=900).pack(anchor="w", pady=(6, 0))
        usage_wrap, self.usage_box = self.tbox(page, height=8)
        usage_wrap.pack(fill="x", pady=(8, 0))
        self.label(page, "Последние шаги", 10, TEXT, "bold").pack(anchor="w", pady=(12, 2))
        activity_wrap, self.activity_box = self.tbox(page, height=14)
        activity_wrap.pack(fill="both", expand=True)
        self.reloads["usage"] = self.reload_usage

    def reload_usage(self):
        usage = core.load_json(core.USAGE, {})
        prompt = int(usage.get("prompt", 0) or 0)
        completion = int(usage.get("completion", 0) or 0)
        self.usage_info.configure(text="Запросов: %d · токенов: %d" % (
            int(usage.get("requests", 0) or 0), prompt + completion))
        self.set_text(self.usage_box, json.dumps({
            "запросов": int(usage.get("requests", 0) or 0),
            "токенов промпта": prompt,
            "токенов ответа": completion,
            "сэкономлено символов": int(usage.get("saved", 0) or 0),
            "последний запрос": time.strftime("%d.%m.%Y %H:%M", time.localtime(
                int(usage.get("last", 0) or 0) / 1000)) if usage.get("last") else "—",
        }, ensure_ascii=False, indent=2))
        lines = []
        for e in core.read_jsonl(core.ACTIVITY)[-120:]:
            when = time.strftime("%H:%M:%S", time.localtime(int(e.get("ts", 0) or 0) / 1000))
            kind = e.get("kind", "")
            detail = e.get("name") or e.get("path") or e.get("command") or str(e.get("text", ""))[:80]
            lines.append("%s  %-12s %s" % (when, kind, detail))
        self.set_text(self.activity_box, "\n".join(lines) or "Пока нет активности")

    # ---------- оформление ----------

    def build_look(self, page):
        s = core.settings()
        self.label(page, "Тема «Аврора»: холодный фон, бирюзово-синие акценты, плотные карточки.",
                   9, MUTED).pack(anchor="w")
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(10, 0))
        self.label(row, "Акцент", 9, MUTED).pack(side="left")
        self.accent_box = ttk.Combobox(row, state="readonly", width=16,
                                       values=[ACCENT_NAMES[k] for k in ACCENTS], font=(FONT, 9))
        self.accent_box.set(ACCENT_NAMES.get(str(s.get("accent") or "teal"), "Бирюзовый"))
        self.accent_box.pack(side="left", padx=(6, 16))
        self.label(row, "Размер шрифта", 9, MUTED).pack(side="left")
        self.font_size = tk.Spinbox(row, from_=9, to=22, width=4, bg=CARD, fg=TEXT,
                                    insertbackground=TEXT, relief="flat", bd=4,
                                    highlightthickness=1, highlightbackground=LINE)
        self.font_size.delete(0, "end")
        self.font_size.insert(0, str(int(s.get("fontSize") or 15)))
        self.font_size.pack(side="left", padx=(6, 16))
        self.label(row, "Плотность", 9, MUTED).pack(side="left")
        self.density_box = ttk.Combobox(row, state="readonly", width=14,
                                        values=["comfortable", "compact"], font=(FONT, 9))
        self.density_box.set(str(s.get("density") or "comfortable"))
        self.density_box.pack(side="left", padx=(6, 0))

        self.motion_var = tk.BooleanVar(value=bool(s.get("motion", True)))
        ttk.Checkbutton(page, text="Анимации и плавные переходы", variable=self.motion_var).pack(anchor="w", pady=(10, 0))
        actions = tk.Frame(page, bg=BG)
        actions.pack(fill="x", pady=(12, 0))
        self.btn(actions, "Применить и сохранить", self.save_look, primary=True)
        self.btn(actions, "Сбросить на «Аврору»", self.reset_look)
        self.label(page, "Акцент применяется сразу; размер шрифта — после перезапуска окна.",
                   9, MUTED, wrap=800).pack(anchor="w", pady=(8, 0))
        self.reloads["look"] = lambda: None

    def save_look(self):
        s = core.settings()
        name = next((k for k, v in ACCENT_NAMES.items() if v == self.accent_box.get()), "teal")
        s["accent"] = name
        s["theme"] = "aurora"
        try:
            s["fontSize"] = max(9, min(22, int(self.font_size.get())))
        except ValueError:
            s["fontSize"] = 15
        s["density"] = self.density_box.get() or "comfortable"
        s["motion"] = bool(self.motion_var.get())
        core.save_json(core.SETTINGS, s)
        self.accent = ACCENTS.get(name, ACCENTS["teal"])
        self.apply_style()
        self.show(self.section)
        self.set_status("Оформление сохранено")

    def reset_look(self):
        s = core.settings()
        s.update({"theme": "aurora", "accent": "teal", "density": "comfortable",
                  "fontSize": 15, "motion": True})
        core.save_json(core.SETTINGS, s)
        self.accent = ACCENTS["teal"]
        self.apply_style()
        self.show("look")
        self.set_status("Тема «Аврора» по умолчанию")

    # ---------- синхронизация ----------

    def build_sync(self, page):
        cfg = core.public_sync_cfg()
        self.label(page, "Двусторонняя синхронизация: телефон присылает сообщения и файлы на ПК, "
                         "ПК отправляет свои изменения на телефон. Чат можно продолжать с любого "
                         "подключённого устройства — история подтягивается сама.", 9, MUTED,
                   wrap=920).pack(anchor="w")
        row1 = tk.Frame(page, bg=BG)
        row1.pack(fill="x", pady=(10, 0))
        self.label(row1, "Адрес телефона/устройства", 9, MUTED).pack(side="left")
        self.sync_url = self.field(row1, cfg.get("phoneUrl", ""), 38)
        self.label(row1, "Токен устройства", 9, MUTED).pack(side="left")
        self.sync_token = self.field(row1, "", 24, show="•")
        self.label(row1, "Пароль E2E", 9, MUTED).pack(side="left")
        self.sync_pass = self.field(row1, "", 18, show="•")

        row2 = tk.Frame(page, bg=BG)
        row2.pack(fill="x", pady=(8, 0))
        self.sync_vars = {}
        for key, title in (("enabled", "Включена"), ("full", "Присылать снимок"),
                           ("applyRemote", "Применять входящие пакеты")):
            var = tk.BooleanVar(value=bool(cfg.get(key, key != "enabled")))
            self.sync_vars[key] = var
            ttk.Checkbutton(row2, text=title, variable=var).pack(side="left", padx=(0, 14))
        self.label(row2, "Интервал снимка, мин", 9, MUTED).pack(side="left")
        self.sync_interval = tk.Spinbox(row2, from_=1, to=180, width=5, bg=CARD, fg=TEXT,
                                        insertbackground=TEXT, relief="flat", bd=4,
                                        highlightthickness=1, highlightbackground=LINE)
        self.sync_interval.delete(0, "end")
        self.sync_interval.insert(0, str(int(cfg.get("intervalMin") or 15)))
        self.sync_interval.pack(side="left", padx=(6, 0))

        actions = tk.Frame(page, bg=BG)
        actions.pack(fill="x", pady=(12, 0))
        self.btn(actions, "Сохранить", self.save_sync, primary=True)
        self.btn(actions, "Проверить связь", self.sync_test)
        self.btn(actions, "Отправить всё на телефон", self.sync_push_all)
        self.btn(actions, "Показать токен для телефона", self.sync_show_token)
        self.btn(actions, "QR-сопряжение", self.sync_show_qr)
        self.btn(actions, "Сканер секретов", self.show_secret_scanner)
        self.btn(actions, "Обновить статус", self.reload_sync)

        self.sync_token_label = self.label(page, "", 9, TEXT)
        self.sync_token_label.pack(anchor="w", pady=(8, 0))
        self.sync_note = self.label(page, "", 9, MUTED, wrap=920)
        self.sync_note.pack(anchor="w")
        status_wrap, self.sync_box = self.tbox(page, height=8)
        status_wrap.pack(fill="x", pady=(8, 0))
        self.label(page, "Принятые события", 10, TEXT, "bold").pack(anchor="w", pady=(10, 2))
        wrap, self.sync_tv = self.table(page, [("Время", 140), ("Устройство", 220),
                                               ("Тип", 120), ("seq", 90)], height=6)
        wrap.pack(fill="both", expand=True)
        self.reloads["sync"] = self.reload_sync

    def save_sync(self):
        cfg = core.load_json(core.SYNC_CONFIG, {})
        cfg["phoneUrl"] = self.sync_url.get().strip()
        for key, var in self.sync_vars.items():
            cfg[key] = bool(var.get())
        try:
            cfg["intervalMin"] = max(1, min(180, int(self.sync_interval.get())))
        except ValueError:
            cfg["intervalMin"] = 15
        token = self.sync_token.get().strip()
        if token:
            cfg["token"] = token
            self.sync_token.delete(0, "end")
        password = self.sync_pass.get().strip()
        if password:
            cfg["password"] = password
            self.sync_pass.delete(0, "end")
        core.save_json(core.SYNC_CONFIG, cfg)
        if cfg.get("enabled") and (core.thread_sync is None or not core.thread_sync.is_alive()):
            core.thread_sync = threading.Thread(target=core.sync_loop,
                                                args=(threading.Event(),), daemon=True)
            core.thread_sync.start()
        self.reload_sync()
        self.set_status("Синхронизация сохранена")

    def sync_test(self):
        def work():
            return core.push_to_phone(core.make_event("ping", {"text": "проверка связи"}))

        self.run_bg(work, lambda res: self.set_status(
            "Связь есть" if res[0] else "Нет связи: " + str(res[1])))
        self.set_status("Проверяю связь…")

    def sync_push_all(self):
        def work():
            return core.push_to_phone(core.full_packet())

        self.run_bg(work, lambda res: self.set_status(
            "Снимок отправлен" if res[0] else "Не отправлено: " + str(res[1])))
        self.set_status("Отправляю полный снимок…")

    def sync_show_token(self):
        self.sync_token_label.configure(text="Токен этого ПК для телефона: " + core.access_token())

    def sync_show_qr(self):
        link = core.sync_link()
        win = tk.Toplevel(self.root)
        win.title("QR-сопряжение")
        win.configure(bg=BG)
        self.label(win, "Открой «Синхронизация» на телефоне и нажми «Сканировать QR»",
                   10, TEXT, "bold").pack(padx=16, pady=(14, 4))
        self.label(win, link, 9, MUTED, wrap=420).pack(padx=16)
        try:
            import qrcode
        except ImportError:
            self.label(win, "Нет модуля qrcode: pip install qrcode", 9, MUTED).pack(padx=16, pady=12)
            return
        qr = qrcode.QRCode(border=2, box_size=1)
        qr.add_data(link)
        qr.make(fit=True)
        matrix = qr.get_matrix()
        cell = 6
        side = len(matrix) * cell
        cv = tk.Canvas(win, width=side, height=side, bg="white", highlightthickness=0)
        cv.pack(padx=16, pady=12)
        for y, row in enumerate(matrix):
            for x, on in enumerate(row):
                if on:
                    cv.create_rectangle(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell,
                                        fill="black", outline="black")

    def show_secret_scanner(self):
        win = tk.Toplevel(self.root)
        win.title("Сканер секретов")
        win.configure(bg=BG)
        self.label(win, "Вставь текст или код — найду ключи, токены и опасные места",
                   10, TEXT, "bold").pack(padx=16, pady=(14, 6))
        txt = tk.Text(win, width=70, height=14, bg="#141828", fg=TEXT, insertbackground=TEXT,
                      relief="flat", padx=8, pady=8)
        txt.pack(padx=16, pady=4)
        out = self.label(win, "", 9, MUTED, wrap=520)
        out.pack(padx=16, pady=(6, 0), fill="x")

        def scan():
            findings = core.fx.scan_secrets(txt.get("1.0", "end"))
            if not findings:
                out.configure(text="Чисто: проблем не найдено")
            else:
                out.configure(text="\n".join(
                    "Строка %d · %s · %s" % (f["line"], f["kind"], f["match"]) for f in findings[:20]))

        self.btn(win, "Проверить", scan)

    def reload_sync(self):
        state = core.load_json(core.SYNC_STATE, {})
        cfg = core.public_sync_cfg()
        pending = len(core.read_lines(core.SYNC_OUTBOX))
        events = core.read_jsonl(core.SYNC_EVENTS)
        local = "127.0.0.1" if self.host in ("127.0.0.1", "localhost") else "0.0.0.0"
        self.set_text(self.sync_box, "\n".join([
            "Адрес ПК для телефона: http://<IP ПК>:%d" % self.port,
            "Слушает: %s · включена: %s · применяет входящие: %s" % (
                local, "да" if cfg.get("enabled") else "нет", "да" if cfg.get("applyRemote", True) else "нет"),
            "Событий принято: %s · последний seq: %s" % (state.get("events", 0), state.get("last_seq", 0)),
            "В очереди на отправку: %d" % pending,
            "Пакетов full принято: %s" % state.get("fulls", 0),
        ]))
        self.sync_tv.delete(*self.sync_tv.get_children())
        for e in events[-40:][::-1]:
            when = time.strftime("%d.%m %H:%M:%S", time.localtime(int(e.get("ts", 0) or 0) / 1000))
            self.sync_tv.insert("", "end", values=(when, e.get("device", "—"),
                                                   e.get("kind") or e.get("type", "—"),
                                                   e.get("seq", "")))

    # ---------- резервная копия ----------

    def build_backup(self, page):
        self.label(page, "Резервная копия переносит провайдеров, настройки, навыки, агентов, "
                         "локальные модели, сессии и сообщения на другое устройство.", 9, MUTED,
                   wrap=900).pack(anchor="w")
        actions = tk.Frame(page, bg=BG)
        actions.pack(fill="x", pady=(12, 0))
        self.btn(actions, "Экспорт в JSON", self.backup_export, primary=True)
        self.btn(actions, "Импорт из JSON", self.backup_import)
        self.btn(actions, "Открыть папку данных", self.open_home)
        info_wrap, self.backup_box = self.tbox(page, height=8)
        info_wrap.pack(fill="x", pady=(10, 0))
        self.reloads["backup"] = lambda: self.set_text(self.backup_box, "\n".join([
            "Данные: %s" % core.HOME,
            "Чатов: %d" % len(core.load_sessions()["items"]),
            "Сообщений: %d" % len(core.read_jsonl(core.CHAT)),
            "Навыков: %d" % len(core.load_skills()),
            "Проектов: %d" % len(core.list_projects()),
            "Скачанных моделей: %d" % len(core.downloaded_models()),
        ]))

    def backup_export(self):
        path = filedialog.asksaveasfilename(title="Сохранить резервную копию",
                                            defaultextension=".json",
                                            initialfile="pocketcode-backup.json",
                                            filetypes=[("JSON", "*.json")])
        if not path:
            return
        data = core.backup_export()
        Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        self.set_status("Копия сохранена: " + path)

    def backup_import(self):
        path = filedialog.askopenfilename(title="Выбрать резервную копию",
                                          filetypes=[("JSON", "*.json"), ("Все файлы", "*")])
        if not path:
            return
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            messagebox.showerror("PocketCode", "Не удалось прочитать файл: " + str(e))
            return
        res = core.backup_import(data)
        self.reloads["backup"]()
        self.reload_chat()
        self.set_status("Импортировано: " + ", ".join(res.get("imported") or []) or "ничего")

    def open_home(self):
        path = str(core.HOME)
        try:
            if os.name == "nt":
                os.startfile(path)  # noqa: S606 — открыть папку в проводнике
            elif sys.platform == "darwin":
                subprocess.Popen(["open", path])
            else:
                subprocess.Popen(["xdg-open", path])
        except Exception as e:  # noqa: BLE001
            self.set_status("Не удалось открыть папку: " + core.mask(e))

    # ---------- диагностика ----------

    def build_diag(self, page):
        top = tk.Frame(page, bg=BG)
        top.pack(fill="x")
        self.btn(top, "Обновить", self.reload_diag, primary=True)
        self.btn(top, "Проверить обновления", self.check_updates)
        self.btn(top, "Открыть папку данных", self.open_home)
        out_wrap, self.diag_box = self.tbox(page, height=24)
        out_wrap.pack(fill="both", expand=True, pady=(8, 0))
        self.reloads["diag"] = self.reload_diag

    def reload_diag(self):
        data = core.diagnostics()
        data["tk"] = tk.TkVersion
        data["sync_server"] = "http://%s:%d" % (self.host, self.port)
        data["server_running"] = bool(self.server)
        self.set_text(self.diag_box, json.dumps(data, ensure_ascii=False, indent=2))

    def check_updates(self):
        def done(res):
            if res.get("error"):
                self.set_status("Не удалось проверить: " + str(res["error"]))
                return
            newer = res.get("newer")
            self.set_status(("Есть новая версия %s" % res.get("latest")) if newer
                            else ("Версия %s актуальна" % res.get("current")))

        self.run_bg(core.check_updates, done)
        self.set_status("Проверяю обновления…")

    # ---------- о программе ----------

    def build_about(self, page):
        self.label(page, "PocketCode Desktop", 18, TEXT, "bold").pack(anchor="w")
        self.label(page, "Версия " + core.app_version(), 10, MUTED).pack(anchor="w", pady=(2, 12))
        text = ("Нативное приложение: браузер не нужен. Тот же агент и инструменты, что в "
                "Android-приложении, плюс удобства ПК.\n\n"
                "Чат · агент с шагами, подтверждением опасных команд и отменой\n"
                "Проект · файлы и редактор, снимки и откат, git, терминал, несколько проектов\n"
                "Агент · провайдеры и ключи, локальные GGUF-модели со скачиванием и отменой, "
                "навыки, плагины с GitHub, сравнение моделей, очередь задач\n"
                "Ещё · экономия токенов, расход, оформление, резервные копии, диагностика\n\n"
                "Синхронизация двусторонняя: телефон присылает сообщения и файлы, ПК отправляет "
                "свои изменения, а чат можно продолжать с любого подключённого устройства.\n\n"
                f"Данные: {core.HOME}\n"
                f"Сервер синхронизации: http://{self.host}:{self.port}\n\n"
                "Горячие клавиши: Ctrl+Enter — отправить в чате.")
        self.label(page, text, 10, TEXT, wrap=900).pack(anchor="w")

    # ---------- фоновые задачи ----------

    def tick(self):
        try:
            self._tick()
        except Exception:  # noqa: BLE001 — фоновая проверка не должна ронять окно
            traceback.print_exc()
        finally:
            self.root.after(1200, self.tick)

    def _tick(self):
        # Входящие с других устройств: файл чата изменился — обновляем ленту.
        try:
            mtime = core.CHAT.stat().st_mtime_ns
        except OSError:
            mtime = 0
        if mtime and mtime != self._chat_mtime and not self.job:
            self.render_chat()
            self.reload_sessions()
            self.update_chat_header()
            self.set_status("Получены изменения с другого устройства")
        cfg = core.load_json(core.SYNC_CONFIG, {})
        pending = len(core.read_lines(core.SYNC_OUTBOX))
        if cfg.get("enabled") and pending and time.time() - self._last_flush > 15:
            self._last_flush = time.time()
            threading.Thread(target=core.flush_outbox, daemon=True).start()
        state = core.load_json(core.SYNC_STATE, {})
        note = "Синхронизация: %s · в очереди %d · событий %s" % (
            "вкл" if cfg.get("enabled") else "выкл", pending, state.get("events", 0))
        if note != self._last_sync_note:
            self._last_sync_note = note
            if self.section == "sync":
                self.reload_sync()
        self.head_meta.configure(text="проект %s · %s" % (self.project or "desktop", note))
        if self.section == "local":
            self.tick_local()

    def on_close(self):
        try:
            if core.MODEL_PROC is not None and core.MODEL_PROC.poll() is None:
                core.stop_model_server()
        except Exception:  # noqa: BLE001
            pass
        try:
            if self.server is not None:
                threading.Thread(target=self.server.shutdown, daemon=True).start()
        except Exception:  # noqa: BLE001
            pass
        self.root.destroy()


def run(host="0.0.0.0", port=8765, server=None):
    if tk is None:
        print("Не установлен Tkinter — окно приложения построить не из чего.")
        print("Debian/Ubuntu: sudo apt install python3-tk")
        print("Windows/macOS: поставь Python с python.org (Tkinter входит в установку).")
        return 1
    try:
        root = tk.Tk()
    except tk.TclError as e:
        print("Не удалось открыть окно: %s" % e)
        print("Нужен графический сеанс (на сервере без экрана запусти pocketcode_desktop.py --serve).")
        return 1
    root.geometry("1280x840")
    root.minsize(1024, 680)
    app = App(root, server=server, host=host, port=port)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    root.mainloop()
    return 0


def main():
    import argparse

    try:
        from pocketcode_desktop import start_server_async
    except Exception:  # noqa: BLE001
        start_server_async = None
    ap = argparse.ArgumentParser(description="PocketCode Desktop — нативное приложение")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0", help="адрес сервера синхронизации")
    ap.add_argument("--no-server", action="store_true", help="не поднимать сервер синхронизации")
    a = ap.parse_args()
    server = None
    if not a.no_server and start_server_async is not None:
        server, error = start_server_async(a.host, a.port)
        if error:
            print("Сервер синхронизации не запущен: %s" % error)
    return run(host=a.host, port=a.port, server=server)


if __name__ == "__main__":
    sys.exit(main())
