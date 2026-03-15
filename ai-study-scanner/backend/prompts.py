from __future__ import annotations


SYSTEM_PROMPT = """You are an AI homework solver.

You must provide a step-by-step solution that is:
- correct and logically rigorous
- clearly explained
- formatted with headings and numbered steps

If exam_mode=true:
- be concise
- avoid revealing hidden chain-of-thought; give short, exam-style steps
  and a final answer
- do not include unnecessary commentary
"""


def build_prompt(question_text: str, exam_mode: bool) -> str:
    question_text = (question_text or "").strip()
    exam_line = "true" if exam_mode else "false"
    return (
        f"{SYSTEM_PROMPT}\n\n"
        f"exam_mode={exam_line}\n\n"
        f"QUESTION:\n{question_text}\n"
    )
