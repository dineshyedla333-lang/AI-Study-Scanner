# AGENTS.md

This repository keeps one set of agent instructions, in **[CLAUDE.md](CLAUDE.md)**.

Read that file. It is authoritative for this project: the Windows conda Python path
that every Python command must use, which backend directory is canonical
(`ai-study-scanner/backend/`, never the stale `app/`), the Android release procedure,
the deploy remote, and the project guardrails.

This file exists only so agents that look for `AGENTS.md` find their way there. Do not
copy the guidance back into here — two copies drift, and the stale one is the one
somebody follows by mistake. That already happened once: this file spent a while
claiming the app was in internal testing at v1.2.4 while it was live in production.
