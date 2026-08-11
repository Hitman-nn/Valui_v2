#!/usr/bin/env bash
# =============================================================================
# Valui PROD — запуск под systemd (замена valui-prod-start-jmx-tmux-v2.sh).
# Тот же набор JVM/JMX флагов, но без tmux — процессом управляет systemd
# (автоперезапуск при падении/OOM-kill вместо ручного tmux attach).
#
# Установка на сервере см. scripts/valui-app.service
# =============================================================================

set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/app}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env.prod}"
JAR_FILE="${JAR_FILE:-$APP_DIR/valui-app-2.0.0-SNAPSHOT.jar}"
JMX_PORT="${JMX_PORT:-9010}"
# logback's own rolling FILE appender (logback-spring.xml, prod profile) writes here directly —
# one continuous, dated, rotated valui-app.log rather than a brand-new timestamped file per
# run that nothing ever cleans up. LOG_PATH is read by logback via springProperty.
LOG_DIR="${LOG_DIR:-$APP_DIR/logs}"
export LOG_PATH="$LOG_DIR"

JAVA_XMS="${JAVA_XMS:-512m}"
JAVA_XMX="${JAVA_XMX:-3g}"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: env file not found: $ENV_FILE" >&2
  exit 1
fi
if [[ ! -f "$JAR_FILE" ]]; then
  echo "ERROR: jar file not found: $JAR_FILE" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"

set -a
source "$ENV_FILE"
set +a

echo "================================================"
echo " Starting Valui PROD (systemd) with JMX"
echo "================================================"
echo "App dir:      $APP_DIR"
echo "Jar:          $JAR_FILE"
echo "Profile:      ${SPRING_PROFILES_ACTIVE:-not set}"
echo "JMX endpoint: 127.0.0.1:$JMX_PORT"
echo "Log dir:      $LOG_DIR  (valui-app.log, rotated by logback)"
echo "JVM Xms/Xmx:  $JAVA_XMS / $JAVA_XMX"
echo "================================================"

java \
  -Xms"$JAVA_XMS" \
  -Xmx"$JAVA_XMX" \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="$LOG_DIR/heap-dump-$(date +%F_%H-%M-%S).hprof" \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port="$JMX_PORT" \
  -Dcom.sun.management.jmxremote.rmi.port="$JMX_PORT" \
  -Dcom.sun.management.jmxremote.host=127.0.0.1 \
  -Djava.rmi.server.hostname=127.0.0.1 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dspring.servlet.multipart.max-file-size=10MB \
  -Dspring.servlet.multipart.max-request-size=12MB \
  -jar "$JAR_FILE"
# No shell-level tee/redirect here anymore: logback's own FILE appender (prod profile) writes
# the rotated, dated log directly to $LOG_PATH — stdout stays exactly what a `tmux attach` or
# `systemctl status`/journald sees live, colorized, unmodified.
