#!/usr/bin/env bash
# =============================================================================
# Valui PROD — внешний watchdog (вне JVM).
#
# Раз в запуск (см. valui-healthcheck.timer) опрашивает /actuator/health.
# Если приложение недоступно или отвечает не 200 — шлёт алерт в Telegram и,
# после нескольких подряд неудач, перезапускает сервис через systemd.
#
# Это ловит именно то, что systemd's Restart=on-failure не может поймать
# сам по себе: процесс жив (не упал), но завис (deadlock/livelock) и не
# отвечает — исключительно по коду выхода такое состояние неразличимо
# от "работает нормально".
#
# Требует: valui-app запущен через systemd (scripts/valui-app.service) —
# без этого RESTART_ON_FAILURE ничего не сможет перезапустить.
#
# Установка на сервере см. README ниже (или docs) — коротко:
#   cp valui-healthcheck.sh ~/app/
#   chmod +x ~/app/valui-healthcheck.sh
#   sudo cp valui-healthcheck.service valui-healthcheck.timer /etc/systemd/system/
#   sudo systemctl daemon-reload
#   sudo systemctl enable --now valui-healthcheck.timer
# =============================================================================

set -uo pipefail
# Deliberately NOT `set -e`: a failed curl/health-check is the expected, handled
# case here, not a script bug — `set -e` would abort the alerting logic itself
# on the exact condition this script exists to react to.

APP_DIR="${APP_DIR:-$HOME/app}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env.prod}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
SYSTEMD_UNIT="${SYSTEMD_UNIT:-valui-app}"
# State file — how many consecutive checks have failed. Survives across timer
# runs (each run is a fresh process), reset to 0 on the first successful check.
STATE_FILE="${STATE_FILE:-$APP_DIR/.valui-healthcheck.state}"
# After this many consecutive failed checks (at the timer's own interval —
# see valui-healthcheck.timer), treat it as a genuine outage, not a blip
# (deploy in progress, one slow request, transient network hiccup).
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
# 30.09 incident: this script used to alert + attempt a restart exactly ONCE per outage
# (the moment fail_count first crossed FAIL_THRESHOLD) and then go completely silent for
# every check after that, no matter how long the outage continued. That one restart attempt
# hit a corrupted jar (a botched deploy the night before) and failed identically every time
# it was retried — but nothing said so again: the operator got a single "restarting..."
# message at ~10:27 and then zero further signal while the outage silently continued for
# 6+ hours (fail_count reached 411) until someone happened to check by hand. Re-alerting
# (and retrying the restart — cheap and harmless if the underlying cause is still there,
# and a real chance of self-healing if it was transient) every ESCALATION_INTERVAL checks
# after the first makes an ongoing outage impossible to mistake for "one message, must be
# fine now".
ESCALATION_INTERVAL="${ESCALATION_INTERVAL:-10}"
CURL_TIMEOUT_SEC="${CURL_TIMEOUT_SEC:-5}"

# 30.09 incident, second finding: this unit runs as root (see valui-healthcheck.service), so
# its journal entries are only visible to root/adm/systemd-journal — NOT to the unprivileged
# `valui` user that owns everything else under APP_DIR and that operators actually SSH in as.
# `journalctl -u valui-healthcheck.service` came back completely empty while investigating that
# incident, for exactly this reason — there was no way to tell from the valui account whether
# this watchdog had even run, let alone what it saw. Mirroring all of this script's own output
# into a plain, world-readable file under APP_DIR/logs closes that gap without touching the
# journal behavior at all. No rotation here on purpose: a failing check writes at most a few
# lines per run, so even a multi-hour outage only adds a few hundred lines — add a logrotate
# stanza later if that assumption ever stops holding.
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/healthcheck.log}"
mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
touch "$LOG_FILE" 2>/dev/null || true
chmod 644 "$LOG_FILE" 2>/dev/null || true
exec > >(tee -a "$LOG_FILE") 2>&1

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

send_telegram_alert() {
  local text="$1"
  if [[ -z "${TELEGRAM_BOT_TOKEN:-}" || "${TELEGRAM_BOT_TOKEN:-change-me}" == "change-me" ]]; then
    echo "[healthcheck] TELEGRAM_BOT_TOKEN not configured — alert suppressed: $text" >&2
    return
  fi
  if [[ -z "${TELEGRAM_ADMIN_CHAT_ID:-}" || "${TELEGRAM_ADMIN_CHAT_ID:-0}" == "0" ]]; then
    echo "[healthcheck] TELEGRAM_ADMIN_CHAT_ID not configured — alert suppressed: $text" >&2
    return
  fi

  local curl_args=(-sS -m "$CURL_TIMEOUT_SEC" -X POST
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage"
    --data-urlencode "chat_id=${TELEGRAM_ADMIN_CHAT_ID}"
    --data-urlencode "text=${text}"
    --data-urlencode "parse_mode=Markdown")

  # The app itself reaches api.telegram.org through a SOCKS5 proxy (valui.bot.proxy.* /
  # BOT_PROXY_* — see application.yml) because Telegram is geo-blocked from this server's
  # IP directly. This script runs outside the JVM, so it must replicate that same proxy
  # hop itself, or the alert silently never arrives — exactly the failure mode that would
  # make this whole watchdog useless without anyone noticing.
  if [[ "${BOT_PROXY_ENABLED:-false}" == "true" && -n "${BOT_PROXY_HOST:-}" ]]; then
    curl_args+=(--socks5-hostname "${BOT_PROXY_HOST}:${BOT_PROXY_PORT:-1080}")
    if [[ -n "${BOT_PROXY_USER:-}" ]]; then
      curl_args+=(--proxy-user "${BOT_PROXY_USER}:${BOT_PROXY_PASS:-}")
    fi
  fi

  if ! curl "${curl_args[@]}" -o /dev/null; then
    echo "[healthcheck] Failed to deliver Telegram alert (curl error) — see stderr above" >&2
  fi
}

fail_count=0
[[ -f "$STATE_FILE" ]] && fail_count=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
[[ "$fail_count" =~ ^[0-9]+$ ]] || fail_count=0

http_code=$(curl -sS -o /dev/null -w '%{http_code}' -m "$CURL_TIMEOUT_SEC" "$HEALTH_URL" 2>/dev/null)
curl_status=$?

if [[ $curl_status -eq 0 && "$http_code" == "200" ]]; then
  if [[ "$fail_count" -ge "$FAIL_THRESHOLD" ]]; then
    # Was down, now recovered — close the loop so the next outage gets its own alert
    # instead of silence (the alert-on-first-threshold-crossing design below only fires
    # once per outage; without a recovery notice you'd have no way to tell "still down"
    # from "recovered 10 minutes ago" without re-checking manually).
    send_telegram_alert "✅ *Valui снова отвечает* на \`/actuator/health\` после ${fail_count} неудачных проверок подряд."
  fi
  echo 0 > "$STATE_FILE"
  exit 0
fi

fail_count=$((fail_count + 1))
echo "$fail_count" > "$STATE_FILE"

echo "[healthcheck] health check failed (curl_status=$curl_status http_code=${http_code:-none}), consecutive failures=$fail_count" >&2

# Act at the first confirmed outage, then again every ESCALATION_INTERVAL checks for as
# long as it continues — not every single minute (that would just be noise once you already
# know), but never silent for longer than that on a still-ongoing outage either.
act_now=false
if [[ "$fail_count" -eq "$FAIL_THRESHOLD" ]]; then
  act_now=true
elif [[ "$fail_count" -gt "$FAIL_THRESHOLD" ]] \
      && (( (fail_count - FAIL_THRESHOLD) % ESCALATION_INTERVAL == 0 )); then
  act_now=true
fi

if [[ "$act_now" == "true" ]]; then
  send_telegram_alert "🔴 *Valui не отвечает*
\`${HEALTH_URL}\` — ${fail_count} неудачных проверок подряд (curl exit=${curl_status}, HTTP=${http_code:-none}).

Перезапускаю \`${SYSTEMD_UNIT}\` через systemd..."

  if command -v systemctl >/dev/null 2>&1; then
    if systemctl restart "$SYSTEMD_UNIT" 2>/tmp/valui-healthcheck-restart.err; then
      send_telegram_alert "🔄 \`${SYSTEMD_UNIT}\` перезапущен. Проверю на следующем цикле."
    else
      send_telegram_alert "⚠️ Не удалось перезапустить \`${SYSTEMD_UNIT}\` — нужно вмешаться руками.
\`\`\`
$(cat /tmp/valui-healthcheck-restart.err 2>/dev/null | tail -5)
\`\`\`"
    fi
  else
    send_telegram_alert "⚠️ systemctl недоступен в этой среде — перезапустить автоматически не получится, нужно руками."
  fi
fi

exit 1
