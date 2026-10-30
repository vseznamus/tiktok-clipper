#!/usr/bin/env bash
# Запуск сервера.
#   bash start.sh          — только для этого устройства: http://127.0.0.1:8080
#   bash start.sh --lan    — доступ с других устройств в домашней сети (нужен пароль CLIPPER_TOKEN)
cd "$(dirname "$0")" || exit 1
[ -x .venv/bin/python ] || { echo "Программа не установлена. Сначала выполните: bash install.sh"; exit 1; }

# пароль и другие настройки можно хранить в файле .env рядом со start.sh
if [ -f .env ]; then
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue ;; esac
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    [ -z "${!k:-}" ] && export "$k=$v"
  done < .env
fi

HOST="${CLIPPER_HOST:-127.0.0.1}"
PORT="${CLIPPER_PORT:-8080}"
[ "${1:-}" = "--lan" ] && HOST=0.0.0.0

if [ "$HOST" != "127.0.0.1" ]; then
  IP=$(.venv/bin/python -c 'import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(("10.255.255.255",1));print(s.getsockname()[0])' 2>/dev/null)
  [ -n "$IP" ] && echo "  С другого устройства в этой сети откройте: http://$IP:$PORT"
fi
case "${PREFIX:-}" in *com.termux*)
  echo "  Не закрывайте Termux (можно свернуть). Телефон не уснёт, пока идёт нарезка." ;;
esac
exec .venv/bin/python clipper.py serve --host "$HOST" --port "$PORT"
