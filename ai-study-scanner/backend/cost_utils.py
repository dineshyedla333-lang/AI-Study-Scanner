from __future__ import annotations

import re
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass


_WHITESPACE_RE = re.compile(r"\s+")
_REPEAT_PUNCT_RE = re.compile(r"([?!.,:=+\-*/])\1{2,}")
_LINE_BREAK_RE = re.compile(r"\n{3,}")


def normalize_question_text(question_text: str, max_chars: int) -> str:
    text = (question_text or "").replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace("\u00a0", " ").replace("\t", " ")
    text = _WHITESPACE_RE.sub(" ", text)
    text = text.replace(" ?", "?").replace(" !", "!").replace(" ,", ",").replace(" .", ".")
    text = _REPEAT_PUNCT_RE.sub(r"\1\1", text)
    text = _LINE_BREAK_RE.sub("\n\n", text)
    text = text.strip()

    if max_chars > 0 and len(text) > max_chars:
        text = text[:max_chars].rstrip()
    return text


def cache_key_for(question_text: str, exam_mode: bool) -> str:
    mode_key = "exam" if exam_mode else "learn"
    return f"{mode_key}:{question_text}"


@dataclass(frozen=True)
class CacheEntry:
    value: object
    created_at: float


class TTLCache:
    def __init__(self, max_size: int, ttl_seconds: int) -> None:
        self.max_size = max(1, max_size)
        self.ttl_seconds = max(0, ttl_seconds)
        self._lock = threading.Lock()
        self._data: OrderedDict[str, CacheEntry] = OrderedDict()

    def get(self, key: str) -> object | None:
        now = time.time()
        with self._lock:
            entry = self._data.get(key)
            if entry is None:
                return None
            if self.ttl_seconds and now - entry.created_at > self.ttl_seconds:
                self._data.pop(key, None)
                return None
            self._data.move_to_end(key)
            return entry.value

    def set(self, key: str, value: object) -> None:
        now = time.time()
        with self._lock:
            self._data[key] = CacheEntry(value=value, created_at=now)
            self._data.move_to_end(key)
            self._evict_expired(now)
            while len(self._data) > self.max_size:
                self._data.popitem(last=False)

    def _evict_expired(self, now: float) -> None:
        if not self.ttl_seconds:
            return
        expired_keys = [
            key for key, entry in self._data.items()
            if now - entry.created_at > self.ttl_seconds
        ]
        for key in expired_keys:
            self._data.pop(key, None)

    def stats(self) -> dict[str, int]:
        with self._lock:
            return {"size": len(self._data), "max_size": self.max_size, "ttl_seconds": self.ttl_seconds}