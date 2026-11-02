"""Всплески чата: окно 15 с против обычного темпа этого эфира за последние 10 минут.

Считаются и сообщения, и **разные авторы**: всплеск — когда в разы чаще обычного пишут **много людей**, а не один спамер.
Реакции (KEKW, LUL, «ахах», «ору», 😂 …) весят больше обычного сообщения. **Волна** — одно и то же сообщение
(«KEKW», «F», копипаста) от многих авторов сразу: она подкрепляет всплеск и попадает в подпись.
После клипа — пауза `cooldown` секунд. Счёт идёт по секундам, поэтому проверка дешёвая даже в очень быстром чате.
"""
from __future__ import annotations

import re
import statistics
from collections import Counter, deque
from dataclasses import dataclass, field

HYPE = re.compile(
    r"kekw|lul|lmao|omegalul|pog|pogchamp|ахах|хаха|хах|аха|ор[уy]+|лол|жесть|вау|омг|omg|wtf|clip|клип|"
    r"😂|🤣|😱|🔥|!!!|\?\?\?|ееее|ааааа",
    re.IGNORECASE,
)
WINDOW = 15          # секунд в окне
HISTORY = 600        # секунд истории для «обычного темпа»
WORD = re.compile(r"[\w@#'-]+|[^\w\s]", re.UNICODE)
WAVE_BOOST = 1.15    # волна одинаковых сообщений усиливает всплеск
# чат-боты не «реагируют» — их не считаем
BOTS = {"nightbot", "streamelements", "moobot", "fossabot", "streamlabs", "wizebot", "sery_bot", "soundalerts",
        "botrixoficial", "kofistreambot", "pokemoncommunitygame", "commanderroot"}


def wave_key(text: str) -> str:
    """Сообщение «по сути»: «KEKW KEKW KEKW» → «kekw», «ахахаааа» → «ахахаа», без регистра и повторов."""
    t = re.sub(r"(.)\1{2,}", r"\1\1", text.lower().strip())
    words = []
    for w in t.split():
        if w not in words:
            words.append(w)
    return " ".join(words[:5])[:40]


@dataclass
class Spike:
    ratio: float              # во сколько раз активнее обычного (меньшее из «сообщений» и «авторов», с учётом волны)
    msgs: int                 # сообщений за окно
    word: str | None          # самое частое слово или смайл
    authors: int = 0          # разных авторов за окно
    wave: str | None = None   # одинаковое сообщение у многих («KEKW»)
    wave_users: int = 0       # сколько авторов написали его


@dataclass
class ChannelChat:
    threshold: float = 3.0
    min_msgs: int = 15
    cooldown: float = 180.0
    per_sec: dict = field(default_factory=dict)    # секунда → [вес, число сообщений, множество авторов]
    recent: deque = field(default_factory=deque)   # (время, текст, автор) за последнее окно — для подписи и волны
    last_clip: float = -1e18
    started: float | None = None                   # время первого сообщения

    def add(self, t: float, text: str, user: str = "") -> None:
        user = (user or "").lower()
        if user in BOTS:
            return
        if self.started is None:
            self.started = t
        sec = int(t)
        b = self.per_sec.setdefault(sec, [0.0, 0, set()])
        b[0] += 1.6 if HYPE.search(text) else 1.0
        b[1] += 1
        b[2].add(user or f"#{sec}.{b[1]}")  # без ника — считаем каждое сообщение отдельным автором
        self.recent.append((t, text[:80], user))
        while self.recent and self.recent[0][0] < t - WINDOW - 5:
            self.recent.popleft()
        if len(self.per_sec) > HISTORY + 60:
            for s in [s for s in self.per_sec if s < sec - HISTORY]:
                del self.per_sec[s]

    def _window(self, end: float) -> tuple[float, int, int]:
        """Вес, число сообщений и число разных авторов в окне (end − 15 с, end]."""
        e = int(end)
        w, n = 0.0, 0
        users: set = set()
        for s in range(e - WINDOW + 1, e + 1):
            b = self.per_sec.get(s)
            if b:
                w += b[0]
                n += b[1]
                users |= b[2]
        return w, n, len(users)

    def baseline(self, now: float) -> tuple[float, float]:
        """Медианы веса и числа авторов 15-секундных окон за историю (без текущего окна); не меньше 1. 0 — истории нет."""
        start = int(max(self.started if self.started is not None else now, now - HISTORY))
        ws, us = [], []
        for t in range(start + WINDOW - 1, int(now) - WINDOW + 1, 5):
            w, _, u = self._window(t)
            ws.append(w)
            us.append(u)
        if not ws:
            return 0.0, 0.0
        return max(1.0, statistics.median(ws)), max(1.0, statistics.median(us))

    def check(self, now: float, threshold: float | None = None) -> Spike | None:
        """Всплеск прямо сейчас? Нужна хотя бы минута истории, иначе не с чем сравнивать.
        threshold — порог этого канала (с поправкой по оценкам 👍/👎), иначе общий."""
        if self.started is None or now - self.started < 60 or now - self.last_clip < self.cooldown:
            return None
        thr = threshold if threshold is not None else self.threshold
        w, n, u = self._window(now)
        if n < self.min_msgs or u < max(3, self.min_msgs // 3):
            return None
        bw, bu = self.baseline(now)
        if bw <= 0:
            return None
        ratio = min(w / bw, u / bu)  # чаще пишут И больше людей — один спамер всплеска не делает
        wave, wave_users = self.wave(now, u)
        if wave:
            ratio *= WAVE_BOOST
        if ratio < thr:
            return None
        return Spike(ratio=round(ratio, 1), msgs=n, word=self.top_word(now), authors=u, wave=wave, wave_users=wave_users)

    def wave(self, now: float, authors: int) -> tuple[str | None, int]:
        """Одинаковое сообщение от многих авторов в окне: хотя бы 4 человека и 30 % пишущих."""
        by: dict[str, set] = {}
        for t, text, user in self.recent:
            if now - WINDOW < t <= now + 1e-9:
                k = wave_key(text)
                if k:
                    by.setdefault(k, set()).add(user or f"{t}")
        if not by:
            return None, 0
        key, users = max(by.items(), key=lambda kv: len(kv[1]))
        if len(users) >= max(4, 0.3 * authors):
            return key, len(users)
        return None, 0

    def top_word(self, now: float) -> str | None:
        c: Counter = Counter()
        for t, text, _user in self.recent:
            if now - WINDOW < t <= now + 1e-9:
                for wd in set(WORD.findall(text)):
                    if (len(wd) >= 2 or not wd.isalnum()) and not wd.startswith("@"):
                        c[wd] += 1
        best = c.most_common(1)
        return best[0][0] if best and best[0][1] >= 2 else None
