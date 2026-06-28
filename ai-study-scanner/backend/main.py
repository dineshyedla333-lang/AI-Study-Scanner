"""
AI Study Scanner - Backend entrypoint (FastAPI)

Run (PowerShell) after activating conda env:
  conda activate ai_study_scanner
  python -m uvicorn main:app --reload

Or run directly with env python (no activation needed):
  cmd /c ""C:\\Users\\dines\\anaconda3\\envs\\ai_study_scanner\\python.exe" ^
    -m uvicorn main:app --reload"
"""
import logging
import os
import re
from typing import Literal

from fastapi import Body, FastAPI, HTTPException, Request
from fastapi.exception_handlers import http_exception_handler
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
    MissingAPIKeyError,
    SolveResult,
    generate_homework,
    solve_agentic,
    solve_gemini,
)
from config import load_settings
from cost_utils import TTLCache, cache_key_for, normalize_question_text
from news import NewsResult, NewsUnavailableError, generate_news_qna
import notifications
from notifications import LiveAgentNotConfigured
from prompts import build_prompt

settings = load_settings()

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("ai-study-scanner")
solve_cache = TTLCache(
    max_size=settings.solve_cache_max_size,
    ttl_seconds=settings.solve_cache_ttl_s,
)
news_cache = TTLCache(max_size=32, ttl_seconds=settings.news_cache_ttl_s)

_TIME_RE = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")


def _validate_times(times: list[str] | None) -> list[str]:
    """Keep valid 'HH:MM' entries, normalise to zero-padded, dedupe, cap at 3."""
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
    return result[:3]

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
limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Prometheus metrics
Instrumentator().instrument(app).expose(app, endpoint="/metrics")


class SolveRequest(BaseModel):
    # Accept both new and legacy keys for compatibility:
    # - Android can send: question + mode
    # - Existing clients can send: question_text + exam_mode
    question_text: str | None = Field(None, min_length=1, max_length=20000)
    exam_mode: bool | None = None
    question: str | None = Field(None, min_length=1, max_length=20000)
    mode: bool | None = None
    # Optional exam board override: Auto / CBSE / JEE / NEET / EAMCET.
    # "Auto" (or empty) lets the agent detect the board from the question.
    board: str | None = None

    def board_value(self) -> str:
        return (self.board or "Auto").strip() or "Auto"

    def normalized(self) -> tuple[str, bool]:
        raw_question = self.question_text or self.question or ""
        question_text = normalize_question_text(
            raw_question,
            max_chars=settings.max_question_chars,
        )
        if self.exam_mode is not None:
            exam_mode = bool(self.exam_mode)
        else:
            exam_mode = bool(self.mode)
        return question_text, exam_mode


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


class HomeworkRequest(BaseModel):
    topic: str = Field(..., min_length=1, max_length=200)
    count: int = Field(10, ge=3, le=20)
    exam_mode: bool = False
    board: str | None = None

    def board_value(self) -> str:
        return (self.board or "Auto").strip() or "Auto"


class HomeworkItemResponse(BaseModel):
    question: str
    answer: str


class HomeworkResponse(BaseModel):
    provider: Literal["groq"]
    model: str
    topic: str
    questions: list[HomeworkItemResponse]
    latency_ms: int


class NewsRequest(BaseModel):
    exam: str = "UPSC"
    count: int = Field(5, ge=1, le=10)


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
    exam: str = "UPSC"
    times: list[str] = Field(default_factory=lambda: ["08:00"])
    tz: str = "Asia/Kolkata"
    count: int = Field(5, ge=1, le=10)
    enabled: bool = True


class UnsubscribeRequest(BaseModel):
    token: str = Field(..., min_length=10, max_length=4096)


class SimpleStatus(BaseModel):
    status: str
    detail: str | None = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "app": settings.app_name, "env": settings.env, "v": "3"}


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
@limiter.limit(os.getenv("SOLVE_RATE_LIMIT", "10/minute"))
def solve_endpoint(request: Request, req: SolveRequest = Body()) -> SolveResponse:
    question_text, exam_mode = req.normalized()
    if not question_text:
        raise HTTPException(
            status_code=422,
            detail="question_text (or question) is required",
        )

    board = req.board_value()
    prompt = build_prompt(
        question_text,
        exam_mode,
        exam_type=board,
        answer_style=settings.prompt_answer_style,
    )
    key = cache_key_for(question_text, exam_mode) + ":" + board
    cached_result = solve_cache.get(key)
    if isinstance(cached_result, SolveResult):
        logger.info(
            "Solved from cache",
            extra={
                "provider": cached_result.provider,
                "model": cached_result.model,
                "latency_ms": cached_result.latency_ms,
                "exam_mode": exam_mode,
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
            exam_mode=exam_mode,
            settings=settings,
            prompt=prompt,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Solve failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    solve_cache.set(key, result)

    logger.info(
        "Solved",
        extra={
            "provider": result.provider,
            "model": result.model,
            "latency_ms": result.latency_ms,
            "exam_mode": exam_mode,
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
@limiter.limit(os.getenv("SOLVE_RATE_LIMIT", "10/minute"))
def agent_solve_endpoint(
    request: Request, req: SolveRequest = Body()
) -> AgenticSolveResponse:
    question_text, exam_mode = req.normalized()
    if not question_text:
        raise HTTPException(
            status_code=422,
            detail="question_text (or question) is required",
        )

    board = req.board_value()
    key = "agent:" + cache_key_for(question_text, exam_mode) + ":" + board
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
        )

    try:
        result = solve_agentic(
            question_text=question_text,
            exam_mode=exam_mode,
            settings=settings,
            board=board,
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Agent solve failed")
        raise HTTPException(
            status_code=502,
            detail=f"Upstream AI provider error: {e}",
        ) from e

    solve_cache.set(key, result)
    logger.info(
        "Agent solved",
        extra={
            "model": result.model,
            "total_latency_ms": result.total_latency_ms,
            "steps": len(result.steps),
            "exam_mode": exam_mode,
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
    )


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
@limiter.limit(os.getenv("HOMEWORK_RATE_LIMIT", "5/minute"))
def homework_endpoint(
    request: Request, req: HomeworkRequest = Body()
) -> HomeworkResponse:
    topic = normalize_question_text(req.topic, max_chars=200).strip()
    if not topic:
        raise HTTPException(status_code=422, detail="topic is required")

    count = max(3, min(20, req.count))
    board = req.board_value()
    mode_key = "exam" if req.exam_mode else "learn"
    key = f"homework:{board}:{mode_key}:{count}:{topic.lower()}"

    cached = solve_cache.get(key)
    if isinstance(cached, HomeworkResult):
        logger.info(
            "Homework from cache",
            extra={"topic_chars": len(topic), "count": count, "board": board},
        )
        return _homework_to_response(cached)

    try:
        result = generate_homework(
            topic=topic,
            count=count,
            exam_mode=bool(req.exam_mode),
            settings=settings,
            board=board,
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
            "exam_mode": req.exam_mode,
            "latency_ms": result.latency_ms,
        },
    )
    return _homework_to_response(result)


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


@app.post("/news", response_model=NewsResponse)
@limiter.limit(os.getenv("NEWS_RATE_LIMIT", "10/minute"))
def news_endpoint(
    request: Request, req: NewsRequest = Body()
) -> NewsResponse:
    exam = (req.exam or "UPSC").strip().upper() or "UPSC"
    count = max(1, min(10, req.count))
    key = f"news:{exam}:{count}"

    cached = news_cache.get(key)
    if isinstance(cached, NewsResult):
        return _news_to_response(cached)

    try:
        result = generate_news_qna(settings=settings, exam=exam, count=count)
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
            detail="Provide 1-3 valid times in HH:MM (24h) format.",
        )

    sub = {
        "token": req.token,
        "userId": req.user_id or "",
        "exam": (req.exam or "UPSC").strip().upper() or "UPSC",
        "times": times,
        "tz": (req.tz or "Asia/Kolkata").strip() or "Asia/Kolkata",
        "count": max(1, min(10, req.count)),
        "enabled": bool(req.enabled),
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


@app.post("/cron/dispatch")
def cron_dispatch(request: Request, key: str | None = None) -> dict:
    """Protected: an external cron (or Render cron) calls this every ~15 min.

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
