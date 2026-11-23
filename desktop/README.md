# PocketCode Desktop (Windows / Linux)

Лёгкий компаньон на стандартной Python 3: чат с полными ответами без обрезки, автоматическая синхронизация с телефона, профили моделей и обмен задачами с IDE. Это не полный порт Android Compose-интерфейса, а рабочий мост для ПК без сборки APK.

## Запуск

```sh
python3 pocketcode_desktop.py
# открой http://127.0.0.1:8765/
```

Параметры: `--port 8765`, `--host 127.0.0.1`. Чтобы телефон в той же Wi-Fi сети отправлял события, запусти с `--host 0.0.0.0` (только в доверенной сети).

Данные: `~/.pocketcode-desktop/` — profiles.json, chat.jsonl, tasks.json, sync/events.jsonl, sync/state.json, sync/files/.

## Установка пакетами

- Windows: `powershell -ExecutionPolicy Bypass -File build_windows.ps1` → PocketCodeDesktop-<версия>-windows.zip
- Debian/Ubuntu: `bash build_linux_deb.sh` → pocketcode-desktop_<версия>_<арх>.deb

Версию пакетов не нужно вписывать руками: `version.py` берёт мажор.минор из `../version.properties`, а патч — как CI, по числу коммитов с последней правки этого файла. Поэтому ПК-пакеты и APK выходят с одной версией.

Подробности — INSTALL.md.

## Вкладки

Чат (полные ответы, /api/messages/full), Провайдеры (LM Studio, llama.cpp, LocalAI, vLLM), Обмен с телефоном, Агенты и роли, Автосинхронизация (режим LIVE/PERIODIC/BOTH, интервал, статус).

## Синхронизация

Протокол v2 — sync_protocol.md. Эндпоинты: POST /api/sync/push, GET /api/sync/pull?since=<seq>, /api/sync/status, /api/sync/config. Телефон отправляет event (сообщение, правка файла, статус) в реальном времени и full (история + файлы) по расписанию. Секреты маскируются на устройстве.

## IDE

EDITOR_INTEGRATION.md. Мост: python3 ide_bridge.py, порт 8766.

## Ограничения

Нет нативного UI/редактора; GGUF запускается через LM Studio/llama.cpp/Ollama; синхронизация по HTTP без шифрования — только в доверенной сети.
