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

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel, Field
import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.logging import LoggingIntegration
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

from backend.ai_solver import MissingAPIKeyError, solve_gemini
from backend.config import load_settings
from backend.prompts import build_prompt

settings = load_settings()

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("ai-study-scanner")

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
        question_text = (self.question_text or self.question or "").strip()
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


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "app": settings.app_name, "env": settings.env}


@app.exception_handler(Exception)
async def unhandled_exception_handler(
    request: Request,
    exc: Exception,
) -> JSONResponse:
    logger.exception("Unhandled error", extra={"path": str(request.url.path)})
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error"},
    )


@app.post("/solve", response_model=SolveResponse)
@limiter.limit(os.getenv("SOLVE_RATE_LIMIT", "10/minute"))
def solve_endpoint(req: SolveRequest) -> SolveResponse:
    question_text, exam_mode = req.normalized()
    if not question_text:
        raise HTTPException(
            status_code=422,
            detail="question_text (or question) is required",
        )

    prompt = build_prompt(question_text, exam_mode)

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

    logger.info(
        "Solved",
        extra={
            "provider": result.provider,
            "model": result.model,
            "latency_ms": result.latency_ms,
            "exam_mode": exam_mode,
        },
    )

    return SolveResponse(
        provider="gemini",
        model=result.model,
        answer=result.answer,
        latency_ms=result.latency_ms,
    )
