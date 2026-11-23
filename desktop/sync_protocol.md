# PocketCode Sync Protocol v2

Транспорт: HTTP POST `http://<PC>:8765/api/sync/push` (телефон → ПК) и
`POST http://<телефон>:8765/api/sync/push` (ПК → телефон).
Ответы ПК: `GET /api/sync/pull?since=<seq>`, `GET /api/events?since=<seq>` (long-poll 20 c).
Все данные JSON (UTF-8). Секреты маскируются до отправки и в логах.

## Пакеты

### full — полный снимок (периодический режим)
```json
{
  "v": 2, "type": "full", "seq": 12, "device": "Infinix X665B",
  "project": "main", "ts": 1730000000000,
  "items": [{"role":"user","text":"..."},{"role":"assistant","text":"..."}],
  "files": [{"path":"src/Main.kt","text":"..."}]
}
```

### event — точечное изменение
```json
{
  "v": 2, "type": "event", "seq": 13, "device": "Infinix X665B",
  "project": "main", "ts": 1730000001000,
  "kind": "message",
  "data": {"role":"user","text":"..."}
}
```

`kind`: `message`, `file`, `clear`, `status`, `ping`.

### Дедупликация
Пакет с парой `(seq, device)` записывается один раз. Повторная отправка того же
пакета не дублирует сообщения и файлы. `seq` монотонен в пределах устройства.

## Аутентификация
- TELEFON → PC: заголовок `X-PocketCode-Token: <токен из /api/sync/token>`.
- PC → телефон: тот же заголовок из настроек (`syncToken`).
- Локальный браузер (127.0.0.1) работает без токена; удалённый — только с токеном.

## E2E-шифрование
`PCENC1:<base64(iv 12 байт)>:<base64(ciphertext+tag 128 bit)>`,
AES-256-GCM. Ключ = `SHA-256(password)` ×2000 итераций (совместимо с Android
`SyncCrypto`). Без пароля передаётся открытый JSON. Если `cryptography` не
установлен, ПК явно сообщает об ошибке, а не притворяется зашифрованным.

## Двусторонность
- ПК сохраняет принятые `full.items` в `chat.jsonl`, файлы — в `sync/files/`.
- События ПК (`message`, `file`, `clear`, `ping`) идут в `sync/outbox.jsonl`
  и доставляются на телефон фоновым потоком с ретраями; при неудаче остаются в очереди.
- Периодический `full` от ПК отправляется раз в `intervalMin` минут (по умолчанию 15).
- Настройки: `POST /api/sync/config` (`phoneUrl`, `token`, `password`, `intervalMin`, `enabled`, `full`, `applyRemote`).

## Ограничения
- Текстовые файлы до 2 МБ, не более 300 файлов на пакет.
- Исключаются `.git`, `build`, `node_modules`, `__pycache__`, `.env`, ключи и бинарники.
- Пути проверяются: `..`, абсолютные пути и `~` отклоняются.
- Журнал не содержит токенов и текста сообщений.
