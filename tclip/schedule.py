"""Расписание и чередование постов.

Модель:
  * пост с номером i относится к блоку i // posts_per_block, позиция в блоке i % ppb + 1;
  * блок — posts_per_block постов подряд из ОДНОГО источника;
  * типы блоков идут по кругу в порядке type_order (типы без источников пропускаются),
    внутри типа источники идут по очереди;
  * начатые блоки «замораживаются» в state.json (state["blocks"]), поэтому добавление
    или удаление источников не меняет уже начатый блок;
  * текущий пост — следующий необработанный (len(history)); дни без публикации ничего
    не сдвигают, просто текущий пост становится «просроченным».
"""
from __future__ import annotations

import datetime as dt

from .config import TYPE_NAMES

EMPTY_STATE = {"blocks": [], "history": [], "due_date": None, "used_files": {}}


def _d(s) -> dt.date | None:
    if not s:
        return None
    if isinstance(s, dt.date):
        return s
    return dt.date.fromisoformat(str(s)[:10])


def normalize_state(state: dict | None) -> dict:
    st = {k: (v.copy() if isinstance(v, (list, dict)) else v) for k, v in EMPTY_STATE.items()}
    if state:
        st.update(state)
    return st


def enabled_sources(cfg: dict, type_: str | None = None) -> list[dict]:
    return [s for s in cfg.get("sources", []) if s.get("enabled", True) and (type_ is None or s["type"] == type_)]


def active_types(cfg: dict) -> list[str]:
    return [t for t in cfg["type_order"] if enabled_sources(cfg, t)]


def _next_block(cfg: dict, blocks: list[dict]) -> dict | None:
    types = active_types(cfg)
    if not types:
        return None
    if not blocks:
        t = types[0]
    else:
        order = cfg["type_order"]
        last_t = blocks[-1]["type"]
        start = order.index(last_t) if last_t in order else -1
        t = None
        for k in range(1, len(order) + 1):
            cand = order[(start + k) % len(order)]
            if cand in types:
                t = cand
                break
    srcs = enabled_sources(cfg, t)
    ids = [s["id"] for s in srcs]
    prev = [b for b in blocks if b["type"] == t]
    if prev and prev[-1]["source_id"] in ids:
        idx = (ids.index(prev[-1]["source_id"]) + 1) % len(ids)
    else:
        idx = len(prev) % len(ids)
    return {"type": t, "source_id": ids[idx]}


def plan_blocks(cfg: dict, state: dict, upto_block: int, freeze_upto: int | None = None) -> list[dict]:
    """Список блоков 0..upto_block. Замороженные берутся из state, остальные досчитываются.

    Если источник в незавершённом (текущем/будущем) замороженном блоке удалён или
    выключен, этот блок и последующие пересчитываются.
    """
    blocks = list(state.get("blocks") or [])
    ids = {s["id"] for s in enabled_sources(cfg)}
    if freeze_upto is not None:
        for bi in range(min(len(blocks), freeze_upto), len(blocks)):
            if blocks[bi]["source_id"] not in ids:
                blocks = blocks[:bi]
                break
    while len(blocks) <= upto_block:
        nb = _next_block(cfg, blocks)
        if nb is None:
            break
        blocks.append(nb)
    if freeze_upto is not None:
        state["blocks"] = blocks[: freeze_upto + 1]
    return blocks


def current_index(state: dict) -> int:
    return len(state.get("history") or [])


def due_date(cfg: dict, state: dict, today: dt.date) -> dt.date:
    d = _d(state.get("due_date")) or _d(cfg.get("start_date")) or today
    return d


def describe_post(cfg: dict, blocks: list[dict], index: int, due: dt.date) -> dict | None:
    ppb = cfg["posts_per_block"]
    b = index // ppb
    if b >= len(blocks):
        return None
    blk = blocks[b]
    src = next((s for s in cfg["sources"] if s["id"] == blk["source_id"]), None)
    return {
        "index": index,
        "block": b,
        "pos": index % ppb + 1,
        "posts_per_block": ppb,
        "type": blk["type"],
        "type_name": TYPE_NAMES.get(blk["type"], blk["type"]),
        "source_id": blk["source_id"],
        "source_title": src["title"] if src else "(источник удалён)",
        "date": due.isoformat(),
    }


def upcoming(cfg: dict, state: dict, today: dt.date, count: int = 9) -> list[dict]:
    """Текущий пост и следующие за ним. Начатый блок замораживается в state."""
    ppb = cfg["posts_per_block"]
    cur = current_index(state)
    last = cur + count - 1
    blocks = plan_blocks(cfg, state, last // ppb, freeze_upto=cur // ppb)
    due = due_date(cfg, state, today)
    out = []
    for k in range(count):
        p = describe_post(cfg, blocks, cur + k, due + dt.timedelta(days=k * cfg["interval_days"]))
        if p is None:
            break
        out.append(p)
    return out


def due_status(due: dt.date, today: dt.date) -> str:
    diff = (due - today).days
    if diff == 0:
        return "сегодня"
    if diff < 0:
        return f"просрочен на {-diff} дн."
    if diff == 1:
        return "завтра"
    return f"через {diff} дн."


def suggest_files(src_type: str, files: list[dict], used: list[str]) -> dict:
    """Какой файл брать: для сериалов/фильмов — первый неиспользованный по порядку имён,
    для стримов/блогеров — самый новый неиспользованный. Плюс «продолжить» последний."""
    unused = [f for f in files if f["path"] not in used]
    if src_type in ("stream", "blogger"):
        unused = sorted(unused, key=lambda f: -f["mtime"])
    last = used[-1] if used else None
    cont = next((f for f in files if f["path"] == last), None)
    return {"next": unused[0] if unused else None, "continue": cont}


# ---------------------------------------------------------------- действия

def publish(cfg: dict, state: dict, today: dt.date, file: str | None = None, clip_id: str | None = None) -> dict:
    posts = upcoming(cfg, state, today, 1)
    if not posts:
        raise ValueError("Нет источников — нечего публиковать")
    p = posts[0]
    due = due_date(cfg, state, today)
    state["history"].append({"index": p["index"], "action": "published", "date": today.isoformat(),
                             "source_id": p["source_id"], "file": file, "clip_id": clip_id,
                             "prev_due": state.get("due_date")})
    if file:
        used = state.setdefault("used_files", {}).setdefault(p["source_id"], [])
        if file in used:
            used.remove(file)
        used.append(file)
    state["due_date"] = (max(due, today) + dt.timedelta(days=cfg["interval_days"])).isoformat()
    return p


def skip(cfg: dict, state: dict, today: dt.date) -> dict:
    """Пропустить текущий пост: следующий пост встаёт на ту же дату."""
    posts = upcoming(cfg, state, today, 1)
    if not posts:
        raise ValueError("Нет источников")
    p = posts[0]
    state["history"].append({"index": p["index"], "action": "skipped", "date": today.isoformat(),
                             "source_id": p["source_id"], "file": None, "clip_id": None,
                             "prev_due": state.get("due_date")})
    state["due_date"] = due_date(cfg, state, today).isoformat()
    return p


def postpone(cfg: dict, state: dict, today: dt.date, days: int = 1) -> str:
    due = max(due_date(cfg, state, today), today) + dt.timedelta(days=max(1, int(days)))
    state["due_date"] = due.isoformat()
    return state["due_date"]


def undo(state: dict) -> dict | None:
    """Отменить последнее действие (на случай случайного нажатия)."""
    if not state.get("history"):
        return None
    last = state["history"].pop()
    if last.get("file"):
        used = state.get("used_files", {}).get(last["source_id"], [])
        if last["file"] in used:
            used.remove(last["file"])
    state["due_date"] = last.get("prev_due")
    return last
