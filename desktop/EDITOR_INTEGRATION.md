# IDE-интеграция (VS Code / JetBrains / Vim)

PocketCode IDE Bridge — локальный HTTP-сервис на `127.0.0.1:8766`, который принимает задачи
из редактора и добавляет их в очередь PocketCode Desktop.

## Запуск

```sh
python3 ide_bridge.py
```

## VS Code

В `.vscode/tasks.json` проекта добавь задачу:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "PocketCode: send selection",
      "type": "shell",
      "command": "curl -s -X POST http://127.0.0.1:8766/api/task -H 'Content-Type: application/json' -d '{\"text\":\"${selection}\",\"file\":\"${file}\",\"project\":\"${workspaceFolder}\"}'"
    }
  ]
}
```

Затем `Ctrl+Shift+P → Tasks: Run Task → PocketCode: send selection`.

## JetBrains

Settings → Tools → External Tools → `+`:
- Program: `curl`
- Arguments: `-s -X POST http://127.0.0.1:8766/api/task -H "Content-Type: application/json" -d "{\"text\":\"$Selection$\",\"file\":\"$FilePath$\",\"project\":\"$ProjectFileDir$\"}"`

## Vim / Neovim

```vim
:call system('curl -s -X POST http://127.0.0.1:8766/api/task -H "Content-Type: application/json" -d "{\"text\":\"'.expand('%').'\"}"')
```

## Ограничения

- Мост слушает только localhost; открывать его в сеть не рекомендуется.
- Это не полноценный IDE-плагин: нет inline-подсказок и apply-patch из редактора; задачи передаются через очередь Desktop.
