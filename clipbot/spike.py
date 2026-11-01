"""Всплески чата: окно 15 с против обычного темпа этого эфира за последние 10 минут.

Реакции (KEKW, LUL, «ахах», «ору», 😂 …) весят больше обычного сообщения. Всплеск — когда в окне
сообщений в `threshold` раз больше обычного и их не меньше `min_msgs`. После клипа — пауза `cooldown` секунд.
Счёт идёт по секундам, поэтому проверка дешёвая даже в очень быстром чате.
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


@dataclass
class Spike:
    ratio: float      # во сколько раз чаще обычного
    msgs: int         # сообщений за окно
    word: str | None  # самое частое слово или смайл


@dataclass
class ChannelChat:
    threshold: float = 3.0
    min_msgs: int = 15
    cooldown: float = 180.0
    per_sec: dict = field(default_factory=dict)    # секунда → [вес, число сообщений]
    recent: deque = field(default_factory=deque)   # (время, текст) за последнее окно — для подписи
    last_clip: float = -1e18
    started: float | None = None                   # время первого сообщения

    def add(self, t: float, text: str) -> None:
        if self.started is None:
            self.started = t
        sec = int(t)
        b = self.per_sec.setdefault(sec, [0.0, 0])
        b[0] += 1.6 if HYPE.search(text) else 1.0
        b[1] += 1
        self.recent.append((t, text[:60]))
        while self.recent and self.recent[0][0] < t - WINDOW - 5:
            self.recent.popleft()
        if len(self.per_sec) > HISTORY + 60:
            for s in [s for s in self.per_sec if s < sec - HISTORY]:
                del self.per_sec[s]

    def _window(self, end: float) -> tuple[float, int]:
        """Вес и число сообщений в окне (end − 15 с, end]."""
        e = int(end)
        w, n = 0.0, 0
        for s in range(e - WINDOW + 1, e + 1):
            b = self.per_sec.get(s)
            if b:
                w += b[0]
                n += b[1]
        return w, n

    def baseline(self, now: float) -> float:
        """Медиана веса 15-секундных окон за историю (без текущего окна); не меньше 1. 0 — истории ещё нет."""
        start = int(max(self.started if self.started is not None else now, now - HISTORY))
        rates = [self._window(t)[0] for t in range(start + WINDOW - 1, int(now) - WINDOW + 1, 5)]
        return max(1.0, statistics.median(rates)) if rates else 0.0

    def check(self, now: float) -> Spike | None:
        """Всплеск прямо сейчас? Нужна хотя бы минута истории, иначе не с чем сравнивать."""
        if self.started is None or now - self.started < 60 or now - self.last_clip < self.cooldown:
            return None
        w, n = self._window(now)
        base = self.baseline(now)
        if base <= 0 or n < self.min_msgs or w < self.threshold * base:
            return None
        return Spike(ratio=round(w / base, 1), msgs=n, word=self.top_word(now))

    def top_word(self, now: float) -> str | None:
        c: Counter = Counter()
        for t, text in self.recent:
            if now - WINDOW < t <= now + 1e-9:
                for wd in set(WORD.findall(text)):
                    if (len(wd) >= 2 or not wd.isalnum()) and not wd.startswith("@"):
                        c[wd] += 1
        best = c.most_common(1)
        return best[0][0] if best and best[0][1] >= 2 else None
