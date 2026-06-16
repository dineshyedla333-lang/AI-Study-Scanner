# CLAUDE.md — AI Study Scanner

Guidance for Claude Code when working in this repository.

## Project overview
AI Study Scanner is an AI-powered study helper:
- **Backend** — a FastAPI service exposing a `/solve` endpoint that answers study/exam
  questions using **Google Gemini** (`gemini-1.5-flash`). Includes rate limiting,
  caching, cost tracking, Prometheus metrics, and Sentry error reporting.
- **Clients** — a **Flutter** mobile app (`flutter_mobile/`) and a native **Android** app
  (`android-app/`) that call the backend.
- **monitoring/** — observability setup (Prometheus / health checks).

## CRITICAL: Python environment (Windows) — always use this
ALWAYS run **every** Python command (install, run, tests, scripts) with this exact interpreter.
NEVER use the `base` conda env or system Python.

- Conda env: `ai_study_scanner`
- Python path: `C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe`

Run Python via the full path, e.g.:
```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m <module> ...
```

## Repository structure
```
AI Study Scanner/
├─ ai-study-scanner/
│  ├─ app/        # FastAPI app the README runs (app.main:app via --app-dir ai-study-scanner)
│  └─ backend/    # Deployment-ready copy: Dockerfile, docker-compose, cost_utils, monetization, .env.example
├─ flutter_mobile/   # Flutter mobile client (pubspec.yaml)
├─ android-app/      # Native Android client
├─ monitoring/       # Prometheus / monitoring config
├─ scripts/          # Helper scripts
├─ docs/             # Documentation
└─ README.md
```

### ⚠️ Known issue: duplicated backend (`app/` vs `backend/`)
There are two near-identical backends (`ai-study-scanner/app/` and `ai-study-scanner/backend/`),
both containing `main.py`, `ai_solver.py`, `config.py`, `prompts.py`.
- `app/` is what the README's run command targets.
- `backend/` is more complete (Docker, cost_utils, monetization docs, `.env.example`).
**Before making feature changes, confirm with the user which one is canonical and consolidate
to a single backend** to avoid drift. Do not edit both blindly.

## Backend stack
FastAPI · Uvicorn · Pydantic v2 · python-dotenv · google-generativeai (Gemini) ·
slowapi (rate limiting) · prometheus-fastapi-instrumentator · sentry-sdk

## Setup — install backend dependencies
```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m pip install -r "ai-study-scanner\backend\requirements.txt"
```

## Run the backend locally
```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m uvicorn app.main:app --app-dir ai-study-scanner --host 0.0.0.0 --port 8000 --log-level info
```
- Health: http://127.0.0.1:8000/health
- Docs (Swagger): http://127.0.0.1:8000/docs
- Solve endpoint: `POST /solve`
  ```json
  { "question_text": "Solve: 2x+3=11", "exam_mode": true }
  ```

## Environment variables / secrets
Loaded from a local `.env` (see `ai-study-scanner/backend/.env.example`). Key vars:
- `GEMINI_API_KEY` (required), `GEMINI_MODEL` (default `gemini-1.5-flash`)
- `GEMINI_TIMEOUT_S`, `GEMINI_TEMPERATURE_EXAM`, `GEMINI_TEMPERATURE_DEFAULT`, `GEMINI_MAX_OUTPUT_TOKENS`
- `MAX_QUESTION_CHARS`, `PROMPT_ANSWER_STYLE`, `SOLVE_CACHE_TTL_S`
- `HOST`, `PORT`, `LOG_LEVEL`, `ENV`, `SENTRY_DSN` (if used)

NEVER commit `.env` or real API keys. Keep `.gitignore` covering `.env`. Use `.env.example` as the template.

## Testing
- Put backend tests under the backend dir; run with the conda Python:
```bat
"C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m pytest -q
```
- (pytest is not yet in requirements — add it as a dev dependency before writing tests.)
- Always run the relevant tests after a change before declaring it done.

## Docker / deployment (backend)
A `Dockerfile` and `docker-compose.yml` exist in `ai-study-scanner/backend/`.
```bat
cd ai-study-scanner\backend
docker compose up --build
```
For cloud deploy, prefer the user's AWS background: container on **ECS Fargate** or **App Runner**
(alternatively Render / Railway / Fly.io for the fastest path). Inject secrets as env vars; never bake keys into the image.

## Mobile clients
- Flutter: `cd flutter_mobile` → `flutter pub get` → `flutter run`. Point the API base URL at the
  local backend (`http://127.0.0.1:8000`) for dev, and the deployed URL for release.
- Build release: `flutter build apk` (or `appbundle`).

## Conventions & guardrails
- Use the `ai_study_scanner` conda Python for ALL Python commands (repeat: never `base`).
- Make focused changes; confirm the `app/` vs `backend/` question before broad edits.
- Run a security review before deploying (there is a public `/solve` endpoint and an API key).
- Keep secrets out of git; validate and rate-limit user input on `/solve`.
- Prefer small, testable increments: implement → run locally → test → commit.
