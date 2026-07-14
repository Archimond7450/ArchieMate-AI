# ArchieMate

Your intelligent chatbot companion for live streaming. Connects to Twitch, Kick, and YouTube to provide real-time chatbot functionality.

## Tech Stack

- **Backend**: Scala 3, Apache Pekko Typed, Pekko HTTP, Pekko Persistence JDBC, Circe
- **Frontend**: Scala.js, Laminar, Tailwind CSS
- **Database**: PostgreSQL
- **Testing**: ScalaTest, Vitest, Playwright
- **Containerization**: Docker, Docker Compose

## Project Structure

```
ArchieMate/
├── backend/          # Scala backend (Pekko HTTP, actors, persistence)
├── frontend/         # Scala.js frontend (Laminar, Tailwind)
├── frontend-test/    # E2E tests (Playwright) and unit tests (Vitest)
├── shared/           # Shared types and utilities
├── docker-compose.yml  # Docker Compose for local dev
├── build.sbt         # SBT multi-project build
└── project/          # SBT plugins and configuration
```

## Prerequisites

- JDK 21+
- sbt 1.10+
- Node.js 20+
- Docker & Docker Compose (optional, for local dev)

## Getting Started

### 1. Configure Environment

```bash
# Copy and edit the configuration
cp backend/src/main/resources/application.conf backend/src/main/resources/application.local.conf
```

Set environment variables:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/archiemate
export DATABASE_USER=archiemate
export DATABASE_PASSWORD=archiemate
```

### 2. Run Backend

```bash
sbt backend/run
```

### 3. Run Frontend (Development)

```bash
cd frontend
npm install
npm run dev
```

### 4. Run Tests

```bash
# Backend tests
sbt backend/test

# Frontend Scala tests
sbt frontend/test

# Frontend unit tests
cd frontend-test
npm install
npm run test

# E2E tests
npm run test:e2e
```

### 5. Docker

```bash
docker compose up -d
```

## API Endpoints

| Method | Endpoint        | Description              |
|--------|-----------------|--------------------------|
| GET    | `/api/v1/live`  | Liveness probe           |
| GET    | `/api/v1/ready` | Readiness probe          |

## Configuration

Environment variables take precedence over `application.conf` values:

| Variable              | Description              | Default            |
|-----------------------|--------------------------|--------------------|
| `SERVER_HOST`         | HTTP listen address      | `0.0.0.0`         |
| `SERVER_PORT`         | HTTP listen port         | `8080`             |
| `DATABASE_URL`        | PostgreSQL connection    | (required)         |
| `DATABASE_USER`       | PostgreSQL username      | (required)         |
| `DATABASE_PASSWORD`   | PostgreSQL password      | (required)         |

## Development

### Code Formatting

```bash
sbt scalafmtAll
```

### Build Everything

```bash
sbt compile package
```

## License

© 2022 - 2026 Archimond7450
