"""UPSC Live Agent — FCM push + Firestore subscription store (firebase-admin).

Firebase is initialised lazily so the rest of the API (e.g. /solve, /news)
keeps working even when no service-account credential is configured. Anything
that needs push/Firestore raises LiveAgentNotConfigured, which the API layer
turns into a clear 503.

Firestore schema — collection `news_subscriptions`, document id = FCM token:
  token: str, userId: str, email: str, phone: str, exam: str,
  times: [ "HH:MM" ], tz: str (IANA), count: int, enabled: bool,
  lastSentSlot: str ("YYYY-MM-DD HH:MM"), updatedAt: server timestamp

Registered users — collection `users`, document id = Firebase uid:
  uid: str, email: str, phone: str, lastToken: str, verified: bool,
  updatedAt: server timestamp
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

from config import Settings

logger = logging.getLogger("ai-study-scanner.notifications")

_COLLECTION = "news_subscriptions"
NOTIFICATION_CHANNEL_ID = "upsc_live_agent"

# Lazily-created firebase-admin handles
_initialized = False
_db = None


class LiveAgentNotConfigured(RuntimeError):
    """Firebase credentials are missing, so push/Firestore are unavailable."""


def is_configured(settings: Settings) -> bool:
    import os

    return bool(
        settings.firebase_credentials_json
        or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    )


def _ensure_init(settings: Settings) -> None:
    global _initialized, _db
    if _initialized:
        return
    if not is_configured(settings):
        raise LiveAgentNotConfigured(
            "Live Agent is not configured (set FIREBASE_CREDENTIALS_JSON)."
        )

    import firebase_admin
    from firebase_admin import credentials, firestore

    if not firebase_admin._apps:
        if settings.firebase_credentials_json:
            cred = credentials.Certificate(
                json.loads(settings.firebase_credentials_json)
            )
            firebase_admin.initialize_app(cred)
        else:
            firebase_admin.initialize_app()
    _db = firestore.client()
    _initialized = True


# --------------------------------------------------------------------------- #
# Subscription CRUD
# --------------------------------------------------------------------------- #
def upsert_subscription(settings: Settings, sub: dict) -> None:
    _ensure_init(settings)
    from firebase_admin import firestore

    token = sub["token"]
    data = dict(sub)
    data["updatedAt"] = firestore.SERVER_TIMESTAMP
    _db.collection(_COLLECTION).document(token).set(data, merge=True)


def delete_subscription(settings: Settings, token: str) -> None:
    _ensure_init(settings)
    _db.collection(_COLLECTION).document(token).delete()


def verify_id_token(settings: Settings, id_token: str | None) -> dict | None:
    """Verify a Firebase ID token; return decoded claims or None.

    Returns None (rather than raising) when the token is missing/invalid or
    Firebase is not configured, so callers can fall back to client-supplied
    identity without failing the request.
    """
    if not id_token:
        return None
    try:
        _ensure_init(settings)
        from firebase_admin import auth as fb_auth

        return fb_auth.verify_id_token(id_token)
    except LiveAgentNotConfigured:
        return None
    except Exception:
        logger.warning("ID token verification failed", exc_info=True)
        return None


def upsert_user(settings: Settings, user: dict) -> None:
    """Store/merge a registered-user record keyed by Firebase uid."""
    uid = user.get("uid")
    if not uid:
        return
    _ensure_init(settings)
    from firebase_admin import firestore

    data = dict(user)
    data["updatedAt"] = firestore.SERVER_TIMESTAMP
    _db.collection("users").document(uid).set(data, merge=True)


# --------------------------------------------------------------------------- #
# Dispatch (called by the cron endpoint)
# --------------------------------------------------------------------------- #
@dataclass
class DispatchSummary:
    checked: int = 0
    due: int = 0
    sent: int = 0
    failed: int = 0
    pruned: int = 0


def _due_slot(times, tz_name: str, now_utc: datetime, window_min: int):
    """Return the most recent slot ('HH:MM') that is due now, else None.

    A slot is due if local-now is within [slot, slot + window] today — the
    window prevents firing stale slots if the cron was down for a while.
    """
    try:
        tz = ZoneInfo(tz_name)
    except Exception:
        tz = ZoneInfo("Asia/Kolkata")
    local_now = now_utc.astimezone(tz)
    best = None
    best_dt = None
    for slot in times:
        try:
            hh, mm = (int(x) for x in slot.split(":"))
        except Exception:
            continue
        slot_dt = local_now.replace(hour=hh, minute=mm, second=0, microsecond=0)
        delta = (local_now - slot_dt).total_seconds() / 60.0
        if 0 <= delta <= window_min and (best_dt is None or slot_dt > best_dt):
            best, best_dt = slot, slot_dt
    if best is None:
        return None, None
    return best, f"{local_now.date().isoformat()} {best}"


def dispatch(settings: Settings, now_utc: datetime | None = None) -> DispatchSummary:
    """Find subscriptions due now and push fresh current-affairs Q&A to them."""
    _ensure_init(settings)
    from firebase_admin import messaging

    from news import generate_news_qna

    now_utc = now_utc or datetime.now(tz=ZoneInfo("UTC"))
    window = settings.news_dispatch_window_min
    summary = DispatchSummary()

    # Cache generated news per exam so one cron run hits Groq once per exam.
    news_by_exam: dict[str, list[dict]] = {}

    docs = list(_db.collection(_COLLECTION).where("enabled", "==", True).stream())
    summary.checked = len(docs)

    for doc in docs:
        sub = doc.to_dict() or {}
        token = sub.get("token") or doc.id
        times = sub.get("times") or []
        tz_name = sub.get("tz") or "Asia/Kolkata"
        exam = (sub.get("exam") or "UPSC").upper()
        count = int(sub.get("count") or 5)

        slot, slot_key = _due_slot(times, tz_name, now_utc, window)
        if slot is None:
            continue
        if sub.get("lastSentSlot") == slot_key:
            continue  # already delivered this slot today
        summary.due += 1

        if exam not in news_by_exam:
            result = generate_news_qna(settings=settings, exam=exam, count=count)
            news_by_exam[exam] = [
                {"question": i.question, "answer": i.answer} for i in result.items
            ]
        qna = news_by_exam[exam]
        if not qna:
            continue

        title = f"{exam} Current Affairs — {len(qna)} questions"
        body = qna[0]["question"]
        message = messaging.Message(
            token=token,
            notification=messaging.Notification(title=title, body=body),
            data={
                "type": "upsc_news",
                "exam": exam,
                "date": slot_key,
                "qna": json.dumps(qna, ensure_ascii=False),
            },
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    channel_id=NOTIFICATION_CHANNEL_ID,
                ),
            ),
        )
        try:
            messaging.send(message)
            _db.collection(_COLLECTION).document(doc.id).update(
                {"lastSentSlot": slot_key}
            )
            summary.sent += 1
        except messaging.UnregisteredError:
            _db.collection(_COLLECTION).document(doc.id).delete()
            summary.pruned += 1
        except Exception:
            logger.exception("FCM send failed for token %s", token[:12])
            summary.failed += 1

    return summary
