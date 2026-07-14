# ArchieMate - Project Context

## Overview
ArchieMate is a full-stack chatbot for live streaming (Twitch, Kick, YouTube). Written in Scala 3 with a Pekko backend and Scala.js frontend.

## Project Structure
```
backend/     - Pekko HTTP server, actors, persistence
frontend/    - Scala.js, Laminar, Tailwind CSS
frontend-test/ - Playwright E2E + Vitest unit tests
shared/      - Shared types between backend/frontend
docker/      - Dockerfile + docker-compose
```

## Key Commands
```bash
sbt backend/run          # Start backend
sbt backend/test         # Backend tests
sbt frontend/test        # Frontend Scala tests
cd frontend-test && npm run test       # Vitest unit tests
docker compose up -d  # Full stack
```

## E2E Testing
E2E tests require the full Docker stack (PostgreSQL + backend). Always follow this exact sequence:

```bash
# 1. Force rebuild and start containers
docker compose build --no-cache archiemate && docker compose up --force-recreate -d

# 2. Wait for health checks
sleep 5

# 3. Run E2E tests
cd frontend-test && npm run test:e2e

# 4. Stop containers and remove orphans
cd .. && docker compose down --remove-orphans
```

**Important:** Never skip the `build --no-cache` step — without it, cached Docker layers may serve stale frontend code. Never skip `down --remove-orphans` — orphans can interfere with subsequent runs.

## Configuration
- `backend/src/main/resources/application.conf` - Main config
- Environment variables override config values: `SERVER_HOST`, `SERVER_PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`

## Architecture
- Package: `com.archimond7450.archiemate`
- REST API: `/api/v1/...`
- Persistence: Pekko Persistence JDBC → PostgreSQL
- Frontend: Scala.js → Laminar → Tailwind CSS

## Version Info
Footer auto-generates version/built-at from build.sbt via `VersionInfo` object.

## Progress
See PROGRESS.md for development tracking.
