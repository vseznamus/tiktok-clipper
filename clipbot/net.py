"""Мини-HTTP только на стандартной библиотеке Python — без сторонних пакетов, чтобы бот работал и на роутере
(Keenetic + Entware), и на VPS. Запросы выполняются в отдельных потоках, не блокируя чат."""
from __future__ import annotations

import asyncio
import http.client
import json as _json
import os
import ssl
import urllib.error
import urllib.parse
import urllib.request

_CTX: ssl.SSLContext | None = None


def ssl_context() -> ssl.SSLContext:
    """Проверка сертификатов; на роутере путь к корневым сертификатам задаёт SSL_CERT_FILE."""
    global _CTX
    if _CTX is None:
        _CTX = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE") or None)
    return _CTX


class Response:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self.body = body

    def json(self) -> dict:
        try:
            v = _json.loads(self.body or b"{}")
        except ValueError:
            return {}
        return v if isinstance(v, dict) else {}


def _do(method, url, params, form, json, headers, timeout) -> Response:
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    h = dict(headers or {})
    data = None
    if json is not None:
        data = _json.dumps(json).encode()
        h["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl_context()) as r:
            return Response(r.status, r.read())
    except urllib.error.HTTPError as e:
        return Response(e.code, e.read())
    except http.client.HTTPException as e:   # оборванный ответ — как сетевая ошибка
        raise ConnectionError(str(e)) from e


async def request(method: str, url: str, *, params: dict | list | None = None, form: dict | None = None,
                  json: dict | None = None, headers: dict | None = None, timeout: float = 30) -> Response:
    """Сетевые ошибки — OSError (ConnectionError, таймаут); ответы 4xx/5xx — обычный Response."""
    return await asyncio.to_thread(_do, method, url, params, form, json, headers, timeout)
