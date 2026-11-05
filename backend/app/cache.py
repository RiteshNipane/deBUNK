import hashlib
import re
import time
from collections import OrderedDict
from typing import Callable, Generic, TypeVar

T = TypeVar("T")


def text_key(text: str) -> str:
    """Stable key for a claim/headline: case/punctuation/whitespace-insensitive SHA-256 (first 32 hex chars)."""
    norm = re.sub(r"[\W_]+", " ", text.lower()).strip()
    return hashlib.sha256(norm.encode()).hexdigest()[:32]


class TTLCache(Generic[T]):
    """Tiny in-memory LRU+TTL cache. Per-instance: swap for Redis if you run more than one replica."""

    def __init__(self, ttl_s: float, maxsize: int = 2048, clock: Callable[[], float] = time.monotonic):
        self._ttl, self._max, self._clock = ttl_s, maxsize, clock
        self._data: OrderedDict[str, tuple[float, T]] = OrderedDict()

    def get(self, key: str) -> T | None:
        item = self._data.get(key)
        if item is None:
            return None
        expires, value = item
        if expires < self._clock():
            del self._data[key]
            return None
        self._data.move_to_end(key)
        return value

    def set(self, key: str, value: T, ttl_s: float | None = None) -> None:
        self._data[key] = (self._clock() + (self._ttl if ttl_s is None else ttl_s), value)
        self._data.move_to_end(key)
        while len(self._data) > self._max:
            self._data.popitem(last=False)
