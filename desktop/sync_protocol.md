# PocketCode Sync Protocol v2

Транспорт: HTTP POST `http://<PC>:8765/api/sync/push`, GET `/api/sync/pull?since=<seq>`.
Все данные JSON (UTF-8). Секреты маскируются на телефоне до отправки.

## Пакеты

### full — полный снимок (периодический режим)
```json
{
  "v": 2,
  "type": "full",
  "seq": 12,
  "device": "Infinix X665B",
  "project": "main",
  "ts": 1730000000000,
  "items": [{"role":"user","text":"..."},{"role":"assistant","text":"..."}],
  "files": [{"path":"src/Main.kt","text":"..."}]
}
```

### event — дельта в реальном времени
```json
{
  "v": 2,
  "type": "event",
  "seq": 13,
  "device": "Infinix X665B",
  "project": "main",
  "ts": 1730000001000,
  "kind": "message" | "file" | "status",
  "data": {"role":"assistant","text":"..."}
}
```

`kind=message`: новое сообщение чата (полный текст, без обрезки).
`kind=file`: `{path, text}` после правки файла агентом.
`kind=status`: `{text}` — ход работы/ошибка.

## Порядок и надёжность

- `seq` монотонно растёт на телефоне; ПК хранит последний принятый `seq` в `state.json`.
- `pull?since=<seq>` возвращает события ПК (например, задачи из IDE) с `seq > since`.
- При недоступности сервера события складываются в локальную очередь и отправляются повторно с backoff.
- Лимит одного файла — 2 МБ; каталоги `.git`, `build`, `.gradle`, `__pycache__` пропускаются.

## Обратная совместимость

Старый формат v1 (`{"version":1,"items":...}`) принимается как `full` без файлов.
