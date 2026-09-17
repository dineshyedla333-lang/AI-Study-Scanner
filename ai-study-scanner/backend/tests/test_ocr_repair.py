import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pytest

from ai_solver import _parse_classification_json, _pick_corrected_question
from ocr_repair import describe_repair, normalize_ocr


@pytest.mark.parametrize(
    "raw, expected",
    [
        # Unicode superscripts -> caret notation.
        ("x = t³ − 6t² + 9t + 5", "x = t^3 - 6t^2 + 9t + 5"),
        ("v = 3 × 10⁻³ m/s", "v = 3 × 10^{-3} m/s"),
        ("a¹⁰ + b", "a^{10} + b"),
        # Accented o read for 6 in a maths context, left alone inside words.
        ("x = t3 - ót2 + 9t + 5", "x = t3 - 6t2 + 9t + 5"),
        ("2ó + 4", "26 + 4"),
        ("The señóra walked", "The señóra walked"),
        # Capital O read for zero next to digits.
        ("1O + 2O5 = O.5 x", "10 + 205 = 0.5 x"),
        ("Oxygen has 8 protons", "Oxygen has 8 protons"),
        # Dashes and caret spacing.
        ("y – 3 = 0", "y - 3 = 0"),
        ("t ^3 - 2t^ 2", "t^3 - 2t^2"),
        # Prose is untouched.
        ("Who wrote the Indian Constitution?", "Who wrote the Indian Constitution?"),
        ("", ""),
    ],
)
def test_normalize_ocr(raw, expected):
    assert normalize_ocr(raw) == expected


def test_normalize_is_idempotent():
    once = normalize_ocr("x = t³ − ót² + 1O")
    assert normalize_ocr(once) == once


def test_describe_repair():
    assert describe_repair("a", "a") == "No OCR errors detected."
    text = describe_repair("t3", "t^3")
    assert "Read by scanner:\nt3" in text and "Interpreted as:\nt^3" in text


def test_parse_classification_json_tolerates_fences_and_prose():
    fenced = '```json\n{"subject": "Math", "corrected_question": "t^3"}\n```'
    assert _parse_classification_json(fenced)["corrected_question"] == "t^3"
    prose = 'Here you go:\n{"subject": "Math"}\nHope this helps.'
    assert _parse_classification_json(prose) == {"subject": "Math"}
    assert _parse_classification_json("not json") == {}
    assert _parse_classification_json("[1, 2]") == {}


def test_pick_corrected_question_rejects_rewrites():
    original = "x = t - 6t + 9t + 5"
    assert _pick_corrected_question(original, "x = t^3 - 6t^2 + 9t + 5") == "x = t^3 - 6t^2 + 9t + 5"
    assert _pick_corrected_question(original, "") == original
    assert _pick_corrected_question(original, None) == original
    assert _pick_corrected_question(original, "A cubic polynomial in t, please differentiate it twice and discuss") == original
