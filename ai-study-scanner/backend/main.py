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
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from backend.ai_solver import MissingAPIKeyError, solve_gemini
from backend.config import load_settings
from backend.prompts import build_prompt

settings = load_settings()

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("ai-study-scanner")

app = FastAPI(title=settings.app_name)


class SolveRequest(BaseModel):
    question_text: str = Field(..., min_length=1, max_length=20000)
    exam_mode: bool = False


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
def solve_endpoint(req: SolveRequest) -> SolveResponse:
    prompt = build_prompt(req.question_text, req.exam_mode)

    try:
        result = solve_gemini(
            question_text=req.question_text,
            exam_mode=req.exam_mode,
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
            "exam_mode": req.exam_mode,
        },
    )

    return SolveResponse(
        provider="gemini",
        model=result.model,
        answer=result.answer,
        latency_ms=result.latency_ms,
    )
