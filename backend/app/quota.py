"""Per-device daily scan quota (free plan) and premium flag, stored in SQLite.

The device id is a salted hash of the phone's ANDROID_ID sent by the app in X-Device-Id. It survives uninstall/reinstall
(until a factory reset) so deleting the app does not reset the free allowance. It is an abuse-prevention counter, not a
login: it cannot be tied back to a person. Enforcement lives here on the server because anything kept only in the app
can be cleared or edited by the user.
"""
import re
import sqlite3
import threading
from datetime import datetime, timedelta, timezone

DEVICE_ID = re.compile(r"^[A-Za-z0-9_\-]{16,128}$")


def clean_device_id(raw: str | None) -> str | None:
    return raw if raw and DEVICE_ID.match(raw) else None


class QuotaStore:
    def __init__(self, path: str = ":memory:", reset_offset_min: int = 0, premium_ids: frozenset[str] = frozenset()):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._lock = threading.Lock()
        self._offset = timedelta(minutes=reset_offset_min)
        self._env_premium = premium_ids
        with self._lock:
            self._db.execute("CREATE TABLE IF NOT EXISTS usage (device TEXT, day TEXT, n INTEGER, PRIMARY KEY (device, day))")
            self._db.execute("CREATE TABLE IF NOT EXISTS premium (device TEXT PRIMARY KEY, since TEXT)")
            self._db.commit()

    def today(self, now: datetime | None = None) -> str:
        return ((now or datetime.now(timezone.utc)) + self._offset).strftime("%Y-%m-%d")

    def resets_at(self, now: datetime | None = None) -> str:
        """ISO time (UTC) of the next quota reset."""
        local = (now or datetime.now(timezone.utc)) + self._offset
        nxt = (local + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
        return (nxt - self._offset).isoformat()

    def used(self, device: str, now: datetime | None = None) -> int:
        with self._lock:
            row = self._db.execute("SELECT n FROM usage WHERE device=? AND day=?", (device, self.today(now))).fetchone()
        return row[0] if row else 0

    def add(self, device: str, now: datetime | None = None) -> int:
        day = self.today(now)
        with self._lock:
            self._db.execute(
                "INSERT INTO usage(device, day, n) VALUES (?,?,1) ON CONFLICT(device, day) DO UPDATE SET n = n + 1", (device, day))
            self._db.commit()
            return self._db.execute("SELECT n FROM usage WHERE device=? AND day=?", (device, day)).fetchone()[0]

    def is_premium(self, device: str) -> bool:
        if device in self._env_premium:
            return True
        with self._lock:
            return self._db.execute("SELECT 1 FROM premium WHERE device=?", (device,)).fetchone() is not None

    def set_premium(self, device: str, on: bool) -> None:
        with self._lock:
            if on:
                self._db.execute("INSERT OR REPLACE INTO premium(device, since) VALUES (?, ?)",
                                 (device, datetime.now(timezone.utc).isoformat()))
            else:
                self._db.execute("DELETE FROM premium WHERE device=?", (device,))
            self._db.commit()
