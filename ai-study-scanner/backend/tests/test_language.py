"""Mother-tongue explanations: language plumbing and its two sharp edges.

The sharp edges are (a) a cached answer in the wrong language and (b) an old
app build that sends `exam_mode` and no `language` — either one is invisible in
normal use until a real student hits it.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cost_utils import cache_key_for  # noqa: E402
from prompts import (  # noqa: E402
    build_prompt,
    is_translated,
    language_directive,
    normalize_language,
)


# --------------------------------------------------------------------------- #
# normalize_language
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    "raw,expected",
    [
        ("te", "te"),
        ("TE", "te"),
        ("te-IN", "te"),
        ("te_IN", "te"),
        ("Telugu", "te"),
        ("hindi", "hi"),
        ("", "en"),
        (None, "en"),
        ("klingon", "en"),  # unknown -> English, never a crash
    ],
)
def test_normalize_language(raw, expected):
    assert normalize_language(raw) == expected


def test_english_is_not_translated():
    assert not is_translated("en")
    assert is_translated("te")
    assert is_translated("hi")


# --------------------------------------------------------------------------- #
# the directive itself
# --------------------------------------------------------------------------- #
def test_english_prompt_has_no_language_block():
    assert language_directive("en") == ""


def test_telugu_directive_names_script_and_endonym():
    block = language_directive("te")
    assert "తెలుగు" in block
    assert "Telugu script" in block
    # Transliteration is the failure mode that makes the feature useless.
    assert "Do not transliterate" in block


def test_json_mode_protects_the_keys():
    block = language_directive("hi", json_mode=True)
    assert "JSON key in English" in block
    assert "हिन्दी" in block


def test_build_prompt_carries_the_language():
    english = build_prompt("Solve x^2 = 4", exam_type="CBSE")
    telugu = build_prompt("Solve x^2 = 4", exam_type="CBSE", language="te")
    assert "తెలుగు" not in english
    assert "తెలుగు" in telugu
    # The question itself is untouched in both.
    assert "Solve x^2 = 4" in english
    assert "Solve x^2 = 4" in telugu


def test_build_prompt_is_step_by_step_not_exam_mode():
    prompt = build_prompt("Solve x^2 = 4")
    assert "**Steps**" in prompt
    assert "**Final Answer**" in prompt
    assert "exam mode" not in prompt.lower()


def test_legacy_answer_style_still_boots():
    """Render still has PROMPT_ANSWER_STYLE=compact from before the reposition."""
    assert "**Steps**" in build_prompt("q", answer_style="compact")
    assert "**Steps**" in build_prompt("q", answer_style="ultra_compact")
    assert "**Steps**" in build_prompt("q", answer_style="nonsense")


# --------------------------------------------------------------------------- #
# the cache
# --------------------------------------------------------------------------- #
def test_cache_key_separates_languages():
    """Without this, the first asker decides everyone else's language."""
    assert cache_key_for("q", "en") != cache_key_for("q", "te")
    assert cache_key_for("q", "te") == cache_key_for("q", "te")
