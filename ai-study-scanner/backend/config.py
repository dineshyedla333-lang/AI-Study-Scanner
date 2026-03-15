from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv
import os


@dataclass(frozen=True)
class Settings:
    app_name: str = "AI Study Scanner"
    host: str = "127.0.0.1"
    port: int = 8000
    log_level: str = "info"
    env: str = "dev"

    # Gemini
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-1.5-flash"
    gemini_timeout_s: float = 30.0


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
    )
