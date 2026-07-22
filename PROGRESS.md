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
| 8f9cb6d | feat: add ConnectionRoutes — CRUD API for platform connections (GET/POST/DELETE /api/v1/connections) |
| 5748f63 | feat: add Kick and YouTube platform actors — KickApiActor, YoutubeApiActor, KickConfig, YoutubeConfig, wire into ArchieMateApp |
| 8b0267f | chore: ignore metals.sbt in project and project/project |
| a6d3cc0 | chore: add metals.sbt to .gitignore |
| 036f291 | refactor: rename callbackBaseUrl and callbackPath for clarity |
| b3f7e7d | refactor: move redirectUriPrefix from TwitchConfig to AppConfig |
| e46ce56 | feat: add configurable Twitch redirect URI prefix with secure cookie logic |
| c65cbee | feat: add administrator page with configurable admin user ID |
| c60b5aa | feat: make ask timeout configurable via application.conf and ASK_TIMEOUT env var |
| 67d2895 | docs: clarify that only case object needs 'final', not case class |

## Completed

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
- [x] Token refresh mechanism (TwitchApiActor.RefreshToken command)
- [x] User info retrieval (TwitchApiActor.GetUserById, GetCurrentUser, GetUserByLogin)
- [x] Logout endpoint (`/api/v1/logout`)

### JWT Token Refresh ✅ COMPLETE
- [x] JwtActor.Refresh command — decodes existing token, re-encodes with fresh expiry
- [x] JwtActorSpec tests for refresh (valid token, invalid token)
- [x] /auth/refresh GET endpoint — reads JWT cookie, calls JwtActor, sets new cookie, redirects
- [x] AuthRoutesSpec — 6 tests covering all refresh scenarios
- [x] UserStore.refresh() — calls /auth/refresh, follows redirect to reload with fresh cookie
- [x] checkLogin() calls refresh() automatically when /api/v1/me fails (token expired)
- [x] All 84 backend tests pass

### Phase 8: Actor System ✅ COMPLETE
- [x] HttpClientActor (HTTP client for platform connections)
- [x] ArchieMateMediator (inter-actor command routing)
- [x] HttpRequestActor (typed request wrapper with decode function)
- [x] Wire HttpRequestActor into ArchieMateApp
- [x] Add SendHttpRequest command to ArchieMateMediator
- [x] Update TwitchOAuthActor to use HttpRequestActor for HTTP calls
- [x] Expand HttpRequestActorSpec from 1 to 5 tests (success, HTTP error, decode failure, connection error, concurrent)

### Phase 9: Platform Connection Management ✅ COMPLETE
- [x] TwitchApiActor (token refresh + user info — wired into ArchieMateApp)
- [x] Platform connection persistent actor (stores per-user platform connections — part of UserTokenActor)
- [x] Kick platform actor (constructs requests, decodes JSON, auto-refreshes tokens — wired into ArchieMateApp)
- [x] YouTube platform actor (constructs requests, decodes JSON, auto-refreshes tokens — wired into ArchieMateApp)
- [x] API endpoints for connection CRUD (`/api/v1/connections/...` — GET list, GET by platform, POST register, DELETE revoke)
- [x] ConnectionRoutes with full CRUD (GET/POST/DELETE /api/v1/connections)

### Phase 11: Frontend Auth & Dashboard ✅ COMPLETE
- [x] UserStore with HTTP-only cookie auth (no localStorage)
- [x] /api/v1/me endpoint reads JWT from cookie
- [x] /api/v1/logout POST endpoint clears cookie
- [x] /api/v1/twitch/me endpoint returns Twitch profile
- [x] Dashboard page at /dashboard (accessible to logged-in users)
- [x] UserMenu component — avatar dropdown with Dashboard + Logout
- [x] Header shows login button when not logged in, avatar when logged in
- [x] Mobile menu includes login/dashboard/logout links
- [x] checkLogin() on app mount to detect existing session
- [x] All backend tests pass
- [x] All frontend tests pass

## TODO

### Phase 8: Actor System
- [ ] Chat message actor
- [ ] Event dispatcher actor
- [ ] Command processing actor
- [ ] Persistence with Pekko Persistence
- [ ] Cluster support (future)

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

1. **Phase 10 - Chatbot Features**: Implement the core chatbot command system (command parsing, message filtering, custom responses).
2. **Phase 11 - Frontend Pages**: Build Settings page and Chat viewer component.
3. **Phase 12 - Production Hardening**: Health checks, metrics, rate limiting, CORS.

## Notes

- All dependencies should be kept at their newest compatible versions
- TDD approach: write tests before implementation
- No cookies except session cookie for login
- REST API versioning: `/api/v[version]/...`
- Package: `com.archimond7450.archiemate`
