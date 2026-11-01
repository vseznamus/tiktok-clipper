#!/bin/sh
# Установка бота автоклипов Twitch на роутер Keenetic с Entware (OPKG). В SSH-консоли Entware выполните:
#
#   opkg update && opkg install curl ca-bundle
#   curl -fsSL https://raw.githubusercontent.com/vseznamus/tiktok-clipper/main/clipbot/install-keenetic.sh | sh
#
# Повторный запуск — обновление (ключи, каналы и вход в Twitch сохраняются).
DIR=/opt/clipbot
RAW=${CLIPBOT_RAW:-https://raw.githubusercontent.com/vseznamus/tiktok-clipper/main/clipbot}
FILES="__init__ bot net spike store telegram twitch"
export PATH=/opt/sbin:/opt/bin:/opt/usr/bin:/usr/sbin:/usr/bin:/sbin:/bin

say()  { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✔ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;35m  ! %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m  ✘ ОШИБКА: %s\033[0m\n\n' "$*"; exit 1; }
ask() { # ask ПЕРЕМЕННАЯ "вопрос" [secret]
  v=""
  while [ -z "$v" ]; do
    printf '%s: ' "$2" > /dev/tty
    [ "${3:-}" = secret ] && stty -echo < /dev/tty 2>/dev/null
    read -r v < /dev/tty
    [ "${3:-}" = secret ] && { stty echo < /dev/tty 2>/dev/null; echo > /dev/tty; }
    v="$(printf '%s' "$v" | tr -d ' \t\r\n')"
  done
  eval "$1=\$v"
}

main() {  # всё тело в функции: при «curl | sh» скрипт читается целиком до запуска
[ -x /opt/bin/opkg ] || fail "Не найден Entware (/opt/bin/opkg). Установите OPKG и Entware по инструкции Keenetic, затем зайдите по SSH в Entware."

say "Проверяю роутер"
MEM=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
FREE=$(df -m /opt | awk 'NR==2 {print $4}')
echo "  Память: ${MEM} МБ, свободно в /opt: ${FREE} МБ, процессор: $(uname -m)"
[ "${FREE:-0}" -ge 60 ] || fail "Мало места в /opt (нужно 60 МБ). Поставьте Entware на флешку."
[ "${MEM:-0}" -ge 120 ] || warn "Памяти мало (${MEM} МБ) — бот может мешать работе роутера. Лучше модель с 256 МБ и больше."
ok "Подходит"

say "Python (10–40 МБ, несколько минут)"
opkg update >/dev/null 2>&1
need_py() { python3 -c "import asyncio, ssl, json, urllib.request, statistics, secrets" >/dev/null 2>&1; }
need_py || opkg install python3 ca-bundle || fail "Не удалось установить python3 (opkg install python3)"
need_py || opkg install python3-asyncio python3-openssl python3-urllib python3-email python3-codecs python3-decimal >/dev/null 2>&1
need_py || fail "Python установлен не полностью. Выполните: opkg install python3 — и запустите установку снова."
opkg install ca-bundle >/dev/null 2>&1
CERT=/opt/etc/ssl/certs/ca-certificates.crt
[ -f "$CERT" ] || fail "Нет корневых сертификатов ($CERT). Выполните: opkg install ca-bundle"
ok "$(python3 --version)"

say "Код бота"
mkdir -p "$DIR/clipbot" "$DIR/data" && chmod 700 "$DIR/data"
for f in $FILES; do
  curl -fsSL "$RAW/$f.py" -o "$DIR/clipbot/$f.py.new" || fail "Не удалось скачать $f.py — проверьте интернет на роутере"
done
for f in $FILES; do mv "$DIR/clipbot/$f.py.new" "$DIR/clipbot/$f.py"; done
ok "Код в $DIR"

if [ ! -f "$DIR/.env" ]; then
  say "Ключи (остаются только на роутере, в файле $DIR/.env)"
  echo "  Токен бота — у @BotFather в Телеграме (/newbot). Ключи Twitch — dev.twitch.tv/console → ваше приложение."
  ask TG "Токен бота Телеграм" secret
  ask CID "Twitch Client ID"
  ask CSEC "Twitch Client Secret" secret
  PAIR=$(python3 -c "import secrets; print('%08d' % secrets.randbelow(10**8))")
  umask 077
  cat > "$DIR/.env" <<EOF
TELEGRAM_BOT_TOKEN=$TG
TWITCH_CLIENT_ID=$CID
TWITCH_CLIENT_SECRET=$CSEC
BOT_PAIR_CODE=$PAIR
TWITCH_REDIRECT_URI=http://localhost
EOF
  chmod 600 "$DIR/.env"
  ok "Ключи сохранены"
fi

# Запуск в цикле: если бот упадёт или пропадёт интернет — поднимется сам
cat > "$DIR/run.sh" <<EOF
#!/bin/sh
cd $DIR || exit 1
set -a; . ./.env; set +a
export DATA_DIR=$DIR/data SSL_CERT_FILE=$CERT PYTHONUNBUFFERED=1 PATH=/opt/sbin:/opt/bin:\$PATH
trap 'kill \$CHILD 2>/dev/null; exit 0' TERM INT
while true; do
  [ -f bot.log ] && [ \$(wc -c < bot.log) -gt 500000 ] && mv bot.log bot.log.1
  python3 -m clipbot.bot >> bot.log 2>&1 &
  CHILD=\$!
  wait \$CHILD
  sleep 15
done
EOF
chmod 700 "$DIR/run.sh"

# Автозапуск при включении роутера (Entware запускает /opt/etc/init.d/S*)
cat > /opt/etc/init.d/S99clipbot <<EOF
#!/bin/sh
PIDF=$DIR/run.pid
running() { [ -f \$PIDF ] && kill -0 \$(cat \$PIDF) 2>/dev/null; }
case "\$1" in
  start)   running && { echo "бот уже работает"; exit 0; }
           nohup $DIR/run.sh >/dev/null 2>&1 &
           echo \$! > \$PIDF; echo "бот запущен" ;;
  stop)    running && kill \$(cat \$PIDF); rm -f \$PIDF; echo "бот остановлен" ;;
  restart) \$0 stop; sleep 3; \$0 start ;;
  status)  running && echo "бот работает" || echo "бот не работает" ;;
  *)       echo "Использование: \$0 start|stop|restart|status" ;;
esac
EOF
chmod 755 /opt/etc/init.d/S99clipbot

say "Запуск"
/opt/etc/init.d/S99clipbot restart >/dev/null
sleep 8
if ! /opt/etc/init.d/S99clipbot status | grep -q "работает" || ! grep -q "Бот автоклипов запущен" "$DIR/bot.log" 2>/dev/null; then
  tail -n 20 "$DIR/bot.log" 2>/dev/null
  fail "Бот не запустился (причина выше). Ключи: vi $DIR/.env, затем снова команда установки."
fi
ok "Бот работает и сам запускается при включении роутера"

PAIR=$(grep '^BOT_PAIR_CODE=' "$DIR/.env" | cut -d= -f2)
printf '\n\033[1;32mГотово!\033[0m Откройте своего бота в Телеграме и отправьте:\n\n    /start %s\n\n' "$PAIR"
echo "Журнал:       tail -f $DIR/bot.log"
echo "Перезапуск:   /opt/etc/init.d/S99clipbot restart"
}

main "$@"
