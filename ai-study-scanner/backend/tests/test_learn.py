"""/learn plumbing, plus the quota decorator it shares with the other endpoints.

The slowapi decorators strip type hints from body params, which is how this
project has produced surprise 422s before — so these tests exercise the real
JSON wiring rather than calling the functions directly.
"""
import asyncio
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import main  # noqa: E402
from ai_solver import HomeworkItem, LearnResult  # noqa: E402


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(main, "_reserve", lambda request, kind: None)
    main.solve_cache._data.clear()
    main.limiter.reset()
    return TestClient(main.app)


# --------------------------------------------------------------------------- #
# metered()
# --------------------------------------------------------------------------- #
def test_metered_refunds_an_async_handler_that_fails(monkeypatch):
    """A sync wrapper around a coroutine never sees the failure inside it.

    No async endpoint is metered today, but /ocr is async and is the obvious
    candidate next. Without this branch the student would silently lose the
    quota unit on every failure.
    """
    refunded = []
    monkeypatch.setattr(main, "_reserve", lambda request, kind: "res-1")
    monkeypatch.setattr(main.quota, "refund", lambda s, r: refunded.append(r))

    @main.metered("other")
    async def handler(request):
        raise RuntimeError("upstream died")

    with pytest.raises(RuntimeError):
        asyncio.run(handler(request=object()))

    assert refunded == ["res-1"]


def test_metered_keeps_the_reservation_when_the_handler_succeeds(monkeypatch):
    refunded = []
    monkeypatch.setattr(main, "_reserve", lambda request, kind: "res-1")
    monkeypatch.setattr(main.quota, "refund", lambda s, r: refunded.append(r))

    @main.metered("other")
    async def handler(request):
        return "ok"

    assert asyncio.run(handler(request=object())) == "ok"
    assert refunded == []


def test_metered_still_refunds_a_sync_handler(monkeypatch):
    refunded = []
    monkeypatch.setattr(main, "_reserve", lambda request, kind: "res-2")
    monkeypatch.setattr(main.quota, "refund", lambda s, r: refunded.append(r))

    @main.metered("other")
    def handler(request):
        raise RuntimeError("boom")

    with pytest.raises(RuntimeError):
        handler(request=object())

    assert refunded == ["res-2"]


# --------------------------------------------------------------------------- #
# /learn
# --------------------------------------------------------------------------- #
def _fake_learn():
    return LearnResult(
        provider="groq",
        model="m",
        key_concept="Factorise the quadratic.",
        practice=[HomeworkItem(question="x^2-7x+12=0", answer="x=3, 4")],
        latency_ms=90,
    )


def test_learn_returns_concept_and_practice(client, monkeypatch):
    seen = {}

    def fake(**kwargs):
        seen.update(kwargs)
        return _fake_learn()

    monkeypatch.setattr(main, "generate_learn_extras", fake)
    r = client.post(
        "/learn",
        json={
            "question_text": "Solve x^2-5x+6=0",
            "answer_text": "x = 2 or 3",
            "subject": "Math",
            "topic": "Quadratic Equations",
            "language": "hi",
            "count": 3,
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["key_concept"]
    assert len(body["practice"]) == 1
    assert seen["language"] == "hi"


def test_learn_requires_both_question_and_answer(client):
    r = client.post("/learn", json={"question_text": "q"})
    assert r.status_code == 422


def test_learn_caches_per_language(client, monkeypatch):
    calls = []

    def fake(**kwargs):
        calls.append(kwargs["language"])
        return _fake_learn()

    monkeypatch.setattr(main, "generate_learn_extras", fake)
    payload = {"question_text": "Solve x^2-5x+6=0", "answer_text": "x=2,3"}
    client.post("/learn", json=payload)
    client.post("/learn", json=payload)  # cache hit
    client.post("/learn", json={**payload, "language": "te"})
    assert calls == ["en", "te"]


def test_learn_surfaces_provider_failure_as_502(client, monkeypatch):
    def boom(**kwargs):
        raise RuntimeError("groq down")

    monkeypatch.setattr(main, "generate_learn_extras", boom)
    r = client.post(
        "/learn",
        json={"question_text": "q", "answer_text": "a"},
    )
    assert r.status_code == 502
