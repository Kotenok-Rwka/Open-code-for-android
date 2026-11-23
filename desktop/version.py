#!/usr/bin/env python3
"""Версия ПК-пакета — та же, что у приложения, без ручных чисел.

Мажор.минор берётся из version.properties (VERSION_BASE), патч — как в CI:
число коммитов с последней правки version.properties плюс 1.
Переопределить можно переменными окружения PC_VERSION (полная версия)
или PC_PATCH (только патч).

Запуск: python3 desktop/version.py  ->  например 4.0.15
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *args],
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()


def _prop(name: str) -> str:
    try:
        for line in (ROOT / "version.properties").read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return ""


def _base() -> str:
    return _prop("VERSION_BASE") or "4.0"


def _patch() -> str:
    env = os.environ.get("PC_PATCH", "").strip()
    if env:
        return env
    fixed = _prop("VERSION_PATCH")
    if fixed.isdigit():
        return fixed
    try:
        first = _git("log", "-1", "--format=%H", "--", "version.properties")
        return str(int(_git("rev-list", "--count", f"{first}..HEAD")) + 1)
    except Exception:
        # не git-копия (архив исходников): версия без патча
        return "0"


def version() -> str:
    override = os.environ.get("PC_VERSION", "").strip().lstrip("vV")
    return override or f"{_base()}.{_patch()}"


if __name__ == "__main__":
    sys.stdout.write(version() + "\n")
