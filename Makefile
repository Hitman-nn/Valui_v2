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

.PHONY: help \
        up down reset logs psql redis-cli \
        test-up test-down test-restart test-logs test-status test-psql test-redis \
        prod-up prod-down prod-restart prod-logs prod-status prod-deploy \
        ui-build ui-deploy

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
	@echo "  FRONTEND (Admin UI):"
	@echo "    make ui-build       — собрать Docker-образ фронта"
	@echo "    make ui-deploy      — пересобрать образ и перезапустить контейнер"
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
# ui-build  — собрать Docker-образ фронта (запускать на сервере после git pull)
# ui-deploy — пересобрать образ и перезапустить контейнер без простоя
ui-build:
	docker build -t valui-admin-ui:latest ./admin-ui

ui-deploy:
	docker build -t valui-admin-ui:latest ./admin-ui
	$(PROD) up -d --no-deps admin-ui
