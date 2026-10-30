"""«Самое свежее» у стримеров и блогеров: YouTube RSS (без ключей) и Twitch (по желанию).

Ничего не скачивается — только ссылка, название и дата, чтобы не пропустить новое видео.
"""
from __future__ import annotations

import json
import os
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

_cache: dict[str, tuple[float, dict]] = {}
TTL = 30 * 60
NS = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015",
      "media": "http://search.yahoo.com/mrss/"}


def parse_youtube_rss(xml_text: str, limit: int = 3) -> list[dict]:
    root = ET.fromstring(xml_text)
    items = []
    for e in root.findall("a:entry", NS)[:limit]:
        link = e.find("a:link", NS)
        items.append({
            "title": (e.findtext("a:title", default="", namespaces=NS) or "").strip(),
            "url": link.get("href") if link is not None else "",
            "published": e.findtext("a:published", default="", namespaces=NS),
            "video_id": e.findtext("yt:videoId", default="", namespaces=NS),
        })
    return items


def _get(url: str, headers: dict | None = None, data: bytes | None = None, timeout: float = 8) -> bytes:
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "tiktok-clipper/1.0", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def youtube_latest(channel_id: str) -> dict:
    channel_id = (channel_id or "").strip()
    if not channel_id:
        return {"ok": False, "error": "Не указан ID YouTube-канала"}
    key = "yt:" + channel_id
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < TTL:
        return hit[1]
    url = "https://www.youtube.com/feeds/videos.xml?channel_id=" + urllib.parse.quote(channel_id)
    try:
        items = parse_youtube_rss(_get(url).decode("utf-8", errors="replace"))
        res = {"ok": True, "items": items}
    except Exception as e:  # сеть, 404, неверный ID
        res = {"ok": False, "error": f"YouTube недоступен или неверный ID канала ({type(e).__name__})"}
    _cache[key] = (time.time(), res)
    return res


def twitch_latest(login: str) -> dict:
    cid, secret = os.environ.get("TWITCH_CLIENT_ID"), os.environ.get("TWITCH_CLIENT_SECRET")
    if not login:
        return {"ok": False, "error": "Не указан логин Twitch"}
    if not cid or not secret:
        return {"ok": False, "error": "Twitch не настроен: задайте TWITCH_CLIENT_ID и TWITCH_CLIENT_SECRET"}
    key = "tw:" + login
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < TTL:
        return hit[1]
    try:
        tok = json.loads(_get("https://id.twitch.tv/oauth2/token", data=urllib.parse.urlencode(
            {"client_id": cid, "client_secret": secret, "grant_type": "client_credentials"}).encode()))
        h = {"Client-ID": cid, "Authorization": "Bearer " + tok["access_token"]}
        users = json.loads(_get("https://api.twitch.tv/helix/users?login=" + urllib.parse.quote(login), h))
        uid = users["data"][0]["id"]
        vids = json.loads(_get(f"https://api.twitch.tv/helix/videos?user_id={uid}&type=archive&first=3", h))
        items = [{"title": v["title"], "url": v["url"], "published": v["created_at"]} for v in vids["data"]]
        res = {"ok": True, "items": items}
    except Exception as e:
        res = {"ok": False, "error": f"Twitch недоступен ({type(e).__name__})"}
    _cache[key] = (time.time(), res)
    return res
