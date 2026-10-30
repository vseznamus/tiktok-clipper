#!/bin/sh
# Программа работает от имени владельца папки /data: файлы на компьютере будут принадлежать вам,
# а папки, созданные самим Docker (владелец root), тоже будут доступны для записи.
set -e
mkdir -p /data /media /clips
uid=$(stat -c %u /data)
gid=$(stat -c %g /data)
if [ "$(id -u)" = "0" ] && [ "$uid" != "0" ]; then
  exec setpriv --reuid "$uid" --regid "$gid" --clear-groups env HOME=/tmp "$@"
fi
exec "$@"
