# syntax=docker/dockerfile:1
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# System deps (kept minimal)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
  && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt
RUN pip install --upgrade pip && pip install -r /app/requirements.txt

# Copy backend package
COPY . /app

# Security: non-root user
RUN useradd -m appuser
USER appuser

EXPOSE 8000

# Production defaults (override via env if needed)
# HOST=0.0.0.0 PORT=8000 LOG_LEVEL=info
CMD ["sh", "-c", "uvicorn backend.main:app --host ${HOST:-0.0.0.0} --port ${PORT:-8000} --log-level ${LOG_LEVEL:-info} --access-log"]
