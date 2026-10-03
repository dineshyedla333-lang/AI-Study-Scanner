"""Firebase-auth gate and the server-side free quota.

Firebase and Firestore are replaced with fakes: a verifier that returns canned
claims and quota.MemoryStore. The rules under test are the business ones — the
3-solve trial for anonymous users, the daily limit, ad bonuses and refunds.
"""
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

import auth
import main
import notifications
import play_verify
import quota
from config import Settings

ANON = {"uid": "u_anon", "firebase": {"sign_in_provider": "anonymous", "identities": {}}}
LINKED = {
    "uid": "u_anon",
    "firebase": {"sign_in_provider": "anonymous", "identities": {"google.com": ["1"]}},
}
GOOGLE = {"uid": "u_g", "firebase": {"sign_in_provider": "google.com", "identities": {"google.com": ["1"]}}}


def _settings(mode="optional"):
    return replace(Settings(), auth_mode=mode, firebase_credentials_json="{}")


def _verifier(claims):
    def verify(settings, token):
        if token == "bad":
            raise ValueError("invalid")
        return claims

    return verify


# --------------------------------------------------------------------------- #
# auth.authenticate
# --------------------------------------------------------------------------- #
def test_optional_mode_allows_missing_token_for_old_builds():
    assert auth.authenticate(_settings("optional"), None, _verifier(ANON)) is None


def test_required_mode_rejects_missing_token():
    with pytest.raises(auth.AuthError) as e:
        auth.authenticate(_settings("required"), None, _verifier(ANON))
    assert e.value.status == 401


def test_invalid_token_rejected_even_when_optional():
    with pytest.raises(auth.AuthError) as e:
        auth.authenticate(_settings("optional"), "Bearer bad", _verifier(ANON))
    assert e.value.code == "invalid_token"


def test_off_mode_skips_verification():
    assert auth.authenticate(_settings("off"), "Bearer bad", _verifier(ANON)) is None


def test_anonymous_caller():
    caller = auth.authenticate(_settings(), "Bearer t", _verifier(ANON))
    assert caller == auth.Caller(uid="u_anon", is_anonymous=True)


def test_linked_account_keeps_uid_but_is_not_anonymous():
    caller = auth.authenticate(_settings(), "Bearer t", _verifier(LINKED))
    assert caller == auth.Caller(uid="u_anon", is_anonymous=False)


# --------------------------------------------------------------------------- #
# quota
# --------------------------------------------------------------------------- #
@pytest.fixture
def store(monkeypatch):
    mem = quota.MemoryStore()
    monkeypatch.setattr(quota, "_store", mem)
    return mem


ANON_CALLER = auth.Caller("a1", is_anonymous=True)
USER = auth.Caller("r1", is_anonymous=False)


def test_anonymous_gets_exactly_three_trial_solves(store):
    s = _settings()
    for _ in range(3):
        quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)
    with pytest.raises(quota.QuotaExceeded) as e:
        quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)
    assert e.value.code == "registration_required"


def test_failed_solve_is_refunded_so_trial_counts_only_answers(store):
    s = _settings()
    for _ in range(3):
        r = quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)
        quota.refund(s, r)
    assert store.trial["a1"] == 0
    quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)  # still allowed


def test_trial_does_not_gate_homework(store):
    s = _settings()
    for _ in range(3):
        quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)
    quota.reserve(s, ANON_CALLER, quota.OTHER, is_pro=False)


def test_after_linking_the_same_uid_keeps_solving(store):
    s = _settings()
    for _ in range(3):
        quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=False)
    linked = auth.Caller("a1", is_anonymous=False)
    quota.reserve(s, linked, quota.SOLVE, is_pro=False)
    assert store.daily[("a1", quota.today(s))].count == 4  # same day's usage carried


def test_daily_limit_then_ad_bonus(store):
    s = _settings()
    for _ in range(s.daily_free_limit):
        quota.reserve(s, USER, quota.SOLVE, is_pro=False)
    with pytest.raises(quota.QuotaExceeded) as e:
        quota.reserve(s, USER, quota.SOLVE, is_pro=False)
    assert e.value.code == "daily_limit"
    quota.grant_bonus(s, USER)
    for _ in range(s.ad_bonus_solves):
        quota.reserve(s, USER, quota.SOLVE, is_pro=False)
    with pytest.raises(quota.QuotaExceeded):
        quota.reserve(s, USER, quota.SOLVE, is_pro=False)


def test_ad_bonus_is_capped_per_day(store):
    s = _settings()
    for _ in range(s.max_ad_bonuses_per_day):
        quota.grant_bonus(s, USER)
    with pytest.raises(quota.QuotaExceeded) as e:
        quota.grant_bonus(s, USER)
    assert e.value.code == "bonus_limit"


def test_pro_skips_trial_and_free_limit(store):
    s = _settings()
    for _ in range(s.daily_free_limit + 5):
        quota.reserve(s, ANON_CALLER, quota.SOLVE, is_pro=True)


# --------------------------------------------------------------------------- #
# through the app: middleware + metered endpoint
# --------------------------------------------------------------------------- #
@pytest.fixture
def client(monkeypatch, store):
    monkeypatch.setattr(main, "settings", _settings("optional"))
    monkeypatch.setattr(notifications, "is_configured", lambda s: True)
    monkeypatch.setattr(play_verify, "is_pro", lambda s, t: False)
    def fake_verify(settings, token):
        if token == "bad":
            raise ValueError("invalid")
        return ANON

    monkeypatch.setattr(auth, "_verify_with_firebase", fake_verify)
    main.solve_cache._data.clear()
    def fake_solve(question_text, exam_mode, settings, board):
        return main.AgenticSolveResult(provider="groq", model="m", answer="42")

    monkeypatch.setattr(main, "solve_agentic", fake_solve)
    main.limiter.reset()
    return TestClient(main.app)


def _solve(c, n, token="Bearer t"):
    return c.post(
        "/solve/agent",
        json={"question_text": f"q{n}", "exam_mode": True},
        headers={"Authorization": token} if token else {},
    )


def test_endpoint_trial_wall_after_three_answers(client):
    c = client
    for i in range(3):
        assert _solve(c, i).status_code == 200
    r = _solve(c, 99)
    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "registration_required"


def test_endpoint_bad_token_is_401(client):
    c = client
    r = _solve(c, 1, token="Bearer bad")
    assert r.status_code == 401


def test_endpoint_missing_token_allowed_in_optional_mode(client):
    c = client
    for i in range(5):  # no trial wall for legacy builds
        assert _solve(c, i, token=None).status_code == 200


def test_endpoint_missing_token_rejected_in_required_mode(client, monkeypatch):
    c = client
    monkeypatch.setattr(main, "settings", _settings("required"))
    assert _solve(c, 1, token=None).status_code == 401


def test_health_needs_no_token(client, monkeypatch):
    c = client
    monkeypatch.setattr(main, "settings", _settings("required"))
    assert c.get("/health").status_code == 200
