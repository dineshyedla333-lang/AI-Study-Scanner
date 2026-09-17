from __future__ import annotations

import logging
import os
from dataclasses import dataclass, replace
from pathlib import Path

from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    app_name: str = "AI Study Scanner Agent"
    host: str = "127.0.0.1"
    port: int = 8000
    log_level: str = "info"
    env: str = "dev"

    # Groq
    groq_api_key: str | None = None
    # Groq retired the whole Llama family (llama-3.3-70b-versatile returned
    # model_not_found from 2026-09) — every solve 502'd in production until this
    # moved. gpt-oss-120b is the closest replacement in capability and cost.
    groq_model: str = "openai/gpt-oss-120b"
    # gpt-oss is a reasoning model: its thinking tokens count against max_tokens,
    # and at the default effort a 150-token classify call is all reasoning and no
    # answer. "low" keeps outputs inside the existing budgets. Set to "" to omit
    # the parameter for models that treat it as "turn thinking on" (e.g. qwen).
    groq_reasoning_effort: str = "low"
    groq_timeout_s: float = 30.0
    groq_temperature_exam: float = 0.2
    groq_temperature_default: float = 0.35
    # Includes the reasoning model's thinking, not just the visible answer;
    # 512 left nothing for the answer on a confusing question.
    groq_max_output_tokens: int = 2048
    # Home Work generates many Q&A at once, so it needs a bigger budget.
    groq_homework_max_output_tokens: int = 3072
    groq_homework_timeout_s: float = 60.0
    # AI Planner builds a multi-month program, so it needs the biggest budget.
    groq_planner_max_output_tokens: int = 4096
    groq_planner_timeout_s: float = 60.0

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

    # Math OCR (Mathpix) for the Pro scan path. Unset -> /ocr answers 503 and
    # the app keeps using on-device ML Kit, so this is safe to leave empty.
    mathpix_app_id: str | None = None
    mathpix_app_key: str | None = None
    mathpix_timeout_s: float = 20.0
    ocr_max_image_bytes: int = 3 * 1024 * 1024


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
        groq_model=os.getenv("GROQ_MODEL", Settings.groq_model),
        groq_reasoning_effort=os.getenv(
            "GROQ_REASONING_EFFORT", Settings.groq_reasoning_effort
        ).strip(),
        groq_timeout_s=float(os.getenv("GROQ_TIMEOUT_S", "30.0")),
        groq_temperature_exam=float(
            os.getenv("GROQ_TEMPERATURE_EXAM", "0.2")
        ),
        groq_temperature_default=float(
            os.getenv("GROQ_TEMPERATURE_DEFAULT", "0.35")
        ),
        groq_max_output_tokens=int(
            os.getenv("GROQ_MAX_OUTPUT_TOKENS", "2048")
        ),
        groq_homework_max_output_tokens=int(
            os.getenv("GROQ_HOMEWORK_MAX_OUTPUT_TOKENS", "3072")
        ),
        groq_homework_timeout_s=float(
            os.getenv("GROQ_HOMEWORK_TIMEOUT_S", "60.0")
        ),
        groq_planner_max_output_tokens=int(
            os.getenv("GROQ_PLANNER_MAX_OUTPUT_TOKENS", "4096")
        ),
        groq_planner_timeout_s=float(
            os.getenv("GROQ_PLANNER_TIMEOUT_S", "60.0")
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
        mathpix_app_id=os.getenv("MATHPIX_APP_ID"),
        mathpix_app_key=os.getenv("MATHPIX_APP_KEY"),
        mathpix_timeout_s=float(os.getenv("MATHPIX_TIMEOUT_S", "20.0")),
        ocr_max_image_bytes=int(
            os.getenv("OCR_MAX_IMAGE_BYTES", str(3 * 1024 * 1024))
        ),
    )


def ensure_model_available(settings: Settings) -> Settings:
    """
    Swap in the default model if the configured one no longer exists on Groq.

    Groq retires models with little notice (the whole Llama family went in
    Sep 2026) and a GROQ_MODEL pinned in the Render env group then outlives the
    model — every solve 502s until someone edits the dashboard. Asking Groq once
    at startup costs a few hundred ms and lets the service heal itself. Any
    failure to check leaves the configuration exactly as it was.
    """
    if not settings.groq_api_key:
        return settings
    try:
        from groq import Groq

        available = {
            m.id for m in Groq(api_key=settings.groq_api_key).models.list().data
        }
    except Exception as exc:  # network, auth, SDK — never block startup on it
        logging.getLogger("ai-study-scanner").warning(
            "Could not verify GROQ_MODEL against Groq: %s", exc
        )
        return settings
    if not available or settings.groq_model in available:
        return settings
    fallback = Settings.groq_model
    if fallback not in available:
        logging.getLogger("ai-study-scanner").error(
            "GROQ_MODEL %r is unavailable and so is the default %r",
            settings.groq_model,
            fallback,
        )
        return settings
    logging.getLogger("ai-study-scanner").warning(
        "GROQ_MODEL %r is not available on Groq; using %r instead. "
        "Update GROQ_MODEL in the environment.",
        settings.groq_model,
        fallback,
    )
    return replace(settings, groq_model=fallback)


def _parse_feeds(raw: str | None) -> tuple[str, ...]:
    """Comma-separated feed override; falls back to the built-in defaults."""
    if not raw:
        return Settings.news_rss_feeds
    feeds = tuple(f.strip() for f in raw.split(",") if f.strip())
    return feeds or Settings.news_rss_feeds
