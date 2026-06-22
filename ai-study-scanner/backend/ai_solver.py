from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any

import google.generativeai as genai

from config import Settings
from prompts import (
    AGENT_SOLVE_PROMPT_TEMPLATE,
    CLASSIFY_PROMPT_TEMPLATE,
    EXAM_MODE_GUIDE,
    ANSWER_STYLE_GUIDE,
    _normalize_answer_style,
    _normalize_exam_type,
)


@dataclass(frozen=True)
class SolveResult:
    provider: str
    model: str
    prompt: str
    answer: str
    latency_ms: int
    raw: dict[str, Any] | None = None


@dataclass(frozen=True)
class AgentStep:
    name: str
    output: str
    latency_ms: int


@dataclass(frozen=True)
class AgenticSolveResult:
    provider: str
    model: str
    steps: list[AgentStep] = field(default_factory=list)
    answer: str = ""
    total_latency_ms: int = 0


class MissingAPIKeyError(RuntimeError):
    pass


def _call_gemini(
    model: genai.GenerativeModel,
    prompt: str,
    temperature: float,
    max_tokens: int,
    timeout: float,
) -> tuple[str, int]:
    """Single Gemini call. Returns (text, latency_ms)."""
    started = time.perf_counter()
    resp = model.generate_content(
        prompt,
        generation_config={
            "temperature": temperature,
            "max_output_tokens": max_tokens,
        },
        request_options={"timeout": timeout},
    )
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    text = (getattr(resp, "text", None) or "").strip()
    return text, elapsed_ms


def solve_gemini(
    *,
    question_text: str,
    exam_mode: bool,
    settings: Settings,
    prompt: str,
) -> SolveResult:
    """Single-shot Gemini solve (legacy endpoint)."""
    if not settings.gemini_api_key:
        raise MissingAPIKeyError("GEMINI_API_KEY is not configured")

    genai.configure(api_key=settings.gemini_api_key)

    started = time.perf_counter()
    model = genai.GenerativeModel(settings.gemini_model)

    generation_config = {
        "temperature": (
            settings.gemini_temperature_exam
            if exam_mode
            else settings.gemini_temperature_default
        ),
        "max_output_tokens": settings.gemini_max_output_tokens,
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


def solve_agentic(
    *,
    question_text: str,
    exam_mode: bool,
    settings: Settings,
    exam_type: str = "CBSE",
    answer_style: str = "compact",
) -> AgenticSolveResult:
    """
    Two-step agentic pipeline:
      Step 1 — Classify: identify subject, topic, difficulty, board, approach.
      Step 2 — Solve: use the classification plan to produce a focused answer.
    """
    if not settings.gemini_api_key:
        raise MissingAPIKeyError("GEMINI_API_KEY is not configured")

    genai.configure(api_key=settings.gemini_api_key)
    model = genai.GenerativeModel(settings.gemini_model)
    steps: list[AgentStep] = []

    # ── Step 1: Classify ──────────────────────────────────────────────────
    classify_prompt = CLASSIFY_PROMPT_TEMPLATE.format(
        question_text=question_text
    )
    classify_text, classify_ms = _call_gemini(
        model,
        classify_prompt,
        temperature=0.1,   # deterministic classification
        max_tokens=150,
        timeout=settings.gemini_timeout_s,
    )
    steps.append(AgentStep(
        name="Classify",
        output=classify_text,
        latency_ms=classify_ms,
    ))

    # Parse classification JSON; fall back to defaults on any error
    classification: dict[str, str] = {}
    try:
        raw_json = classify_text
        # Strip markdown fences if model wrapped the JSON
        if raw_json.startswith("```"):
            raw_json = raw_json.split("```")[1]
            if raw_json.startswith("json"):
                raw_json = raw_json[4:]
        classification = json.loads(raw_json.strip())
    except Exception:
        pass

    norm_exam = _normalize_exam_type(
        classification.get("exam_board") or exam_type
    )
    norm_style = _normalize_answer_style(answer_style)
    mode_line = (
        "exam mode on: keep steps short and direct." if exam_mode else ""
    )

    # ── Step 2: Solve with plan ───────────────────────────────────────────
    solve_prompt = AGENT_SOLVE_PROMPT_TEMPLATE.format(
        subject=classification.get("subject", "General"),
        topic=classification.get("topic", "General"),
        difficulty=classification.get("difficulty", "Medium"),
        exam_board=norm_exam,
        approach=classification.get("approach", "Solve step by step"),
        exam_guide=EXAM_MODE_GUIDE[norm_exam],
        style_guide=ANSWER_STYLE_GUIDE[norm_style],
        mode_line=mode_line,
        question_text=question_text,
    )
    solve_text, solve_ms = _call_gemini(
        model,
        solve_prompt,
        temperature=(
            settings.gemini_temperature_exam
            if exam_mode
            else settings.gemini_temperature_default
        ),
        max_tokens=settings.gemini_max_output_tokens,
        timeout=settings.gemini_timeout_s,
    )
    steps.append(AgentStep(
        name="Solve",
        output=solve_text,
        latency_ms=solve_ms,
    ))

    total_ms = sum(s.latency_ms for s in steps)
    return AgenticSolveResult(
        provider="gemini",
        model=settings.gemini_model,
        steps=steps,
        answer=solve_text,
        total_latency_ms=total_ms,
    )
