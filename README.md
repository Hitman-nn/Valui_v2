# Valui v2

Telegram-бот мониторинга спортивных событий у букмекеров.
Модульный монолит на Spring Boot 3.3 / Java 21 с Kafka-pipeline для at-least-once доставки уведомлений.

---

## Быстрый старт

### Требования
- Java 21+
- Docker + Docker Compose

### Запуск (dev)

```bash
# 1. Поднять инфраструктуру
docker-compose up -d

# 2. Создать application-local.yml (или задать env vars)
# Минимум: TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME

# 3. Запустить приложение
./mvnw spring-boot:run -pl valui-app -Dspring-boot.run.profiles=local
```

### Сборка fat JAR

```bash
./mvnw clean package -DskipTests
java -jar valui-app/target/valui-app-*.jar --spring.profiles.active=prod
```

---

## Модули

| Модуль | Назначение |
|---|---|
| `valui-app` | Точка входа, конфигурация Kafka-топиков, логирование |
| `valui-common` | JPA entities, Kafka records, exceptions, `@Audit` |
| `valui-bot` | Telegram-бот: команды, wizard, клавиатуры, webhook |
| `valui-user` | Пользователи, подписки, токены, репозитории, аудит |
| `valui-parser` | Парсеры 5 букмекеров (1xBet, Fonbet, Olimp, BetCity, BetBoom) |
| `valui-monitor` | Планировщик опросов, dedup, outbox, Kafka-продюсер |
| `valui-notify` | Kafka-консюмер, доставка уведомлений, retry-лесенка |
| `valui-admin` | REST API, JWT-аутентификация, платежи (Юкасса), DLQ-replay |

---

## Технологии

```
Java 21 (virtual threads) · Spring Boot 3.3.5 · PostgreSQL 15 · Redis · Apache Kafka 3
Resilience4j 2.2 · Micrometer/Prometheus · Spring Security 6 · JJWT 0.12
TelegramBots 6.9.7 · Spring WebFlux · Netty/Protobuf (BetBoom WS)
Testcontainers · WireMock · Lombok · MapStruct
```

---

## Конфигурация

Все настройки — в `valui-app/src/main/resources/application.yml`.
Профили: `local` (docker-compose), `server-test` (SSH-туннель), `docker`, `prod`.

### Ключевые переменные окружения (prod)

```bash
TELEGRAM_BOT_TOKEN=<токен>
TELEGRAM_BOT_USERNAME=<username>
BOT_MODE=webhook
BOT_WEBHOOK_URL=https://yourdomain.com/webhook
BOT_SECRET_TOKEN=<случайная строка>

POSTGRES_HOST=localhost  POSTGRES_PORT=5432
POSTGRES_DB=valui_db     POSTGRES_USER=valui  POSTGRES_PASSWORD=<пароль>

REDIS_HOST=localhost     REDIS_PORT=6379

KAFKA_BOOTSTRAP_SERVERS=localhost:9092

JWT_SECRET=<base64, минимум 256 бит>
ADMIN_API_KEY=<секрет>

# Оплата (опционально)
PAYMENT_GATEWAY=yookassa
YOOKASSA_SHOP_ID=<id>  YOOKASSA_SECRET_KEY=<ключ>

# Прокси для парсеров (опционально, нужен для 1xBet)
PROXY_ENABLED=true
PROXY_HOST=<host>  PROXY_PORT=1080  PROXY_USERNAME=<user>  PROXY_PASSWORD=<pass>
```

---

## Тесты

```bash
mvn test            # unit-тесты (без Docker)
mvn verify          # все тесты, включая integration (требует Docker)
mvn test -pl valui-parser   # только парсеры
```

---

## Статистика проекта

| Модуль | Файлов (.java) | Продакшн (строк) | Тесты (строк) |
|---|---|---|---|
| valui-common | 52 | 1 347 | 174 |
| valui-bot | 82 | 5 554 | 2 068 |
| valui-user | 49 | 2 340 | 899 |
| valui-parser | 31 | 3 015 | 1 212 |
| valui-monitor | 26 | 2 043 | 1 747 |
| valui-notify | 24 | 1 326 | 1 014 |
| valui-admin | 36 | 1 374 | 370 |
| valui-app | 9 | 580 | 144 |
| **Итого** | **309** | **17 579** | **7 628** |

- Миграции Flyway: **17 файлов**
- Kafka-топиков: **9**
- JPA-entities: **15**
- Букмекеров: **5** (1xBet, Fonbet, Olimp, BetCity, BetBoom)

---

## Документация

- [Архитектура](docs/ARCHITECTURE.md) — модули, потоки данных, DB-схема, Redis, Kafka
