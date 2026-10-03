"""Server-side free quota, per Firebase uid.

The app's own Firestore counter (usage_daily) is written by the client, so it is
only a display. This one is written solely by the backend, in collections the
client rules do not open:

  quota_users/{uid}            trialUsed               lifetime free-trial solves
  quota_daily/{uid}_{day}      count, bonus, bonusGrants   one document per day

A request reserves one use before the AI call and refunds it if the call fails,
so only answers that actually came back stay counted, and two concurrent
requests can never both take the last free solve.

Rules:
  - anonymous (not yet registered): TRIAL_FREE_SOLVES solves in total, after
    which solving needs registration. Home Work / Planner / News are not trial
    gated, only daily limited — the same as the app.
  - everyone not Pro: DAILY_FREE_LIMIT + ad bonus per day across metered calls.
  - verified Pro: PRO_DAILY_CAP per day, a ceiling against a leaked token.
"""
from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import datetime
from typing import Callable, Protocol, TypeVar
from zoneinfo import ZoneInfo

import notifications
from auth import Caller
from config import Settings

T = TypeVar("T")

SOLVE = "solve"
OTHER = "other"


@dataclass
class Usage:
    trial_used: int = 0
    count: int = 0
    bonus: int = 0
    bonus_grants: int = 0


@dataclass(frozen=True)
class Reservation:
    uid: str
    day: str
    trial: bool


class QuotaExceeded(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class Store(Protocol):
    def update(self, uid: str, day: str, fn: Callable[[Usage], T]) -> T:
        """Run fn on the stored usage atomically, persisting its mutations."""


class FirestoreStore:
    def __init__(self, settings: Settings):
        self._settings = settings

    def update(self, uid: str, day: str, fn: Callable[[Usage], T]) -> T:
        from firebase_admin import firestore

        db = notifications.firestore_client(self._settings)
        user_ref = db.collection("quota_users").document(uid)
        day_ref = db.collection("quota_daily").document(f"{uid}_{day}")

        @firestore.transactional
        def run(txn):
            user = user_ref.get(transaction=txn).to_dict() or {}
            daily = day_ref.get(transaction=txn).to_dict() or {}
            usage = Usage(
                trial_used=int(user.get("trialUsed", 0)),
                count=int(daily.get("count", 0)),
                bonus=int(daily.get("bonus", 0)),
                bonus_grants=int(daily.get("bonusGrants", 0)),
            )
            before = Usage(**usage.__dict__)
            result = fn(usage)  # raising here aborts the transaction
            if usage.trial_used != before.trial_used:
                txn.set(
                    user_ref,
                    {
                        "uid": uid,
                        "trialUsed": usage.trial_used,
                        "updatedAt": firestore.SERVER_TIMESTAMP,
                    },
                    merge=True,
                )
            if usage != before:
                txn.set(
                    day_ref,
                    {
                        "uid": uid,
                        "day": day,
                        "count": usage.count,
                        "bonus": usage.bonus,
                        "bonusGrants": usage.bonus_grants,
                        "updatedAt": firestore.SERVER_TIMESTAMP,
                    },
                    merge=True,
                )
            return result

        return run(db.transaction())


class MemoryStore:
    """In-process store for tests."""

    def __init__(self):
        self._lock = threading.Lock()
        self.trial: dict[str, int] = {}
        self.daily: dict[tuple[str, str], Usage] = {}

    def update(self, uid: str, day: str, fn: Callable[[Usage], T]) -> T:
        with self._lock:
            saved = self.daily.get((uid, day), Usage())
            usage = Usage(**saved.__dict__)
            usage.trial_used = self.trial.get(uid, 0)
            result = fn(usage)
            self.trial[uid] = usage.trial_used
            self.daily[(uid, day)] = usage
            return result


_store: Store | None = None


def _get_store(settings: Settings) -> Store:
    global _store
    if _store is None:
        _store = FirestoreStore(settings)
    return _store


def today(settings: Settings) -> str:
    return datetime.now(ZoneInfo(settings.quota_tz)).date().isoformat()


def _take(
    usage: Usage, settings: Settings, *, kind: str, is_anonymous: bool, is_pro: bool
) -> bool:
    """Consume one use or raise. Returns True if it also used a trial solve."""
    if is_pro:
        if usage.count >= settings.pro_daily_cap:
            raise QuotaExceeded(
                "daily_limit", "You've reached today's limit. Please try again tomorrow."
            )
        usage.count += 1
        return False

    trial = is_anonymous and kind == SOLVE
    if trial and usage.trial_used >= settings.trial_free_solves:
        raise QuotaExceeded(
            "registration_required",
            f"You've used your {settings.trial_free_solves} free solves. "
            "Sign in to keep solving — it's free.",
        )
    limit = settings.daily_free_limit + usage.bonus
    if usage.count >= limit:
        raise QuotaExceeded(
            "daily_limit",
            f"Daily free limit reached ({limit}/day). Watch an ad for "
            f"+{settings.ad_bonus_solves} more, or try again tomorrow.",
        )
    usage.count += 1
    if trial:
        usage.trial_used += 1
    return trial


def reserve(settings: Settings, caller: Caller, kind: str, *, is_pro: bool) -> Reservation:
    day = today(settings)
    trial = _get_store(settings).update(
        caller.uid,
        day,
        lambda u: _take(
            u, settings, kind=kind, is_anonymous=caller.is_anonymous, is_pro=is_pro
        ),
    )
    return Reservation(uid=caller.uid, day=day, trial=trial)


def refund(settings: Settings, reservation: Reservation) -> None:
    def give_back(usage: Usage) -> None:
        usage.count = max(0, usage.count - 1)
        if reservation.trial:
            usage.trial_used = max(0, usage.trial_used - 1)

    _get_store(settings).update(reservation.uid, reservation.day, give_back)


def grant_bonus(settings: Settings, caller: Caller) -> Usage:
    """Add ad-reward solves for today, capped so the endpoint can't be farmed."""

    def grant(usage: Usage) -> Usage:
        if usage.bonus_grants >= settings.max_ad_bonuses_per_day:
            raise QuotaExceeded(
                "bonus_limit", "You've reached today's bonus limit. Try again tomorrow."
            )
        usage.bonus += settings.ad_bonus_solves
        usage.bonus_grants += 1
        return Usage(**usage.__dict__)

    return _get_store(settings).update(caller.uid, today(settings), grant)
