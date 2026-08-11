# Valui PROD — эксплуатация сервера

Актуальная схема запуска: **systemd**, а не tmux. tmux-скрипт
(`valui-prod-start-jmx-tmux-v2.sh`) оставлен в репозитории как резервный
способ запуска руками, если вдруг понадобится (например, для разовой
диагностики без перезапуска штатного сервиса) — на постоянку не использовать,
иначе получится два одновременных инстанса бота (конфликт long-polling).

## Быстрая шпаргалка

```bash
# запустить / перезапустить / остановить
sudo systemctl start valui-app
sudo systemctl restart valui-app
sudo systemctl stop valui-app

# статус (жив ли процесс, PID, аптайм)
sudo systemctl status valui-app

# смотреть лог живьём (аналог tmux attach)
journalctl -u valui-app -f

# лог из файла (то же самое, что пишет logback)
tail -f ~/app/logs/valui-app.log
```

Автозапуск при перезагрузке сервера уже включён (`enable --now` при
установке) — руками после ребута ничего поднимать не нужно.

---

## 1. Запуск приложения

Обычно ничего делать не нужно — `valui-app.service` в статусе `enabled`,
поднимается сам при старте сервера. Вручную (после деплоя нового jar-а,
после ручной остановки и т.п.):

```bash
sudo systemctl start valui-app
journalctl -u valui-app -f
```

В логе должен появиться баннер `VALUI READY` — сборка стартовала штатно.

## 2. Остановка

```bash
sudo systemctl stop valui-app
```

Важно: `stop` не отключает автозапуск — после ребута сервиса он всё равно
поднимется. Если нужно именно выключить и не запускать снова:

```bash
sudo systemctl disable --now valui-app
```

## 3. Перезапуск (например, после выкладки нового jar-а)

```bash
sudo systemctl restart valui-app
journalctl -u valui-app -f    # убедиться, что поднялся (баннер VALUI READY)
```

## 4. Просмотр логов

Два равнозначных способа — оба читают, по сути, один и тот же поток:

```bash
# через journalctl — то, что сервис пишет в stdout (цветной ASYNC_CONSOLE-аппендер)
journalctl -u valui-app -f
journalctl -u valui-app -n 200        # последние 200 строк без слежения
journalctl -u valui-app --since "1 hour ago"

# через файл — то, что пишет logback (ASYNC_FILE-аппендер), ротируется сам
tail -f ~/app/logs/valui-app.log
```

Ротация файла: `valui-app-YYYY-MM-DD.N.log.gz`, старые архивы logback чистит
сам (см. `logback-spring.xml`, prod-профиль) — руками ничего убирать не надо.

## 5. Автоперезапуск при падении

`valui-app.service` настроен с `Restart=on-failure` (5 сек. пауза, до 5
попыток за 10 минут) — если процесс упадёт (в том числе через
`-XX:+ExitOnOutOfMemoryError`), systemd поднимет его снова сам, без участия
человека. Плюс `MemoryMax=4G` — если процесс раздуется по памяти, cgroup
убьёт и перезапустит именно этот сервис, а не даст host-овому OOM-killer
выбирать жертву среди всех процессов (Postgres/Redis/Kafka в том числе).

Проверить, что реально работает:
```bash
sudo systemctl status valui-app   # запомнить PID
sudo kill -9 <PID>
sleep 7
sudo systemctl status valui-app   # должен быть снова active (running), новый PID
```

## 6. Health-check watchdog (ловит зависания, не только падения)

`Restart=on-failure` не спасает, если процесс жив, но завис (deadlock) —
код выхода в этом случае не отличается от "работает нормально". Для этого
случая есть отдельный внешний скрипт `valui-healthcheck.sh`, который раз в
минуту (через `valui-healthcheck.timer`) опрашивает `/actuator/health` и,
если приложение не отвечает `FAIL_THRESHOLD` (по умолчанию 3) проверок
подряд, шлёт алерт в Telegram и делает `systemctl restart valui-app`.

```bash
# статус таймера / когда следующий прогон
systemctl list-timers valui-healthcheck.timer

# лог последних прогонов
journalctl -u valui-healthcheck -n 50

# прогнать проверку прямо сейчас, не дожидаясь таймера
sudo systemctl start valui-healthcheck.service
```

### Установка на новом сервере / после чистой переустановки

```bash
cp valui-healthcheck.sh ~/app/
chmod +x ~/app/valui-run-systemd.sh ~/app/valui-healthcheck.sh

sudo cp valui-app.service valui-healthcheck.service valui-healthcheck.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now valui-app
sudo systemctl enable --now valui-healthcheck.timer
```

`valui-healthcheck.service` выполняется от root (нужно право на
`systemctl restart valui-app`) — если хочется без root, см. комментарий в
шапке `valui-healthcheck.service` про sudoers-альтернативу.

## 7. GC pause watchdog (раннее предупреждение "вот-вот упадёт")

Встроен прямо в приложение (`GcPauseWatchdog`, `valui-app`), отдельной
установки не требует. Долгая GC-пауза (≥1с) пишется в лог как WARN, пауза
≥3с дополнительно шлёт алерт в Telegram (не чаще одного раза в 5 минут) —
такая пауза обычно предшествует OOM/падению, так что это шанс среагировать
до реального краша, а не только постфактум.

## 8. Прочее

| Файл | Назначение |
|---|---|
| `valui-app.service` | systemd-юнит основного приложения |
| `valui-run-systemd.sh` | стартовый скрипт, вызывается из `valui-app.service` |
| `valui-healthcheck.sh` / `.service` / `.timer` | внешний health-check watchdog |
| `valui-prod-start-jmx-tmux-v2.sh` | резервный ручной запуск через tmux (не для постоянной эксплуатации) |
| `start-prod.sh` | (см. содержимое файла) |

Telegram-токен/чат и данные прокси берутся из `~/app/.env.prod` — те же
переменные, что использует само приложение (`TELEGRAM_BOT_TOKEN`,
`TELEGRAM_ADMIN_CHAT_ID`, `BOT_PROXY_*`), отдельно настраивать для
watchdog-скрипта не нужно.
