from __future__ import annotations


SYSTEM_PROMPT = (
    "You solve Indian school and entrance exam questions. "
    "Be accurate, brief, and easy to score from. "
    "If the question is unclear or incomplete, "
    "ask up to 2 short clarifying questions. "
    "If you must assume something, state it in one short line."
)

# Agent Step 1: classify the question and choose an approach
CLASSIFY_PROMPT_TEMPLATE = (
    "You are a question classifier for Indian school and entrance exams.\n"
    "Analyze the question below and respond with JSON only — no extra text.\n"
    "JSON fields (all required):\n"
    '  "subject":    one of Math / Physics / Chemistry / Biology'
    " / English / History / Geography / Other\n"
    '  "topic":      specific topic'
    ' (e.g. "Quadratic Equations", "Newton\'s Laws")\n'
    '  "difficulty": Easy / Medium / Hard\n'
    '  "exam_board": CBSE / JEE / NEET / EAMCET / Board\n'
    '  "approach":   one-line best strategy to solve this'
    ' (e.g. "Factoring: find two numbers that multiply to c and add to b")\n'
    "\nQuestion:\n{question_text}"
)

# Agent Step 2: solve using the plan from Step 1
AGENT_SOLVE_PROMPT_TEMPLATE = (
    "You solve Indian school and entrance exam questions.\n"
    "Classification — Subject: {subject} | Topic: {topic}"
    " | Difficulty: {difficulty} | Board: {exam_board}\n"
    "Recommended approach: {approach}\n\n"
    "{exam_guide}\n"
    "{style_guide}\n"
    "{mode_line}\n"
    "Question:\n{question_text}"
)

ANSWER_STYLE_GUIDE = {
    "compact": (
        "Keep the reply concise.\n"
        "Use this format:\n"
        "1. Given\n"
        "2. Method/Formula\n"
        "3. Steps\n"
        "4. Final Answer\n"
        "Keep each section short. No extra chat."
    ),
    "ultra_compact": (
        "Be very concise.\n"
        "Use this format:\n"
        "Method/Formula\n"
        "Steps\n"
        "Final Answer\n"
        "Skip long explanations."
    ),
}

EXAM_MODE_GUIDE = {
    "CBSE": (
        "CBSE: prefer NCERT wording, step-wise marking, and units."
    ),
    "JEE": (
        "JEE: be concise, calculation-efficient, and use standard shortcuts only."
    ),
    "NEET": (
        "NEET: prefer NCERT-based points, crisp definitions, and units where needed."
    ),
    "EAMCET": (
        "EAMCET (AP/TS EAMCET): MCQ-oriented, formula-first, fast elimination;"
        " state the final option/value clearly with only the key step."
    ),
}

# Home Work: generate a set of practice questions (with hidden answers)
HOMEWORK_PROMPT_TEMPLATE = (
    "You are a practice-question generator for Indian school and entrance exams.\n"
    "Generate exactly {count} original practice questions on the topic below.\n"
    "Target exam/board: {exam_label}. {exam_guide}\n"
    "Order them easy first, then harder; cover a balanced mix of sub-skills.\n"
    "{mode_line}\n"
    "Rules:\n"
    "- Each question must be self-contained and solvable on its own.\n"
    "- Provide a correct, concise worked answer for every question.\n"
    "- Do NOT repeat or trivially reword questions.\n"
    "Respond with JSON ONLY (no markdown, no commentary) as an array of objects:\n"
    '[{{"question": "...", "answer": "..."}}]\n'
    "\nTopic:\n{topic}"
)

# UPSC Live Agent: turn fresh news headlines into current-affairs Q&A
NEWS_QNA_PROMPT_TEMPLATE = (
    "You are a current-affairs tutor for Indian competitive exams ({exam}).\n"
    "From the recent news headlines below, create exactly {count} high-yield"
    " current-affairs questions a {exam} aspirant should revise today, each"
    " with a crisp, factual answer (1-3 sentences).\n"
    "Prefer exam-relevant items: government schemes, appointments, indices and"
    " reports, summits and agreements, awards, science & tech, environment,"
    " polity and economy. Avoid opinion pieces and routine sports trivia.\n"
    "Respond with JSON ONLY (no markdown, no commentary) as an array of objects:\n"
    '[{{"question": "...", "answer": "..."}}]\n'
    "\nRecent headlines:\n{headlines}"
)


def _normalize_exam_type(exam_type: str | None) -> str:
    value = (exam_type or "CBSE").strip().upper()
    if value not in EXAM_MODE_GUIDE:
        return "CBSE"
    return value


def _normalize_answer_style(answer_style: str | None) -> str:
    value = (answer_style or "compact").strip().lower()
    if value not in ANSWER_STYLE_GUIDE:
        return "compact"
    return value


def build_prompt(
    question_text: str,
    exam_mode: bool,
    exam_type: str = "CBSE",
    answer_style: str = "compact",
) -> str:
    """Builds a single-turn prompt for exam solving."""
    question_text = (question_text or "").strip()
    exam_type = _normalize_exam_type(exam_type)
    answer_style = _normalize_answer_style(answer_style)
    mode_line = (
        "exam mode on: keep steps short and direct." if exam_mode else ""
    )
    exam_guide = EXAM_MODE_GUIDE[exam_type]
    style_guide = ANSWER_STYLE_GUIDE[answer_style]

    parts = [
        SYSTEM_PROMPT,
        exam_guide,
        style_guide,
    ]
    if mode_line:
        parts.append(mode_line)
    parts.append(f"Question:\n{question_text}")

    return "\n\n".join(parts)
