# ══════════════════════════════════════════════════════════════════════════════
# Valui v2 — управление окружениями
#
#   make help           — список команд
#   make test-up        — поднять тест-инфраструктуру
#   make prod-up        — поднять прод
#   make prod-deploy    — скачать новый образ app и перезапустить
# ══════════════════════════════════════════════════════════════════════════════

-include .env
export

PROD  = docker compose -p valui-prod -f docker-compose.prod.yml --env-file .env.prod
TEST  = docker compose -p valuii-test -f docker-compose.test.yml --env-file .env.test

ADMIN_UI_IMAGE = ghcr.io/hitman-nn/valui-admin-ui
# SSH_KEY и SERVER берутся из .env (gitignored). Пример: SSH_KEY=~/.ssh/valui_prod
SSH_OPTS = $(if $(SSH_KEY),-i $(SSH_KEY),)

.PHONY: help \
        up down reset logs psql redis-cli \
        test-up test-down test-restart test-logs test-status test-psql test-redis \
        prod-up prod-down prod-restart prod-logs prod-status prod-deploy \
        ui-build ui-push ui-deploy \
        miniapp-build miniapp-deploy

# ── Помощь ────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  TEST (инфраструктура, app запускается отдельно):"
	@echo "    make test-up        — запустить postgres / redis / kafka / kafka-ui"
	@echo "    make test-down      — остановить контейнеры"
	@echo "    make test-restart   — пересоздать контейнеры"
	@echo "    make test-logs      — хвост логов (Ctrl+C для выхода)"
	@echo "    make test-status    — статус контейнеров"
	@echo "    make test-psql      — psql внутри тест-postgres"
	@echo "    make test-redis     — redis-cli внутри тест-redis"
	@echo ""
	@echo "  PROD:"
	@echo "    make prod-up        — запустить все сервисы"
	@echo "    make prod-down      — остановить контейнеры"
	@echo "    make prod-restart   — пересоздать контейнеры"
	@echo "    make prod-logs      — хвост логов (Ctrl+C для выхода)"
	@echo "    make prod-status    — статус контейнеров"
	@echo "    make prod-deploy    — скачать новый образ app и перезапустить"
	@echo ""
	@echo "  FRONTEND (Admin UI — запускать ЛОКАЛЬНО, не на сервере):"
	@echo "    make ui-build       — собрать образ локально"
	@echo "    make ui-push        — собрать и запушить в GHCR (текущая ветка + latest)"
	@echo "    make ui-deploy      — pull образа на сервере + перезапуск контейнера"
	@echo ""
	@echo "  MINIAPP (Telegram Mini App — запускать ЛОКАЛЬНО, не на сервере):"
	@echo "    make miniapp-build  — npm install + build (dist/ в valui-miniapp-ui/)"
	@echo "    make miniapp-deploy — build + rsync dist/ на сервер + nginx reload"
	@echo "    Требует в .env: SERVER, SSH_KEY (опц.), MINIAPP_DEPLOY_PATH"
	@echo ""

# ── Обратная совместимость (старые цели без префикса = prod) ──────────────────
up:
	$(PROD) up -d

down:
	$(PROD) down

reset:
	$(PROD) down -v --remove-orphans
	$(PROD) up -d

logs:
	$(PROD) logs -f $(s)

psql:
	docker exec -it valui-postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

redis-cli:
	docker exec -it valui-redis redis-cli -a $(REDIS_PASSWORD)

# ── TEST ──────────────────────────────────────────────────────────────────────
test-up:
	$(TEST) up -d

test-down:
	$(TEST) down

test-restart:
	$(TEST) down
	$(TEST) up -d

test-logs:
	$(TEST) logs -f --tail=100 $(s)

test-status:
	$(TEST) ps

test-psql:
	docker exec -it valui-test-postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

test-redis:
	docker exec -it valui-test-redis redis-cli -a $(REDIS_PASSWORD)

# ── PROD ──────────────────────────────────────────────────────────────────────
prod-up:
	$(PROD) up -d

prod-down:
	$(PROD) down

prod-restart:
	$(PROD) down
	$(PROD) up -d

prod-logs:
	$(PROD) logs -f --tail=100 $(s)

prod-status:
	$(PROD) ps

prod-deploy:
	$(PROD) pull app
	$(PROD) up -d --no-deps app

# ── Admin UI ──────────────────────────────────────────────────────────────────
# Все три команды запускаются ЛОКАЛЬНО на маке.
# ui-build  — собрать образ локально (для проверки)
# ui-push   — собрать и запушить в GHCR с тегом ветки + latest
# ui-deploy — запушить образ и дать команду серверу перезапустить контейнер
BRANCH := $(shell git rev-parse --abbrev-ref HEAD)

ui-build:
	docker buildx build --platform linux/amd64 -t $(ADMIN_UI_IMAGE):$(BRANCH) -t $(ADMIN_UI_IMAGE):latest ./admin-ui

ui-push:
	docker buildx build --platform linux/amd64 \
		-t $(ADMIN_UI_IMAGE):$(BRANCH) \
		-t $(ADMIN_UI_IMAGE):latest \
		--push ./admin-ui

ui-deploy: ui-push
	ssh $(SSH_OPTS) $(SERVER) "cd app && docker compose -p valui-prod -f docker-compose.prod.yml --env-file .env.prod pull admin-ui && docker compose -p valui-prod -f docker-compose.prod.yml --env-file .env.prod up -d --no-deps admin-ui"

# ── Telegram Mini App ─────────────────────────────────────────────────────────
# miniapp-build  — локальная сборка (dist/)
# miniapp-deploy — сборка + rsync на сервер + nginx reload
# Требует в .env: SERVER, MINIAPP_DEPLOY_PATH (напр. /opt/valui/miniapp)
MINIAPP_DEPLOY_PATH ?= /app/miniapp

miniapp-build:
	cd valui-miniapp-ui && npm install --silent && npm run build

miniapp-deploy: miniapp-build
	ssh $(SSH_OPTS) $(SERVER) "sudo mkdir -p $(MINIAPP_DEPLOY_PATH) && sudo chown $(shell ssh $(SSH_OPTS) $(SERVER) whoami) $(MINIAPP_DEPLOY_PATH)"
	rsync -az --delete $(if $(SSH_KEY),--rsh="ssh -i $(SSH_KEY)",) \
		valui-miniapp-ui/dist/ $(SERVER):$(MINIAPP_DEPLOY_PATH)/
	ssh $(SSH_OPTS) $(SERVER) "sudo nginx -s reload"
