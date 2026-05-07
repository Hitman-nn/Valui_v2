# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build fat JAR (valui-app)
mvn clean package -pl valui-app -am -DskipTests

# Run all tests
mvn test

# Run tests for a specific module
mvn test -pl valui-notify

# Run a single test class
mvn test -Dtest=ControllerTaskExecutorTest

# Run a single test method
mvn test -Dtest=ControllerTaskExecutorTest#methodName

# Start test infrastructure (PostgreSQL:5433, Redis:6380, Kafka:29093) — required for integration tests
make test-up
make test-down
```

## Module Structure

Maven multimodule project. Each module has `src/main/java/com/valui/<module>/` with standard subpackages: `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `kafka/`, `config/`.

| Module | Purpose |
|--------|---------|
| `valui-common` | Shared Kafka records, DTOs, domain enums — no Spring, no JPA |
| `valui-user` | User/subscription JPA entities, Spring Security |
| `valui-parser` | Bookmaker HTTP + WebSocket parsers (1xBet, Fonbet, Olimp, BetCity, BetBoom) using Strategy+Factory |
| `valui-monitor` | Scheduler, value/odds change detection, Kafka outbox producer |
| `valui-notify` | Kafka consumer → notification filtering → Telegram dispatch |
| `valui-bot` | Telegram FSM, inline keyboards, wizard flows |
| `valui-admin` | REST admin API |
| `valui-betting` | Betting journal and P&L statistics |
| `valui-app` | Spring Boot entry point, fat JAR, Flyway migrations |

## Architecture: Kafka Transactional Outbox

The core pipeline uses transactional outbox to guarantee at-least-once delivery:

1. `ControllerTaskExecutor` calls a `BookmakerParser`, detects value changes
2. Within a **single DB transaction**: persist `DetectedEvent` + `OutboxEvent`
3. `@TransactionalEventListener(AFTER_COMMIT)` → `OutboxSenderService` sends to Kafka topic `sport.events.detected`
4. `SportEventConsumer` (valui-notify) filters by user subscriptions → `notification_log`
5. `TelegramNotificationSender` dispatches with Redis-based rate limiting
6. Failures → `notifications.dlq` → `DlqConsumer` (3 retries with backoff)

**Never bypass the outbox** — direct Kafka sends from `valui-monitor` would break delivery guarantees.

## Parsers

`BookmakerParser` interface with `ParseResult` return type. Adding a new bookmaker requires:
1. Implement `BookmakerParser` in `valui-parser`
2. Register in `ParserFactory`
3. Add `Bookmaker` enum entry in `valui-common`
4. Add Flyway migration for bookmaker metadata

BetBoom uses WebSocket + Protobuf (`valui-parser/src/main/proto/`). All others use HTTP via WebFlux + Resilience4j circuit breaker.

## Bot FSM

Telegram bot uses a finite-state-machine in `valui-bot`. The 5-step subscription wizard states are stored in Redis keyed by chat ID. Callback data prefixes (e.g., `BK_`, `SPORT_`, `TOURN_`) route to handlers via `CallbackQueryDispatcher`.

## Spring Profiles

| Profile | Use case | DB port | Redis port | Kafka |
|---------|---------|---------|-----------|-------|
| `local` | Dev on localhost with docker-compose | 5432 | 6379 | localhost:29092 |
| `docker` | Containerized (staging/CI) | postgres:5432 | redis:6379 | kafka:9092 |
| `prod` | Production (all from env vars) | `$POSTGRES_HOST` | `$REDIS_HOST` | `$KAFKA_BOOTSTRAP_SERVERS` |
| `server-test` | App local, infra via SSH tunnel | 5433 | 6380 | `$SERVER_IP`:29093 |

## Database

- Flyway migrations in `valui-app/src/main/resources/db/migration/` (V1–V23)
- All schema changes go through versioned migration files; `validate-on-migrate: true` is enforced
- `valui-common` has **no** JPA/Spring Data — entity definitions live in their owning modules

## Key Technical Constraints

- **Java 21 virtual threads** are enabled for Tomcat + Spring MVC — avoid `ThreadLocal` patterns that assume platform threads
- **MapStruct** is used for DTO mapping with `unmappedTargetPolicy=ERROR` — all fields must be explicitly mapped or ignored
- **valui-common** must remain free of Spring and JPA dependencies to stay importable as a plain library
- Surefire is configured with `--add-opens` for Java 21 reflection; do not remove these flags
