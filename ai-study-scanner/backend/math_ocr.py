from __future__ import annotations

import base64
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from config import Settings

MATHPIX_URL = "https://api.mathpix.com/v3/text"


class MathOcrNotConfigured(RuntimeError):
    """Mathpix credentials are absent; callers should fall back to on-device OCR."""


class MathOcrError(RuntimeError):
    pass


@dataclass(frozen=True)
class MathOcrResult:
    text: str
    confidence: float
    latency_ms: int


def recognize_math(
    image_bytes: bytes,
    content_type: str,
    settings: Settings,
) -> MathOcrResult:
    """
    Send one image to Mathpix and return plain text with inline LaTeX.

    Mathpix is the only step in the pipeline that costs real money per call
    ($0.002/image), so the endpoint gates it to Pro users and caps image size;
    this function just does the call. stdlib urllib keeps the dependency list
    unchanged — one request per scan does not need a client library.
    """
    if not (settings.mathpix_app_id and settings.mathpix_app_key):
        raise MathOcrNotConfigured("MATHPIX_APP_ID / MATHPIX_APP_KEY not set")

    payload = {
        "src": f"data:{content_type};base64,"
        + base64.b64encode(image_bytes).decode("ascii"),
        "formats": ["text"],
        # $...$ is what students and LLMs both read most easily.
        "math_inline_delimiters": ["$", "$"],
        "rm_spaces": True,
    }
    req = urllib.request.Request(
        MATHPIX_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "app_id": settings.mathpix_app_id,
            "app_key": settings.mathpix_app_key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=settings.mathpix_timeout_s) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise MathOcrError(f"Mathpix HTTP {e.code}: {detail}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MathOcrError(f"Mathpix unreachable: {e}") from e
    latency_ms = int((time.perf_counter() - started) * 1000)

    if "error" in body:
        raise MathOcrError(f"Mathpix error: {body.get('error')}")
    return MathOcrResult(
        text=str(body.get("text") or "").strip(),
        confidence=float(body.get("confidence") or 0.0),
        latency_ms=latency_ms,
    )
