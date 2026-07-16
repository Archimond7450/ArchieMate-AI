# ArchieMate - Project Context

## Overview
ArchieMate is a full-stack chatbot for live streaming (Twitch, Kick, YouTube). Written in Scala 3 with a Pekko backend and Scala.js frontend.

## Project Structure
```
backend/         - Pekko HTTP server, actors, persistence
frontend/        - Scala.js, Laminar, Tailwind CSS
frontend-test/   - Playwright E2E + Vitest unit tests
shared/          - Shared types between backend/frontend
Dockerfile       - Production build (fullOptJS)
Dockerfile.e2e   - E2E test build (fastOptJS)
docker-compose.yml       - Production stack
docker-compose.e2e.yml   - E2E test stack
```

## Key Commands
```bash
sbt backend/run          # Start backend
sbt backend/test         # Backend tests
sbt frontend/test        # Frontend Scala tests
cd frontend-test && npm run test       # Vitest unit tests
docker compose up -d     # Production stack (fullOptJS)
docker compose -f docker-compose.e2e.yml up -d  # E2E stack (fastOptJS)
```

## E2E Testing
E2E tests use a separate Docker stack built with `Dockerfile.e2e` (fastOptJS for faster rebuilds) and `docker-compose.e2e.yml`.

Always follow this exact sequence:

```bash
# 1. Force rebuild and start containers
docker compose -f docker-compose.e2e.yml build --no-cache archiemate && \
  docker compose -f docker-compose.e2e.yml up --force-recreate -d

# 2. Wait for health checks
sleep 5

# 3. Run E2E tests
cd frontend-test && npm run test:e2e

# 4. Stop containers and remove orphans
cd .. && docker compose -f docker-compose.e2e.yml down --remove-orphans
```

**Important:** Never skip the `build --no-cache` step — without it, cached Docker layers may serve stale frontend code. Never skip `down --remove-orphans` — orphans can interfere with subsequent runs. E2E uses its own PostgreSQL volume (`postgres_data_e2e`) separate from production.

## Docker Builds
- `Dockerfile` — Production build using `fullOptJS` (optimized, minified JS). Used by `docker-compose.yml`.
- `Dockerfile.e2e` — E2E test build using `fastOptJS` (unoptimized, faster compile). Used by `docker-compose.e2e.yml`.
- `frontend/index.html` references `archiemate-frontend-opt.js` for production; `Dockerfile.e2e` copies `fastopt.js` and serves the same HTML.

## Configuration
- `backend/src/main/resources/application.conf` - Main config
- Environment variables override config values: `SERVER_HOST`, `SERVER_PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`, `JWT_TOKEN_LIFETIME_MINUTES`, `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET`, `TWITCH_REDIRECT_URI_POSTFIX`
- `example.env` — template with all environment variables. **Keep this file accurate and up-to-date** whenever a new env var is added to `application.conf`.

## Architecture
- Package: `com.archimond7450.archiemate`
- REST API: `/api/v1/...`
- Persistence: Pekko Persistence JDBC → PostgreSQL
- Frontend: Scala.js → Laminar → Tailwind CSS

## Version Info
Footer auto-generates version/built-at from build.sbt via `VersionInfo` object.

## Pekko Typed Actors
See [pekko-typed-actors-best-practices.md](docs/pekko-typed-actors-best-practices.md) for conventions on writing Pekko Typed actors (object vs class pattern, state management, supervision, etc.).

## Scala Best Practices
See [scala-best-practices.md](docs/scala-best-practices.md) for conventions on Scala 3 syntax, given/using, extension methods, implicit conversions, and circe JSON encoding/decoding.

## ScalaTest
Prefer `AnyWordSpecLike` over `AnyWordSpec` for all ScalaTest suites. Actor tests must extend both `ScalaTestWithActorTestKit` and `AnyWordSpecLike`.

## Progress
See [PROGRESS.md](PROGRESS.md) for development tracking. **Always keep this file accurate and up-to-date:** mark items as completed when done, move completed items from TODO to Completed, and add a "Recent Work" section for the last 10–15 commits. Never let this file become stale, duplicated, or misleading — it is the primary mechanism for the AI agent to understand what has been done and what remains.
