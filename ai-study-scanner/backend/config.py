from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv
import os


@dataclass(frozen=True)
class Settings:
    app_name: str = "AI Study Scanner Agent"
    host: str = "127.0.0.1"
    port: int = 8000
    log_level: str = "info"
    env: str = "dev"

    # Gemini
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-1.5-flash"
    gemini_timeout_s: float = 30.0
    gemini_temperature_exam: float = 0.2
    gemini_temperature_default: float = 0.35
    gemini_max_output_tokens: int = 512

    # Cost controls
    max_question_chars: int = 4000
    prompt_answer_style: str = "compact"
    solve_cache_ttl_s: int = 900
    solve_cache_max_size: int = 256


def load_settings() -> Settings:
    """
    Loads environment variables from a local `.env` if present,
    then returns settings.
    """
    # Load .env from backend/ if present (safe no-op if missing)
    backend_dir = Path(__file__).resolve().parent
    load_dotenv(backend_dir / ".env")

    return Settings(
        app_name=os.getenv("APP_NAME", Settings.app_name),
        host=os.getenv("HOST", Settings.host),
        port=int(os.getenv("PORT", str(Settings.port))),
        log_level=os.getenv("LOG_LEVEL", Settings.log_level),
        env=os.getenv("ENV", Settings.env),
        gemini_api_key=os.getenv("GEMINI_API_KEY"),
        gemini_model=os.getenv("GEMINI_MODEL", "gemini-1.5-flash"),
        gemini_timeout_s=float(os.getenv("GEMINI_TIMEOUT_S", "30.0")),
        gemini_temperature_exam=float(
            os.getenv("GEMINI_TEMPERATURE_EXAM", "0.2")
        ),
        gemini_temperature_default=float(
            os.getenv("GEMINI_TEMPERATURE_DEFAULT", "0.35")
        ),
        gemini_max_output_tokens=int(
            os.getenv("GEMINI_MAX_OUTPUT_TOKENS", "512")
        ),
        max_question_chars=int(os.getenv("MAX_QUESTION_CHARS", "4000")),
        prompt_answer_style=os.getenv("PROMPT_ANSWER_STYLE", "compact"),
        solve_cache_ttl_s=int(os.getenv("SOLVE_CACHE_TTL_S", "900")),
        solve_cache_max_size=int(os.getenv("SOLVE_CACHE_MAX_SIZE", "256")),
    )
