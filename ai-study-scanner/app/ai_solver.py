from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import google.generativeai as genai

from app.config import Settings


@dataclass(frozen=True)
class SolveResult:
    provider: str
    model: str
    prompt: str
    answer: str
    latency_ms: int
    raw: dict[str, Any] | None = None


class MissingAPIKeyError(RuntimeError):
    pass


def solve_gemini(
    *,
    question_text: str,
    exam_mode: bool,
    settings: Settings,
    prompt: str,
) -> SolveResult:
    """
    Calls Gemini and returns a step-by-step solution.
    """
    if not settings.gemini_api_key:
        raise MissingAPIKeyError("GEMINI_API_KEY is not configured")

    genai.configure(api_key=settings.gemini_api_key)

    started = time.perf_counter()
    model = genai.GenerativeModel(settings.gemini_model)

    # Keep generation conservative for reliability; tune later.
    generation_config = {
        "temperature": 0.2 if exam_mode else 0.4,
        "max_output_tokens": 1024,
    }

    resp = model.generate_content(
        prompt,
        generation_config=generation_config,
        request_options={"timeout": settings.gemini_timeout_s},
    )

    elapsed_ms = int((time.perf_counter() - started) * 1000)
    text = (getattr(resp, "text", None) or "").strip()

    raw: dict[str, Any] | None = None
    try:
        # Some versions expose a dict-like representation; keep best-effort.
        raw = resp.to_dict()  # type: ignore[attr-defined]
    except Exception:
        raw = None

    return SolveResult(
        provider="gemini",
        model=settings.gemini_model,
        prompt=prompt,
        answer=text,
        latency_ms=elapsed_ms,
        raw=raw,
    )
