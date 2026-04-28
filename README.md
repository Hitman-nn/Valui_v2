# Valui v2.0 — Modular Monolith Telegram Betting Monitor

Telegram-бот мониторинга ставок. Переписан как **Modular Monolith** на Spring Boot 3.3 / Java 21
с Kafka-pipeline для at-least-once доставки уведомлений.

---

## Архитектура

```
┌─────────────────────────────── valui-app (fat JAR) ──────────────────────────────────┐
│                                                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────────┐ │
│  │  valui-bot   │  │  valui-user  │  │  valui-admin │  │      valui-notify       │ │
│  │  (Telegram)  │  │  (JPA/Redis) │  │  (REST API)  │  │  SportEventConsumer     │ │
│  └──────┬───────┘  └──────────────┘  └──────────────┘  │  NotificationDispatcher │ │
│         │                                               │  DlqConsumer            │ │
│         └──────────────────┐          ┌────────────────-└─────────────────────────┘ │
│                            ▼          ▼                                              │
│                     ┌─────────────────────┐                                         │
│                     │     valui-common     │                                         │
│                     │  Kafka records, DTOs │                                         │
│                     │  domain enums        │                                         │
│                     └─────────────────────┘                                         │
│                                  ▲                                                   │
│  ┌──────────────┐  ┌─────────────┴────┐                                             │
│  │ valui-monitor│─▶│  valui-parser    │                                             │
│  │  Scheduler   │  │ XBet/Fonbet/Olimp│                                             │
│  │  Outbox      │  │ BetCity/BetBoom  │                                             │
│  └──────┬───────┘  └──────────────────┘                                             │
│         │                                                                             │
└─────────┼─────────────────────────────────────────────────────────────────────────--┘
          │  Kafka: sport.events.detected
          └──────────────────────────────────────────▶ valui-notify
```

### Kafka-поток

```
BookmakerParser
     │
     ▼
ControllerTaskExecutor [TX]
     ├── DetectedEvent → PostgreSQL
     └── OutboxEvent   → PostgreSQL (outbox_events)
                              │
              @TransactionalEventListener(AFTER_COMMIT)
                              ▼
                 OutboxSenderService.publishImmediate()
                         │              ↑
             Kafka send  │    @Scheduled(5s) retry unsent
                         ▼
          ┌─── sport.events.detected ───┐
          │                             │
          ▼                             │ (at-least-once)
   SportEventConsumer                  │
   (valui-notify)                      │
   ├── filter: isActive/!muted/ACTIVE  │
   ├── filterRule regex                │
   ├── notification_log PENDING        │
   └── user.notifications.pending ─────┘

   NotificationDispatcher
   ├── TELEGRAM → TelegramNotificationSender
   │              └── Redis rate-limit 1msg/s
   ├── EMAIL    → EmailNotificationSender (stub)
   └── WEBHOOK  → WebhookNotificationSender (stub)
        │ failure
        ▼
   notifications.dlq
        │
        ▼
   DlqConsumer: 3 retry (1s→5s→30s backoff)
   └── после 3 попыток: FAILED + ALERT
```

---

## Групповые чаты

Бот работает в Telegram-группах. В группе контроллеры привязаны к чату, а не к личному ID пользователя.

### Разделение контекста

`BotUpdateContext` разделяет два ID:

| Поле | Значение | Используется для |
|---|---|---|
| `chatId` | ID группы (< 0) или личного чата | Назначение уведомлений, группа |
| `fromId` | Всегда личный ID пользователя Telegram | Сессия, тариф, токены |

### Квота группы

```
Базовая квота:  3 слота (бесплатно для каждой группы)
Расширение:     +1 слот за каждый вложенный токен
Личная квота:   по тарифному плану пользователя
Итог:           оба ограничения проверяются независимо
```

При истечении подписки токены пользователя отзываются: квота группы уменьшается, лишние контроллеры деактивируются (сначала контроллеры самого пользователя).

---

## Токены

| Тарифный план | Токены при активации (одноразово) |
|---|---|
| FREE | 0 |
| PRO | 50 |
| PREMIUM | 300 |

Токены хранятся в поле `token_balance` пользователя. Тратятся через кнопку **BOOST** в группе.
При истечении подписки баланс токенов обнуляется, а все вложенные в группы токены отзываются.

---

## Модули

| Модуль | Назначение | Ключевые зависимости |
|---|---|---|
| `valui-common` | Kafka-records, DTOs, domain enums — без Spring | jakarta.* only |
| `valui-bot` | Telegram FSM, команды, inline-клавиатуры | spring-boot, telegrambots, redis |
| `valui-user` | Пользователи, подписки, JPA, Flyway | spring-data-jpa, spring-security, redis |
| `valui-parser` | HTTP + WebSocket парсеры букмекеров | spring-webflux, resilience4j, protobuf |
| `valui-monitor` | Планировщик, дедупликация, Kafka outbox | spring-kafka (producer), valui-parser |
| `valui-notify` | Kafka consumers, фильтрация, отправка | spring-kafka (consumer), valui-bot, redis |
| `valui-admin` | REST API администрирования | spring-web, spring-security, springdoc |
| `valui-app` | Spring Boot entrypoint, fat JAR, Flyway migrations | все модули |

---

## Технологический стек

- **Java 21** (Virtual Threads — планировщик + DLQ backoff)
- **Spring Boot 3.3.5**
- **Apache Kafka** — 5 топиков, transactional outbox, DLQ
- **PostgreSQL 15+** + **Flyway** (V1–V12 миграции)
- **Redis** — dedup SET, Kafka outbox retry, Telegram rate-limit, парсер-кэш
- **Resilience4j** — Circuit Breaker + Retry на всех парсерах
- **Micrometer → Prometheus** — метрики мониторинга и Kafka producer
- **Lombok 1.18.34** + **MapStruct 1.5.5**
- **Testcontainers** (PostgreSQL + Kafka integration tests)
- **telegrambots 6.9.7.1**

---

## Быстрый старт

### Предусловия

| Инструмент | Версия |
|---|---|
| JDK | 21+ |
| Maven | 3.9+ |
| Docker + Docker Compose | 24+ |

### 1. Клонировать и поднять инфраструктуру

```bash
git clone <repo-url>
cd Valui_v2
docker compose up -d postgres redis kafka
```

Проверить, что контейнеры запустились:

```bash
docker compose ps
# postgres, redis, kafka — все Up
```

### 2. Переменные окружения

Минимально необходимые для локального запуска:

```bash
# Telegram
export VALUI_BOT_TOKEN=123456789:AABBcc...
export VALUI_BOT_USERNAME=YourBotName

# Прокси для XBet (если нужен парсинг XBet)
export PARSER_PROXY_HOST=91.147.122.69
export PARSER_PROXY_PORT=4232
export PARSER_PROXY_USERNAME=user283146
export PARSER_PROXY_PASSWORD=0w3qzb
```

В `docker` и `prod`-профилях добавить:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/valui
export SPRING_DATASOURCE_USERNAME=valui
export SPRING_DATASOURCE_PASSWORD=secret

export SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
export SPRING_DATA_REDIS_HOST=redis

# Prod — 3 репликации топиков
export KAFKA_TOPICS_REPLICATION_FACTOR=3
```

### 3. Собрать и запустить

```bash
# Сборка fat JAR (пропустить тесты для скорости)
mvn clean package -pl valui-app -am -DskipTests

# Запуск с профилем local (localhost-инфраструктура из docker-compose)
java -jar valui-app/target/valui-app-2.0.0-SNAPSHOT.jar \
     --spring.profiles.active=local
```

### 4. Запустить тесты

```bash
# Все модули
mvn test

# Конкретный модуль
mvn test -pl valui-notify

# Возобновить с упавшего модуля
mvn test -rf :valui-notify
```

### 5. Swagger UI

```
http://localhost:8080/swagger-ui.html
```

### 6. Actuator / Метрики

```
http://localhost:8080/actuator/health
http://localhost:8080/actuator/metrics
http://localhost:8080/actuator/prometheus
```

---

## docker-compose.yml (пример)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: valui
      POSTGRES_USER: valui
      POSTGRES_PASSWORD: secret
    ports:
      - "5432:5432"
    volumes:
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  kafka:
    image: confluentinc/cp-kafka:7.7.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
    ports:
      - "9092:9092"

volumes:
  pg_data:
```

---

## Конфигурация по профилям

| Профиль | Когда использовать | Где задавать |
|---|---|---|
| `local` | Разработка на localhost | `application.yml` defaults |
| `docker` | Docker Compose / K8s staging | env vars + `application.yml` docker-блок |
| `prod` | Production | **только** env vars, никаких секретов в YML |

Активация: `--spring.profiles.active=local` или `SPRING_PROFILES_ACTIVE=docker`.

### Полный список env vars для prod

| Переменная | Описание | Обязательна |
|---|---|---|
| `VALUI_BOT_TOKEN` | Telegram Bot API token | ✅ |
| `VALUI_BOT_USERNAME` | Имя бота (без @) | ✅ |
| `SPRING_DATASOURCE_URL` | JDBC URL PostgreSQL | ✅ |
| `SPRING_DATASOURCE_USERNAME` | Пользователь БД | ✅ |
| `SPRING_DATASOURCE_PASSWORD` | Пароль БД | ✅ |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker адреса | ✅ |
| `SPRING_DATA_REDIS_HOST` | Redis хост | ✅ |
| `SPRING_DATA_REDIS_PASSWORD` | Redis пароль (если есть) | — |
| `KAFKA_TOPICS_REPLICATION_FACTOR` | Репликация топиков (3 для prod) | — (default 1) |
| `PARSER_PROXY_HOST` | HTTP-прокси для XBet | Если нужен XBet |
| `PARSER_PROXY_PORT` | Порт прокси | Если нужен XBet |
| `PARSER_PROXY_USERNAME` | Логин прокси | Если нужен XBet |
| `PARSER_PROXY_PASSWORD` | Пароль прокси | Если нужен XBet |
| `VALUI_BOT_PROXY_ENABLED` | Включить SOCKS5 для Telegram | Если заблокирован |
| `VALUI_BOT_PROXY_HOST` | SOCKS5 хост для Telegram | — |
| `VALUI_BOT_PROXY_PORT` | SOCKS5 порт для Telegram | — |
| `VALUI_BOT_MODE` | `long_polling` или `webhook` | — (default long_polling) |
| `VALUI_BOT_WEBHOOK_URL` | URL вебхука (если webhook-режим) | Если mode=webhook |

---

## Структура директорий

```
Valui_v2/
├── pom.xml                           # valui-parent (root BOM)
├── docker-compose.yml
├── docs/
│   └── ARCHITECTURE.md
│
├── valui-common/
│   └── src/main/java/com/valui/common/
│       ├── domain/    (BookmakerType, UserStatus, ControllerType)
│       ├── entity/    (UserEntity, ControllerEntity, DetectedEventEntity,
│       │               GroupChatQuotaEntity, GroupTokenContributionEntity)
│       └── kafka/     (SportEventDetectedMessage, UserNotificationRequestMessage, ...)
│
├── valui-monitor/
│   └── src/main/java/com/valui/monitor/
│       ├── scheduler/  (MonitorScheduler, ControllerTask, ControllerTaskExecutor)
│       ├── dedup/      (EventDeduplicationService, DedupSyncScheduler)
│       ├── kafka/      (SportEventKafkaProducer, SportEventMapper, KafkaProducerMetrics)
│       └── outbox/     (OutboxEvent, OutboxEventRepository, OutboxSenderService)
│
├── valui-notify/
│   └── src/main/java/com/valui/notify/
│       ├── config/     (KafkaConsumerConfig — 4 factories)
│       ├── consumer/   (SportEventConsumer, DlqConsumer)
│       ├── dispatcher/ (NotificationDispatcher, NotificationDispatchService)
│       ├── sender/     (TelegramNotificationSender, EmailNotificationSender, ...)
│       ├── formatter/  (NotificationFormatter)
│       ├── ratelimit/  (TelegramRateLimiter)
│       └── log/        (NotificationLogEntity, NotificationLogRepository, NotificationLogService)
│
├── valui-parser/
│   └── src/main/java/com/valui/parser/
│       ├── api/            (BookmakerParser interface, ParseResult)
│       └── bookmaker/
│           ├── fonbet/     (FonbetParser, FonbetEndpointPool)
│           ├── xbet/       (XBetParser)
│           ├── olimp/      (OlimpParser)
│           ├── betcity/    (BetCityParser)
│           └── betboom/    (BetBoomParser, WsClientBorrowingPool)
│
├── valui-user/
│   └── src/main/java/com/valui/user/
│       ├── repository/ (UserRepository, ControllerRepository,
│       │               GroupChatQuotaRepository, GroupTokenContributionRepository)
│       ├── service/    (GroupQuotaService, PlanLimitChecker, SubscriptionServiceImpl)
│       └── dto/        (GroupStatusDto, LimitInfoDto, SubscriptionPlanDto)
│
├── valui-app/
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/
│           ├── V1__init_schema.sql
│           ├── V2__seed_plans.sql
│           ├── V3__indexes.sql
│           ├── V4__add_payment_transactions.sql
│           ├── V5__free_plan_all_bookmakers.sql
│           ├── V6__fix_extra_data_type.sql
│           ├── V7__add_global_filters.sql
│           ├── V8__add_outbox_events.sql
│           ├── V9__audit_log.sql
│           ├── V10__add_notification_chat_id.sql  (controllers.notification_chat_id)
│           ├── V11__add_group_quota_tables.sql     (users.token_balance, group_chat_quota,
│           │                                        group_token_contribution)
│           └── V12__add_token_reward_to_plans.sql  (subscription_plans.token_reward)
│
└── valui-bot/
    └── src/main/java/com/valui/bot/
        ├── handler/
        │   ├── command/   (StartCommandHandler, AddCommandHandler, ListCommandHandler,
        │   │               InfoCommandHandler, ...)
        │   ├── callback/  (QuickAddControllerCallback, ControllerConfirmCallback,
        │   │               GroupBoostCallback, ...)
        │   └── message/   (MenuButtonHandler, WizardTextHandler)
        ├── keyboard/
        │   └── menu/      (MainMenuKeyboard — group-aware BOOST row)
        ├── state/         (UserBotSession, BotState FSM)
        └── webhook/       (ValuiWebhookBot, WebhookController)
```

---

## Добавление нового букмекера

1. Создать пакет `com.valui.parser.bookmaker.<name>/`
2. Реализовать `BookmakerParser` и пометить `@Component`
3. Добавить значение в `BookmakerType`
4. Написать unit-тест с mock HTTP-ответом

Spring автоматически подхватит через `List<BookmakerParser>` в `ParserFactory`.

---

## Структура меню бота

### Команды

| Команда | Описание |
|---|---|
| `/start` | Регистрация / приветствие, сброс FSM-сессии |
| `/add` | Запуск мастера добавления контроллера |
| `/list` | Список активных контроллеров (в группе — контроллеры группы) |
| `/listfilter` | Список настроенных фильтров |
| `/info` | Личный кабинет (тариф + токены) или статус группы (квота + вкладчики) |
| `/stop` | Остановить все активные контроллеры |
| `/deleteall` | Удалить всё (подтверждение «ДА») |
| `/language` | Сменить язык (RU / EN) |
| `/help` | Справка |

### Кнопки reply-клавиатуры

В **личном чате** отображаются: добавить, список, фильтры, тариф, помощь, язык.

В **групповом чате** добавляется строка с кнопками **"🚀 Расширить квоту группы"** и **"📊 Статус группы"**.

### FSM-состояния

```
IDLE → SELECTING_BOOKMAKER → SELECTING_SPORT
     → SELECTING_TOURNAMENT → WAITING_FILTER_RULE
     → WAITING_CONFIRM_CREATE → IDLE

IDLE → WAITING_BOOST_AMOUNT → IDLE   (только в группе, кнопка BOOST)
```

Сессия хранится в Redis (TTL 30 мин).

### Поведение /info

| Контекст | Что показывает |
|---|---|
| Личный чат | Текущий тариф, лимиты, баланс токенов, дата истечения подписки |
| Групповой чат | Квота группы, активных контроллеров, список вкладчиков с токенами |

### Формат уведомления

```
🔔 *FONBET*
Spartak Moscow - CSKA Moscow
https://fonbet.ru/sports/football/12345
```
