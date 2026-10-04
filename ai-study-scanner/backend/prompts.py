from __future__ import annotations


SYSTEM_PROMPT = (
    "You are a patient tutor for Indian school and entrance-exam students. "
    "Your job is to make the student understand how the question is solved, "
    "not just to hand over the answer. "
    "Be accurate and teach the method. "
    "If the question is unclear or incomplete, "
    "ask up to 2 short clarifying questions. "
    "If you must assume something, state it in one short line."
)

# Agent Step 1: classify the question and choose an approach
#
# Deliberately always in English: the app parses this JSON to show the
# subject/topic/difficulty chips (see SolutionViewModel.parseClassification),
# and `corrected_question` must stay in the question's own language because it
# is shown back as "Interpreted as" so a wrong OCR guess stays visible.
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
    '  "corrected_question": the question exactly as the student intended.'
    " The text comes from phone-camera OCR that drops superscripts and"
    " confuses similar glyphs, so repair only what is clearly an OCR error:"
    " x2->x^2, t3->t^3, ó/б->6 (an accented o is always a 6), l/I->1, O->0,"
    " S->5, missing = or ^, split lines."
    " Dropped superscripts: a polynomial written as 't - 6t + 9t + 5' has lost"
    " its powers, and powers descend left to right, so it is"
    " 't^3 - 6t^2 + 9t + 5'. When answer options are given, check that the"
    " repaired equation can actually produce one of them and revise if not."
    " Keep every word and number that is plausible as written."
    " Write powers with a caret (t^3, 10^{{-3}}), never Unicode superscripts."
    " If nothing needs fixing, copy the question verbatim.\n"
    "\nQuestion:\n{question_text}"
)

# Agent Step 2: solve using the plan from Step 1
AGENT_SOLVE_PROMPT_TEMPLATE = (
    "You are a patient tutor for Indian school and entrance-exam students.\n"
    "Classification — Subject: {subject} | Topic: {topic}"
    " | Difficulty: {difficulty} | Board: {exam_board}\n"
    "Recommended approach: {approach}\n\n"
    "{exam_guide}\n"
    "{style_guide}\n"
    "{language_guide}\n"
    "Question:\n{question_text}"
)

# How the explanation is laid out. The default teaches the method first and
# marks the final answer at the end — the app is a learning tool, so an answer
# with no visible working is a failed answer even when the number is right.
ANSWER_STYLE_GUIDE = {
    "explain": (
        "Explain the solution step by step, in this order:\n"
        "**Concept** — in 1-2 lines, which idea or formula this question"
        " tests, and the formula itself.\n"
        "**Method** — in 1-2 lines, the plan you will follow.\n"
        "**Steps** — the working, one step per line, numbered. Each step shows"
        " what you did AND why it follows from the step before.\n"
        "**Final Answer** — the result on its own line, in bold, with units.\n"
        "Never jump straight to the result: the student must be able to redo"
        " the question on their own after reading this.\n"
        "Keep each line short and readable on a phone. No chat, no filler."
    ),
    "brief": (
        "Explain the solution step by step, but keep it tight:\n"
        "**Concept / Formula**\n"
        "**Steps** — numbered working, one line each.\n"
        "**Final Answer** — on its own line, in bold, with units.\n"
        "Still show every step; shorten the wording, not the working."
    ),
}

# Legacy PROMPT_ANSWER_STYLE values from before the learning reposition.
# Kept so the existing Render env var keeps booting instead of silently
# falling back; both now mean "explain it".
_LEGACY_ANSWER_STYLES = {
    "compact": "explain",
    "ultra_compact": "brief",
}

# Syllabus flavour per board. These shape WHAT is emphasised (wording, units,
# which shortcuts are standard) — never how much of the working to show, which
# is ANSWER_STYLE_GUIDE's job.
BOARD_GUIDE = {
    "CBSE": (
        "CBSE: use NCERT wording and notation, lay the steps out the way a"
        " step-wise marking scheme expects, and always carry units."
    ),
    "JEE": (
        "JEE: use the standard JEE method and name the shortcut you use, then"
        " show why it works so the student can reuse it."
    ),
    "NEET": (
        "NEET: stay close to NCERT facts and definitions, and carry units"
        " where they matter."
    ),
    "EAMCET": (
        "EAMCET (AP/TS EAMCET): formula-first, the way EAMCET MCQs are solved."
        " Show the working, then state clearly which option it matches and why"
        " the others are wrong."
    ),
}

# --------------------------------------------------------------------------- #
# Mother-tongue explanations
# --------------------------------------------------------------------------- #
# code -> (English name, endonym, script name). The endonym is what the app
# shows in the picker; the script name is in the prompt because models
# otherwise answer in Latin transliteration ("prashna" instead of "प्रश्न").
LANGUAGES: dict[str, tuple[str, str, str]] = {
    "en": ("English", "English", "Latin"),
    "hi": ("Hindi", "हिन्दी", "Devanagari"),
    "te": ("Telugu", "తెలుగు", "Telugu"),
    "ta": ("Tamil", "தமிழ்", "Tamil"),
    "kn": ("Kannada", "ಕನ್ನಡ", "Kannada"),
    "ml": ("Malayalam", "മലയാളം", "Malayalam"),
    "mr": ("Marathi", "मराठी", "Devanagari"),
    "bn": ("Bengali", "বাংলা", "Bengali"),
    "gu": ("Gujarati", "ગુજરાતી", "Gujarati"),
}

DEFAULT_LANGUAGE = "en"

_LANGUAGE_DIRECTIVE = (
    "LANGUAGE — this overrides any other instruction about wording:\n"
    "Write the whole explanation in {endonym} ({english}), in the {script}"
    " script. Do not transliterate {english} into the Latin alphabet.\n"
    "Keep these in English, written exactly as the student's textbook writes"
    " them:\n"
    "- all mathematics — numbers, variables, equations, units and LaTeX\n"
    "- chemical formulae and element symbols (H2SO4, NaCl, Fe)\n"
    "- standard subject terms the student will meet in the English textbook"
    " and in the exam paper (quadratic equation, photosynthesis,"
    " acceleration, mitochondria)\n"
    "- names of people, places and organisations\n"
    "The first time such a term appears, put its {endonym} meaning in brackets"
    " right after it, then keep using the English term.\n"
    "Everything around them — the concept, the method, the reason for each"
    " step and the conclusion — must be in {endonym}, including the section"
    " headings.\n"
    "Use simple spoken {endonym} that a school student understands, not"
    " literary or Sanskritised {endonym}.\n"
    "Do not translate or rewrite the question itself; explain it in"
    " {endonym}.\n"
)

# Appended for the endpoints that must answer with machine-readable JSON.
_LANGUAGE_JSON_RULE = (
    "Keep every JSON key in English exactly as specified; only the values are"
    " in {endonym}.\n"
)


def normalize_language(language: str | None) -> str:
    """Map whatever the client sent to a supported language code.

    Accepts a code ("te"), a BCP-47 tag ("te-IN") or the English name
    ("Telugu"); anything unknown falls back to English, so a future app build
    sending a language this server does not have still gets an answer.
    """
    value = (language or "").strip().lower()
    if not value:
        return DEFAULT_LANGUAGE
    value = value.replace("_", "-").split("-")[0]
    if value in LANGUAGES:
        return value
    for code, (english, _endonym, _script) in LANGUAGES.items():
        if value == english.lower():
            return code
    return DEFAULT_LANGUAGE


def language_label(language: str | None) -> str:
    """English name of the language, for logs and cache keys."""
    return LANGUAGES[normalize_language(language)][0]


def is_translated(language: str | None) -> bool:
    """True when the answer will be in a language other than English."""
    return normalize_language(language) != DEFAULT_LANGUAGE


def language_directive(
    language: str | None, *, json_mode: bool = False
) -> str:
    """The instruction block that makes the model answer in `language`.

    Empty for English, so English users get byte-identical prompts to before.
    """
    code = normalize_language(language)
    if code == DEFAULT_LANGUAGE:
        return ""
    english, endonym, script = LANGUAGES[code]
    block = _LANGUAGE_DIRECTIVE.format(
        english=english, endonym=endonym, script=script
    )
    if json_mode:
        block += _LANGUAGE_JSON_RULE.format(endonym=endonym)
    return block


# Home Work: generate a set of practice questions (with hidden answers)
HOMEWORK_PROMPT_TEMPLATE = (
    "You are a practice-question generator for Indian school and entrance exams.\n"
    "Generate exactly {count} original practice questions on the topic below.\n"
    "Target exam/board: {exam_label}. {exam_guide}\n"
    "Order them easy first, then harder; cover a balanced mix of sub-skills.\n"
    "{style_line}\n"
    "{language_guide}\n"
    "Rules:\n"
    "- Each question must be self-contained and solvable on its own.\n"
    "- Provide a correct worked answer for every question.\n"
    "- Do NOT repeat or trivially reword questions.\n"
    "Respond with JSON ONLY (no markdown, no commentary) as an array of objects:\n"
    '[{{"question": "...", "answer": "..."}}]\n'
    "\nTopic:\n{topic}"
)

# AI Planner: exam/board-specific study program guidance
PLANNER_EXAM_GUIDE = {
    "CBSE": "Target: CBSE board exams (NCERT-based, chapter-wise).",
    "JEE": "Target: JEE Main/Advanced — Physics, Chemistry, Maths.",
    "NEET": "Target: NEET — Physics, Chemistry, Biology (NCERT-heavy).",
    "EAMCET": "Target: AP/TS EAMCET — MCQ, formula-first, speed.",
    "UPSC": (
        "Target: UPSC Civil Services — Prelims + Mains, GS papers,"
        " CSAT, current affairs, optional and answer writing."
    ),
}

# AI Planner: build a month-by-month study program for the chosen exam/board
PLANNER_PROMPT_TEMPLATE = (
    "You are an expert study mentor for Indian students preparing for"
    " {exam_label}.\n{exam_guide}\n"
    "Design a realistic, motivating study program spanning exactly"
    " {months} month(s). Assume the student can study about"
    " {hours_per_day} hours per day.\n"
    "{goal_line}"
    "Progress from foundations to advanced topics; reserve the final"
    " stretch for full-syllabus revision and mock tests.\n"
    "For EACH month provide: a short theme title, the key syllabus"
    " topics to cover that month (as a list of 3-6 items), and one"
    " concrete milestone to reach by month end (e.g. a mock-test"
    " target or number of chapters).\n"
    "{language_guide}\n"
    "Respond with JSON ONLY (no markdown, no commentary) as an object:\n"
    '{{"overview": "2-3 sentence summary of the strategy",'
    ' "months": [{{"month": 1, "title": "...",'
    ' "topics": ["...", "..."], "milestone": "..."}}]}}\n'
    'Return exactly {months} entries in "months", numbered 1..{months}.'
)


# "Learn this" card: the concept behind a solved question plus a few questions
# the student can try on the same idea. Runs after the answer, so the prompt
# gets the question and answer and must not re-solve anything.
LEARN_PROMPT_TEMPLATE = (
    "A student has just been shown the worked solution below.\n"
    "Help them turn that one answer into something they can reuse.\n"
    "Give:\n"
    '  "key_concept": 2-3 short lines naming the idea being tested, the'
    " formula or rule it rests on, and the one mistake students usually make"
    " with it. No worked numbers.\n"
    '  "practice": exactly {count} NEW questions on the same idea, slightly'
    " varied in numbers and phrasing, ordered easiest first. Each needs a"
    " short worked answer so the student can check themselves.\n"
    "Do not repeat the solved question itself.\n"
    "{language_guide}\n"
    "Respond with JSON ONLY (no markdown, no commentary):\n"
    '{{"key_concept": "...", "practice": [{{"question": "...",'
    ' "answer": "..."}}]}}\n'
    "\nSubject: {subject} | Topic: {topic}\n"
    "\nSolved question:\n{question_text}\n"
    "\nThe solution the student just read:\n{answer_text}"
)


def normalize_planner_exam(exam: str | None) -> str:
    value = (exam or "").strip().upper()
    if value not in PLANNER_EXAM_GUIDE:
        return "GENERAL"
    return value


# UPSC Live Agent: turn fresh news headlines into current-affairs Q&A
NEWS_QNA_PROMPT_TEMPLATE = (
    "You are a current-affairs tutor for Indian competitive exams ({exam}).\n"
    "From the recent news headlines below, create exactly {count} high-yield"
    " current-affairs questions a {exam} aspirant should revise today, each"
    " with a crisp, factual answer (1-3 sentences).\n"
    "Prefer exam-relevant items: government schemes, appointments, indices and"
    " reports, summits and agreements, awards, science & tech, environment,"
    " polity and economy. Avoid opinion pieces and routine sports trivia.\n"
    "{language_guide}\n"
    "Respond with JSON ONLY (no markdown, no commentary) as an array of objects:\n"
    '[{{"question": "...", "answer": "..."}}]\n'
    "\nRecent headlines:\n{headlines}"
)


def _normalize_exam_type(exam_type: str | None) -> str:
    value = (exam_type or "CBSE").strip().upper()
    if value not in BOARD_GUIDE:
        return "CBSE"
    return value


def _normalize_answer_style(answer_style: str | None) -> str:
    value = (answer_style or "explain").strip().lower()
    value = _LEGACY_ANSWER_STYLES.get(value, value)
    if value not in ANSWER_STYLE_GUIDE:
        return "explain"
    return value


def build_prompt(
    question_text: str,
    exam_type: str = "CBSE",
    answer_style: str = "explain",
    language: str = DEFAULT_LANGUAGE,
) -> str:
    """Builds a single-turn prompt for a step-by-step explanation."""
    question_text = (question_text or "").strip()
    exam_type = _normalize_exam_type(exam_type)
    answer_style = _normalize_answer_style(answer_style)

    parts = [
        SYSTEM_PROMPT,
        BOARD_GUIDE[exam_type],
        ANSWER_STYLE_GUIDE[answer_style],
    ]
    directive = language_directive(language)
    if directive:
        parts.append(directive)
    parts.append(f"Question:\n{question_text}")

    return "\n\n".join(parts)
