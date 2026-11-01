#!/usr/bin/env bash
# Установка бота автоклипов Twitch на VPS (Ubuntu/Debian) одной командой:
#
#   curl -fsSL https://raw.githubusercontent.com/vseznamus/tiktok-clipper/main/clipbot/install.sh | sudo bash
#
# Повторный запуск той же команды — обновление (настройки и вход в Twitch сохраняются).
set -u
DIR=/opt/tiktok-clipper
REPO=https://github.com/vseznamus/tiktok-clipper.git

say()  { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✔ %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m  ✘ ОШИБКА: %s\033[0m\n\n' "$*"; exit 1; }
ask()  { # ask ПЕРЕМЕННАЯ "вопрос" [secret] — читаем с клавиатуры, даже если скрипт пришёл через «curl | bash»
  local v=""
  while [ -z "$v" ]; do
    printf '%s: ' "$2" > /dev/tty
    if [ "${3:-}" = secret ]; then read -rs v < /dev/tty; echo > /dev/tty; else read -r v < /dev/tty; fi
    v="$(printf '%s' "$v" | tr -d '[:space:]')"
  done
  printf -v "$1" '%s' "$v"
}

[ "$(id -u)" = 0 ] || fail "Нужны права администратора: запустите через sudo (или под root)."

say "Docker"
if ! command -v docker >/dev/null 2>&1; then
  command -v curl >/dev/null 2>&1 || { apt-get update -y && apt-get install -y curl; } || fail "Не удалось поставить curl"
  curl -fsSL https://get.docker.com | sh || fail "Не удалось установить Docker"
fi
docker compose version >/dev/null 2>&1 || fail "Нет docker compose. Выполните: apt-get install -y docker-compose-plugin"
ok "Docker готов"

say "Код бота"
command -v git >/dev/null 2>&1 || apt-get install -y git || fail "Не удалось поставить git"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only || fail "Не удалось обновить код в $DIR"
else
  git clone --depth 1 "$REPO" "$DIR" || fail "Не удалось скачать код"
fi
cd "$DIR/clipbot" || fail "Нет папки $DIR/clipbot"
ok "Код в $DIR/clipbot"

if [ ! -f .env ]; then
  say "Ключи (вводятся здесь, на сервере, и остаются только в файле $DIR/clipbot/.env)"
  echo "  Токен бота — у @BotFather в Телеграме (/newbot). Ключи Twitch — dev.twitch.tv/console → ваше приложение."
  ask TG "Токен бота Телеграм" secret
  ask CID "Twitch Client ID"
  ask CSEC "Twitch Client Secret" secret
  PAIR="$(tr -dc '0-9' < /dev/urandom | head -c 8)"
  umask 077
  cat > .env <<EOF
TELEGRAM_BOT_TOKEN=$TG
TWITCH_CLIENT_ID=$CID
TWITCH_CLIENT_SECRET=$CSEC
BOT_PAIR_CODE=$PAIR
TWITCH_REDIRECT_URI=http://localhost
EOF
  chmod 600 .env
  ok "Ключи сохранены"
fi
mkdir -p data && chmod 700 data

say "Запуск"
docker compose up -d --build || fail "Бот не запустился. Посмотрите: cd $DIR/clipbot && docker compose logs"
sleep 5
docker compose ps --status running | grep -q clipbot || {
  docker compose logs --tail 30
  fail "Бот остановился (причина выше). Ключи поправить: nano $DIR/clipbot/.env, затем снова эту команду."
}
ok "Бот работает и сам перезапускается после перезагрузки сервера"

PAIR="$(grep '^BOT_PAIR_CODE=' .env | cut -d= -f2)"
printf '\n\033[1;32mГотово!\033[0m Откройте своего бота в Телеграме и отправьте:\n\n    /start %s\n\n' "$PAIR"
echo "Код привязки нужен один раз — после него бот слушается только вас."
echo "Журнал бота:   cd $DIR/clipbot && docker compose logs -f --tail 50"
