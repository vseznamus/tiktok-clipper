#!/usr/bin/env bash
# Установка tiktok-clipper одной командой.
#
#   bash install.sh                 — основная установка (Termux на Android или Linux)
#   bash install.sh --whisper       — плюс распознавание речи (Termux: собрать whisper.cpp, ПК: faster-whisper)
#   bash install.sh --model base    — скачать другую модель для whisper.cpp (tiny, base, small)
#   bash install.sh --face          — попробовать поставить OpenCV для автопоиска вебкамеры
#   bash install.sh --autostart     — Termux: запускать сервер при включении телефона (нужно приложение Termux:Boot)
set -u
cd "$(dirname "$0")" || exit 1
ROOT="$(pwd)"

say()  { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✔ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;35m  ! %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m  ✘ ОШИБКА: %s\033[0m\n\n' "$*"; exit 1; }

WHISPER=0; FACE=0; AUTOSTART=0; MODEL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --whisper) WHISPER=1 ;;
    --face) FACE=1 ;;
    --autostart) AUTOSTART=1 ;;
    --model) shift; MODEL="${1:-}"; WHISPER=1 ;;
    *) warn "Неизвестный параметр: $1" ;;
  esac
  shift
done

IS_TERMUX=0
case "${PREFIX:-}" in *com.termux*) IS_TERMUX=1 ;; esac

# ------------------------------------------------------------------ системные пакеты
if [ $IS_TERMUX = 1 ]; then
  say "Termux: проверяю пакеты"
  need=""
  command -v python >/dev/null 2>&1 || need="$need python"
  command -v ffmpeg >/dev/null 2>&1 || need="$need ffmpeg"
  if [ -n "$need" ]; then
    echo "  Устанавливаю:$need (это может занять несколько минут)"
    pkg install -y $need || fail "Не удалось установить:$need. Выполните: pkg update, затем снова bash install.sh"
  fi
  if [ ! -d "$HOME/storage/shared" ]; then
    warn "Нет доступа к памяти телефона. Сейчас появится запрос — нажмите «Разрешить»."
    termux-setup-storage
    sleep 4
    [ -d "$HOME/storage/shared" ] || fail "Доступ к памяти не выдан. Выполните termux-setup-storage, разрешите доступ и повторите установку."
  fi
  ok "Доступ к памяти телефона есть"
fi

say "Проверяю ffmpeg"
command -v ffmpeg >/dev/null 2>&1 || fail "ffmpeg не найден. Termux: pkg install ffmpeg. Ubuntu/Debian: sudo apt install ffmpeg"
command -v ffprobe >/dev/null 2>&1 || fail "ffprobe не найден (обычно идёт вместе с ffmpeg). Переустановите ffmpeg."
ffmpeg -hide_banner -filters 2>/dev/null | grep -qE '^ *[^ ]+ +ass ' \
  || fail "ffmpeg собран без libass — субтитры вшить нельзя. Установите ffmpeg из pkg/apt, а не из стороннего сборника."
ffmpeg -hide_banner -encoders 2>/dev/null | grep -qE '^ *[^ ]+ +libx264 ' || fail "ffmpeg собран без libx264 (H.264)."
ok "ffmpeg с libass и H.264 на месте"

say "Проверяю Python"
PY=python3; command -v $PY >/dev/null 2>&1 || PY=python
command -v $PY >/dev/null 2>&1 || fail "Python не найден. Termux: pkg install python. Ubuntu: sudo apt install python3 python3-venv"
$PY -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' || fail "Нужен Python 3.10 или новее"
ok "$($PY --version)"

# ------------------------------------------------------------------ окружение Python
say "Ставлю зависимости Python (flask, waitress)"
if [ ! -x .venv/bin/python ]; then
  if [ $IS_TERMUX = 1 ]; then
    $PY -m venv --system-site-packages .venv || fail "Не удалось создать окружение Python"
  else
    $PY -m venv .venv || fail "Не удалось создать окружение. Ubuntu/Debian: sudo apt install python3-venv"
  fi
fi
.venv/bin/python -m pip install -q --upgrade pip >/dev/null 2>&1
.venv/bin/python -m pip install -q -r requirements.txt || fail "pip не смог установить зависимости. Проверьте интернет и повторите."
ok "Зависимости установлены"

# ------------------------------------------------------------------ распознавание речи
if [ $WHISPER = 1 ]; then
  if [ $IS_TERMUX = 1 ]; then
    MODEL="${MODEL:-tiny}"
    say "Собираю whisper.cpp (10–30 минут, телефон может нагреться)"
    pkg install -y clang cmake make git curl || fail "Не удалось поставить инструменты сборки (clang, cmake)"
    if [ ! -d whisper.cpp ]; then
      git clone --depth 1 https://github.com/ggml-org/whisper.cpp whisper.cpp || fail "Не удалось скачать whisper.cpp с GitHub"
    fi
    ( cd whisper.cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF \
        && cmake --build build -j "$(nproc 2>/dev/null || echo 4)" --config Release --target whisper-cli ) \
      || fail "Сборка whisper.cpp не удалась. Клипы будут работать без субтитров."
    ok "whisper.cpp собран"
  else
    MODEL="${MODEL:-}"
    say "Ставлю faster-whisper (около 200 МБ)"
    .venv/bin/python -m pip install -q faster-whisper || fail "Не удалось установить faster-whisper"
    ok "faster-whisper установлен. Модель скачается сама при первом клипе с субтитрами."
  fi
  if [ -n "$MODEL" ]; then
    case "$MODEL" in tiny|base|small|medium) ;; *) fail "Модель должна быть tiny, base, small или medium" ;; esac
    mkdir -p data/models/whisper.cpp
    dst="data/models/whisper.cpp/ggml-$MODEL.bin"
    if [ ! -s "$dst" ]; then
      say "Скачиваю модель $MODEL"
      curl -L --fail -o "$dst.part" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$MODEL.bin" \
        && mv "$dst.part" "$dst" || { rm -f "$dst.part"; fail "Не удалось скачать модель $MODEL"; }
    fi
    ok "Модель $MODEL готова"
  fi
fi

if [ $FACE = 1 ]; then
  say "Пробую поставить OpenCV (необязательно)"
  if [ $IS_TERMUX = 1 ]; then
    pkg install -y opencv-python && ok "OpenCV установлен" || warn "OpenCV в Termux не встал — рамку вебкамеры задавайте пальцем, всё остальное работает."
  else
    .venv/bin/python -m pip install -q "opencv-python-headless>=4.8,<5" && ok "OpenCV установлен" || warn "OpenCV не встал — это не страшно."
  fi
fi

# ------------------------------------------------------------------ папки и ярлыки
say "Создаю папки"
.venv/bin/python - <<'EOF' || fail "Не удалось создать папки"
from tclip.config import Store
s = Store()
for p in (s.media_root(), s.output_dir()):
    p.mkdir(parents=True, exist_ok=True)
    print("  ", p)
EOF
chmod +x start.sh clipper.py 2>/dev/null

if [ $IS_TERMUX = 1 ]; then
  if ! grep -q "alias clipper=" "$HOME/.bashrc" 2>/dev/null; then
    echo "alias clipper='bash $ROOT/start.sh'" >> "$HOME/.bashrc"
  fi
  ok "Команда для запуска: clipper (или bash $ROOT/start.sh)"
  if [ $AUTOSTART = 1 ]; then
    mkdir -p "$HOME/.termux/boot"
    printf '#!/data/data/com.termux/files/usr/bin/sh\ntermux-wake-lock\ncd %s && nohup bash start.sh > data/server.log 2>&1 &\n' "$ROOT" > "$HOME/.termux/boot/tiktok-clipper.sh"
    chmod +x "$HOME/.termux/boot/tiktok-clipper.sh"
    ok "Автозапуск настроен (нужно приложение Termux:Boot из F-Droid, откройте его один раз)"
  fi
fi

say "Проверка"
.venv/bin/python clipper.py check || warn "Есть замечания выше — прочитайте их."
printf '\n\033[1;32mУстановка завершена.\033[0m Запуск сервера:  bash start.sh\n\n'
