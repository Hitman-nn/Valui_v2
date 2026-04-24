# Load .env so variables are available to all targets (psql, redis-cli, etc.)
-include .env
export

COMPOSE := docker compose

.PHONY: up down reset logs psql redis-cli

## Start all services in detached mode
up:
	$(COMPOSE) up -d

## Stop all services (keep volumes)
down:
	$(COMPOSE) down

## Full reset: stop + wipe volumes + restart fresh
reset:
	$(COMPOSE) down -v --remove-orphans
	$(COMPOSE) up -d

## Stream logs from all services (Ctrl-C to stop).
## Optionally filter by service name: make logs s=kafka
logs:
	$(COMPOSE) logs -f $(s)

## Open a psql shell inside the Postgres container
psql:
	docker exec -it valui-postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

## Open a redis-cli shell inside the Redis container
redis-cli:
	docker exec -it valui-redis redis-cli -a $(REDIS_PASSWORD)