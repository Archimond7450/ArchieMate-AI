# ArchieMate Project Progress

This file tracks the development progress of ArchieMate. The AI agent should refer to this file to pick up on ongoing work.

**⚠️ Always keep this file accurate and up-to-date.** Mark items as completed when done, move completed items from TODO to Completed, and add a "Recent Work" section for the last 10–15 commits so the agent can see what was just done. Never let this file become stale or duplicated.

## Completed

### Phase 1: Project Foundation ✅
- [x] Initialize git repository
- [x] Create sbt multi-project build (backend, frontend, shared, frontend-test)
- [x] Configure Scala 3.6.4, Pekko 1.1.5, Pekko HTTP 1.1.0, Circe 0.14.14
- [x] Configure Scala.js 1.18.2, Laminar 17.2.1, Tailwind 3.4
- [x] Configure testing: ScalaTest, Vitest, Playwright
- [x] Generate AGENTS.md with project context
- [x] Generate this PROGRESS.md file

### Phase 2: Backend Core ✅
- [x] Backend application entry point (ArchieMateApp)
- [x] HTTP server setup with Pekko HTTP
- [x] API routes structure (`/api/v1/...`)
- [x] Liveness endpoint (`/api/v1/live` → 204 No Content)
- [x] Readiness endpoint (`/api/v1/ready` → 204 No Content)
- [x] Configuration via application.conf + environment variables
- [x] Shutdown hook
- [x] Backend tests (ApiRoutesSpec, AppConfigSpec, ReadinessTrackerSpec) — all passing
- [x] Database configuration (PostgreSQL, Pekko Persistence JDBC)
- [x] ReadinessTracker actor (multi-stage initialization, counter-based state)

### Phase 3: Frontend Core ✅
- [x] Scala.js frontend entry point (App.scala with Waypoint router)
- [x] Multi-page routing: Home (`/`), About (`/about`), Docs (`/docs`)
- [x] HomePage component
- [x] AboutPage component
- [x] DocsPage component
- [x] Header component (responsive nav, hamburger mobile menu, active link styling)
- [x] Footer component (version, copyright, tech stack lines)
- [x] Tailwind CSS configuration
- [x] Dark/light mode support (localStorage persistence + system preference detection)
- [x] Dark/light mode toggle button in header
- [x] Mobile-friendly responsive design
- [x] VersionInfo generation (version + build timestamp)
- [x] Frontend Scala tests (HomePageSpec, AboutPageSpec, DocsPageSpec, FooterSpec) — all passing

### Phase 4: Testing Infrastructure ✅
- [x] Backend test setup (ScalaTest) — 3 tests passing
- [x] Frontend Scala test setup (ScalaTest) — 4 tests passing
- [x] Frontend unit test setup (Vitest)
- [x] E2E test setup (Playwright) — 7 test files
  - `homepage.spec.ts`, `about-page.spec.ts`, `docs-page.spec.ts`
  - `footer.spec.ts`, `dark-mode.spec.ts`, `mobile-menu.spec.ts`, `routing.spec.ts`
- [x] E2E tests for all pages, dark mode, mobile menu, and routing

### Phase 5: Deployment ✅
- [x] Production Dockerfile (multi-stage build, fullOptJS)
- [x] E2E Dockerfile (fastOptJS for faster rebuilds)
- [x] Docker Compose with PostgreSQL (production)
- [x] Docker Compose for E2E tests (separate PostgreSQL volume)
- [x] .dockerignore and .gitignore
- [x] logback.xml configuration (STDOUT output)

### Phase 6: Documentation ✅
- [x] pekko-typed-actors-best-practices.md
- [x] scala-best-practices.md

## Recent Work (Last 10 Commits)

| Commit | Description |
|--------|-------------|  
| xxxxxxx | feat: complete Phase 7 auth flow — UserTokenRegistry, wire callback to store tokens + issue JWT |
| xxxxxxx | feat: add UserTokenRegistry actor to manage per-user UserTokenActor instances |
| b6ecbc6 | feat: add Twitch OAuth flow with /auth/twitch/login and /auth/twitch/callback |
| 4a89ba0 | feat: integrate ArchieMateMediator with HttpClientActor and complete Phase 8 actor system |
| aeb9af2 | feat: add HttpClientActor with 6 passing tests |
| 8484d4a | feat: add JWT actor with per-command response types and auth directives |
| 5d4eee3 | feat: add Phase 7 (Auth) and Phase 9 (Platform Connections) to TODO |
| xxxxxxx | feat: add UserTokenActor (persistent OAuth token storage) with 15 passing tests |
| 85e83e4 | chore: rewrite PROGRESS.md — fix duplication, update completed work, add recent commits |

## In Progress

### Phase 8: Actor System ✅ COMPLETE
- [x] Rewrite ArchieMateMediator to accept ActorRefs at construction (avoids config/dependency injection)
- [x] Update ArchieMateApp to spawn HttpClientActor and ArchieMateMediator
- [x] Update ApiRoutes to accept mediator alongside readinessTracker and jwtActor
- [x] Keep AuthDirectives.authenticateToken using direct jwtActor ref (ask-through-mediator breaks response routing)
- [x] Add HttpClientConfig to AppConfig with maxConnections and maxIdleTimeoutMinutes
- [x] Fix ArchieMateMediatorSpec with unique actor names per test (resolved InvalidActorNameException)
- [x] Add ArchieMateMediatorIntegrationSpec — 5 tests: GET/POST routing, concurrent messages, error propagation, status code preservation
- [x] All 63 tests pass
- [x] HttpClientActor with StatusReply, Http().singleRequest, internal Unmarshal
- [x] All Phase 8 items complete — mediator infrastructure ready for platform actors

### Phase 7: Authentication ✅ COMPLETE
- [x] JWT actor (encode, decode, validate with expiration, refresh)
- [x] JwtClaim fluent builder for token creation
- [x] Per-command sealed response traits
- [x] Auth directives for Pekko HTTP (extract Bearer token from Authorization header)
- [x] Wire JwtActor into ArchieMateApp and ApiRoutes
- [x] Add /api/v1/me authenticated endpoint
- [x] Add /api/v1/auth public endpoint
- [x] Add jwt section to application.conf
- [x] User token persistent actor (stores OAuth tokens per user)
- [x] Twitch OAuth actor (state generation, code exchange, user fetch)
- [x] Twitch login endpoint (`/api/v1/auth/twitch/login` → redirect to Twitch OAuth)
- [x] Twitch callback endpoint (`/api/v1/auth/twitch/callback` → exchange code for token + store tokens + issue JWT)
- [x] UserTokenRegistry actor (manages per-user UserTokenActor instances)
- [ ] Token refresh mechanism (auto-refresh on HTTP 401)
- [ ] Authenticated route middleware / context propagation
- [ ] Logout endpoint

## TODO

### Phase 8: Actor System ✅ COMPLETE
- [x] HttpClientActor (HTTP client for platform connections)
- [x] ArchieMateMediator (inter-actor command routing)
- [x] HttpRequestActor (typed request wrapper with decode function)
- [x] Wire HttpRequestActor into ArchieMateApp
- [x] Add SendHttpRequest command to ArchieMateMediator
- [x] Update TwitchOAuthActor to use HttpRequestActor for HTTP calls
- [x] Expand HttpRequestActorSpec from 1 to 5 tests (success, HTTP error, decode failure, connection error, concurrent)
- [ ] Chat message actor
- [ ] Event dispatcher actor
- [ ] Command processing actor
- [ ] Persistence with Pekko Persistence
- [ ] Cluster support (future)

### Phase 9: Platform Connection Management
- [ ] Platform connection persistent actor (stores per-user platform connections)
- [ ] Twitch platform actor (constructs requests, decodes JSON, auto-refreshes tokens)
- [ ] Kick platform actor (constructs requests, decodes JSON, auto-refreshes tokens)
- [ ] YouTube platform actor (constructs requests, decodes JSON, auto-refreshes tokens)
- [ ] Dashboard page with platform connection UI
- [ ] API endpoints for connection CRUD (`/api/v1/connections/...`)

### Phase 10: Chatbot Features
- [ ] Command system (`!command` syntax)
- [ ] Message filtering
- [ ] User moderation
- [ ] Custom responses
- [ ] Statistics and analytics

### Phase 11: Frontend Pages (remaining)
- [ ] Settings page
- [ ] Chat viewer component

### Phase 12: Production Hardening
- [ ] Health check improvements (database connectivity)
- [ ] Metrics and monitoring
- [ ] Structured logging
- [ ] Rate limiting
- [ ] CORS configuration
- [ ] API versioning strategy
- [ ] CI/CD pipeline

### Phase 13: Documentation
- [ ] API documentation
- [ ] Architecture decision records
- [ ] Contributing guide
- [ ] Deployment guide

## Suggested Next Steps

1. **Phase 7 - Complete Auth flow**: Wire up UserTokenActor to store Twitch tokens after callback, and issue JWT for the session. This completes the auth flow.
2. **Phase 9 - Platform Connections**: Add platform connection actors (Twitch, Kick, YouTube) and dashboard UI for managing connections.
3. **Phase 10 - Chatbot Features**: Implement the core chatbot command system.

## Notes

- All dependencies should be kept at their newest compatible versions
- TDD approach: write tests before implementation
- No cookies except session cookie for login
- REST API versioning: `/api/v[version]/...`
- Package: `com.archimond7450.archiemate`
