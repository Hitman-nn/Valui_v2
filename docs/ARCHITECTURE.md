# Valui v2 — Developer Architecture Guide

> Актуально для ветки `main`. Обновляй при изменении ключевых компонентов.

---

## Содержание

1. [Обзор системы](#1-обзор-системы)
2. [Модульная структура](#2-модульная-структура)
3. [Жизненный цикл события](#3-жизненный-цикл-события)
4. [Планировщик мониторинга (valui-monitor)](#4-планировщик-мониторинга-valui-monitor)
   - 4.1 [MonitorScheduler](#41-monitorscheduler)
   - 4.2 [ControllerTask](#42-controllertask)
   - 4.3 [ControllerTaskExecutor](#43-controllertaskexecutor)
   - 4.4 [Метрики](#44-метрики)
5. [Дедупликация событий](#5-дедупликация-событий)
   - 5.1 [Зачем Redis, а не только БД](#51-зачем-redis-а-не-только-бд)
   - 5.2 [EventDeduplicationService](#52-eventdeduplicationservice)
   - 5.3 [DedupSyncScheduler — ночная синхронизация](#53-dedupsyncscheduler--ночная-синхронизация)
   - 5.4 [Граничные случаи](#54-граничные-случаи)
6. [Парсеры букмекеров (valui-parser)](#6-парсеры-букмекеров-valui-parser)
   - 6.1 [Fonbet](#61-fonbet)
   - 6.2 [1xBet](#62-1xbet)
   - 6.3 [Olimp](#63-olimp)
   - 6.4 [BetCity](#64-betcity)
   - 6.5 [BetBoom](#65-betboom)
7. [HTTP-клиенты и прокси](#7-http-клиенты-и-прокси)
8. [Сводная таблица парсеров](#8-сводная-таблица-парсеров)
9. [Конфигурация (application.yml)](#9-конфигурация-applicationyml)
10. [Технологический стек](#10-технологический-стек)

---

## 1. Обзор системы

Valui — Telegram-бот, который мониторит линии букмекерских контор и уведомляет
пользователей о появлении новых матчей или турниров по заданным URL.

**Принцип работы в одном предложении:**  
Каждый *controller* — это сохранённый пользователем URL букмекера; планировщик
регулярно опрашивает этот URL через парсер, сравнивает результат с тем, что уже
было видено (дедупликация), и публикует новые события в Kafka → Telegram.

---

## 2. Модульная структура

```
valui-app       ← Fat JAR, точка входа Spring Boot
valui-common    ← Shared DTOs, события, enum BookmakerType (без Spring)
valui-bot       ← Telegram-бот, FSM состояний, inline-клавиатуры
valui-user      ← Пользователи, подписки, JPA-репозитории, Flyway
valui-parser    ← Парсеры HTTP/WebSocket для всех букмекеров
valui-monitor   ← Планировщик + дедупликация + публикация событий
valui-notify    ← Kafka-consumer, фильтрация, отправка уведомлений
valui-admin     ← REST API для административных операций
```

Зависимости текут в одну сторону: `app → monitor/notify/bot → parser/user → common`.
`valui-parser` не зависит от `valui-user` — парсеры не знают ни о пользователях,
ни о контроллерах.

---

## 3. Жизненный цикл события

```
Пользователь в Telegram добавляет URL
          │
          ▼
  valui-bot сохраняет Controller в PostgreSQL
  (bookmaker, url, pollIntervalSec, userId, isActive=true)
          │
          ▼ ApplicationEvent: ControllerAddedEvent
  MonitorScheduler.scheduleController()
  ├── dedup.seedIfAbsent()  ← заполняет Redis из DB (идемпотентно)
  └── triggerPool.scheduleWithFixedDelay(task, 0, pollIntervalSec, SECONDS)
          │
          ▼ каждые pollIntervalSec секунд
  ControllerTask.run() на Virtual Thread
  ├── TX-1 (readOnly): loadContext() — свежий контекст из DB
  ├── fetch() — внешний HTTP/WS вызов (вне транзакции!)
  └── TX-2: persistNewEvents()
       ├── dedup.claimIfNew()  ← Redis SADD (атомарно)
       ├── DetectedEventRepository.save()
       └── ApplicationEventPublisher.publishEvent(SportEventDetectedEvent)
          │
          ▼ внутри той же TX-2
  MonitorEventListener → KafkaTemplate.send("valui.match.discovered")
          │
          ▼
  valui-notify: MatchEventConsumer → NotificationDispatcher → Telegram
```

**Почему HTTP-вызов вне транзакции?**  
Держать транзакцию открытой на время сетевого запроса (сотни мс) означает
удерживать соединение из пула HikariCP. При 50 параллельных задачах это
исчерпало бы пул. Поэтому: TX-1 (загрузка контекста) → закрыть → HTTP → TX-2
(запись результата).

---

## 4. Планировщик мониторинга (valui-monitor)

### 4.1 MonitorScheduler

**Файл:** `valui-monitor/.../scheduler/MonitorScheduler.java`

Центральный компонент. Управляет жизненным циклом всех контроллеров.

**Два пула потоков:**

| Пул | Тип | Размер | Назначение |
|-----|-----|--------|-----------|
| `triggerPool` | Platform threads | `CPU/2`, мин. 2 | Только fires `scheduleWithFixedDelay` — минимальная работа |
| `taskPool` | **Virtual threads** | Без ограничений | Выполняет тело задачи (HTTP + DB) |

**Почему разделение triggerPool / taskPool?**  
`ScheduledExecutorService` не поддерживает Virtual Threads напрямую — `scheduleWithFixedDelay`
требует платформенных потоков для надёжного учёта таймингов. Само тело задачи
отправляется в `taskPool` из Virtual Threads — это позволяет сотням задач
блокировать I/O без overhead платформенных потоков.

**Защита от перегрузки:**
- `globalSemaphore(50)` — жёсткий cap на всю систему
- `perUserCounter(5)` — cap на одного пользователя (защита от монополии)
- Если семафор не захвачен → итерация **тихо пропускается**, следующий тик повторит

**Реакция на события Spring:**
```
ControllerAddedEvent      → scheduleController()
ControllerRemovedEvent    → unscheduleController()
SubscriptionChangedEvent  → rescheduleUser()   ← пересчёт pollIntervalSec
SubscriptionExpiredEvent  → rescheduleUser()   ← возможно, снятие с расписания
```

**При старте (`@PostConstruct`):**  
Загружает все активные контроллеры из DB и планирует их. Это гарантирует
восстановление мониторинга после рестарта без потери состояния.

**При остановке (`@PreDestroy`):**  
Отменяет все `ScheduledFuture`, не ждёт завершения текущих задач (`cancel(false)`).

---

### 4.2 ControllerTask

**Файл:** `valui-monitor/.../scheduler/ControllerTask.java`

Реализует `Runnable`. Не является Spring-бином — создаётся `MonitorScheduler`
при каждом `doSchedule()` и живёт до `unscheduleController()`.

**Поток выполнения:**
```
run()
 ├── tryAcquire(globalSemaphore) → если нет: metrics.onTaskSkipped() + return
 ├── userSlots.incrementAndGet() → если > max: userSlots.decrement() + return
 ├── executeTask()
 │    ├── loadContext(controllerId)  — TX-1 readOnly
 │    │    └── если контроллер неактивен или URL не парсится → return (без ошибки)
 │    ├── fetch(ctx)                 — внешний вызов, БЕЗ транзакции
 │    │    └── при ошибке: log.warn + return (circuit breaker сам закроется)
 │    └── persistNewEvents(ctx, items) — TX-2
 └── release(globalSemaphore) + sample.stop(taskTimer)
```

**Важно:** `loadContext` вызывается на каждой итерации (не кэшируется),
потому что пользователь может изменить или удалить контроллер между тиками.

---

### 4.3 ControllerTaskExecutor

**Файл:** `valui-monitor/.../scheduler/ControllerTaskExecutor.java`

Spring-сервис. Инкапсулирует все транзакционные операции, чтобы `ControllerTask`
(не Spring-бин) мог вызывать их через проксированный бин.

**Три шага:**

| Метод | TX | Что делает |
|-------|-----|-----------|
| `loadAllActiveForScheduling()` | readOnly | Загружает все активные контроллеры при старте |
| `loadContext(id)` | readOnly | Загружает контекст + парсит URL для извлечения tournamentId/sportId |
| `fetch(ctx)` | нет | Вызывает `parser.fetchMatches()` или `fetchTournaments()` |
| `persistNewEvents(ctx, items)` | write | Dedup → save → update timestamps → publish event |

**`persistNewEvents` — детали:**
1. `dedup.claimIfNew(controllerId, eventId)` — Redis SADD (атомарно, O(1))
2. Если новое → `DetectedEventRepository.save(entity)`
3. При `DataIntegrityViolationException` (race Redis TTL + параллельный поток): логируем как дубликат, не падаем
4. `ctrl.lastCheckedAt = now()` — всегда
5. `ctrl.lastEventAt = now()` — только если есть новые события
6. `ApplicationEventPublisher.publishEvent(SportEventDetectedEvent)` — публикуется **внутри транзакции**, чтобы rollback отменил и событие

---

### 4.4 Метрики

**Файл:** `valui-monitor/.../scheduler/MonitorMetrics.java`

Все метрики через Micrometer → Prometheus (`/actuator/metrics`).

| Метрика | Тип | Описание |
|---------|-----|---------|
| `monitor.controllers.scheduled` | Gauge | Сколько контроллеров активно сейчас |
| `monitor.events.detected` | Counter | Суммарно новых событий с запуска |
| `monitor.tasks.skipped` | Counter | Пропущено из-за лимитов concurrency |
| `monitor.task.duration` | Timer + histogram | Время выполнения одной задачи p50/p95/p99 |
| `cache.dedup.hit` | Counter | Событий отфильтровано как уже виденные |
| `cache.dedup.miss` | Counter | Новых событий (miss = реально новое) |
| `cache.dedup.set_size{controller_id}` | Gauge | Размер Redis SET на контроллер |

---

## 5. Дедупликация событий

### 5.1 Зачем Redis, а не только БД

Наивный подход — проверять каждый eventId через `SELECT EXISTS(...)` в PostgreSQL.
Это работает, но:
- При 50 параллельных задачах × 100+ матчей = тысячи `SELECT EXISTS` в секунду
- Каждый запрос → транзакция → соединение из пула → latency

**Redis SET** (`SADD` / `SISMEMBER`) работает за O(1) без сетевого round-trip к DB
и не требует транзакции. Это принципиально другой порядок нагрузки.

**Но Redis — не источник истины.** Данные могут исчезнуть (TTL истёк, Redis
перезапущен, ключ удалён вручную). Поэтому схема двухуровневая:
- **Redis** — быстрый first-check, атомарный claim
- **PostgreSQL** — авторитетный источник, recovery, аналитика
- **DedupSyncScheduler** — ночная синхронизация между ними

---

### 5.2 EventDeduplicationService

**Файл:** `valui-monitor/.../dedup/EventDeduplicationService.java`

**Redis-структура:**
```
Ключ: "dedup:ctrl:{controllerId}"    (String UUID)
Тип:  Redis SET
TTL:  7 дней (обновляется при каждой записи)
```

**Основной метод — `claimIfNew(controllerId, eventId)`:**
```
SADD "dedup:ctrl:{id}" "{eventId}"
  └── возвращает 1 (добавлено, новое) или 0 (уже было)
EXPIRE "dedup:ctrl:{id}" 7d
```
Это **атомарная операция** — нет TOCTOU гонки между проверкой и добавлением.
Именно поэтому используется `claimIfNew`, а не отдельные `isNewEvent` + `markAsSeen`.

**`seedIfAbsent(controllerId)`** — вызывается при постановке контроллера в расписание:
- Если ключ в Redis уже есть → ничего не делает (идемпотентно)
- Если нет → загружает eventId из DB за последние `dedupTtlDays` (7 дней) → SADD всё
- Защищает от дублей после рестарта приложения

**`syncSeenEvents(controllerId, authoritative)`** — вызывается из DedupSyncScheduler:
- Двусторонняя сверка: `redis ∖ db` → удалить из Redis; `db ∖ redis` → добавить в Redis

---

### 5.3 DedupSyncScheduler — ночная синхронизация

**Файл:** `valui-monitor/.../dedup/DedupSyncScheduler.java`

**Расписание:** `0 0 3 * * *` (каждый день в 03:00), настраивается через
`valui.monitor.dedup-sync-cron`.

**Зачем нужна:**

| Сценарий | Без синхронизации | С синхронизацией |
|----------|-------------------|-----------------|
| Redis TTL истёк (ключ пропал) | При рестарте seed из DB, но если приложение не рестартовало — следующий тик снова найдёт "новые" события, которые уже есть в DB | Синхронизация добавляет их обратно в Redis |
| Оператор удалил событие из DB вручную | Redis продолжает считать его виденным → никогда не уведомит снова | Синхронизация удаляет из Redis → следующий парсинг создаст уведомление заново |
| Crash посередине TX-2 | Redis заклеймил событие, DB не сохранила | Синхронизация удалит из Redis → событие будет найдено снова |

**Логика на одном контроллере:**
```java
dbIds = detectedRepo.findExternalIds(controllerId, cutoff)    // источник истины
dedup.syncSeenEvents(controllerId, Set.copyOf(dbIds))
  ├── toRemove = redis \ dbIds  → redis.SREM(...)
  └── toAdd   = dbIds \ redis  → redis.SADD(...)
```

**Время cutoff = `now() - dedupTtlDays`** — синхронизируем только те события,
которые «молоды» относительно TTL Redis SET. Старые события в DB игнорируются —
они всё равно пропали бы из Redis по TTL.

---

### 5.4 Граничные случаи

**Redis TTL истёк + нет рестарта:**  
Ключ пропал из Redis. При следующем тике `claimIfNew` вернёт `true` для всех
событий → будут попытки INSERT в DB → `DataIntegrityViolationException` →
перехватывается, логируется как дубликат, транзакция продолжается.
Реального дублирующего уведомления не будет, потому что `save()` упал до `publishEvent()`.

**Параллельные задачи одного контроллера:**  
`claimIfNew` — один SADD в Redis, который атомарен. Два потока не могут оба
получить `true` для одного eventId. (Теоретически возможно при множественных
репликах Redis без Lua-скрипта, но в текущей single-node конфигурации — безопасно.)

**Полная потеря Redis:**  
`seedIfAbsent` при рестарте восстановит состояние из DB. Без рестарта —
события дойдут до `DataIntegrityViolationException`, пользователь не получит дубль.

---

## 6. Парсеры букмекеров (valui-parser)

Все парсеры реализуют интерфейс `BookmakerParser`:
```java
ParseResult<List<SportDto>>      fetchSports()
ParseResult<List<TournamentDto>> fetchTournaments(String sportId)
ParseResult<List<MatchDto>>      fetchMatches(String tournamentId)
boolean                          isAvailable()
```

Оборачиваются в `@CircuitBreaker` + `@Retry` через Resilience4j.
`ParseResult` содержит `data`, `success`, `errorMessage`, `durationMs`.

Circuit Breaker **не** кидает исключения в вызывающий код — парсер вызывает
fallback-метод, который возвращает `ParseResult.error(...)`. Планировщик получает
пустой список и тихо пропускает итерацию.

---

### 6.1 Fonbet

**Файлы:**
- `bookmaker/fonbet/FonbetParser.java`
- `bookmaker/fonbet/FonbetEndpointPool.java`

**Протокол:** HTTPS, JSON, без прокси  
**API:** `https://line{XX}w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600`

**Почему пул зеркал?**  
Fonbet не имеет стабильного публичного API — официальный домен `fon.bet` не
даёт прямого доступа к данным. Зеркала `bk6bba-resources.com` (200 штук:
`line01w`…`line99w` × 2 CDN-суффикса) — реальные CDN-узлы, часть из которых
всегда доступна. Пул выбирает наиболее «свежий» (по timestamp последнего успеха).

**Redis ZSet `fonbet:endpoints`:**
```
score = System.currentTimeMillis() после успешного запроса
score = 0.0                        после неудачи
getBestEndpoint() → reverseRange(key, 0, 0) — URL с максимальным score
```

**Bootstrap при старте** (фоновый поток, не блокирует Spring):
```
HEAD https://lineXXw... (8 параллельных потоков)
  ├── 200 OK → redis ZADD score=currentMs
  ├── 405   → GET Range:bytes=0-0 → 206=OK → redis ZADD score=currentMs
  └── ошибка → redis ZADD score=0.0
```
Через ~15-30с в логах: `[FonbetPool] bootstrap done: alive=45/200 in 18000ms`

**Аварийный rescan:** если после `markFailure` число живых зеркал (score > 0) < 3
→ запускается экстренный rescan (cooldown 10 мин между rescan'ами).

**Еженедельный rescan:** каждый вторник в 03:00 Moscow (16 потоков, все 200 зеркал).

**Кэш снимка (in-memory, TTL 30с):**  
`fetchSports()`, `fetchTournaments()`, `fetchMatches()` вызываются планировщиком
за один цикл подряд. Без кэша — 3 HTTP-запроса на один тик. `AtomicReference<CachedSnap>`
с `ts` гарантирует один запрос за 30 секунд.

**Circuit Breaker `fonbet-cb`:** порог 50%, окно 10, открыт 30с.

---

### 6.2 1xBet

**Файлы:**
- `bookmaker/xbet/XBetParser.java`
- `http/SocksBookmakerHttpClient.java`

**Протокол:** HTTPS через HTTP CONNECT прокси, JSON (gzip)  
**API:** `https://1xbet.kz/service-api/LineFeed/Get*Zip`  
**Прокси:** `91.147.122.69:4232` (HTTP proxy)

**Почему нельзя использовать WebClient (Reactor Netty)?**  
Reactor Netty использует собственный TLS-стек (Netty + OpenSSL / BoringSSL через
`netty-tcnative`), который формирует JA3-fingerprint, отличный от браузерного.
Сервер `1xbet.kz` проверяет JA3 и отвергает соединение с TCP RST даже при
принудительном HTTP/1.1 (`HttpProtocol.HTTP11`).

**`SocksBookmakerHttpClient` использует `java.net.http.HttpClient` (JDK 21):**  
JDK HttpClient использует JSSE — ту же TLS-реализацию, что и `HttpURLConnection`.
Её JA3 fingerprint совпадает с ожидаемым сервером.

**Почему HTTP CONNECT, а не SOCKS5?**  
SOCKS5 аутентификация через `Authenticator.setDefault()` (глобальный JVM-синглтон)
ненадёжна в JDK HttpClient — поведение менялось между версиями JDK 11–21.
HTTP CONNECT с явным `.authenticator()` на builder — официально поддерживаемый путь.

**`jdk.http.auth.tunneling.disabledSchemes`:**  
С JDK 8u111 JDK запрещает Basic-аутентификацию в CONNECT-тоннелях по умолчанию
(security fix). Сбрасывается через `System.setProperty` в `static {}` блоке класса.
Это влияет только на данный JVM-процесс и только на proxy-аутентификацию.

**Кэш чемпионатов:** `ParserCacheService` → Redis, ключ `xbet:champs`, TTL 1 мин.
`fetchTournaments` и `fetchMatches` оба используют этот кэш, чтобы не загружать
весь список чемпионатов дважды.

**Circuit Breaker `xbet-cb`:** порог 50%, окно 10, открыт 30с.

---

### 6.3 Olimp

**Файл:** `bookmaker/olimp/OlimpParser.java`

**Протокол:** HTTPS, JSON, без прокси  
**API:** `https://www.olimp.bet/api/v4/0/line/*`

**Эндпоинты:**
- `GET /sports` → список видов спорта
- `GET /sports-with-competitions` → виды спорта со вложенными турнирами (весь список)
- `GET /planned-events` → все запланированные матчи (весь список)

**Особенность:** API olimp.bet не поддерживает фильтрацию по sportId/tournamentId
в параметрах запроса. `fetchTournaments(sportId)` и `fetchMatches(tournamentId)`
загружают **весь** список и фильтруют на стороне клиента. Это нормально при
умеренном объёме данных, но стоит иметь в виду при росте.

**Circuit Breaker `olimp-cb`:** порог 50%, окно 10, открыт 30с.

---

### 6.4 BetCity

**Файл:** `bookmaker/betcity/BetCityParser.java`

**Протокол:** HTTPS, JSON, без прокси  
**API:** `https://ad.betcity.ru/d/off/*`

**Эндпоинты:**
- `GET  /sports` → список видов спорта
- `GET  /champs?rev=4&ids_sp={sportId}` → чемпионаты по спорту
- `POST /events?rev=6` c `multipart/form-data { ids: tournamentId }` → матчи

**Почему POST multipart?**  
betcity.ru не принимает tournamentId через query-params — требует форму.
`WebClient.postMultipart(...)` автоматически формирует `multipart/form-data`
с правильным boundary.

**Circuit Breaker `betcity-cb`:** порог 50%, окно 10, открыт 30с.

---

### 6.5 BetBoom

**Файлы:**
- `bookmaker/betboom/BetBoomParser.java`
- `bookmaker/betboom/ws/WsRequestService.java`
- `bookmaker/betboom/ws/WsClientBorrowingPool.java`
- `bookmaker/betboom/ws/WsClient.java`

**Протокол:** WebSocket Secure (WSS) + бинарный Protobuf  
**URL:** `wss://ru-ws.sporthub.bet:444/api/tree_ws/v1`  
**Origin заголовок:** `https://betboom.ru` (обязателен, сервер проверяет)

**Почему WSS + Protobuf, а не REST?**  
BetBoom не предоставляет публичного REST API. Официальный веб-сайт использует
WebSocket с бинарным Protobuf-протоколом. Парсер реверс-инжинирит этот протокол.

**Структура сообщений:**
```
Запрос:  бинарный Protobuf Envelope (Request*)
Ответ:   бинарный Protobuf Envelope (Response*)
           └── ServerFrame
                 └── Body[]  (первый непустой Body → конкретный тип данных)
```

**Пул соединений `WsClientBorrowingPool`:**

| Параметр | Значение | Смысл |
|----------|----------|-------|
| `min-size` | 2 | Минимум готовых соединений |
| `max-size` | 6 | Никогда больше 6 одновременно |
| `connect-timeout` | 5с | Таймаут на установку WS-соединения |
| `ready-timeout` | 10с | Ожидание hello/auth фрейма |
| `backoff-base` | 300мс | Начальный backoff при реконнекте |
| `backoff-max` | 10с | Максимальный backoff |
| `warmup.min-ready` | 2 | При старте ждать минимум 2 готовых соединения |
| `warmup.timeout` | 15с | Или не более 15с на warmup |

**`WsRequestService.sendAndAwaitFiltered`:**
```
jitter: Thread.sleep(random 0–200ms)  ← anti-flood, имитация реального пользователя
globalRps.acquire()                   ← semaphore(40) — не более 40 RPS
lease = pool.borrow()                 ← взять соединение из пула
  client.clearInbox()
  client.sendBinary(frame)
  loop until deadline(3000ms):
    bin = client.awaitBinary(leftMs)
    if Envelope.matches(predicate) → return bin
pool.release(lease)
globalRps.release()
```

**`WS_TIMEOUT_MS = 3000`** — если за 3с нужный Envelope не пришёл, бросается
`IllegalStateException("WS timeout")` → circuit breaker считает это отказом.

**Circuit Breaker `betboom-cb`:**
- Окно **6** (меньше, чем у HTTP-парсеров) — WS-соединение флапает сразу несколько запросов
- Открыт **60с** (вдвое дольше) — WS-реконнект медленнее, чем HTTP retry
- **Retry отключён** для BetBoom — WS-запросы non-idempotent, повтор может нарушить состояние соединения

---

## 7. HTTP-клиенты и прокси

**Файл:** `valui-parser/.../http/HttpClientConfig.java`

| Бин | Класс | Прокси | DNS | Для кого |
|-----|-------|--------|-----|---------|
| `xbetHttpClient` | `SocksBookmakerHttpClient` | HTTP CONNECT 4232 | через прокси | XBet |
| `fonbetHttpClient` | `BookmakerHttpClient` | нет | JVM InetAddress | Fonbet |
| `olimpHttpClient` | `BookmakerHttpClient` | нет | JVM InetAddress | Olimp |
| `betcityHttpClient` | `BookmakerHttpClient` | нет | JVM InetAddress | BetCity |
| `betboomHttpClient` | `BookmakerHttpClient` | нет | JVM InetAddress | BetBoom (HTTP fallback) |

**`BookmakerHttpClient` (Reactor Netty):**
- Принудительно HTTP/1.1 (`HttpProtocol.HTTP11`) — убирает ALPN из TLS ClientHello
- `DefaultAddressResolverGroup.INSTANCE` (JVM InetAddress) — системный DNS, надёжно резолвит CDN зеркала
- Сжатие: gzip включён
- Connect timeout: 8с, Response timeout: 15с, Block timeout: 20с
- User-Agent: Chrome 120 (некоторые серверы фильтруют по UA)
- Max response buffer: 10 MB

**`SocksBookmakerHttpClient` (JDK 21 `java.net.http.HttpClient`):**
- HTTP CONNECT proxy на `91.147.122.69:4232`
- Authenticator задаётся явно на builder (не через `Authenticator.setDefault()`)
- `jdk.http.auth.tunneling.disabledSchemes=""` в static блоке — разрешает Basic в CONNECT
- TLS: JDK JSSE — формирует правильный JA3 fingerprint для 1xbet.kz
- Ручная декомпрессия gzip в `decompress(response)`

**`valui.bot.proxy`** (Telegram бот) — отдельная конфигурация, **не связана** с `parser.proxy`:
```yaml
valui.bot.proxy:
  enabled: true
  type: SOCKS5
  port: 14232   # SOCKS5 порт — для Telegram
parser.proxy:
  port: 4232    # HTTP порт — для XBet
```

---

## 8. Сводная таблица парсеров

| | Fonbet | 1xBet | Olimp | BetCity | BetBoom |
|---|---|---|---|---|---|
| **Протокол** | HTTPS | HTTPS + HTTP CONNECT | HTTPS | HTTPS | WSS |
| **Формат** | JSON | JSON gzip | JSON | JSON | Protobuf |
| **Прокси** | нет | HTTP 4232 | нет | нет | нет |
| **HTTP-клиент** | Reactor Netty | JDK HttpClient | Reactor Netty | Reactor Netty | — |
| **Кэш снимка** | 30с in-memory | Redis 1м (champs) | нет | нет | нет |
| **Зеркала** | 200 (Redis ZSet) | нет | нет | нет | нет |
| **CB окно** | 10 | 10 | 10 | 10 | 6 |
| **CB открыт** | 30с | 30с | 30с | 30с | **60с** |
| **Retry** | 2 попытки | 2 попытки | 2 попытки | 2 попытки | **нет** |
| **Причина прокси/особенности** | CDN зеркала | Гео-блокировка + JA3 | — | POST multipart | WS + Protobuf |

---

## 9. Конфигурация (application.yml)

Профили: `local` (docker-compose на localhost), `docker` (контейнеры), `prod` (env vars only).

**Ключевые параметры мониторинга:**
```yaml
valui:
  monitor:
    max-concurrent-tasks: 50       # глобальный cap на параллельные задачи
    max-tasks-per-user: 5          # cap на одного пользователя
    default-poll-interval-sec: 60  # дефолт если у контроллера не задан интервал
    dedup-ttl-days: 7              # TTL Redis SET + горизонт синхронизации с DB
    dedup-sync-cron: "0 0 3 * * *" # ночная синхронизация Redis ↔ DB
```

**Параметры парсера:**
```yaml
parser:
  cache:
    sports-ttl: 1h
    tournaments-ttl: 30m
    matches-ttl: 5m
  proxy:
    enabled: true
    host: 91.147.122.69
    port: 4232          # HTTP proxy для XBet
    username: user283146
    password: 0w3qzb
```

**WebSocket (BetBoom):**
```yaml
ws:
  pool:
    url: wss://ru-ws.sporthub.bet:444/api/tree_ws/v1
    min-size: 2
    max-size: 6
    connect-timeout: 5s
    ready-timeout: 10s
    backoff-base: 300ms
    backoff-max: 10s
    warmup:
      enabled: true
      min-ready: 2
      timeout: 15s
  rps: 40   # глобальный RPS-лимит для WS-запросов
```

**Resilience4j:**
```yaml
resilience4j:
  circuitbreaker:
    configs:
      parser-default:
        failure-rate-threshold: 50        # % ошибок для открытия
        wait-duration-in-open-state: 30s
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
    instances:
      betboom-cb:                         # исключение: WS медленнее
        wait-duration-in-open-state: 60s
        sliding-window-size: 6
  retry:
    instances:
      parser-retry:
        max-attempts: 2
        wait-duration: 1s
        retry-exceptions:                 # retry только при сетевой ошибке
          - WebClientRequestException
        ignore-exceptions:                # HTTP 4xx/5xx — не ретраить
          - WebClientResponseException
```

---

## 10. Технологический стек

| Категория | Технология | Версия |
|-----------|-----------|--------|
| Язык | Java | 21 (Virtual Threads) |
| Фреймворк | Spring Boot | 3.3.x |
| Сборка | Maven | 3.9+ |
| БД | PostgreSQL | 15+ |
| Миграции БД | Flyway | — |
| Кэш / Dedup | Redis (Lettuce) | — |
| Брокер | Kafka | — |
| HTTP (реактивный) | Spring WebFlux / Reactor Netty | — |
| HTTP (JA3-safe) | JDK 21 `java.net.http.HttpClient` | — |
| WebSocket | Reactor Netty WS | — |
| Protobuf | Google Protobuf | — |
| Resilience | Resilience4j | — |
| Метрики | Micrometer → Prometheus | — |
| Кодогенерация | Lombok, MapStruct | — |
| Telegram | telegrambots | 6.9.7 |
| Тесты | JUnit 5, Testcontainers | — |

**Все парсеры находятся в современном стеке.** Единственное исключение — `SocksBookmakerHttpClient`
намеренно использует `java.net.http.HttpClient` (не WebClient) из-за требований к
TLS-fingerprint со стороны 1xbet.kz. Это архитектурное решение, а не технический долг.

**`_migration/`** — устаревшая монолитная версия, оставлена как справочник.
В продакшне не используется.
