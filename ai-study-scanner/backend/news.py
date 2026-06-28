"""UPSC Live Agent — fetch recent news (RSS) and turn it into Q&A via Groq.

RSS is fetched with the standard library only (urllib + ElementTree) so there
is no extra dependency to install on Render. Feed failures are tolerated: a bad
or slow feed is skipped rather than failing the whole request.
"""
from __future__ import annotations

import urllib.request
from dataclasses import dataclass, field
from xml.etree import ElementTree as ET

from groq import Groq

from ai_solver import MissingAPIKeyError, _call_groq, _parse_homework_json
from config import Settings
from prompts import NEWS_QNA_PROMPT_TEMPLATE

_UA = "Mozilla/5.0 (compatible; AIStudyScanAgent/1.0; +https://ai-study-scanner.onrender.com)"


class NewsUnavailableError(RuntimeError):
    """No headlines could be fetched from any configured feed."""


@dataclass(frozen=True)
class NewsItem:
    question: str
    answer: str


@dataclass(frozen=True)
class NewsResult:
    provider: str
    model: str
    exam: str
    headlines_used: int
    items: list[NewsItem] = field(default_factory=list)
    latency_ms: int = 0


def _strip_tags(text: str) -> str:
    """Remove HTML tags from a feed summary without pulling in a parser."""
    out: list[str] = []
    depth = 0
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return " ".join("".join(out).split())


def _parse_feed(xml_bytes: bytes, limit: int) -> list[str]:
    """Extract up to `limit` 'title — summary' lines from RSS or Atom XML."""
    try:
        root = ET.fromstring(xml_bytes)
    except Exception:
        return []

    headlines: list[str] = []
    for node in root.iter():
        tag = node.tag.split("}")[-1].lower()
        if tag not in ("item", "entry"):
            continue
        title = None
        summary = None
        for child in node:
            ctag = child.tag.split("}")[-1].lower()
            if ctag == "title" and child.text:
                title = child.text.strip()
            elif ctag in ("description", "summary") and child.text:
                summary = _strip_tags(child.text)
        if title:
            line = f"{title} — {summary[:200]}" if summary else title
            headlines.append(line)
        if len(headlines) >= limit:
            break
    return headlines


def fetch_headlines(
    feeds: tuple[str, ...] | list[str],
    *,
    per_feed: int,
    total: int,
    timeout: float,
) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for url in feeds:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": _UA})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = resp.read()
        except Exception:
            continue
        for headline in _parse_feed(data, per_feed):
            key = headline.lower()[:80]
            if key in seen:
                continue
            seen.add(key)
            out.append(headline)
            if len(out) >= total:
                return out
    return out


def generate_news_qna(
    *,
    settings: Settings,
    exam: str = "UPSC",
    count: int = 5,
) -> NewsResult:
    if not settings.groq_api_key:
        raise MissingAPIKeyError("GROQ_API_KEY is not configured")

    headlines = fetch_headlines(
        settings.news_rss_feeds,
        per_feed=settings.news_per_feed,
        total=settings.news_max_headlines,
        timeout=settings.news_fetch_timeout_s,
    )
    if not headlines:
        raise NewsUnavailableError(
            "Could not fetch any news headlines right now. Try again shortly."
        )

    prompt = NEWS_QNA_PROMPT_TEMPLATE.format(
        exam=exam,
        count=count,
        headlines="\n".join(f"- {h}" for h in headlines),
    )

    client = Groq(api_key=settings.groq_api_key)
    text, latency_ms = _call_groq(
        client,
        model=settings.groq_model,
        prompt=prompt,
        temperature=0.3,
        max_tokens=settings.groq_homework_max_output_tokens,
        timeout=settings.groq_homework_timeout_s,
    )

    parsed = _parse_homework_json(text, count)
    items = [NewsItem(question=p.question, answer=p.answer) for p in parsed]
    return NewsResult(
        provider="groq",
        model=settings.groq_model,
        exam=exam,
        headlines_used=len(headlines),
        items=items,
        latency_ms=latency_ms,
    )
