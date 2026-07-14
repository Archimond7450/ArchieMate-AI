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
cd frontend-test && npm run test:e2e   # E2E tests
cd frontend-test && npm run test       # Vitest unit tests
docker compose -f docker/docker-compose.yml up -d  # Full stack
```

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
