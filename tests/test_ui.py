"""Запуск UI-прогона Playwright (Node) из pytest. Пропускается, если нет node или playwright."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent


def _has_playwright() -> bool:
    if not shutil.which("node"):
        return False
    r = subprocess.run(["node", "-e", "require('playwright')"], capture_output=True)
    return r.returncode == 0


@pytest.mark.skipif(not _has_playwright(), reason="нет node + playwright")
def test_mobile_ui_flow(tmp_path):
    demo = tmp_path / "demo"
    shots = ROOT / "docs" / "screenshots"
    subprocess.run([sys.executable, str(ROOT / "tests/ui/make_demo.py"), str(demo)], check=True)
    r = subprocess.run(["node", str(ROOT / "tests/ui/ui_test.cjs"), str(ROOT), str(demo), str(shots)],
                       capture_output=True, text=True, timeout=600)
    print(r.stdout[-4000:])
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-3000:]
