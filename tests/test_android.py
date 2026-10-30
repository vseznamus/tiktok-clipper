"""Проверки Android-приложения без телефона: компиляция против классов Android, тесты ядра на JVM,
UI-тест режима приложения. Нужны jar-файлы (переменные ANDROID_ALL, VOSK_JAR, JNA_JAR), иначе пропуск."""
from __future__ import annotations

import filecmp
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
AND = ROOT / "android"
LIBS = {k: os.environ.get(k) for k in ("ANDROID_ALL", "VOSK_JAR", "JNA_JAR")}
need_libs = pytest.mark.skipif(not all(v and Path(v).is_file() for v in LIBS.values()),
                               reason="нет ANDROID_ALL/VOSK_JAR/JNA_JAR")


def test_web_assets_in_sync():
    """Интерфейс в APK совпадает с веб-версией."""
    for name in ("index.html", "app.js", "app.css", "manifest.webmanifest"):
        assert filecmp.cmp(ROOT / "tclip/static" / name, AND / "app/src/main/assets/web" / name, shallow=False), name


@need_libs
def test_android_sources_compile(tmp_path):
    srcs = [str(p) for p in AND.glob("app/src/main/java/**/*.java")]
    cp = ":".join(LIBS.values())
    r = subprocess.run(["javac", "-encoding", "UTF-8", "--release", "8", "-Xlint:-options", "-nowarn", "-cp", cp,
                        "-d", str(tmp_path), *srcs], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr[-3000:]


@need_libs
def test_jvm_core_and_logic():
    env = dict(os.environ, PATH=f"{Path(sys.executable).parent}:{os.environ['PATH']}")
    r = subprocess.run(["bash", str(AND / "tools/run_jvm_tests.sh")], env=env, capture_output=True, text=True, timeout=600)
    print(r.stdout[-3000:])
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]
    assert "✘" not in r.stdout


@need_libs
@pytest.mark.skipif(not shutil.which("node"), reason="нет node")
def test_app_mode_ui(tmp_path):
    classes = tmp_path / "classes"
    classes.mkdir()
    srcs = [str(p) for p in AND.glob("app/src/main/java/ru/tiktokclipper/core/*.java")]
    subprocess.run(["javac", "-encoding", "UTF-8", "--release", "8", "-Xlint:-options", "-nowarn", "-cp", LIBS["ANDROID_ALL"],
                    "-d", str(classes), *srcs, str(AND / "tools/jvmtest/DesktopServer.java")], check=True)
    demo = tmp_path / "demo"
    subprocess.run([sys.executable, str(ROOT / "tests/ui/make_demo.py"), str(demo)], check=True, capture_output=True)
    shutil.copy(demo / "media/matrix/matrix.mp4", demo / "media/matrix/old_rip.mp4")
    r = subprocess.run(["node", str(AND / "tools/jvmtest/ui_app_test.cjs"), str(classes), LIBS["ANDROID_ALL"], str(demo),
                        str(ROOT / "docs/screenshots/app")], capture_output=True, text=True, timeout=600)
    print(r.stdout[-3000:])
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]
