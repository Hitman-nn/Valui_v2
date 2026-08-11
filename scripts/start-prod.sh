#!/usr/bin/env bash
# Запуск valui-app на сервере с prod-профилем (приложение вне Docker, инфраструктура в Docker).
# Использование: ./scripts/start-prod.sh [путь к jar] [доп. JVM флаги...]
# По умолчанию ищет jar в valui-app/target/
#
# Живой цветной вывод (для tmux attach) идёт в stdout как обычно; logback (prod-профиль)
# параллельно сам пишет чистый ротируемый файл в $LOG_PATH (по умолчанию ./logs).

set -euo pipefail

JAR="${1:-valui-app/target/valui-app-*.jar}"
export LOG_PATH="${LOG_PATH:-logs}"

exec java \
  -XX:+UseG1GC \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/valui-heap-dump.hprof \
  -Dspring.profiles.active=prod \
  -jar $JAR
