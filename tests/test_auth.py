"""Безопасность: без токена на 0.0.0.0 не стартует; с токеном без входа данные не отдаются."""
from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from tclip.server import bind_problem, create_app

ROOT = Path(__file__).resolve().parent.parent


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def run_server(tmp_path, host, token):
    env = {k: v for k, v in os.environ.items() if k != "CLIPPER_TOKEN"}
    env["CLIPPER_DATA"] = str(tmp_path / "data")
    env["CLIPPER_MEDIA"] = str(tmp_path / "media")
    env["CLIPPER_OUTPUT"] = str(tmp_path / "out")
    if token is not None:
        env["CLIPPER_TOKEN"] = token
    port = free_port()
    proc = subprocess.Popen([sys.executable, str(ROOT / "clipper.py"), "serve", "--host", host, "--port", str(port)],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return proc, port


def wait_port(port, host="127.0.0.1", timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        try:
            socket.create_connection((host, port), timeout=0.5).close()
            return True
        except OSError:
            time.sleep(0.2)
    return False


def test_bind_problem_rules():
    assert bind_problem("127.0.0.1", "") is None
    assert bind_problem("::1", "") is None
    assert bind_problem("0.0.0.0", "") is not None
    assert bind_problem("192.168.1.5", "") is not None
    assert bind_problem("0.0.0.0", "short") is not None
    assert bind_problem("0.0.0.0", "достаточно-длинный") is None


def test_refuses_to_start_on_all_interfaces_without_token(tmp_path):
    proc, port = run_server(tmp_path, "0.0.0.0", None)
    out, err = proc.communicate(timeout=30)
    assert proc.returncode == 2
    assert "сервер не запущен" in err and "CLIPPER_TOKEN" in err
    with pytest.raises(OSError):
        socket.create_connection(("127.0.0.1", port), timeout=1).close()


def test_refuses_short_token(tmp_path):
    proc, _ = run_server(tmp_path, "0.0.0.0", "123")
    _, err = proc.communicate(timeout=30)
    assert proc.returncode == 2 and "короткий" in err


def get(url, headers=None, data=None):
    req = urllib.request.Request(url, headers=headers or {}, data=data)
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=10) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def test_with_token_everything_requires_login(tmp_path):
    token = "Ochen-Sekretnyi-Parol-42"
    proc, port = run_server(tmp_path, "0.0.0.0", token)
    try:
        assert wait_port(port), proc.stderr.read() if proc.poll() is not None else "порт не открылся"
        hosts = ["127.0.0.1"] + ([lan_ip()] if lan_ip() else [])
        for h in hosts:
            base = f"http://{h}:{port}"
            for path in ("/api/today", "/api/config", "/api/jobs", "/api/files?source=x", "/media/x.mp4",
                         "/clips/abc.mp4", "/api/status", "/static/app.js", "/sw.js"):
                code, body, _ = get(base + path)
                assert code == 401, (h, path, code)
                assert b"sources" not in body
            code, _, hdr = get(base + "/")
            assert code == 302 and hdr["Location"].endswith("/login")
            code, body, _ = get(base + "/login")
            assert code == 200 and "Пароль".encode() in body
            # неверный пароль
            code, _, _ = get(base + "/login", data="password=wrong".encode())
            assert code == 401
            # Bearer-токен
            code, body, _ = get(base + "/api/config", headers={"Authorization": "Bearer " + token})
            assert code == 200 and b"sources" in body
        # вход через форму → cookie
        from urllib.parse import quote
        code, _, hdr = get(f"http://127.0.0.1:{port}/login", data=("password=" + quote(token)).encode())
        assert code == 302
        cookie = hdr["Set-Cookie"].split(";")[0]
        assert "HttpOnly" in hdr["Set-Cookie"] and "SameSite=Strict" in hdr["Set-Cookie"]
        assert token not in cookie  # в cookie не сам пароль, а производное значение
        code, _, _ = get(f"http://127.0.0.1:{port}/api/today", headers={"Cookie": cookie})
        assert code == 200
    finally:
        proc.terminate()
        proc.wait(timeout=10)


def test_cyrillic_password_works_via_login_form(tmp_path):
    from tclip.config import Store
    app = create_app(Store(tmp_path / "d"), token="пароль-по-русски", start_worker=False)
    c = app.test_client()
    assert c.get("/api/today").status_code == 401
    assert c.post("/login", data={"password": "пароль-по-русски"}).status_code == 302
    assert c.get("/api/today").status_code == 200


def test_login_rate_limit(tmp_path):
    from tclip.config import Store
    app = create_app(Store(tmp_path / "d"), token="правильный-пароль", start_worker=False)
    c = app.test_client()
    codes = [c.post("/login", data={"password": "x"}).status_code for _ in range(10)]
    assert codes[:8] == [401] * 8 and codes[8] == 429


def test_without_token_only_local_host_header(tmp_path):
    from tclip.config import Store
    app = create_app(Store(tmp_path / "d"), token="", start_worker=False)
    c = app.test_client()
    assert c.get("/api/config", headers={"Host": "127.0.0.1:8080"}).status_code == 200
    assert c.get("/api/config", headers={"Host": "localhost:8080"}).status_code == 200
    # DNS-rebinding / проброс через прокси без пароля — запрещено
    assert c.get("/api/config", headers={"Host": "evil.example.com"}).status_code == 403
    assert c.get("/api/config", headers={"Host": "192.168.1.10:8080"}).status_code == 403
    assert c.get("/api/config", headers={"Host": "127.0.0.1", "X-Forwarded-For": "8.8.8.8"}).status_code == 403
    assert c.get("/api/config", environ_base={"REMOTE_ADDR": "192.168.1.20"},
                 headers={"Host": "127.0.0.1"}).status_code == 403


def test_path_traversal_blocked(tmp_path):
    from tclip.config import Store
    s = Store(tmp_path / "d")
    s.save_settings({"media_root": str(tmp_path / "m")})
    (tmp_path / "m").mkdir()
    (tmp_path / "secret.mp4").write_bytes(b"x")
    app = create_app(s, token="", start_worker=False)
    c = app.test_client()
    h = {"Host": "127.0.0.1"}
    assert c.get("/media/../secret.mp4", headers=h).status_code in (403, 404)
    assert c.get("/media/%2e%2e/secret.mp4", headers=h).status_code in (403, 404)
    assert c.get("/api/frame?file=../secret.mp4&t=0", headers=h).status_code in (400, 403)
    r = c.post("/api/jobs", json={"type": "clip", "params": {"file": "../secret.mp4", "start": 0, "end": 5}}, headers=h)
    assert r.status_code == 400
    r = c.post("/api/sources", json={"type": "movie", "title": "x", "folder": "../../etc"}, headers=h)
    assert r.status_code == 400
