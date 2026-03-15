# Monitoring Stack (Prometheus + Grafana + Loki)

This folder provides a docker-compose monitoring stack:
- Prometheus (metrics)
- Grafana (dashboards)
- Loki (logs)
- Promtail (ships Docker logs to Loki)

## Quick start
From `monitoring/`:
```bash
docker compose up -d
```

URLs:
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3001  (default user/pass: admin/admin)
- Loki: http://localhost:3100

## FastAPI metrics (Prometheus)
Prometheus is configured to scrape:
- `GET /metrics` on the backend

Config: `monitoring/prometheus/prometheus.yml`

Default target is:
- `host.docker.internal:8000`

That works when:
- Prometheus runs in Docker and your backend runs on the host at port 8000 (Windows/macOS typically support `host.docker.internal`).

If your backend is also running in Docker on the same network, change the target to the backend service name, e.g.:
```yaml
- targets: ["backend:8000"]
```

## Backend instrumentation requirement
The backend must expose `/metrics`. This repo adds it via `prometheus-fastapi-instrumentator`.

## Logs (Loki)
Promtail is configured to scrape Docker JSON logs on Linux via:
- `/var/lib/docker/containers/*/*-json.log`

### Important note for Windows
On Windows (Docker Desktop), that host path typically does not exist, so Promtail will not collect container logs using this approach.

Options on Windows:
1) Run monitoring stack inside WSL2 and point promtail to the Linux docker engine logs.
2) Use Grafana Agent / Alloy with a Windows-friendly log source.
3) Emit application logs to stdout and rely on Docker Desktop integrations (not via promtail filesystem scraping).

Config files:
- `monitoring/logging/loki-config.yml`
- `monitoring/logging/promtail-config.yml`

## Grafana provisioning
Datasources are auto-provisioned:
- Prometheus at `http://prometheus:9090`
- Loki at `http://loki:3100`

File:
- `monitoring/grafana/provisioning/datasources/datasources.yml`
