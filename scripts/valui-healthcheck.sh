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
CURL_TIMEOUT_SEC="${CURL_TIMEOUT_SEC:-5}"

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

if [[ "$fail_count" -eq "$FAIL_THRESHOLD" ]]; then
  # Alert exactly once per outage (at the moment it's confirmed, not a blip) — the timer
  # keeps running every interval afterwards to detect recovery/restart, but repeating the
  # same "still down" alert every minute would just be noise once you already know.
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
