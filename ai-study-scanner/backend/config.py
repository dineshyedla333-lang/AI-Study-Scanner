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

    # Groq
    groq_api_key: str | None = None
    groq_model: str = "llama-3.3-70b-versatile"
    groq_timeout_s: float = 30.0
    groq_temperature_exam: float = 0.2
    groq_temperature_default: float = 0.35
    groq_max_output_tokens: int = 512
    # Home Work generates many Q&A at once, so it needs a bigger budget.
    groq_homework_max_output_tokens: int = 3072
    groq_homework_timeout_s: float = 60.0

    # Cost controls
    max_question_chars: int = 4000
    prompt_answer_style: str = "compact"
    solve_cache_ttl_s: int = 900
    solve_cache_max_size: int = 256

    # UPSC Live Agent (current-affairs news -> Q&A + push)
    news_rss_feeds: tuple[str, ...] = (
        "https://pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3",
        "https://www.thehindu.com/news/national/feeder/default.rss",
        "https://indianexpress.com/section/india/feed/",
    )
    news_per_feed: int = 6
    news_max_headlines: int = 15
    news_fetch_timeout_s: float = 8.0
    news_cache_ttl_s: int = 1800  # news changes slowly; cache 30 min
    news_dispatch_window_min: int = 90  # cron grace window for due slots
    # Secret that protects POST /cron/dispatch (set on Render + cron caller).
    cron_secret: str | None = None
    # Service-account JSON (string) for firebase-admin (FCM + Firestore).
    firebase_credentials_json: str | None = None


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
        groq_api_key=os.getenv("GROQ_API_KEY"),
        groq_model=os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile"),
        groq_timeout_s=float(os.getenv("GROQ_TIMEOUT_S", "30.0")),
        groq_temperature_exam=float(
            os.getenv("GROQ_TEMPERATURE_EXAM", "0.2")
        ),
        groq_temperature_default=float(
            os.getenv("GROQ_TEMPERATURE_DEFAULT", "0.35")
        ),
        groq_max_output_tokens=int(
            os.getenv("GROQ_MAX_OUTPUT_TOKENS", "512")
        ),
        groq_homework_max_output_tokens=int(
            os.getenv("GROQ_HOMEWORK_MAX_OUTPUT_TOKENS", "3072")
        ),
        groq_homework_timeout_s=float(
            os.getenv("GROQ_HOMEWORK_TIMEOUT_S", "60.0")
        ),
        max_question_chars=int(os.getenv("MAX_QUESTION_CHARS", "4000")),
        prompt_answer_style=os.getenv("PROMPT_ANSWER_STYLE", "compact"),
        solve_cache_ttl_s=int(os.getenv("SOLVE_CACHE_TTL_S", "900")),
        solve_cache_max_size=int(os.getenv("SOLVE_CACHE_MAX_SIZE", "256")),
        news_rss_feeds=_parse_feeds(os.getenv("NEWS_RSS_FEEDS")),
        news_per_feed=int(os.getenv("NEWS_PER_FEED", "6")),
        news_max_headlines=int(os.getenv("NEWS_MAX_HEADLINES", "15")),
        news_fetch_timeout_s=float(os.getenv("NEWS_FETCH_TIMEOUT_S", "8.0")),
        news_cache_ttl_s=int(os.getenv("NEWS_CACHE_TTL_S", "1800")),
        news_dispatch_window_min=int(
            os.getenv("NEWS_DISPATCH_WINDOW_MIN", "90")
        ),
        cron_secret=os.getenv("CRON_SECRET"),
        firebase_credentials_json=os.getenv("FIREBASE_CREDENTIALS_JSON"),
    )


def _parse_feeds(raw: str | None) -> tuple[str, ...]:
    """Comma-separated feed override; falls back to the built-in defaults."""
    if not raw:
        return Settings.news_rss_feeds
    feeds = tuple(f.strip() for f in raw.split(",") if f.strip())
    return feeds or Settings.news_rss_feeds
