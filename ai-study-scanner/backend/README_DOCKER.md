# AI Study Scanner Backend (Docker)

## Files
- `Dockerfile` - container image for the FastAPI backend
- `docker-compose.yml` - local/prod-ish compose deployment
- `.env.docker.example` - example environment variables for Docker

## Environment variables
The backend uses `backend/config.py` and reads:
- `ENV` (default `dev`)
- `LOG_LEVEL` (default `info`)
- `HOST` (default `127.0.0.1`, overridden in Docker to `0.0.0.0`)
- `PORT` (default `8000`)
- `GEMINI_API_KEY` (**required** for `/solve`)
- `GEMINI_MODEL` (default `gemini-1.5-flash`)
- `GEMINI_TIMEOUT_S` (default `30.0`)

## Run with Docker Compose
From this folder (`ai-study-scanner/backend/`):

1) Create an env file
```bash
cp .env.docker.example .env
```

2) Edit `.env` and set `GEMINI_API_KEY`

3) Build and run
```bash
docker compose up --build
```

Server will be available at:
- `http://localhost:8000/health`
- `http://localhost:8000/solve`

## Production logging
The container starts uvicorn with:
- `--log-level ${LOG_LEVEL:-info}`
- `--access-log`

Set `LOG_LEVEL=warning` (or `info`) in your environment to control verbosity.
