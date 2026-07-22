"""Fail-fast in-process admission gate with no unbounded waiter queue."""

from __future__ import annotations

from threading import Lock


class AdmissionGate:
    def __init__(self, limit: int) -> None:
        if limit < 1:
            raise ValueError("admission limit must be positive")
        self._limit = limit
        self._in_flight = 0
        self._lock = Lock()

    def try_acquire(self) -> bool:
        with self._lock:
            if self._in_flight >= self._limit:
                return False
            self._in_flight += 1
            return True

    def release(self) -> None:
        with self._lock:
            if self._in_flight < 1:
                raise RuntimeError("admission gate release is unbalanced")
            self._in_flight -= 1
