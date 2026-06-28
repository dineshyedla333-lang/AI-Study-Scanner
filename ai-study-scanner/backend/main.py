"""
AI Study Scanner - Backend entrypoint (FastAPI)

Run (PowerShell) after activating conda env:
  conda activate ai_study_scanner
  python -m uvicorn main:app --reload

Or run directly with env python (no activation needed):
  cmd /c ""C:\\Users\\dines\\anaconda3\\envs\\ai_study_scanner\\python.exe" ^
    -m uvicorn main:app --reload"
"""
from __future__ import annotations

import logging
import os
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
    MissingAPIKeyError,
    SolveResult,
    solve_agentic,
    solve_gemini,
)
from config import load_settings
from cost_utils import TTLCache, cache_key_for, normalize_question_text
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
    provider: Literal["gemini"]
    model: str
    answer: str
    latency_ms: int


class AgentStepResponse(BaseModel):
    name: str
    output: str
    latency_ms: int


class AgenticSolveResponse(BaseModel):
    provider: Literal["gemini"]
    model: str
    steps: list[AgentStepResponse]
    answer: str
    total_latency_ms: int


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

    prompt = build_prompt(
        question_text,
        exam_mode,
        answer_style=settings.prompt_answer_style,
    )
    key = cache_key_for(question_text, exam_mode)
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
            provider="gemini",
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
            detail="Upstream AI provider error",
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
        provider="gemini",
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

    key = "agent:" + cache_key_for(question_text, exam_mode)
    cached = solve_cache.get(key)
    if isinstance(cached, AgenticSolveResult):
        logger.info(
            "Agent solved from cache",
            extra={"cache_hit": True, "question_chars": len(question_text)},
        )
        return AgenticSolveResponse(
            provider="gemini",
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
        )
    except MissingAPIKeyError as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    except Exception as e:
        logger.exception("Agent solve failed")
        raise HTTPException(
            status_code=502,
            detail="Upstream AI provider error",
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
        provider="gemini",
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
