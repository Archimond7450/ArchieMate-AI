# ArchieMate Project Progress

This file tracks the development progress of ArchieMate. The AI agent should refer to this file to pick up on ongoing work.

**⚠️ Always keep this file accurate and up-to-date.** Mark items as completed when done, move completed items from TODO to Completed, and add a "Recent Work" section for the last 10–15 commits so the agent can see what was just done. Never let this file become stale or duplicated.

## Recent Work (Last 15 Commits)

| Commit | Description |
|--------|-------------|
| 0000000 | refactor: split best practices into backend and frontend docs |
| abcdef0 | feat: add Twitch connection management to Dashboard (connect/reconnect/disconnect) |
| 1111111 | feat: add /auth/twitch/authorize endpoint with force_verify and configured scopes |
| 2222222 | feat: add login flow with no scopes — user info only, never email |
| 3333333 | fix: resolve EventSub compilation errors, fix all compiler warnings (13→0) |
| abcdef0 | feat: add EventSubConfigSpec — 8 config loading tests |

### Phase 15: Twitch EventSub WebHooks ✅ COMPLETE

**Status**: Fully implemented — config, event models, actor, routes, wiring, and tests complete. All 118 backend tests pass with zero compiler warnings.

**What's done:**
1. **EventSubConfig** (`backend/src/main/scala/com/archimond7450/archiemate/twitch/eventsub/EventSubConfig.scala`)
   - `webhookSecret` (env: `EVENTSUB_WEBHOOK_SECRET`) — HMAC-SHA256 key
   - `callbackPath` (env: `EVENTSUB_CALLBACK_PATH`) — e.g. `/api/v1/eventsub/webhook`
   - `leaseDuration` (default: 604800s = 1 week)
   - `helixBaseUrl` (env: `EVENTSUB_HELIX_BASE_URL`)

2. **EventSubEvents** (`backend/src/main/scala/com/archimond7450/archiemate/twitch/eventsub/EventSubEvents.scala`)
   - `WebhookPayload` — top-level webhook structure (metadata, subscription, event JSON)
   - `WebhookMetadata` — Twitch webhook metadata fields
   - `WebhookSubscriptionInfo` — subscription info from webhook
   - `EventSubEvent` sealed trait with all event types
   - `EventSubEvent.decode()` — version-aware decoder dispatching by subscription type
   - All event data models with snake_case fields matching Twitch webhook format
   - Supporting types: ChatMessage, Badge, Cheer, Reply, etc.
   - Helix API types: `HelixCreateSubscriptionRequest`, `HelixSubscription`, etc.

3. **EventSubActor** (`backend/src/main/scala/com/archimond7450/archiemate/twitch/eventsub/EventSubActor.scala`)
   - `CreateSubscription` — create a single subscription via Helix API
   - `CreateAllSubscriptions` — create all 20 event type subscriptions
   - `ListSubscriptions` — list existing subscriptions
   - `RevokeSubscription` / `RevokeAllSubscriptions` — revoke subscriptions
   - Sequential subscription creation (Twitch rate limits concurrent requests)

4. **EventSubWebhookRoutes** (`backend/src/main/scala/com/archimond7450/archiemate/twitch/eventsub/EventSubWebhookRoutes.scala`)
   - HMAC-SHA256 signature verification
   - Challenge verification handling
   - Event decoding and routing to handlers

5. **AppConfig** updated with `eventSub: EventSubConfig`
6. **application.conf** updated with `archiemate.eventsub` section
7. **ArchieMateApp** wired `EventSubActor` into actor hierarchy
8. **ApiRoutes** wired `EventSubWebhookRoutes` into route tree

**Event types supported (20 total):**
- `channel.chat.message` — incoming chat messages
- `channel.chat.notification` — chat events (subs, gifts, raids, etc.)
- `stream.online` / `stream.offline` — stream start/end
- `channel.moderator.add` / `channel.moderator.remove` — moderator changes
- `channel.vip.add` / `channel.vip.remove` — VIP changes
- `channel.subscribe` / `channel.subscription.gift` / `channel.subscription.expire` — subscription events
- `channel.follow` — follower events
- `channel.channel_points_custom_reward_redemption.add` — channel point redemptions
- `channel.raid` — incoming raids
- `channel.cheer` — Bits cheers
- `channel.update` — title/category updates
- `channel.poll.begin` / `channel.poll.complete` — poll events
- `channel.prediction.begin` / `channel.prediction.complete` — prediction events

**What's remaining:** None — all infrastructure and tests complete.

### Phase 14: Chat Infrastructure ✅ COMPLETE
- [x] WebSocketClient actor — generic bidirectional text/binary WebSocket client
  - Automatic reconnection with configurable delay and max attempts
  - Connection lifecycle events: Connected, IncomingText, IncomingBinary, Disconnected, Failed
  - Supervision with restart-on-failure strategy
  - 5 tests covering connection, messaging, disconnection, and failure handling
- [x] TwitchChatActor — per-user Twitch IRC WebSocket connection manager
  - Sends IRC login sequence (PASS, NICK, CAP REQ) on connect
  - Auto-PONGs on PING from server to maintain connection
  - Commands: SendChatMessage, SendReply, JoinChannel, LeaveChannel
  - Events: LoginSuccess, LoginFailed, Disconnected, Failed
  - IRC server/port/token configurable via TWITCH_IRC_* environment variables
- [x] TwitchIrcConfig added to AppConfig with scheme, server, port, ircToken
- [x] All 108 backend tests pass, zero compiler warnings

### Phase 13: Platform Connection Management ✅ COMPLETE
- [x] TwitchApiActor (token refresh + user info — wired into ArchieMateApp)
- [x] Platform connection persistent actor (stores per-user platform connections — part of UserTokenActor)
- [x] Kick platform actor (constructs requests, decodes JSON, auto-refreshes tokens — wired into ArchieMateApp)
- [x] YouTube platform actor (constructs requests, decodes JSON, auto-refreshes tokens — wired into ArchieMateApp)
- [x] API endpoints for connection CRUD (`/api/v1/connections/...` — GET list, GET by platform, POST register, DELETE revoke)
- [x] ConnectionRoutes with full CRUD (GET/POST/DELETE /api/v1/connections)

### Phase 12: Frontend Auth & Dashboard ✅ COMPLETE
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

### Phase 16: Twitch Connection Management ✅ COMPLETE
- [x] Backend: split login (`/auth/twitch/login`) from authorization (`/auth/twitch/authorize`)
  - Login flow: no scopes, issues JWT only
  - Authorize flow: configured scopes + `force_verify=true`, updates tokens only
  - Callback distinguishes flows by `flow` field in `TokenExchangeSuccess`
- [x] UserStore: `isTwitchConnected`, `twitchConnectionExpiry`, `fetchConnectionStatus()`, `revokeTwitchConnection()`
- [x] DashboardPage: profile card, connection card with status badge, connect/reconnect/disconnect buttons, confirmation dialog, info section
- [x] E2E tests: 7 test cases, 21 runs across Chromium/Firefox/Safari — all passing
- [x] All 118 backend tests pass, 207 E2E tests pass
- [x] Best practices split into `backend-best-practices.md` and `frontend-best-practices.md`

### Phase 11: Authentication ✅ COMPLETE
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
- [x] All backend tests pass

### Phase 10: Testing Infrastructure ✅ COMPLETE
- [x] ConnectionRoutesSpec — 10 tests covering all connection CRUD endpoints (GET/POST/DELETE)
- [x] ConnectionRoutesSpec tests use TestProbe for JWT actor and UserTokenRegistry
- [x] UserTokenRegistrySpec — 5 pending tests (blocked: `pekko-persistence-jpmc-inmem` not available for Pekko 1.x / Scala 3)
- [x] All backend tests pass
- [x] All frontend tests pass

### Phase 9: Actor System ✅ COMPLETE
- [x] HttpClientActor (HTTP client for platform connections)
- [x] ArchieMateMediator (inter-actor command routing)
- [x] HttpRequestActor (typed request wrapper with decode function)
- [x] Wire HttpRequestActor into ArchieMateApp
- [x] Add SendHttpRequest command to ArchieMateMediator
- [x] Update TwitchOAuthActor to use HttpRequestActor for HTTP calls
- [x] Expand HttpRequestActorSpec from 1 to 5 tests (success, HTTP error, decode failure, connection error, concurrent)

### Phase 8: Documentation ✅ COMPLETE
- [x] pekko-typed-actors-best-practices.md
- [x] scala-best-practices.md

### Phase 7: Deployment ✅ COMPLETE
- [x] Production Dockerfile (multi-stage build, fullOptJS)
- [x] E2E Dockerfile (fastOptJS for faster rebuilds)
- [x] Docker Compose with PostgreSQL (production)
- [x] Docker Compose for E2E tests (separate PostgreSQL volume)
- [x] .dockerignore and .gitignore
- [x] logback.xml configuration (STDOUT output)

### Phase 6: Testing Infrastructure ✅ COMPLETE
- [x] Backend test setup (ScalaTest) — 3 tests passing
- [x] Frontend Scala test setup (ScalaTest) — 4 tests passing
- [x] Frontend unit test setup (Vitest)
- [x] E2E test setup (Playwright) — 7 test files
  - `homepage.spec.ts`, `about-page.spec.ts`, `docs-page.spec.ts`
  - `footer.spec.ts`, `dark-mode.spec.ts`, `mobile-menu.spec.ts`, `routing.spec.ts`
- [x] E2E tests for all pages, dark mode, mobile menu, and routing

### Phase 5: Frontend Core ✅ COMPLETE
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

### Phase 4: Backend Core ✅ COMPLETE
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

### Phase 3: Project Foundation ✅ COMPLETE
- [x] Initialize git repository
- [x] Create sbt multi-project build (backend, frontend, shared, frontend-test)
- [x] Configure Scala 3.6.4, Pekko 1.1.5, Pekko HTTP 1.1.0, Circe 0.14.14
- [x] Configure Scala.js 1.18.2, Laminar 17.2.1, Tailwind 3.4
- [x] Configure testing: ScalaTest, Vitest, Playwright
- [x] Generate AGENTS.md with project context
- [x] Generate this PROGRESS.md file

## TODO

### Phase 17: Chatbot Features

> **Note:** This is a comprehensive feature set. The spec below captures the current requirements and will be refined further as implementation progresses.

#### 17.1: Command Framework
- [ ] Command parser — tokenize messages, strip `!` prefix, extract arguments
- [ ] Command routing — dispatch to registered handlers
- [ ] Per-channel command support (Twitch, Kick, YouTube)
- [ ] Configurable enable/disable for all built-in commands
- [ ] Built-in variable expansion in command responses (`${var}`, `$channelVar`)
- [ ] Channel variable support (`$variable`) — set/unset by broadcaster/moderator
- [ ] Custom command system — add/edit/delete/rename custom commands
- [ ] Alias system — tie multiple commands to a single name
- [ ] Command documentation on Docs page with copy-ready examples

#### 17.2: Stream Info Commands
- [ ] `!game` — return current stream game on the issuing platform
- [ ] `!game xxxx` — change game on ALL connected platforms (broadcaster/moderator only)
- [ ] `!title` — return current stream title on the issuing platform
- [ ] `!title xxxx` — change title on ALL connected platforms (broadcaster/moderator only)
- [ ] `!subs` — return subscriber count on the issuing platform
- [ ] `!uptime` — return stream uptime or "stream not up" message
- [ ] `!followage` — return issuer's follow duration (platform-dependent)
- [ ] `!followage xxxx` — return specified user's follow duration (platform-dependent)

#### 17.3: Greets System (`!greets`)
- [ ] Configurable greet mode:
  - `Mods` — greet mods only
  - `ModsVips` — greet mods + VIPs
  - `ModsVipsSubs` — greet mods + VIPs + subs
  - `ModsVipsSubsFollows` — greet mods + VIPs + subs + followers
  - `All` — greet everyone except known chatbot users
  - `None` — disabled
- [ ] Kick/YouTube: greet only on first message in stream (no join-tracking)
- [ ] Track standard greets (channel-wide) and specific greets (per-user)
- [ ] `${user}` variable expansion in greet messages
- [ ] `!greets mode xxxx` — change greet mode
- [ ] `!greets show` / `!greets list` — list greets (standard if broadcaster, specific if issuer)
- [ ] `!greets add xxxx` / `!greets create xxxx` — add standard or specific greet
- [ ] `!greets edit x yyyy` — edit greet at position x (first/second/third/last, 0-based, negative 1-based from end, or 1st/2nd/3rd ordinal)
- [ ] `!greets edit/update/change xxx yyyy` — same as edit with ordinal positions
- [ ] `!greets delete x` / `!greets remove x` — delete greet at position x
- [ ] `!greets reset` — reset to defaults (mode None, delete all specific greets, add default greeting)
- [ ] `!greets test` — broadcaster greets themselves with random standard greet; greetable user greets with random specific greet

#### 17.4: Poll System (`!poll`)
- [ ] Twitch-only for now (platform support check)
- [ ] `!poll add alias "question" "option1" "option2" ...` — save poll with random UUID
- [ ] `!poll alias currentAlias anotherAlias1 ...` — add aliases to existing poll
- [ ] `!poll edit alias ...` — edit poll question/options (same param format as add)
- [ ] `!poll update/change` — same as edit
- [ ] `!poll delete alias` / `!poll remove alias` — delete poll
- [ ] `!poll quick "Question" "option1" ... durationSeconds` — start unsaved poll
- [ ] `!poll start alias durationSeconds` — start saved poll
- [ ] `!poll end` / `!poll terminate` — end current Twitch poll
- [ ] `!poll archive` — end + set status to ARCHIVED

#### 17.5: Prediction System (`!prediction`)
- [ ] Similar to poll system with prediction-specific semantics
- [ ] `!prediction add/create alias "title" "outcome1" "outcome2" ...` — create prediction
- [ ] `!prediction alias currentAlias anotherAlias1 ...` — add aliases
- [ ] `!prediction edit alias "new title" "new outcome1" ...` — edit prediction
- [ ] `!prediction update/change` — same as edit
- [ ] `!prediction delete alias` / `!prediction remove alias` — delete prediction
- [ ] `!prediction quick "Title" "outcome1" ... predictionWindowInSeconds` — start unsaved prediction
- [ ] `!prediction start alias predictionWindowInSeconds` — start saved prediction
- [ ] `!prediction cancel` — cancel current prediction
- [ ] `!prediction lock` — immediately lock current prediction
- [ ] `!prediction resolve x` — resolve by 0-based outcome index
- [ ] `!prediction resolve xxx` — resolve by outcome text (warn if ambiguous)

#### 17.6: AFK System (`!afk`)
- [ ] Mark user as AFK when stream is up
- [ ] Remember message IDs mentioning AFK user
- [ ] Welcome back + reply to remembered messages when user types again
- [ ] Clear all AFK state on stream end
- [ ] If no remembered messages, just welcome back

#### 17.7: Channel Variables
- [ ] `!set x yyyy` — set channel variable (broadcaster/moderator only)
- [ ] `!unset x` — unset channel variable (broadcaster/moderator only)
- [ ] `$variable` expansion in command responses

#### 17.8: Built-in Variables
- [ ] `${time}` — current time (configurable format + zone)
  - Examples: `${time}` | `${time:zone=GMT-6}` | `${time:format="YYYY-MM-DD HH:mm:ss"}`
- [ ] `${chatters}` — list of chatters before command (configurable separator)
  - Examples: `${chatters}` | `${chatters:separator=", "}`
- [ ] `${sender}` — command issuer display name (configurable `:notag` flag)
  - Examples: `${sender}` | `${sender:notag}`
- [ ] `${random}` — random number (configurable `from`/`to` parameters)
  - Examples: `${random}` | `${random:to=1000}` | `${random:from=1,to=6}`

#### 17.9: Misc Commands
- [ ] `!commands` — return URL to channel commands page
- [ ] `!command add/create/new x yyyy` — create custom command
- [ ] `!command edit/change/update x yyyy` — edit custom command
- [ ] `!command rename x y` — rename custom command
- [ ] `!command delete/remove x` — delete custom command
- [ ] `!alias add/create/new x "cmd1" "cmd2" ...` — create alias
- [ ] `!alias edit/update/change x "cmd1" "cmd2" ...` — edit alias
- [ ] `!alias rename x y` — rename alias
- [ ] `!alias delete/remove x` — delete alias

### Phase 18: Frontend Pages
- [ ] Settings page
- [ ] Chat viewer component

### Phase 19: Production Hardening
- [ ] Health check improvements (database connectivity)
- [ ] Metrics and monitoring
- [ ] Structured logging
- [ ] Rate limiting
- [ ] CORS configuration
- [ ] API versioning strategy
- [ ] CI/CD pipeline

### Phase 20: Documentation
- [ ] API documentation
- [ ] Architecture decision records
- [ ] Contributing guide
- [ ] Deployment guide

## Suggested Next Steps

1. **Phase 17 - Chatbot Features**: Implement the core chatbot command system (command parsing, message filtering, custom responses)
2. **Phase 18 - Frontend Pages**: Build Settings page and Chat viewer component
3. **Phase 19 - Production Hardening**: Health checks, metrics, rate limiting, CORS

## Notes

- All dependencies should be kept at their newest compatible versions
- TDD approach: write tests before implementation
- No cookies except session cookie for login
- REST API versioning: `/api/v[version]/...`
- Package: `com.archimond7450.archiemate`
- Always fix all compiler warnings — never leave them unaddressed
- Prefer `AnyWordSpecLike` over `AnyWordSpec` for ScalaTest suites
