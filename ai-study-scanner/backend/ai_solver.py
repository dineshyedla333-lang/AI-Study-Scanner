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
    HOMEWORK_PROMPT_TEMPLATE,
    _normalize_answer_style,
    _normalize_exam_type,
)


def _is_auto_board(board: str | None) -> bool:
    return (board or "").strip().lower() in ("", "auto")


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


@dataclass(frozen=True)
class HomeworkItem:
    question: str
    answer: str


@dataclass(frozen=True)
class HomeworkResult:
    provider: str
    model: str
    topic: str
    items: list[HomeworkItem] = field(default_factory=list)
    latency_ms: int = 0


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
    board: str = "Auto",
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

    # A user-selected board (anything other than "Auto") overrides the
    # classifier's guess so the answer matches the syllabus they chose.
    if _is_auto_board(board):
        norm_exam = _normalize_exam_type(
            classification.get("exam_board") or exam_type
        )
    else:
        norm_exam = _normalize_exam_type(board)
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


def _parse_homework_json(text: str, count: int) -> list[HomeworkItem]:
    """Best-effort parse of the model's JSON array of {question, answer}."""
    raw = (text or "").strip()
    # Slice to the outermost JSON array, ignoring code fences or stray prose.
    start = raw.find("[")
    end = raw.rfind("]")
    if start != -1 and end != -1 and end > start:
        raw = raw[start : end + 1]

    try:
        data = json.loads(raw)
    except Exception:
        return []
    if not isinstance(data, list):
        return []

    items: list[HomeworkItem] = []
    for obj in data:
        if not isinstance(obj, dict):
            continue
        question = str(obj.get("question", "")).strip()
        answer = str(obj.get("answer", "")).strip()
        if question:
            items.append(HomeworkItem(question=question, answer=answer))
    return items[:count]


def generate_homework(
    *,
    topic: str,
    count: int,
    exam_mode: bool,
    settings: Settings,
    board: str = "Auto",
) -> HomeworkResult:
    if not settings.groq_api_key:
        raise MissingAPIKeyError("GROQ_API_KEY is not configured")

    if _is_auto_board(board):
        exam_label = "General (Indian school / entrance syllabus)"
        exam_guide = "Match the standard Indian school and entrance syllabus."
    else:
        norm_exam = _normalize_exam_type(board)
        exam_label = norm_exam
        exam_guide = EXAM_MODE_GUIDE[norm_exam]

    mode_line = (
        "Keep each answer short and direct (exam style)."
        if exam_mode
        else "Give clear, step-by-step answers suitable for self-study."
    )

    prompt = HOMEWORK_PROMPT_TEMPLATE.format(
        count=count,
        exam_label=exam_label,
        exam_guide=exam_guide,
        mode_line=mode_line,
        topic=topic,
    )

    client = Groq(api_key=settings.groq_api_key)
    text, latency_ms = _call_groq(
        client,
        model=settings.groq_model,
        prompt=prompt,
        temperature=settings.groq_temperature_default,
        max_tokens=settings.groq_homework_max_output_tokens,
        timeout=settings.groq_homework_timeout_s,
    )

    items = _parse_homework_json(text, count)
    return HomeworkResult(
        provider="groq",
        model=settings.groq_model,
        topic=topic,
        items=items,
        latency_ms=latency_ms,
    )
