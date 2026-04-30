# Valui v2 — Architecture

> Актуально для ветки `main`. Обновляй при изменении ключевых компонентов.

---

## Обзор

Valui — Telegram-бот для мониторинга спортивных событий у букмекеров.
Пользователь добавляет **контроллер** (URL турнира или спорта), система периодически парсит его
и присылает уведомление при появлении нового события (матч, турнир).

**Архитектурный паттерн**: модульный монолит — 8 Maven-модулей собираются в единый fat JAR.
Модули общаются через Kafka-топики, Spring-события и интерфейсы-порты в `valui-user`.

---

## Стек

| Категория | Технология |
|---|---|
| Язык | Java 21 (virtual threads включены глобально) |
| Фреймворк | Spring Boot 3.3.5 |
| База данных | PostgreSQL 15+ / Hibernate 6 / Flyway 10 |
| Кеш / dedup | Redis (Lettuce, пул 16 соединений) |
| Очередь | Apache Kafka 3.x (9 топиков) |
| HTTP-клиент | Spring WebFlux WebClient |
| WebSocket | Netty (BetBoom, Protobuf 3.25) |
| Отказоустойчивость | Resilience4j 2.2 (CircuitBreaker + Retry + TimeLimiter) |
| Метрики | Micrometer + Prometheus (`/actuator/prometheus`) |
| Безопасность | Spring Security 6, JJWT 0.12 (HS256) |
| Telegram SDK | TelegramBots 6.9.7.1 |
| Тесты | JUnit 5, Mockito, Testcontainers, WireMock 3.3 |
| Сборка | Maven + Lombok 1.18.34 + MapStruct 1.5.5 |

---

## Структура модулей

```
valui-v2/
├── valui-app        — точка входа, Kafka-топики, логирование
├── valui-common     — JPA entities, Kafka records, exceptions, @Audit
├── valui-bot        — Telegram-бот: команды, wizard, клавиатуры, webhook
├── valui-user       — пользователи, подписки, токены, репозитории, аудит
├── valui-parser     — 5 парсеров букмекеров: HTTP + WebSocket + Protobuf
├── valui-monitor    — планировщик опросов, dedup, outbox, Kafka-продюсер
├── valui-notify     — Kafka-консюмер, отправка уведомлений, retry-лесенка
└── valui-admin      — REST API, JWT-аутентификация, платежи, DLQ-replay
```

Только `valui-app` содержит `spring-boot-maven-plugin` и собирает fat JAR.

---

## Модули — детали

### valui-common
Нет Spring-бинов. Только разделяемые типы.

**JPA Entities (15):**
`UserEntity`, `SubscriptionEntity`, `SubscriptionPlanEntity`, `ControllerEntity`,
`DetectedEventEntity`, `NotificationLogEntity`, `ControllerSubscriptionEntity`,
`GlobalFilterEntity`, `TokenTransactionEntity`, `TokenPackEntity`, `TokenActionCostEntity`,
`UserBkSlotEntity`, `AuditLogEntity`, `AuditFallbackEntity`, `PaymentTransactionEntity`

**Domain enums:** `BookmakerType`, `ControllerType`, `NotificationStatus`, `TokenReasonCode` и др.

**Kafka records:** `SportEventDetectedMessage`, `UserNotificationRequestMessage`,
`SubscriptionChangedMessage`, `AuditEntryMessage`

**Константы топиков:** `KafkaTopics` (9 констант)

**Аннотации:** `@Audit` — методы с ней перехватываются `AuditAspect` в `valui-user`

---

### valui-user
Все Spring Data JPA репозитории (15 штук) и основные сервисы.
Остальные модули работают с данными только через интерфейсы-порты:
- `ControllerPortService` — CRUD контроллеров, управление подписками
- `DetectedEventPortService` — хранение обнаруженных событий

| Сервис | Что делает |
|---|---|
| `UserService` | CRUD, кеш по `telegramId` (`@Cacheable`) |
| `SubscriptionService` | Создание/обновление/проверка подписок |
| `TokenLedgerService` | Списание/зачисление токенов, проверка баланса; бросает `InsufficientTokensException` |
| `PlanLimitFacade` | `debitForBkSlotIfNew()`, `debitForControllerFilter()`, `getLimitInfo()` |
| `GlobalFilterService` | Regex-фильтры уведомлений пользователя |
| `MonthlyTokenBillingScheduler` | Ежемесячное начисление токенов (`@Scheduled cron`) |
| `SubscriptionExpiryScheduler` | Проверка истёкших подписок → `SubscriptionExpiredEvent` |
| `AuditAspect` | Перехват `@Audit` → `AuditService` → Kafka `audit.log` → `AuditLogEntity` |

---

### valui-parser
Единый интерфейс `BookmakerParser`:
```java
ParseResult<List<SportDto>>       fetchSports();
ParseResult<List<TournamentDto>>  fetchTournaments(String sportId);
ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId);
```

`ParseResult` имеет три состояния: `success`, `error`, `unavailable` (CB открыт).

| Букмекер | Протокол | Особенности |
|---|---|---|
| 1xBet | HTTPS REST | `SocksBookmakerHttpClient` (JDK HttpClient для JA3), опциональный SOCKS-прокси |
| Fonbet | HTTPS REST | CDN-зеркала `bk6bba-resources.com`, буфер **50 МБ** (большой ответ) |
| Olimp | HTTPS REST | Стандартный WebClient |
| BetCity | HTTPS REST | Маппинг ID спортов через `BetcitySportsMap` |
| BetBoom | WebSocket + Protobuf | Пул WS-соединений `WsClientBorrowingPool` (min 2, max 6), warmup при старте, backoff 300ms→10s |

**Кеш парсеров** (`CachedBookmakerParser`): спорты 1ч / турниры 30мин / матчи 5мин.

**Circuit breakers** (Resilience4j): `xbet-cb`, `fonbet-cb`, `olimp-cb`, `betcity-cb`, `betboom-cb`.
Порог: 50% ошибок, скользящее окно 10 запросов. При срабатывании парсер возвращает `ParseResult.unavailable()`.

---

### valui-monitor
Планирует и выполняет опросы контроллеров в virtual threads.

**Поток выполнения одного тика (`ControllerTask`):**
```
MonitorScheduler (@Scheduled)
  └── ControllerTask (VirtualThread, per controller)
        ├── TX1: loadContext(controllerId)     — загрузить URL, букмекер, тип
        ├── [no TX]: fetch(ctx)                — HTTP/WS → парсер
        └── TX2: persistNewEvents(ctx, fetched)
              ├── isFirstRun = (lastCheckedAt == null)
              ├── for item: dedup.claimIfNew(controllerId, item.id())  — Redis SADD атомарно
              ├── if isFirstRun  → только пометить виденными, не уведомлять (warmup)
              └── if !isFirstRun && new → DetectedEventEntity + outbox row + Spring event
```

**Concurrency guards:**
- `globalSemaphore` — системный лимит (`monitor.max-concurrent-tasks`, default 50)
- `perUserCounter` — лимит на пользователя (`monitor.max-tasks-per-user`, default 5)

**Дедупликация** (`EventDeduplicationService`):
- Redis SET `dedup:ctrl:{controllerId}` с TTL = `dedupTtlDays`
- `seedIfAbsent()` — восстанавливает из `detected_events` при рестарте (когда Redis пустой)
- `DedupSyncScheduler` — ночная сверка Redis с БД (удаляет призраков, добавляет пропущенных)

**Transactional outbox** (`OutboxSenderService`):
- Событие пишется в `outbox_events` в той же транзакции, что и `detected_events`
- `@Scheduled` каждые 5 сек отправляет несохранённые строки в Kafka
- Гарантирует at-least-once даже при падении Kafka

**Остановка контроллера** (`stopForChat` / `stopAllForUser` / `stopAllForUserInChat`):
```
removeSubscription(controllerId, chatId)
if !hasActiveSubscriptions:
  updateIsActive(controllerId, false)   ← обязательно, иначе останется в листингах
  dedup.clearController()
  updateLastCheckedAt(null)             ← сброс warmup при повторном добавлении
  monitorScheduler.unscheduleController()
```

---

### valui-notify
Kafka-консюмер. Принимает `sport.events.detected`, фильтрует по подпискам и фильтрам,
отправляет уведомления, обрабатывает ошибки через retry-лесенку.

**Retry-лесенка:**
```
user.notifications.pending
  → TelegramNotificationSender
      ✓ → NotificationLog(SENT)
      ✗ → notifications.retry.1s   → sleep 1s  → retry
              ✗ → notifications.retry.5s   → sleep 5s  → retry
                      ✗ → notifications.retry.30s  → sleep 30s → retry
                              ✗ → notifications.dlq        → sleep 5min → retry
                                      ✗ → notifications.dlq.final  → DlqMonitor → admin alert
```

**Rate limiting**: Redis ZSet sliding window, 1 сообщение/сек на `userId`.

---

### valui-bot
Telegram-бот. Два режима: `long_polling` (dev) и `webhook` (prod).

**Маршрутизация обновлений (`CommandRouter`):**
```
Update (text/callback/message)
  ├── CommandHandler   — команды (/start, /list, /add, /help, /info, /language, ...)
  ├── CallbackHandler  — нажатия inline-кнопок (18 обработчиков)
  └── BotUpdateHandler — текст в wizard, кнопки ReplyKeyboard
```

**Wizard добавления контроллера** (FSM через `BotState` + `UserBotSession` в Redis):
```
SELECTING_BOOKMAKER
  → SELECTING_SPORT
  → SELECTING_TOURNAMENT
  → WAITING_FILTER_RULE (опционально)
  → WAITING_CONFIRM_CREATE
```

**Ошибки токенов** в callback-обработчиках:
- `InsufficientTokensException` → `MessageSend.answerCallbackWithModal()` (`showAlert=true`)
- Модальное окно показывает: `"Нужно: {required} • Баланс: {available}\n\nПополните: /plans"`
- Сессия **не сбрасывается** — пользователь может повторить попытку после пополнения

**QuickAdd** (`QuickAddControllerCallback`):
- Кнопка «➕ Следить» в уведомлении
- Данные 24ч хранятся в Redis (`quickadd:{key}`)
- Добавляет контроллер без wizard за одно нажатие

**Webhook-режим:**
- `TelegramIpFilter` — whitelist Telegram IP (149.154.0.0/16, 91.108.0.0/16)
- `WebhookRegistrar` — регистрирует URL при старте приложения

**Локализация**: RU + EN через `BotMessageSource` (`messages_ru.properties` / `messages_en.properties`)

---

### valui-admin
REST API для управления системой. Защищён JWT.

| Группа | Эндпоинты |
|---|---|
| Auth | `POST /auth/login`, `POST /auth/refresh` |
| Users | `GET/POST/PUT /admin/users`, `GET /admin/users/{id}/subscription` |
| Controllers | `GET /admin/controllers` |
| DLQ | `GET /admin/dlq`, `POST /admin/dlq/replay` |
| Stats | `GET /admin/stats` |
| Payment | `POST /payment/create`, `POST /payment/webhook` |

**JWT**: access-token TTL 1ч, refresh-token 30д (хранится в Redis).

**Шлюзы оплаты**: `StubPaymentGateway` (dev) и `YookassaPaymentGateway` (prod),
переключаются через `payment.gateway=yookassa`.

---

## База данных

### Схема (17 миграций Flyway)

```
users (id, telegram_id UNIQUE, role, status, language_code)
  ├── subscriptions        (user_id, plan_id, status, expires_at)
  │     └── subscription_plans (code, max_controllers, max_filters,
  │                             allowed_bookmakers, price_rub, token_reward_monthly)
  ├── controllers (user_id, bookmaker, url, type ENUM, filter_rule,
  │               is_active, poll_interval_sec, last_checked_at, notification_chat_id)
  │     ├── controller_subscriptions (controller_id, chat_id PK,
  │     │                            user_id, is_muted, paused_by_tokens)
  │     └── detected_events (controller_id, event_external_id UNIQUE,
  │                          title, url, extra_data JSONB)
  ├── global_filters       (user_id, regex_pattern, is_active)
  ├── token_transactions   (user_id, amount, balance_after, reason_code)
  ├── user_bk_slots        (user_id, bookmaker, slot_count)
  ├── audit_log            (user_id, action, entity_type, ip_address INET, details JSONB)
  └── payment_transactions (user_id, amount, status, provider_ref)

outbox_events  (topic, message_key, chat_id, created_at, sent_at, retry_count)
notification_log (user_id, event_id, channel, status, attempts, sent_at)
audit_fallback  (user_id, action, details)  ← fallback при недоступности Kafka
```

| Миграция | Что добавляет |
|---|---|
| V1 | Основная схема (users, controllers, detected_events, notification_log, audit_log) |
| V2 | Seed планов подписки (FREE, PRO, PREMIUM) |
| V3 | Индексы на часто запрашиваемых полях |
| V4 | `payment_transactions` |
| V7 | `global_filters` |
| V8 | `outbox_events` (transactional outbox) |
| V9 | `audit_fallback` |
| V10 | `notification_chat_id` в `controllers` |
| V13 | `token_packs`, `token_transactions`, `token_action_costs` |
| V14 | `user_bk_slots` |
| V16 | `controller_subscriptions` |

---

## Kafka

| Топик | Продюсер | Консюмер |
|---|---|---|
| `sport.events.detected` | `SportEventKafkaProducer` (monitor) | `SportEventConsumer` (notify) |
| `user.notifications.pending` | `SportEventConsumer` | `NotificationDispatcher` |
| `subscription.events` | `SubscriptionService` (user) | `SubscriptionExpiredBotListener` (bot) |
| `audit.log` | `AuditService` (user) | `AuditLogConsumer` (notify, только запись) |
| `notifications.retry.1s/5s/30s` | `DeadLetterPublisher` | `RetryTopicConsumer` |
| `notifications.dlq` | `DeadLetterPublisher` | `DlqConsumer` |
| `notifications.dlq.final` | `DlqConsumer` | `DlqMonitor` + admin replay |

**Гарантии**: acks=all, идемпотентный продюсер, transactional outbox → at-least-once.

---

## Redis — структура ключей

| Ключ | Тип | TTL | Содержимое |
|---|---|---|---|
| `session:{userId}` | Hash | 30мин | FSM-состояние wizard (BotState + контекст) |
| `wizard:sports:{userId}` | String | 10мин | Кеш спортов для wizard |
| `wizard:tournaments:{userId}` | String | 10мин | Кеш турниров для wizard |
| `dedup:ctrl:{controllerId}` | Set | dedupTtlDays | ID виденных событий |
| `quickadd:{key}` | String | 24ч | url/bookmaker/title для QuickAdd |
| `ratelimit:{userId}` | ZSet | 1с | Sliding window для rate limiting |
| `refresh:{tokenId}` | String | 30д | JWT refresh-токен |

---

## Профили Spring

| Профиль | Использование | DB | Redis | Kafka |
|---|---|---|---|---|
| `local` | Локальная разработка | localhost:5432 | localhost:6379 | localhost:29092 |
| `server-test` | Приложение локально + инфра на сервере | localhost:5433 | localhost:6380 | SERVER_IP:29093 |
| `docker` | Prod в контейнерах | postgres:5432 | redis:6379 | kafka:9092 |
| `prod` | Prod на сервере (без контейнера) | `${POSTGRES_HOST}` | `${REDIS_HOST}` | `${KAFKA_BOOTSTRAP_SERVERS}` |

---

## Ключевые переменные окружения

```bash
TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME
BOT_MODE=webhook|long_polling
BOT_WEBHOOK_URL, BOT_SECRET_TOKEN

POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
REDIS_HOST, REDIS_PORT, REDIS_PASSWORD
KAFKA_BOOTSTRAP_SERVERS

JWT_SECRET                   # base64, минимум 256 бит
BOT_AUTH_SECRET
ADMIN_API_KEY

PAYMENT_GATEWAY=stub|yookassa
YOOKASSA_SHOP_ID, YOOKASSA_SECRET_KEY

# Прокси для парсеров (опционально)
PROXY_ENABLED, PROXY_HOST, PROXY_PORT, PROXY_USERNAME, PROXY_PASSWORD
```

---

## Потоки данных

### Добавление контроллера
```
User → /add (wizard)
  → ControllerServiceImpl.addController()
      ├── PlanLimitFacade.debitForBkSlotIfNew()     — списать токен
      ├── ControllerRepository.save()               — сохранить
      ├── ControllerPortService.createSubscription() — подписка chatId
      └── MonitorScheduler.scheduleController()     — добавить в планировщик
```

### Обнаружение и доставка события
```
MonitorScheduler (каждые pollIntervalSec)
  → BookmakerParser.fetchMatches/fetchTournaments()
  → dedup.claimIfNew() [Redis]
  → DetectedEventEntity.save() + outbox row (одна TX)
  → OutboxSenderService → Kafka sport.events.detected
  → SportEventConsumer → apply global filters → user.notifications.pending
  → TelegramNotificationSender → Telegram Bot API
  → NotificationLog(SENT)
```

### Первый тик (warmup)
```
lastCheckedAt == null → isFirstRun = true
  → все найденные события помечаются в Redis как виденные
  → в БД НЕ пишутся, уведомления НЕ отправляются
  → lastCheckedAt проставляется → следующий тик будет обычным
```

---

## Тестирование

| Тип | Инструменты | Где |
|---|---|---|
| Unit | JUnit 5 + Mockito | Все модули |
| Integration (DB) | Testcontainers PostgreSQL | `valui-user`, `valui-monitor` |
| Integration (Kafka) | Testcontainers Kafka / EmbeddedKafka | `valui-notify`, `valui-monitor` |
| HTTP Mock | WireMock 3.3 | `valui-parser` |

```bash
mvn test           # только unit
mvn verify         # unit + integration (требует Docker)
mvn test -pl valui-parser   # только тесты парсеров
```
