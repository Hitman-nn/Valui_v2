# Valui v2.0 — Modular Monolith Telegram Betting Monitor

Telegram-бот мониторинга ставок. Переписан как **Modular Monolith** на Spring Boot 3.3 / Java 21,
с чёткими границами между модулями и асинхронным обменом событиями через Kafka.

---

## Архитектура

```
┌─────────────────────────────── valui-app (fat JAR) ───────────────────────────────┐
│                                                                                    │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │  valui-bot   │   │  valui-user  │   │  valui-admin │   │  valui-notify    │  │
│  │  (Telegram)  │   │  (JPA/Redis) │   │  (REST API)  │   │  (Kafka consumer)│  │
│  └──────┬───────┘   └──────────────┘   └──────────────┘   └────────┬─────────┘  │
│         │                                                             │            │
│         └─────────────────────┐          ┌──────────────────────────┘            │
│                               ▼          ▼                                        │
│                        ┌─────────────────────┐                                   │
│                        │    valui-common      │                                   │
│                        │  (DTOs, Events,      │                                   │
│                        │   Exceptions, Enums) │                                   │
│                        └─────────────────────┘                                   │
│                                   ▲                                               │
│  ┌──────────────┐   ┌─────────────┴────┐                                         │
│  │ valui-monitor│──▶│  valui-parser    │                                         │
│  │ (@Scheduled) │   │ (1xBet, Fonbet,  │                                         │
│  └──────┬───────┘   │  Olimp, BetCity, │                                         │
│         │           │  BetBoom)        │                                         │
│         │           └──────────────────┘                                         │
│         │                                                                          │
│         ▼ Kafka: valui.match.discovered                                           │
│    ┌──────────┐                                                                   │
│    │  Kafka   │ ─────────────────────────────────────────────▶ valui-notify      │
│    └──────────┘                                                                   │
└────────────────────────────────────────────────────────────────────────────────────┘
```

### Поток данных

```
BookmakerParser → MonitorScheduler → KafkaProducer
                                           │
                                           ▼  (topic: valui.match.discovered)
                                    MatchEventConsumer
                                           │
                                           ▼
                              NotificationDispatcher
                                           │
                                           ▼
                                    valui-bot (Telegram send)
```

---

## Модули

| Модуль | Назначение | Ключевые зависимости |
|---|---|---|
| `valui-common` | Shared kernel: DTOs, Events, Exceptions, Enums | jakarta.* only, no Spring |
| `valui-bot` | Telegram bot: команды, обработка updates | spring-boot, telegrambots, redis |
| `valui-user` | Пользователи, подписки, auth | spring-data-jpa, spring-security, redis, flyway |
| `valui-parser` | Парсеры букмекеров (HTTP + WebSocket) | spring-web, spring-webflux, websocket, redis |
| `valui-monitor` | Планировщик опросов, детектор новых матчей | spring-kafka (producer), valui-parser |
| `valui-notify` | Kafka consumer, фильтрация, отправка уведомлений | spring-kafka (consumer), valui-bot, valui-user |
| `valui-admin` | REST API для администрирования | spring-web, spring-security, springdoc |
| `valui-app` | Spring Boot entrypoint, fat JAR | все модули |

---

## Технологический стек

- **Java 21** (Virtual Threads ready)
- **Spring Boot 3.3.5**
- **PostgreSQL** + **Flyway** (миграции)
- **Redis** (кэш парсеров, rate-limit бота)
- **Apache Kafka** (async events между monitor и notify)
- **Lombok 1.18.34** + **MapStruct 1.5.5**
- **Springdoc OpenAPI 2.6.0** (Swagger UI на `/swagger-ui.html`)
- **Testcontainers** (integration tests)

---

## Быстрый старт

### Предусловия

- JDK 21
- Maven 3.9+
- Docker + Docker Compose

### 1. Поднять инфраструктуру

```bash
docker compose up -d postgres redis kafka
```

### 2. Задать переменные окружения

```bash
export TELEGRAM_BOT_TOKEN=your_token_here
export TELEGRAM_BOT_USERNAME=YourBotName
```

### 3. Собрать и запустить

```bash
# Сборка fat JAR
mvn clean package -pl valui-app -am

# Запуск с профилем local
java -jar valui-app/target/valui-app-2.0.0-SNAPSHOT.jar \
     --spring.profiles.active=local
```

### 4. Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Конфигурация по профилям

| Профиль | Когда использовать | Где переопределяется |
|---|---|---|
| `local` | Разработка, localhost-инфраструктура | `application.yml` |
| `docker` | docker-compose / K8s staging | `application.yml`, env vars |
| `prod` | Production | исключительно env vars |

Профиль активируется через `--spring.profiles.active=<profile>`
или переменной окружения `SPRING_PROFILES_ACTIVE`.

---

## Структура директорий

```
Valui_v2/
├── pom.xml                          # valui-parent (root)
├── valui-common/
│   └── src/main/java/com/valui/common/
│       ├── domain/   (BookmakerType, MatchStatus)
│       ├── dto/      (MatchDto)
│       ├── event/    (MatchDiscoveredEvent)
│       ├── exception/(ValuiException, ...)
│       └── util/
├── valui-bot/
├── valui-user/
│   └── src/main/resources/db/migration/   # Flyway SQL
├── valui-parser/
│   └── src/main/java/com/valui/parser/
│       ├── api/           (BookmakerParser interface)
│       └── bookmaker/
│           ├── xbet/
│           ├── fonbet/
│           ├── olimp/
│           ├── betcity/
│           └── betboom/
├── valui-monitor/
├── valui-notify/
├── valui-admin/
└── valui-app/
    └── src/main/resources/application.yml
```

---

## Добавление нового букмекера

1. Создать пакет `com.valui.parser.bookmaker.<name>/`
2. Реализовать `BookmakerParser` и пометить `@Component`
3. Добавить значение в `BookmakerType`
4. Написать unit-тест с mock HTTP-ответом

Spring автоматически подхватит новый парсер через `List<BookmakerParser>` в `MonitorScheduler`.
