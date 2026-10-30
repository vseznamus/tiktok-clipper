"""Чередование: блоки по одному источнику, смена типа по кругу, пропуски дней не ломают порядок."""
from __future__ import annotations

import datetime as dt

from tclip import schedule

D0 = dt.date(2026, 11, 2)  # понедельник


def cfg(**kw):
    c = {"start_date": D0.isoformat(), "interval_days": 3, "posts_per_block": 3,
         "type_order": ["series", "movie", "stream", "blogger"],
         "sources": [
             {"id": "bb", "type": "series", "title": "Во все тяжкие"},
             {"id": "got", "type": "series", "title": "Игра престолов"},
             {"id": "matrix", "type": "movie", "title": "Матрица"},
             {"id": "s1", "type": "stream", "title": "Стример 1"},
             {"id": "s2", "type": "stream", "title": "Стример 2"},
             {"id": "blog", "type": "blogger", "title": "Блогер"},
         ]}
    c.update(kw)
    return c


def run_publishing(c, n, days_between=None, skip_every=None):
    """Публикует n постов, между публикациями проходит days_between дней (по умолчанию — по плану)."""
    st = schedule.normalize_state(None)
    today = D0
    seq = []
    for i in range(n):
        p = schedule.upcoming(c, st, today, 1)[0]
        if skip_every and i % skip_every == skip_every - 1:
            schedule.skip(c, st, today)
        else:
            schedule.publish(c, st, today)
        seq.append((p["index"], p["block"], p["pos"], p["type"], p["source_id"]))
        today += dt.timedelta(days=days_between if days_between is not None else c["interval_days"])
    return seq, st


def blocks_of(seq):
    out = {}
    for idx, b, pos, t, sid in seq:
        out.setdefault(b, []).append((pos, t, sid))
    return out


def test_block_is_one_source_and_types_alternate():
    seq, _ = run_publishing(cfg(), 36)
    blocks = blocks_of(seq)
    for b, posts in blocks.items():
        assert [p[0] for p in posts] == [1, 2, 3]
        assert len({p[2] for p in posts}) == 1, f"в блоке {b} разные источники"
    types = [blocks[b][0][1] for b in sorted(blocks)]
    assert all(types[i] != types[i + 1] for i in range(len(types) - 1)), types
    assert types[:8] == ["series", "movie", "stream", "blogger"] * 2
    # внутри типа источники идут по очереди
    series = [blocks[b][0][2] for b in sorted(blocks) if blocks[b][0][1] == "series"]
    assert series == ["bb", "got", "bb"]
    streams = [blocks[b][0][2] for b in sorted(blocks) if blocks[b][0][1] == "stream"]
    assert streams == ["s1", "s2", "s1"]


def test_dates_follow_interval_mon_thu_sun():
    st = schedule.normalize_state(None)
    posts = schedule.upcoming(cfg(), st, D0, 4)
    days = [dt.date.fromisoformat(p["date"]).strftime("%a") for p in posts]
    assert days == ["Mon", "Thu", "Sun", "Wed"]
    assert {p["source_id"] for p in posts[:3]} == {"bb"} and posts[3]["type"] == "movie"


def test_missed_days_do_not_break_order():
    ideal, _ = run_publishing(cfg(), 20)
    late, _ = run_publishing(cfg(), 20, days_between=5)   # публикуем с опозданием
    early, _ = run_publishing(cfg(), 20, days_between=1)  # чаще плана
    assert [s[1:] for s in ideal] == [s[1:] for s in late] == [s[1:] for s in early]


def test_no_publish_keeps_current_post_and_marks_overdue():
    c = cfg()
    st = schedule.normalize_state(None)
    first = schedule.upcoming(c, st, D0, 1)[0]
    later = D0 + dt.timedelta(days=10)
    again = schedule.upcoming(c, st, later, 1)[0]
    assert (again["index"], again["source_id"]) == (first["index"], first["source_id"])
    assert schedule.due_status(dt.date.fromisoformat(again["date"]), later) == "просрочен на 10 дн."
    # после публикации следующий пост — через интервал от сегодняшнего дня
    schedule.publish(c, st, later)
    nxt = schedule.upcoming(c, st, later, 1)[0]
    assert nxt["pos"] == 2 and nxt["source_id"] == "bb"
    assert nxt["date"] == (later + dt.timedelta(days=3)).isoformat()


def test_skip_advances_without_breaking_sequence():
    ideal, _ = run_publishing(cfg(), 18)
    skipped, st = run_publishing(cfg(), 18, skip_every=4)
    assert [s[1:] for s in ideal] == [s[1:] for s in skipped]
    assert sum(1 for h in st["history"] if h["action"] == "skipped") == 4


def test_postpone_and_undo():
    c = cfg()
    st = schedule.normalize_state(None)
    schedule.postpone(c, st, D0, 1)
    assert schedule.upcoming(c, st, D0, 1)[0]["date"] == (D0 + dt.timedelta(days=1)).isoformat()
    schedule.publish(c, st, D0, file="series/e1.mkv")
    assert st["used_files"]["bb"] == ["series/e1.mkv"]
    schedule.undo(st)
    assert st["history"] == [] and st["used_files"]["bb"] == []
    assert schedule.upcoming(c, st, D0, 1)[0]["date"] == (D0 + dt.timedelta(days=1)).isoformat()


def test_adding_source_mid_block_keeps_current_block():
    c = cfg(sources=[{"id": "bb", "type": "series", "title": "BB"}, {"id": "m", "type": "movie", "title": "M"}])
    st = schedule.normalize_state(None)
    schedule.upcoming(c, st, D0, 1)
    schedule.publish(c, st, D0)  # пост 1/3 блока «BB»
    c["sources"].insert(0, {"id": "new", "type": "series", "title": "Новый"})
    p = schedule.upcoming(c, st, D0, 1)[0]
    assert (p["source_id"], p["pos"]) == ("bb", 2)


def test_removed_source_reassigns_current_block():
    c = cfg()
    st = schedule.normalize_state(None)
    schedule.upcoming(c, st, D0, 1)
    c["sources"] = [s for s in c["sources"] if s["id"] != "bb"]
    p = schedule.upcoming(c, st, D0, 1)[0]
    assert p["source_id"] == "got" and p["type"] == "series"


def test_types_without_sources_are_skipped():
    c = cfg(sources=[{"id": "a", "type": "series", "title": "A"}, {"id": "b", "type": "stream", "title": "B"}])
    seq, _ = run_publishing(c, 12)
    types = [t for _, b, pos, t, _ in seq if pos == 1]
    assert types == ["series", "stream", "series", "stream"]


def test_suggest_files():
    files = [{"name": n, "path": "f/" + n, "mtime": m} for n, m in (("E01.mkv", 3), ("E02.mkv", 1), ("E10.mkv", 2))]
    s = schedule.suggest_files("series", files, ["f/E01.mkv"])
    assert s["next"]["name"] == "E02.mkv" and s["continue"]["name"] == "E01.mkv"
    s2 = schedule.suggest_files("stream", files, [])
    assert s2["next"]["name"] == "E01.mkv"  # самый свежий по времени
