# AI Study Scanner – Monetization & Agentic AI Plan (Backend-first)

This document is a practical plan to monetize the app while keeping costs under control.

## Goals
- Support many users (scale) without leaking API keys.
- Prevent abuse (rate limit, auth, quotas).
- Monetize usage (free tier + paid tier).
- Enable “agentic” features as premium value.

---

## Recommended architecture (what you already have)
Android App → **Your Backend (FastAPI)** → Gemini (or other provider)

Why this is best for Play Store scale:
- No API keys in APK
- Central rate limiting + quotas
- One place to log, monitor, improve prompts/models
- Easier to add payments/entitlements later

---

## Minimum production controls (required before public launch)
### 1) Auth (anonymous user tokens)
- App generates a stable device/user id (you already have `UserIdProvider`).
- Backend issues a signed token (JWT) for that user id.
- All paid/free limits are enforced by backend using this user id.

### 2) Rate limiting (done)
- IP-based protection: prevents basic abuse.
- Should be combined with user-based quotas to prevent NAT/shared-IP issues.

### 3) Per-user quotas (recommended next)
- Example:
  - Free: 10 solves/day
  - Pro: 200 solves/day
- Store usage in DB/Redis and reset daily.

### 4) Observability (already partially present)
- `/metrics` Prometheus
- Sentry integration if `SENTRY_DSN` is set

---

## Premium features (agentic AI)
Agentic flow = the backend does multiple steps before answering.

Example “Solve with agentic flow” pipeline:
1) Parse user input (OCR text / question text)
2) Retrieve context:
   - user’s history (last N questions)
   - saved notes / topics
3) Plan step (LLM): decide what to do
   - “explain step-by-step”
   - “generate flashcards”
   - “generate study plan”
4) Execute tools (server-side):
   - DB search
   - summarization
   - flashcard creation
5) Final answer + save results to history

Guardrails:
- max tokens per request
- max tool calls
- safe prompt templates
- timeouts

---

## Payments (Google Play Billing)
Recommended approach:
- Use Google Play Billing (subscriptions or consumables)
- App sends purchase token to backend
- Backend verifies with Google Play Developer API
- Backend stores entitlement and returns “is_pro” to app

Entitlement gating:
- Backend checks `is_pro` before allowing premium endpoints or higher quotas.

---

## Suggested endpoints (next iteration)
- `POST /auth/device` → returns JWT for a device/user id
- `POST /solve` → free/basic solve (rate-limited)
- `POST /solve/agent` → premium solve with agentic flow + higher quotas
- `GET /me` → returns plan + remaining quota

---

## Environment variables (suggested)
- `SOLVE_RATE_LIMIT=10/minute` (already supported)
- `FREE_DAILY_QUOTA=10`
- `PRO_DAILY_QUOTA=200`
- `JWT_SECRET=...`
- `SENTRY_DSN=...`

---

## What to do next (recommended order)
1) Add user auth token endpoint (`/auth/device`) + JWT verification middleware
2) Add per-user quotas (Redis or SQLite/Postgres)
3) Add Play Billing verification + entitlement storage
4) Add `/solve/agent` and implement the agentic pipeline
