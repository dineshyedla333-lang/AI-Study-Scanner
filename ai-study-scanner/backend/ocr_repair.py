"""
Deterministic OCR clean-up that runs before any model sees the question.

Phone-camera OCR (ML Kit Latin) produces a small, predictable set of
corruptions in maths: superscript digits arrive as Unicode superscripts or get
dropped entirely, and similar glyphs are swapped (6->ó, 0->O, minus->en dash).
The fixes here are the ones that are safe to apply blindly — no maths context
needed, no false positives on prose — so the classifier starts from cleaner
text and the "Interpreted as" card is deterministic for the common cases.
Anything that needs judgement (merged `t3`, a dropped power with no trace)
stays with the classifier's `corrected_question`.
"""
from __future__ import annotations

import re

_SUPERSCRIPTS = {
    "⁰": "0", "¹": "1", "²": "2", "³": "3", "⁴": "4",
    "⁵": "5", "⁶": "6", "⁷": "7", "⁸": "8", "⁹": "9",
    "⁺": "+", "⁻": "-", "ⁿ": "n", "ⁱ": "i",
}
_SUPERSCRIPT_RUN = re.compile("[" + "".join(map(re.escape, _SUPERSCRIPTS)) + "]+")

# Accented o (ó ò ô õ ö) or Cyrillic б where OCR meant 6: next to a digit,
# a single-letter variable, an operator, or a bracket. `ó` inside a word
# (two or more letters on either side) is left alone.
_O_AS_SIX = re.compile(
    r"(?<![A-Za-z]{2})[óòôõöб](?![A-Za-z]{2})"
)

# Capital O read for zero when it touches a digit: 1O -> 10, 2O5 -> 205, O.5 -> 0.5.
_O_AFTER_DIGIT = re.compile(r"(?<=\d)[Oo](?=\d|\b)")
_O_BEFORE_DIGIT = re.compile(r"(?<![A-Za-z])O(?=[\d.])")

# Typographic minus / dashes between operands -> ASCII minus.
_DASHES = re.compile("[−–—‑]")

# Space between a variable/bracket and its caret: "t ^3" -> "t^3", "t^ 3" -> "t^3".
_CARET_SPACE = re.compile(r"(?<=[A-Za-z0-9)\]])\s*\^\s*(?=[A-Za-z0-9({\-+])")


def normalize_ocr(text: str) -> str:
    """Return `text` with unambiguous OCR glyph errors repaired."""
    if not text:
        return text
    out = _SUPERSCRIPT_RUN.sub(_superscript_to_caret, text)
    out = _DASHES.sub("-", out)
    out = _O_AS_SIX.sub("6", out)
    out = _O_AFTER_DIGIT.sub("0", out)
    out = _O_BEFORE_DIGIT.sub("0", out)
    out = _CARET_SPACE.sub("^", out)
    return out


def _superscript_to_caret(match: re.Match[str]) -> str:
    digits = "".join(_SUPERSCRIPTS[ch] for ch in match.group(0))
    # Multi-character exponents need braces so `10^{-3}` survives the next
    # reader (LLM or KaTeX) instead of being read as `(10^-)3`.
    return f"^{digits}" if len(digits) == 1 else f"^{{{digits}}}"


def describe_repair(original: str, repaired: str) -> str:
    """Human-readable summary for the agent's step log."""
    if original == repaired:
        return "No OCR errors detected."
    return f"Read by scanner:\n{original}\n\nInterpreted as:\n{repaired}"
