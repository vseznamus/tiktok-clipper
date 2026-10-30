"""Необязательное автоопределение рамки вебкамеры по лицу (OpenCV).

Если OpenCV не установлен (часто так в Termux), функция честно сообщает об этом,
а рамку пользователь задаёт пальцем на экране «Рамка вебкамеры».
"""
from __future__ import annotations

from pathlib import Path


def available() -> bool:
    try:
        import cv2
        return hasattr(cv2, "CascadeClassifier")  # в OpenCV 5 детектор Haar убран
    except Exception:
        return False


def detect_cam_rect(frames: list[Path]) -> dict:
    try:
        import cv2
    except Exception:
        return {"ok": False, "error": "OpenCV не установлен — задайте рамку вручную"}
    if not hasattr(cv2, "CascadeClassifier"):
        return {"ok": False, "error": "В этой версии OpenCV нет детектора лиц — задайте рамку вручную"}
    cascade = cv2.CascadeClassifier(str(Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"))
    found = []
    for fp in frames:
        img = cv2.imread(str(fp))
        if img is None:
            continue
        h, w = img.shape[:2]
        gray = cv2.equalizeHist(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
        faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=6, minSize=(max(24, w // 40),) * 2)
        for (x, y, fw, fh) in faces:
            found.append((x / w, y / h, fw / w, fh / h))
    if not found:
        return {"ok": False, "error": "Лицо не найдено — задайте рамку вручную"}
    # самое крупное лицо, усреднённое по кадрам, где оно примерно на том же месте
    big = max(found, key=lambda f: f[2] * f[3])
    near = [f for f in found if abs(f[0] - big[0]) < 0.08 and abs(f[1] - big[1]) < 0.08]
    x, y, fw, fh = (sum(v) / len(near) for v in zip(*near))
    cx, cy = x + fw / 2, y + fh / 2
    rw, rh = min(1.0, fw * 3.0), min(1.0, fh * 3.2)  # окно вебкамеры шире лица: плечи и фон
    rx = min(max(0.0, cx - rw / 2), 1 - rw)
    ry = min(max(0.0, cy - rh * 0.42), 1 - rh)
    return {"ok": True, "rect": [round(rx, 4), round(ry, 4), round(rw, 4), round(rh, 4)], "faces": len(found)}
