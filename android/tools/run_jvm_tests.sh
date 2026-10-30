#!/usr/bin/env bash
# Компилирует ядро (ru.tiktokclipper.core) и запускает тесты на обычной JVM.
#   ANDROID_ALL=/путь/к/android-all.jar bash tools/run_jvm_tests.sh
# android-all (org.robolectric:android-all, Maven Central) нужен только ради org.json.
set -e
cd "$(dirname "$0")/.."
: "${ANDROID_ALL:?Укажите путь к android-all.jar в ANDROID_ALL}"
OUT=$(mktemp -d)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn -cp "$ANDROID_ALL" -d "$OUT" \
  app/src/main/java/ru/tiktokclipper/core/*.java tools/jvmtest/CoreTest.java
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT:$ANDROID_ALL" CoreTest || exit 1

# Чистая логика Android-части (ресемплер, сведение каналов, раскладки) — сверка с Python-версией
if [ -n "${VOSK_JAR:-}" ] && [ -n "${JNA_JAR:-}" ]; then
  OUT2=$(mktemp -d)
  javac -encoding UTF-8 --release 8 -Xlint:-options -nowarn -cp "$ANDROID_ALL:$VOSK_JAR:$JNA_JAR" -d "$OUT2" \
    $(find app/src/main/java -name '*.java') tools/jvmtest/AppLogicTest.java
  mapfile -t REFS < <(python3 tools/jvmtest/python_layout_ref.py)
  java -Dstdout.encoding=UTF-8 -cp "$OUT2:$ANDROID_ALL:$VOSK_JAR:$JNA_JAR" ru.tiktokclipper.app.AppLogicTest "${REFS[@]}"
fi
