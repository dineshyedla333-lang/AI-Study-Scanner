from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any

from groq import Groq

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


def _call_groq(
    client: Groq,
    model: str,
    prompt: str,
    temperature: float,
    max_tokens: int,
    timeout: float,
) -> tuple[str, int]:
    """Single Groq call. Returns (text, latency_ms)."""
    started = time.perf_counter()
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=temperature,
        max_tokens=max_tokens,
        timeout=timeout,
    )
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    text = (resp.choices[0].message.content or "").strip()
    return text, elapsed_ms


def solve_gemini(
    *,
    question_text: str,
    exam_mode: bool,
    settings: Settings,
    prompt: str,
) -> SolveResult:
    if not settings.groq_api_key:
        raise MissingAPIKeyError("GROQ_API_KEY is not configured")

    client = Groq(api_key=settings.groq_api_key)
    started = time.perf_counter()

    text, latency_ms = _call_groq(
        client,
        model=settings.groq_model,
        prompt=prompt,
        temperature=(
            settings.groq_temperature_exam
            if exam_mode
            else settings.groq_temperature_default
        ),
        max_tokens=settings.groq_max_output_tokens,
        timeout=settings.groq_timeout_s,
    )

    return SolveResult(
        provider="groq",
        model=settings.groq_model,
        prompt=prompt,
        answer=text,
        latency_ms=latency_ms,
    )


def solve_agentic(
    *,
    question_text: str,
    exam_mode: bool,
    settings: Settings,
    exam_type: str = "CBSE",
    answer_style: str = "compact",
) -> AgenticSolveResult:
    if not settings.groq_api_key:
        raise MissingAPIKeyError("GROQ_API_KEY is not configured")

    client = Groq(api_key=settings.groq_api_key)
    steps: list[AgentStep] = []

    # Step 1: Classify
    classify_prompt = CLASSIFY_PROMPT_TEMPLATE.format(
        question_text=question_text
    )
    classify_text, classify_ms = _call_groq(
        client,
        model=settings.groq_model,
        prompt=classify_prompt,
        temperature=0.1,
        max_tokens=150,
        timeout=settings.groq_timeout_s,
    )
    steps.append(AgentStep(name="Classify", output=classify_text, latency_ms=classify_ms))

    # Parse classification JSON; fall back to defaults on any error
    classification: dict[str, str] = {}
    try:
        raw_json = classify_text
        if raw_json.startswith("```"):
            raw_json = raw_json.split("```")[1]
            if raw_json.startswith("json"):
                raw_json = raw_json[4:]
        classification = json.loads(raw_json.strip())
    except Exception:
        pass

    norm_exam = _normalize_exam_type(classification.get("exam_board") or exam_type)
    norm_style = _normalize_answer_style(answer_style)
    mode_line = "exam mode on: keep steps short and direct." if exam_mode else ""

    # Step 2: Solve with plan
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
    solve_text, solve_ms = _call_groq(
        client,
        model=settings.groq_model,
        prompt=solve_prompt,
        temperature=(
            settings.groq_temperature_exam
            if exam_mode
            else settings.groq_temperature_default
        ),
        max_tokens=settings.groq_max_output_tokens,
        timeout=settings.groq_timeout_s,
    )
    steps.append(AgentStep(name="Solve", output=solve_text, latency_ms=solve_ms))

    total_ms = sum(s.latency_ms for s in steps)
    return AgenticSolveResult(
        provider="groq",
        model=settings.groq_model,
        steps=steps,
        answer=solve_text,
        total_latency_ms=total_ms,
    )
