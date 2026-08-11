#!/usr/bin/env bash
# =============================================================================
# Valui PROD — start app in tmux with VisualVM/JMX enabled, V2
#
# Отличие V2:
#   - tmux-сессия НЕ закрывается сразу, если Java упала
#   - логи пишутся в ~/app/logs/
#   - после завершения Java окно tmux остаётся открытым, чтобы увидеть ошибку
#
# Run on SERVER:
#   cd ~/app
#   chmod +x valui-prod-start-jmx-tmux-v2.sh
#   ./valui-prod-start-jmx-tmux-v2.sh
#
# Attach:
#   tmux attach -t valui-app
#
# Detach:
#   Ctrl+B, then D
# =============================================================================

set -euo pipefail

SESSION_NAME="${SESSION_NAME:-valui-app}"
APP_DIR="${APP_DIR:-$HOME/app}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env.prod}"
JAR_FILE="${JAR_FILE:-$APP_DIR/valui-app-2.0.0-SNAPSHOT.jar}"
JMX_PORT="${JMX_PORT:-9010}"
LOG_DIR="${LOG_DIR:-$APP_DIR/logs}"
RUNNER_FILE="$APP_DIR/.valui-prod-jmx-tmux-runner.sh"

cd "$APP_DIR"

if ! command -v tmux >/dev/null 2>&1; then
  echo "ERROR: tmux is not installed. Install it:"
  echo "  sudo apt update && sudo apt install -y tmux"
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java is not installed. Install Java 21:"
  echo "  sudo apt update && sudo apt install -y openjdk-21-jre-headless"
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: env file not found: $ENV_FILE"
  exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
  echo "ERROR: jar file not found: $JAR_FILE"
  exit 1
fi

if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
  echo "tmux session '$SESSION_NAME' already exists."
  echo ""
  echo "Attach:"
  echo "  tmux attach -t $SESSION_NAME"
  echo ""
  exit 0
fi

if ss -lnt | grep -q ":${JMX_PORT}\b"; then
  echo "ERROR: JMX port ${JMX_PORT} is already listening on this server."
  echo "Check existing process:"
  ss -lntp | grep ":${JMX_PORT}\b" || true
  exit 1
fi

mkdir -p "$LOG_DIR"

cat > "$RUNNER_FILE" <<EOF
#!/usr/bin/env bash
set -o pipefail

APP_DIR="$APP_DIR"
ENV_FILE="$ENV_FILE"
JAR_FILE="$JAR_FILE"
JMX_PORT="$JMX_PORT"
LOG_DIR="$LOG_DIR"
export LOG_PATH="\$LOG_DIR"

# JVM memory
JAVA_XMS="\${JAVA_XMS:-512m}"
JAVA_XMX="\${JAVA_XMX:-3g}"

cd "\$APP_DIR"

set -a
source "\$ENV_FILE"
set +a

clear
echo "================================================"
echo " Starting Valui PROD with JMX inside tmux"
echo "================================================"
echo "App dir:      \$APP_DIR"
echo "Jar:          \$JAR_FILE"
echo "Env:          \$ENV_FILE"
echo "Profile:      \${SPRING_PROFILES_ACTIVE:-not set}"
echo "JMX endpoint: 127.0.0.1:\$JMX_PORT"
echo "Log dir:      \$LOG_DIR  (valui-app.log, ротируется logback'ом)"
echo "JVM Xms:      \$JAVA_XMS"
echo "JVM Xmx:      \$JAVA_XMX"
echo ""
echo "Detach: Ctrl+B, then D"
echo "Stop app: Ctrl+C"
echo "================================================"
echo ""

java \\
  -Xms"\$JAVA_XMS" \\
  -Xmx"\$JAVA_XMX" \\
  -XX:+UseG1GC \\
  -XX:+ExitOnOutOfMemoryError \\
  -XX:+HeapDumpOnOutOfMemoryError \\
  -XX:HeapDumpPath="$LOG_DIR/heap-dump-$(date +%F_%H-%M-%S).hprof" \\
  -Dcom.sun.management.jmxremote \\
  -Dcom.sun.management.jmxremote.port="\$JMX_PORT" \\
  -Dcom.sun.management.jmxremote.rmi.port="\$JMX_PORT" \\
  -Dcom.sun.management.jmxremote.host=127.0.0.1 \\
  -Djava.rmi.server.hostname=127.0.0.1 \\
  -Dcom.sun.management.jmxremote.authenticate=false \\
  -Dcom.sun.management.jmxremote.ssl=false \\
  -Dspring.servlet.multipart.max-file-size=10MB \\
  -Dspring.servlet.multipart.max-request-size=12MB \\
  -jar "\$JAR_FILE"

STATUS=\$?

echo ""
echo "================================================"
echo " Valui app exited with code: \$STATUS"
echo "Log dir:      \$LOG_DIR  (valui-app.log, ротируется logback'ом)"
echo "================================================"
echo ""
echo "Press Enter to close this tmux shell..."
read -r _
EOF

chmod +x "$RUNNER_FILE"

tmux new-session -d -s "$SESSION_NAME" "$RUNNER_FILE"

sleep 1

if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
  echo "Valui app started in tmux session: $SESSION_NAME"
  echo ""
  echo "Attach console:"
  echo "  tmux attach -t $SESSION_NAME"
  echo ""
  echo "Detach without stopping:"
  echo "  Ctrl+B, then D"
  echo ""
  echo "Check status:"
  echo "  ./valui-prod-status-v2.sh"
else
  echo "ERROR: tmux session closed immediately."
  echo "Try running runner manually:"
  echo "  $RUNNER_FILE"
  exit 1
fi
