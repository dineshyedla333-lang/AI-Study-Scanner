"""Firebase ID-token checks for the public API.

The app signs every install in with Firebase (anonymously until the user
registers) and sends `Authorization: Bearer <ID token>`. Verifying it here gives
each caller a uid that cannot be invented, so quotas (quota.py) and rate limits
key on a real account instead of a spoofable header.

AUTH_MODE decides what happens without a token — see Settings.auth_mode.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable

import notifications
from config import Settings
from notifications import LiveAgentNotConfigured

logger = logging.getLogger("ai-study-scanner.auth")

_warned_unconfigured = False


@dataclass(frozen=True)
class Caller:
    uid: str
    # True until the anonymous account is linked to Google; drives the free trial.
    is_anonymous: bool


class AuthError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _bearer(header: str | None) -> str | None:
    if not header:
        return None
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def _verify_with_firebase(settings: Settings, token: str) -> dict:
    notifications.firestore_client(settings)  # initialises firebase-admin
    from firebase_admin import auth as fb_auth

    return fb_auth.verify_id_token(token)


def is_anonymous(claims: dict) -> bool:
    """Anonymous until linked. A linked account can keep sign_in_provider
    'anonymous' on its session, so the linked identities decide."""
    fb = claims.get("firebase") or {}
    return fb.get("sign_in_provider") == "anonymous" and not fb.get("identities")


def authenticate(
    settings: Settings,
    authorization: str | None,
    verify: Callable[[Settings, str], dict] | None = None,
) -> Caller | None:
    """Return the verified caller, None for an allowed legacy request, or raise."""
    global _warned_unconfigured
    verify = verify or _verify_with_firebase
    mode = settings.auth_mode
    if mode == "off":
        return None

    token = _bearer(authorization)
    if token is None:
        if mode == "required":
            raise AuthError(
                401,
                "auth_required",
                "Please update the app to keep solving questions.",
            )
        return None

    if not notifications.is_configured(settings):
        if mode == "required":
            raise AuthError(503, "auth_unavailable", "Sign-in is not available right now.")
        if not _warned_unconfigured:
            logger.warning("Firebase not configured; ID tokens are not being verified")
            _warned_unconfigured = True
        return None

    try:
        claims = verify(settings, token)
    except LiveAgentNotConfigured:
        return None
    except Exception as exc:
        # Google's signing-key fetch failing is our outage, not a bad token.
        if type(exc).__name__ == "CertificateFetchError":
            logger.warning("Could not fetch Firebase signing keys", exc_info=True)
            raise AuthError(
                503, "auth_unavailable", "Sign-in check failed. Please try again."
            ) from exc
        raise AuthError(
            401, "invalid_token", "Your session expired. Please try again."
        ) from exc

    uid = claims.get("uid") or claims.get("sub")
    if not uid:
        raise AuthError(401, "invalid_token", "Your session expired. Please try again.")
    return Caller(uid=str(uid), is_anonymous=is_anonymous(claims))
