"""
AI Study Scanner - Backend entrypoint (FastAPI)

Run (PowerShell) after activating conda env:
  conda activate ai_study_scanner
  python -m uvicorn main:app --reload

Or run directly with env python (no activation needed):
  cmd /c ""C:\\Users\\dines\\anaconda3\\envs\\ai_study_scanner\\python.exe" ^
    -m uvicorn main:app --reload"
"""
import functools
import inspect
import logging
import os
import re
from typing import Literal

from fastapi import Body, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.exception_handlers import http_exception_handler
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel, Field
import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.logging import LoggingIntegration
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

from ai_solver import (
    AgenticSolveResult,
    HomeworkResult,
    LearnResult,
    MissingAPIKeyError,
    PlannerResult,
    SolveResult,
    generate_homework,
    generate_learn_extras,
    generate_study_plan,
    solve_agentic,
    solve_gemini,
)
from config import ensure_model_available, load_settings
from cost_utils import TTLCache, cache_key_for, normalize_question_text
from ocr_repair import normalize_ocr
from math_ocr import MathOcrError, MathOcrNotConfigured, recognize_math
from news import NewsResult, NewsUnavailableError, generate_news_qna
import auth
import notifications
import play_verify
import quota
from notifications import LiveAgentNotConfigured
from prompts import LANGUAGES, build_prompt, normalize_language

settings = load_settings()

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
# After logging is up, so the fallback warning is actually seen on Render.
settings = ensure_model_available(settings)
logger = logging.getLogger("ai-study-scanner")
solve_cache = TTLCache(
    max_size=settings.solve_cache_max_size,
    ttl_seconds=settings.solve_cache_ttl_s,
)
news_cache = TTLCache(max_size=32, ttl_seconds=settings.news_cache_ttl_s)

_TIME_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")


def _validate_times(times: list[str] | None) -> list[str]:
    """Keep valid 'HH:MM' times: zero-pad, dedupe, sort, cap at 4."""
    result: list[str] = []
    seen: set[str] = set()
    for raw in times or []:
        value = (raw or "").strip()
        if not _TIME_RE.match(value):
            continue
        hh, mm = value.split(":")
        norm = f"{int(hh):02d}:{mm}"
        if norm not in seen:
            seen.add(norm)
            result.append(norm)
    return sorted(result)[:4]

# Sentry (enabled only if SENTRY_DSN is set)
_sentry_dsn = os.getenv("SENTRY_DSN")
if _sentry_dsn:
    sentry_logging = LoggingIntegration(
        level=logging.INFO,  # breadcrumbs
        event_level=logging.ERROR,  # send errors as events
    )
    sentry_sdk.init(
        dsn=_sentry_dsn,
        environment=settings.env,
        release=os.getenv("SENTRY_RELEASE"),
        traces_sample_rate=float(
            os.getenv("SENTRY_TRACES_SAMPLE_RATE", "0.0"),
        ),
        integrations=[sentry_logging, FastApiIntegration()],
    )

app = FastAPI(title=settings.app_name)

# Rate limiting (basic anti-abuse protection)
def _rate_limit_key(request: Request) -> str:
    """Rate-limit per device, falling back to IP.

    Indian mobile carriers put thousands of subscribers behind one public IP
    (carrier-grade NAT), so a purely IP-keyed limit makes legitimate users
    throttle each other. The app sends a stable per-install id; anything
    without the header (curl, browsers, /docs) still keys on IP.

    A client can spoof the header, so this trades some anti-abuse strength for
    not 429-ing real students. The per-request AI cost is bounded by the
    upstream Groq quota, and abusive ids are visible in logs.
    """
    # A verified Firebase uid (set by firebase_auth_middleware) beats both: it
    # cannot be invented, unlike the header.
    caller = getattr(getattr(request, "state", None), "caller", None)
    if caller is not None:
        return "uid:" + caller.uid
    device = request.headers.get("X-Device-Id")
    if device:
        return "dev:" + device.strip()[:64]
    return get_remote_address(request)


limiter = Limiter(key_func=_rate_limit_key)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Prometheus metrics
Instrumentator().instrument(app).expose(app, endpoint="/metrics")

# Paths that call paid AI (or store user data) and so need a verified caller.
# /health, /metrics, /docs and /cron stay open.
_AUTH_PATHS = (
    "/solve",
    "/homework",
    "/planner",
    "/news",
    "/ocr",
    "/usage",
    "/learn",
)


@app.middleware("http")
async def firebase_auth_middleware(request: Request, call_next):
    """Verify the Firebase ID token before routing, so the rate limiter and the
    quota both see the caller's uid. AUTH_MODE decides about missing tokens."""
    request.state.caller = None
    if request.method != "OPTIONS" and request.url.path.startswith(_AUTH_PATHS):
        try:
            request.state.caller = await run_in_threadpool(
                auth.authenticate, settings, request.headers.get("Authorization")
            )
        except auth.AuthError as e:
            return JSONResponse(
                status_code=e.status,
                content={"detail": {"code": e.code, "message": e.message}},
            )
    return await call_next(request)


def metered(kind: str):
    """Charge one use of the caller's server-side quota for this endpoint.

    The use is reserved before the handler runs and refunded if it raises, so a
    failed or blank AI answer never costs the student a free solve. Requests
    without a verified caller (older app builds while AUTH_MODE=optional) are
    not metered here and keep only the per-device rate limit.

    Async handlers get their own wrapper. A sync wrapper around a coroutine
    function would only ever see the coroutine being *created* — every failure
    inside it would escape the `except` and the student would silently lose the
    quota unit.
    """

    def decorator(fn):
        def _refund(reservation):
            if reservation is None:
                return
            try:
                quota.refund(settings, reservation)
            except Exception:
                logger.warning("Quota refund failed", exc_info=True)

        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def async_wrapper(*args, **kwargs):
                reservation = _reserve(kwargs["request"], kind)
                try:
                    return await fn(*args, **kwargs)
                except BaseException:
                    _refund(reservation)
                    raise

            return async_wrapper

        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            reservation = _reserve(kwargs["request"], kind)
            try:
                return fn(*args, **kwargs)
            except BaseException:
                _refund(reservation)
                raise

        return wrapper

    return decorator


def _reserve(request: Request, kind: str) -> quota.Reservation | None:
    caller = getattr(request.state, "caller", None)
    if caller is None:
        return None
    is_pro = play_verify.is_pro(settings, request.headers.get("X-Play-Purchase-Token"))
    try:
        return quota.reserve(settings, caller, kind, is_pro=is_pro)
    except quota.QuotaExceeded as e:
        raise HTTPException(
            status_code=403, detail={"code": e.code, "message": e.message}
        ) from e
    except Exception:
        # Firestore trouble must not lock every student out; the rate limit and
        # the Groq account quota still bound the cost.
        logger.exception("Quota check failed; allowing request")
        return None


class SolveRequest(BaseModel):
    # Accept both new and legacy keys for compatibility:
    # - Android can send: question + mode
    # - Existing clients can send: question_text + exam_mode
    question_text: str | None = Field(None, min_length=1, max_length=20000)
    question: str | None = Field(None, min_length=1, max_length=20000)
    # Exam Mode is gone — the app is a learning tool and every answer is now a
    # step-by-step explanation. Still accepted and ignored so installs from
    # before build 20 (which send it) do not get a 422.
    exam_mode: bool | None = None
    mode: bool | None = None
    # Optional exam board override: Auto / CBSE / JEE / NEET / EAMCET.
    # "Auto" (or empty) lets the agent detect the board from the question.
    board: str | None = None
    # Language the explanation is written in: en / hi / te / ta / kn / ml /
    # mr / bn / gu. Absent (older builds) means English.
    language: str | None = None

    def board_value(self) -> str:
        return (self.board or "Auto").strip() or "Auto"

    def language_value(self) -> str:
        return normalize_language(self.language)

    def normalized(self) -> str:
        raw_question = self.question_text or self.question or ""
        return normalize_question_text(
            raw_question,
            max_chars=settings.max_question_chars,
        )


class SolveResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    answer: str
    latency_ms: int


class AgentStepResponse(BaseModel):
    name: str
    output: str
    latency_ms: int


class AgenticSolveResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    steps: list[AgentStepResponse]
    answer: str
    total_latency_ms: int
    # Question after OCR repair; optional so older cached results still serialise.
    interpreted_question: str | None = None


class HomeworkRequest(BaseModel):
    topic: str = Field(..., min_length=1, max_length=200)
    count: int = Field(10, ge=3, le=20)
    # Accepted and ignored; see SolveRequest.exam_mode.
    exam_mode: bool | None = None
    board: str | None = None
    language: str | None = None

    def board_value(self) -> str:
        return (self.board or "Auto").strip() or "Auto"

    def language_value(self) -> str:
        return normalize_language(self.language)


class HomeworkItemResponse(BaseModel):
    question: str
    answer: str


class HomeworkResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    topic: str
    questions: list[HomeworkItemResponse]
    latency_ms: int


class PlannerRequest(BaseModel):
    # Target exam/board: CBSE / JEE / NEET / EAMCET / UPSC (others -> general).
    board: str = "CBSE"
    months: int = Field(3, ge=1, le=12)
    hours_per_day: float = Field(3.0, ge=0.5, le=16.0)
    goal: str | None = Field(None, max_length=300)
    language: str | None = None

    def language_value(self) -> str:
        return normalize_language(self.language)


class PlannerMonthResponse(BaseModel):
    month: int
    title: str
    topics: list[str]
    milestone: str


class PlannerResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    board: str
    months: int
    overview: str
    plan: list[PlannerMonthResponse]
    latency_ms: int


class NewsRequest(BaseModel):
    exam: str = "UPSC"
    count: int = Field(5, ge=1, le=10)
    language: str | None = None

    def language_value(self) -> str:
        return normalize_language(self.language)


class NewsItemResponse(BaseModel):
    question: str
    answer: str


class NewsResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    exam: str
    headlines_used: int
    questions: list[NewsItemResponse]
    latency_ms: int


class SubscribeRequest(BaseModel):
    token: str = Field(..., min_length=10, max_length=4096)
    user_id: str | None = None
    email: str | None = Field(None, max_length=320)
    phone: str | None = Field(None, max_length=20)
    id_token: str | None = Field(None, max_length=8192)
    exam: str = "UPSC"
    times: list[str] = Field(default_factory=lambda: ["08:00"])
    tz: str = "Asia/Kolkata"
    count: int = Field(5, ge=1, le=10)
    enabled: bool = True
    # Language for the daily push Q&A; stored on the subscription so dispatch
    # sends each student their own mother tongue.
    language: str | None = None


class UnsubscribeRequest(BaseModel):
    token: str = Field(..., min_length=10, max_length=4096)


class SimpleStatus(BaseModel):
    status: str
    detail: str | None = None


@app.get("/health")
def health() -> dict[str, str]:
    # `model` shows which Groq model this deploy is actually using, so a retired
    # model or a stale GROQ_MODEL override on Render is visible without a solve.
    # `languages` does the same for mother-tongue explanations: it says whether
    # this deploy can answer in Telugu/Hindi without having to spend a solve.
    return {
        "status": "ok",
        "app": settings.app_name,
        "env": settings.env,
        "v": "5",
        "model": settings.groq_model,
        "languages": ",".join(LANGUAGES),
    }


@app.exception_handler(Exception)
async def unhandled_exception_handler(
    request: Request,
    exc: Exception,
) -> JSONResponse:
    if isinstance(exc, HTTPException):
        return await http_exception_handler(request, exc)
    logger.exception("Unhandled error", extra={"path": str(request.url.path)})
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error"},
    )


@app.post("/solve", response_model=SolveResponse)
@limiter.limit(os.getenv("SOLVE_RATE_LIMIT", "20/minute"))
@metered(quota.SOLVE)
def solve_endpoint(request: Request, req: SolveRequest = Body()) -> SolveResponse:
    question_text = req.normalized()
    if not question_text:
        raise HTTPException(
            status_code=422,
            detail="question_text (or question) is required",
        )

    board = req.board_value()
    language = req.language_value()
    # Same glyph-level OCR repair the agent path runs; single-shot has no
    # classifier, so this is its only chance to see `t^3` instead of `t³`/`t`.
    question_text = normalize_ocr(question_text)
    prompt = build_prompt(
        question_text,
        exam_type=board,
        answer_style=settings.prompt_answer_style,
        language=language,
    )
    key = cache_key_for(question_text, language) + ":" + board
    cached_result = solve_cache.get(key)
    if isinstance(cached_result, SolveResult):
        logger.info(
            "Solved from cache",
            extra={
                "provider": cached_result.provider,
                "model": cached_result.model,
                "latency_ms": cached_result.latency_ms,
                "language": language,
                "cache_hit": True,
                "question_chars": len(question_text),
                "prompt_chars": len(prompt),
                "cache_size": solve_cache.stats()["size"],
            },
        )
        return SolveResponse(
            provider="groq",
            model=cached_result.model,
            answer=cached_result.answer,
            latency_ms=cached_result.latency_ms,
        )

    try:
        result = solve_gemini(
            question_text=question_text,
            settings=settings,
            prompt=prompt,
            language=language,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Solve failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    if not result.answer.strip():
        # Never cache or return a blank: the app shows an empty box and the
        # student's free solve is gone.
        raise HTTPException(
            status_code=502,
            detail="AI did not return an answer. Please try again.",
        )

    solve_cache.set(key, result)

    logger.info(
        "Solved",
        extra={
            "provider": result.provider,
            "model": result.model,
            "latency_ms": result.latency_ms,
            "language": language,
            "cache_hit": False,
            "question_chars": len(question_text),
            "prompt_chars": len(prompt),
            "answer_chars": len(result.answer),
            "cache_size": solve_cache.stats()["size"],
        },
    )

    return SolveResponse(
        provider="groq",
        model=result.model,
        answer=result.answer,
        latency_ms=result.latency_ms,
    )


@app.post("/solve/agent", response_model=AgenticSolveResponse)
@limiter.limit(os.getenv("SOLVE_RATE_LIMIT", "20/minute"))
@metered(quota.SOLVE)
def agent_solve_endpoint(
    request: Request, req: SolveRequest = Body()
) -> AgenticSolveResponse:
    question_text = req.normalized()
    if not question_text:
        raise HTTPException(
            status_code=422,
            detail="question_text (or question) is required",
        )

    board = req.board_value()
    language = req.language_value()
    key = "agent:" + cache_key_for(question_text, language) + ":" + board
    cached = solve_cache.get(key)
    if isinstance(cached, AgenticSolveResult):
        logger.info(
            "Agent solved from cache",
            extra={"cache_hit": True, "question_chars": len(question_text)},
        )
        return AgenticSolveResponse(
            provider="groq",
            model=cached.model,
            steps=[
                AgentStepResponse(
                    name=s.name, output=s.output, latency_ms=s.latency_ms
                )
                for s in cached.steps
            ],
            answer=cached.answer,
            total_latency_ms=cached.total_latency_ms,
            interpreted_question=cached.interpreted_question or None,
        )

    try:
        result = solve_agentic(
            question_text=question_text,
            settings=settings,
            board=board,
            answer_style=settings.prompt_answer_style,
            language=language,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Agent solve failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    if not result.answer.strip():
        # Never cache or return a blank: the app shows an empty box and the
        # student's free solve is gone.
        raise HTTPException(
            status_code=502,
            detail="AI did not return an answer. Please try again.",
        )

    solve_cache.set(key, result)
    logger.info(
        "Agent solved",
        extra={
            "model": result.model,
            "total_latency_ms": result.total_latency_ms,
            "steps": len(result.steps),
            "language": language,
            "cache_hit": False,
        },
    )

    return AgenticSolveResponse(
        provider="groq",
        model=result.model,
        steps=[
            AgentStepResponse(
                name=s.name, output=s.output, latency_ms=s.latency_ms
            )
            for s in result.steps
        ],
        answer=result.answer,
        total_latency_ms=result.total_latency_ms,
        interpreted_question=result.interpreted_question or None,
    )


class LearnRequest(BaseModel):
    question_text: str = Field(..., min_length=1, max_length=20000)
    answer_text: str = Field(..., min_length=1, max_length=20000)
    subject: str | None = Field(None, max_length=60)
    topic: str | None = Field(None, max_length=120)
    count: int = Field(3, ge=2, le=5)
    language: str | None = None

    def language_value(self) -> str:
        return normalize_language(self.language)


class LearnResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    key_concept: str
    practice: list[HomeworkItemResponse]
    latency_ms: int


def _learn_to_response(result: LearnResult) -> LearnResponse:
    return LearnResponse(
        provider="groq",
        model=result.model,
        key_concept=result.key_concept,
        practice=[
            HomeworkItemResponse(question=i.question, answer=i.answer)
            for i in result.practice
        ],
        latency_ms=result.latency_ms,
    )


@app.post("/learn", response_model=LearnResponse)
@limiter.limit(os.getenv("LEARN_RATE_LIMIT", "20/minute"))
def learn_endpoint(request: Request, req: LearnRequest = Body()) -> LearnResponse:
    """The concept behind a question the student just solved, plus practice.

    Not metered: the solve it follows already cost a quota unit, and charging
    for the follow-up would train students not to tap it — which is exactly
    the behaviour this feature exists to encourage. The per-device rate limit
    still bounds abuse.
    """
    question_text = normalize_question_text(
        req.question_text, max_chars=settings.max_question_chars
    )
    answer_text = normalize_question_text(
        req.answer_text, max_chars=settings.max_question_chars
    )
    if not question_text or not answer_text:
        raise HTTPException(
            status_code=422, detail="question_text and answer_text are required"
        )

    language = req.language_value()
    subject = (req.subject or "General").strip() or "General"
    topic = (req.topic or "General").strip() or "General"
    key = f"learn:{language}:{req.count}:{topic.lower()}:{question_text.lower()}"

    cached = solve_cache.get(key)
    if isinstance(cached, LearnResult):
        return _learn_to_response(cached)

    try:
        result = generate_learn_extras(
            question_text=question_text,
            answer_text=answer_text,
            settings=settings,
            subject=subject,
            topic=topic,
            count=req.count,
            language=language,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Learn extras failed")
        raise HTTPException(
            status_code=502, detail=f"Upstream AI provider error: {e}"
        ) from e

    if not result.key_concept and not result.practice:
        raise HTTPException(
            status_code=502,
            detail="AI did not return a concept summary. Please try again.",
        )

    solve_cache.set(key, result)
    logger.info(
        "Learn extras generated",
        extra={
            "topic": topic,
            "language": language,
            "practice": len(result.practice),
            "latency_ms": result.latency_ms,
        },
    )
    return _learn_to_response(result)


def _homework_to_response(result: HomeworkResult) -> HomeworkResponse:
    return HomeworkResponse(
        provider="groq",
        model=result.model,
        topic=result.topic,
        questions=[
            HomeworkItemResponse(question=i.question, answer=i.answer)
            for i in result.items
        ],
        latency_ms=result.latency_ms,
    )


@app.post("/homework", response_model=HomeworkResponse)
@limiter.limit(os.getenv("HOMEWORK_RATE_LIMIT", "10/minute"))
@metered(quota.OTHER)
def homework_endpoint(
    request: Request, req: HomeworkRequest = Body()
) -> HomeworkResponse:
    topic = normalize_question_text(req.topic, max_chars=200).strip()
    if not topic:
        raise HTTPException(status_code=422, detail="topic is required")

    count = max(3, min(20, req.count))
    board = req.board_value()
    language = req.language_value()
    key = f"homework:{board}:{language}:{count}:{topic.lower()}"

    cached = solve_cache.get(key)
    if isinstance(cached, HomeworkResult):
        logger.info(
            "Homework from cache",
            extra={
                "topic_chars": len(topic),
                "count": count,
                "board": board,
                "language": language,
            },
        )
        return _homework_to_response(cached)

    try:
        result = generate_homework(
            topic=topic,
            count=count,
            settings=settings,
            board=board,
            language=language,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Homework generation failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    if not result.items:
        raise HTTPException(
            status_code=502,
            detail="AI did not return any questions. Please try again.",
        )

    solve_cache.set(key, result)
    logger.info(
        "Homework generated",
        extra={
            "topic_chars": len(topic),
            "count": len(result.items),
            "requested": count,
            "board": board,
            "language": language,
            "latency_ms": result.latency_ms,
        },
    )
    return _homework_to_response(result)


# --------------------------------------------------------------------------- #
# AI Planner — month-by-month study program for a chosen exam/board
# --------------------------------------------------------------------------- #
def _planner_to_response(result: PlannerResult) -> PlannerResponse:
    return PlannerResponse(
        provider="groq",
        model=result.model,
        board=result.board,
        months=result.months,
        overview=result.overview,
        plan=[
            PlannerMonthResponse(
                month=m.month,
                title=m.title,
                topics=m.topics,
                milestone=m.milestone,
            )
            for m in result.plan
        ],
        latency_ms=result.latency_ms,
    )


@app.post("/planner", response_model=PlannerResponse)
@limiter.limit(os.getenv("PLANNER_RATE_LIMIT", "10/minute"))
@metered(quota.OTHER)
def planner_endpoint(
    request: Request, req: PlannerRequest = Body()
) -> PlannerResponse:
    board = (req.board or "CBSE").strip() or "CBSE"
    months = max(1, min(12, req.months))
    hours = max(0.5, min(16.0, req.hours_per_day))
    goal = normalize_question_text(req.goal or "", max_chars=300).strip()

    language = req.language_value()
    # Round hours for a clean cache key and prompt (e.g. 2.0, 3.5).
    hours = round(hours * 2) / 2
    key = f"planner:{board.upper()}:{language}:{months}:{hours}:{goal.lower()}"

    cached = solve_cache.get(key)
    if isinstance(cached, PlannerResult):
        logger.info(
            "Planner from cache",
            extra={"board": board, "months": months},
        )
        return _planner_to_response(cached)

    try:
        result = generate_study_plan(
            board=board,
            months=months,
            hours_per_day=hours,
            goal=goal or None,
            settings=settings,
            language=language,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Planner generation failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    if not result.plan:
        raise HTTPException(
            status_code=502,
            detail="AI did not return a plan. Please try again.",
        )

    solve_cache.set(key, result)
    logger.info(
        "Planner generated",
        extra={
            "board": board,
            "months": months,
            "returned": len(result.plan),
            "latency_ms": result.latency_ms,
        },
    )
    return _planner_to_response(result)


# --------------------------------------------------------------------------- #
# UPSC Live Agent — current-affairs news + scheduled push
# --------------------------------------------------------------------------- #
def _news_to_response(result: NewsResult) -> NewsResponse:
    return NewsResponse(
        provider="groq",
        model=result.model,
        exam=result.exam,
        headlines_used=result.headlines_used,
        questions=[
            NewsItemResponse(question=i.question, answer=i.answer)
            for i in result.items
        ],
        latency_ms=result.latency_ms,
    )


class OcrResponse(BaseModel):
    provider: Literal["mathpix"]
    text: str
    confidence: float
    latency_ms: int


@app.post("/ocr", response_model=OcrResponse)
@limiter.limit(os.getenv("OCR_RATE_LIMIT", "6/minute"))
async def ocr_endpoint(
    request: Request, image: UploadFile = File(...)
) -> OcrResponse:
    """
    Math-aware OCR for the Pro scan path. The app only calls this for Pro
    users and falls back to on-device OCR on any non-200, so an unconfigured
    or failing Mathpix degrades to today's behaviour rather than an error.

    Entitlement is client-side (see BillingManager), so the real abuse limits
    here are the per-IP rate limit and the image size cap.
    """
    content_type = (image.content_type or "").lower()
    if content_type not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(status_code=415, detail="Send a JPEG, PNG or WebP image")
    data = await image.read(settings.ocr_max_image_bytes + 1)
    if len(data) > settings.ocr_max_image_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"Image larger than {settings.ocr_max_image_bytes // 1024} KB",
        )
    if not data:
        raise HTTPException(status_code=422, detail="Empty image")

    try:
        result = recognize_math(data, content_type, settings)
    except MathOcrNotConfigured:
        raise HTTPException(status_code=503, detail="Math OCR is not enabled")
    except MathOcrError as e:
        logger.warning("Math OCR failed: %s", e)
        raise HTTPException(status_code=502, detail="Math OCR provider error") from e

    logger.info(
        "Math OCR done",
        extra={
            "latency_ms": result.latency_ms,
            "confidence": result.confidence,
            "image_bytes": len(data),
            "text_chars": len(result.text),
        },
    )
    return OcrResponse(
        provider="mathpix",
        text=result.text,
        confidence=result.confidence,
        latency_ms=result.latency_ms,
    )


@app.post("/news", response_model=NewsResponse)
@limiter.limit(os.getenv("NEWS_RATE_LIMIT", "10/minute"))
@metered(quota.OTHER)
def news_endpoint(
    request: Request, req: NewsRequest = Body()
) -> NewsResponse:
    exam = (req.exam or "UPSC").strip().upper() or "UPSC"
    count = max(1, min(10, req.count))
    language = req.language_value()
    key = f"news:{exam}:{language}:{count}"

    cached = news_cache.get(key)
    if isinstance(cached, NewsResult):
        return _news_to_response(cached)

    try:
        result = generate_news_qna(
            settings=settings, exam=exam, count=count, language=language
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except NewsUnavailableError as e:
        raise HTTPException(status_code=503, detail=str(e)) from e
    except Exception as e:
        logger.exception("News generation failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    if not result.items:
        raise HTTPException(
            status_code=502,
            detail="AI did not return any questions. Please try again.",
        )

    news_cache.set(key, result)
    logger.info(
        "News generated",
        extra={
            "exam": exam,
            "count": len(result.items),
            "headlines_used": result.headlines_used,
            "language": language,
            "latency_ms": result.latency_ms,
        },
    )
    return _news_to_response(result)


@app.post("/news/subscribe", response_model=SimpleStatus)
@limiter.limit(os.getenv("SUBSCRIBE_RATE_LIMIT", "20/minute"))
def subscribe_endpoint(
    request: Request, req: SubscribeRequest = Body()
) -> SimpleStatus:
    if not notifications.is_configured(settings):
        raise HTTPException(
            status_code=503,
            detail="UPSC Live Agent is not configured on the server yet.",
        )

    times = _validate_times(req.times)
    if not times:
        raise HTTPException(
            status_code=422,
            detail="Provide 1-4 valid times in HH:MM (24h) format.",
        )

    # Identity: prefer values verified from the Firebase ID token over whatever
    # the client claims. Falls back to the raw fields if the token is absent or
    # unverifiable (e.g. Firebase not configured) so subscribe never hard-fails.
    verified = (
        notifications.verify_id_token(settings, req.id_token)
        if req.id_token
        else None
    )
    uid = (verified or {}).get("uid") or (req.user_id or "")
    email = ((verified or {}).get("email") or (req.email or "")).strip()
    phone = (req.phone or "").strip()

    sub = {
        "token": req.token,
        "userId": uid,
        "email": email,
        "phone": phone,
        "exam": (req.exam or "UPSC").strip().upper() or "UPSC",
        "times": times,
        "tz": (req.tz or "Asia/Kolkata").strip() or "Asia/Kolkata",
        "count": max(1, min(10, req.count)),
        "enabled": bool(req.enabled),
        "lang": normalize_language(req.language),
    }
    try:
        notifications.upsert_subscription(settings, sub)
    except LiveAgentNotConfigured as e:
        raise HTTPException(status_code=503, detail=str(e)) from e
    except Exception as e:
        logger.exception("Subscribe failed")
        raise HTTPException(
            status_code=502,
            detail=f"Could not save subscription: {e}",
        ) from e

    # Best-effort user record so we have a registry of who signed up.
    if uid:
        try:
            notifications.upsert_user(
                settings,
                {
                    "uid": uid,
                    "email": email,
                    "phone": phone,
                    "lastToken": req.token,
                    "verified": verified is not None,
                },
            )
        except Exception:
            logger.warning("User record upsert failed", exc_info=True)

    return SimpleStatus(
        status="ok",
        detail=f"Subscribed to {sub['exam']} Live Agent at {', '.join(times)}.",
    )


@app.post("/news/unsubscribe", response_model=SimpleStatus)
@limiter.limit(os.getenv("SUBSCRIBE_RATE_LIMIT", "20/minute"))
def unsubscribe_endpoint(
    request: Request, req: UnsubscribeRequest = Body()
) -> SimpleStatus:
    if not notifications.is_configured(settings):
        raise HTTPException(
            status_code=503,
            detail="UPSC Live Agent is not configured on the server yet.",
        )
    try:
        notifications.delete_subscription(settings, req.token)
    except LiveAgentNotConfigured as e:
        raise HTTPException(status_code=503, detail=str(e)) from e
    except Exception as e:
        logger.exception("Unsubscribe failed")
        raise HTTPException(
            status_code=502,
            detail=f"Could not remove subscription: {e}",
        ) from e
    return SimpleStatus(status="ok", detail="Unsubscribed from Live Agent.")


@app.post("/usage/bonus")
@limiter.limit(os.getenv("BONUS_RATE_LIMIT", "10/minute"))
def usage_bonus_endpoint(request: Request) -> dict:
    """Record a watched rewarded ad: +AD_BONUS_SOLVES for today, capped per day."""
    caller = getattr(request.state, "caller", None)
    if caller is None:
        # Older builds / AUTH_MODE=off: there is no server quota to top up.
        return {"status": "ok", "bonus": 0}
    try:
        usage = quota.grant_bonus(settings, caller)
    except quota.QuotaExceeded as e:
        raise HTTPException(
            status_code=403, detail={"code": e.code, "message": e.message}
        ) from e
    return {"status": "ok", "bonus": usage.bonus}


@app.api_route("/cron/dispatch", methods=["GET", "POST"])
def cron_dispatch(request: Request, key: str | None = None) -> dict:
    """Protected: an external cron calls this every ~15 min (GET or POST).

    Auth via ?key=... or the X-Cron-Key header, matched against CRON_SECRET.
    """
    secret = settings.cron_secret
    provided = key or request.headers.get("X-Cron-Key")
    if not secret or provided != secret:
        raise HTTPException(status_code=403, detail="Forbidden")

    if not notifications.is_configured(settings):
        raise HTTPException(
            status_code=503,
            detail="UPSC Live Agent is not configured on the server yet.",
        )

    try:
        summary = notifications.dispatch(settings)
    except Exception as e:
        logger.exception("Dispatch failed")
        raise HTTPException(
            status_code=502, detail=f"Dispatch failed: {e}"
        ) from e

    logger.info(
        "Dispatch run",
        extra={
            "checked": summary.checked,
            "due": summary.due,
            "sent": summary.sent,
            "failed": summary.failed,
            "pruned": summary.pruned,
        },
    )
    return {
        "checked": summary.checked,
        "due": summary.due,
        "sent": summary.sent,
        "failed": summary.failed,
        "pruned": summary.pruned,
    }
