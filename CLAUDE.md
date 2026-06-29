# CLAUDE.md — AI Study Scan Agent

Guidance for Claude Code when working in this repository.

## Project overview
An AI-powered study helper for Indian school / entrance-exam students. Live on
Google Play (internal testing) as **"AI Study Scan Agent"**.
- **Backend** — FastAPI service using **Groq** (`llama-3.3-70b-versatile`) as the
  LLM. It answers exam questions (single-shot and agentic), generates practice
  **Home Work**, and produces daily **UPSC current-affairs** Q&A delivered via FCM
  push. Includes slowapi rate limiting, TTL caching, Prometheus metrics, Sentry,
  and Firebase (**firebase-admin**) for FCM + Firestore.
- **Live client** — native **Android** app (`android-app/AIStudyScanner/`, Kotlin +
  Jetpack Compose). This is the published, canonical client.
- **Flutter** app (`flutter_mobile/`) — older/secondary; **not** the shipping client.
- **monitoring/** — Prometheus / health checks.

> **Provider note:** the project MIGRATED Google Gemini → **Groq** (Gemini needed
> paid prepay in India and 1.5-flash was deprecated). Some symbols still read
> "gemini" (e.g. `solve_gemini`) but they call Groq. Treat any Gemini reference in
> old docs/README as stale.

## CRITICAL: Python environment (Windows) — always use this
ALWAYS run **every** Python command (install, run, tests, scripts) with this exact
interpreter. NEVER use the `base` conda env or system Python.

- Conda env: `ai_study_scanner`
- Python path: `C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe`

```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m <module> ...
```

## CRITICAL: which backend is canonical
**`ai-study-scanner/backend/` is the canonical backend** — it is what Render
deploys and what you should edit. `ai-study-scanner/app/` is a **stale legacy copy**
(the old README run target); do NOT edit it. Removing `app/` to end the drift is
still pending.

## Repository structure
```
AI Study Scanner/
├─ ai-study-scanner/
│  ├─ app/        # LEGACY/STALE copy — do not edit
│  └─ backend/    # CANONICAL backend (deployed to Render): main.py, ai_solver.py,
│                 #   config.py, prompts.py, news.py, notifications.py, cost_utils.py,
│                 #   Dockerfile, docker-compose, requirements.txt, .env.example
├─ android-app/AIStudyScanner/   # CANONICAL native Android client (Kotlin/Compose)
├─ flutter_mobile/               # older Flutter client (secondary)
├─ monitoring/   # Prometheus / monitoring config
├─ scripts/  · docs/  · README.md
```

## Backend stack
FastAPI · Uvicorn · Pydantic v2 · python-dotenv · **groq** ·
**firebase-admin** (FCM + Firestore) · slowapi (rate limiting) ·
prometheus-fastapi-instrumentator · sentry-sdk

## Setup — install backend dependencies
```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m pip install -r "ai-study-scanner\backend\requirements.txt"
```

## Run the canonical backend locally
```bat
cd ai-study-scanner\backend
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m uvicorn main:app --host 0.0.0.0 --port 8000 --log-level info
```
- Health: http://127.0.0.1:8000/health · Docs: /docs · Metrics: /metrics
- (The old README command `app.main:app --app-dir ai-study-scanner` targets the
  stale `app/` copy — prefer the `backend/` command above.)

## API endpoints
- `POST /solve` — single-shot answer. Body: `{question_text, exam_mode, board?}`
- `POST /solve/agent` — agentic (classify → solve); **the Android app uses this**.
  Returns reasoning `steps` + `answer`.
- `POST /homework` — practice questions. Body: `{topic, count (3-20), exam_mode, board}`
  → `{questions:[{question, answer}]}`
- `POST /news` — UPSC current-affairs Q&A from live RSS. Body: `{exam, count (1-10)}`
- `POST /news/subscribe` · `POST /news/unsubscribe` — FCM token + schedule, stored in
  Firestore `news_subscriptions`. Needs Firebase configured (else graceful 503).
- `GET` or `POST /cron/dispatch?key=<CRON_SECRET>` — an external cron calls this every
  ~15 min; it sends due pushes. 403 without the secret.
- `board` ∈ **Auto · CBSE · JEE · NEET · EAMCET** (Auto = let the agent detect it).

## Environment variables / secrets
Loaded from a local `.env` in `ai-study-scanner/backend/` (template: `.env.example`).
- `GROQ_API_KEY` (required), `GROQ_MODEL` (default `llama-3.3-70b-versatile`)
- `GROQ_TIMEOUT_S`, `GROQ_TEMPERATURE_EXAM`, `GROQ_TEMPERATURE_DEFAULT`,
  `GROQ_MAX_OUTPUT_TOKENS`, `GROQ_HOMEWORK_MAX_OUTPUT_TOKENS`, `GROQ_HOMEWORK_TIMEOUT_S`
- `MAX_QUESTION_CHARS`, `PROMPT_ANSWER_STYLE`, `SOLVE_CACHE_TTL_S`
- **UPSC Live Agent:** `NEWS_RSS_FEEDS` (optional, comma-separated; sensible defaults
  built in), `NEWS_PER_FEED`, `NEWS_MAX_HEADLINES`, `NEWS_CACHE_TTL_S`,
  `NEWS_DISPATCH_WINDOW_MIN`, `CRON_SECRET` (protects `/cron/dispatch`),
  `FIREBASE_CREDENTIALS_JSON` (service-account JSON, **one line** — enables FCM + Firestore)
- `HOST`, `PORT`, `LOG_LEVEL`, `ENV`, `SENTRY_DSN`

NEVER commit `.env`, the Firebase service-account JSON, or keystore passwords.
Keep `.gitignore` covering `.env`. Use `.env.example` as the template.

## Testing
- Run with the conda Python. The backend modules import each other by bare name, so
  run from the backend dir (or set `PYTHONPATH` to it):
```bat
cd ai-study-scanner\backend
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m pytest -q
```
- FastAPI `TestClient` (httpx) works for endpoint tests. pytest is a dev-only dep
  (not in requirements) — add it before writing tests.
- Always run the relevant tests/build after a change before declaring it done.

## Deployment (backend)
- **Render** web service auto-deploys from the **`correct-origin`** remote
  (`github.com/dineshyedla333-lang/AI-Study-Scanner`) **`main`** branch. Push there to
  deploy. Do NOT push to `origin` (dinesh6802 — 403 denied).
- Prod URL: `https://ai-study-scanner.onrender.com` (free tier sleeps ~50s cold start).
- Secrets live in the Render env group `ai-study-scanner-prod` (GROQ_API_KEY,
  CRON_SECRET, FIREBASE_CREDENTIALS_JSON, …). Daily pushes require an external
  scheduler (cron-job.org) hitting `/cron/dispatch?key=<CRON_SECRET>` every ~15 min.
- `Dockerfile` / `docker-compose.yml` exist in `backend/` for container deploys.

### Cron / push scheduling (cron-job.org — chosen over Render cron, which is paid)
Two cron-job.org jobs are configured and live:
1. **UPSC Live Agent dispatch** — `GET/POST /cron/dispatch?key=<CRON_SECRET>` every **15 min**.
2. **Keep Warm** — `GET /health` every **10 min** (stops the Render free instance
   sleeping so dispatch never lands on a cold start).
- Set **"Save responses in job history" = OFF** on both. Leaving it ON caused a
  `Failed (output too large)` because cron-job.org tried to STORE Render's large
  cold-start error HTML (the real `/cron/dispatch` response is ~52 bytes).
- Cron runs in UTC, but **dispatch matches each subscription's own timezone**, so a
  UTC cron + IST subscriber still fires at the subscriber's local time.

## Android app (canonical client)
- Path: `android-app/AIStudyScanner/`. Kotlin + Compose; applicationId
  `com.aistudyscanner.agent`. Uses Firebase (`google-services.json` present);
  FCM notification channel id `upsc_live_agent`.
- **Current release: v1.2.1 / versionCode 10** on Play Internal testing (next = 11).
  App uses the bare default `MaterialTheme {}` (no custom palette), so the 4 home
  action buttons set their colors explicitly in `screens/HomeScreen.kt`
  (Scan=purple, Upload=blue, Home Work=green, UPSC=orange; white text).
- UPSC Live Agent subscribe sends the **device timezone**
  (`TimeZone.getDefault().id`) and **24h "HH:MM" times**; the server fires the push
  at the subscriber's local time on the next cron tick.
- Quick Kotlin check (no packaging): `.\gradlew :app:compileDebugKotlin`
- Signed release AAB (**bump `RELEASE_VERSION_CODE` every release**; keystore
  password/alias are kept locally, NOT in this file):
```bat
cd android-app\AIStudyScanner
.\gradlew bundleRelease "-PAPI_BASE_URL=https://ai-study-scanner.onrender.com" "-PRELEASE_VERSION_CODE=<N>" "-PRELEASE_VERSION_NAME=<x.y.z>" "-PRELEASE_STORE_FILE=<path>\release.keystore" "-PRELEASE_STORE_PASSWORD=<local-secret>" "-PRELEASE_KEY_ALIAS=ai-study-scanner" "-PRELEASE_KEY_PASSWORD=<local-secret>"
```
AAB output: `app/build/outputs/bundle/release/app-release.aab` → upload to Play
Console (Internal testing).

## Conventions & guardrails
- Use the `ai_study_scanner` conda Python for ALL Python commands (never `base`).
- Edit only the canonical `ai-study-scanner/backend/` (never the stale `app/`).
- Every Gson network model needs `@SerializedName` on each field **and** matching
  keep rules in `app/proguard-rules.pro` — release minify (R8) strips names otherwise.
- FastAPI body params decorated with slowapi need an explicit `= Body()` (the
  decorator strips type hints → otherwise 422). See `notifications.py`/`main.py`.
- Keep secrets out of git; rate-limit and validate input on public endpoints.
- Implement → run locally / test → commit. Push to `correct-origin` to deploy.
- Run a security review before shipping (public endpoints + an LLM API key).
