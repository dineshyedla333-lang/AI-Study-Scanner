"""Verify a Pro subscription with the Google Play Developer API.

Pro is bought on the phone, so the server only trusts it after asking Play about
the purchase token the app sends (X-Play-Purchase-Token). It calls
purchases.subscriptionsv2.get with the Firebase service account, which must be
granted access in Play Console (Users and permissions -> invite the service
account email -> "View financial data"). Until then Play answers 401/403, the
user is treated as free, and a warning is logged.

Results are cached in memory so a Pro user costs one Play call per hour.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from urllib.parse import quote

from config import Settings

logger = logging.getLogger("ai-study-scanner.play")

_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
_ACTIVE = {"SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"}
_MAX_CACHE = 5000

_lock = threading.Lock()
_cache: dict[str, tuple[bool, float]] = {}
_session = None


def _get_session(settings: Settings):
    global _session
    if _session is None:
        from google.auth.transport.requests import AuthorizedSession
        from google.oauth2 import service_account

        creds = service_account.Credentials.from_service_account_info(
            json.loads(settings.firebase_credentials_json), scopes=[_SCOPE]
        )
        _session = AuthorizedSession(creds)
    return _session


def _ask_play(settings: Settings, token: str) -> tuple[bool, int]:
    """(is_pro, seconds to cache)."""
    if not settings.firebase_credentials_json:
        return False, 600
    url = (
        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
        f"{settings.play_package_name}/purchases/subscriptionsv2/tokens/"
        f"{quote(token, safe='')}"
    )
    try:
        resp = _get_session(settings).get(url, timeout=10)
    except Exception:
        # Play unreachable is our problem, not the subscriber's: let them through
        # briefly. A forged token cannot cause this.
        logger.warning("Play verification request failed", exc_info=True)
        return True, 60
    if resp.status_code >= 500:
        logger.warning("Play verification returned %s", resp.status_code)
        return True, 60
    if resp.status_code in (401, 403):
        logger.warning(
            "Play verification not authorised (%s). Grant the Firebase service "
            "account access in Play Console; Pro users are metered as free until then.",
            resp.status_code,
        )
        return False, 600
    if resp.status_code != 200:
        return False, 600  # 400/404/410: not a real or current purchase

    data = resp.json()
    active = data.get("subscriptionState") in _ACTIVE
    has_pro = any(
        item.get("productId") == settings.play_pro_product_id
        for item in data.get("lineItems") or []
    )
    return (True, 3600) if active and has_pro else (False, 600)


def is_pro(settings: Settings, purchase_token: str | None) -> bool:
    token = (purchase_token or "").strip()[:1024]
    if not token:
        return False
    now = time.monotonic()
    with _lock:
        hit = _cache.get(token)
        if hit and hit[1] > now:
            return hit[0]
    result, ttl = _ask_play(settings, token)
    with _lock:
        if len(_cache) >= _MAX_CACHE:
            _cache.clear()
        _cache[token] = (result, now + ttl)
    return result
