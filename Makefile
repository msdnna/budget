BACKEND_DIR  := backend
FRONTEND_DIR := frontend

# Go 1.25 binary — falls back to system go if not found
GO := $(shell command -v go1.25.9 2>/dev/null || command -v go)

# Local dev database — override via environment or .env file
MONGO_USERNAME ?= admin
MONGO_PASSWORD ?= password
MONGO_DB       ?= budget
PORT           ?= 8080
MONGO_URI      ?= mongodb://$(MONGO_USERNAME):$(MONGO_PASSWORD)@localhost:27017/$(MONGO_DB)?authSource=admin
DB_NAME        ?= $(MONGO_DB)

# HTTP proxy for dependency downloads — leave empty if not needed
# Override via environment variables:  export HTTP_PROXY=http://proxy:port
HTTP_PROXY  ?=
HTTPS_PROXY ?=
NO_PROXY    ?= localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16

export HTTP_PROXY HTTPS_PROXY NO_PROXY

.DEFAULT_GOAL := help

# ─── Docker (production) ─────────────────────────────────────────────────────

.PHONY: up
up: ## Start all services in Docker (detached)
	docker compose up --build -d

.PHONY: up-logs
up-logs: ## Start all services in Docker (follow logs)
	docker compose up --build

.PHONY: down
down: ## Stop all Docker services
	docker compose down

.PHONY: clean
clean: ## Remove containers, volumes, and built images
	docker compose down -v --rmi local

.PHONY: logs
logs: ## Tail Docker logs
	docker compose logs -f

# ─── Local development ───────────────────────────────────────────────────────

.PHONY: dev
dev: mongo-up ## Start backend + frontend locally (MongoDB in Docker)
	$(MAKE) -j2 dev-backend dev-frontend

.PHONY: dev-backend
dev-backend: ## Run Go backend locally (requires MongoDB on :27017)
	cd $(BACKEND_DIR) && \
	  MONGO_URI="$(MONGO_URI)" DB_NAME=$(DB_NAME) PORT=$(PORT) \
	  $(GO) run .

.PHONY: dev-frontend
dev-frontend: ## Run Vue dev server on 0.0.0.0:5173 (all interfaces)
	cd $(FRONTEND_DIR) && npm run dev

.PHONY: mongo-up
mongo-up: ## Start only MongoDB in Docker for local dev
	docker compose up -d mongodb
	@echo "Waiting for MongoDB to be ready..."
	@until docker exec budget-mongodb mongosh --eval "db.adminCommand('ping')" --quiet 2>/dev/null; \
	  do sleep 1; done
	@echo "MongoDB is ready on :27017"

.PHONY: mongo-down
mongo-down: ## Stop local dev MongoDB
	docker compose stop mongodb

# ─── Install / Update ────────────────────────────────────────────────────────

.PHONY: install
install: ## Install all dependencies (Go + Node)
	cd $(BACKEND_DIR)  && GOPROXY=https://proxy.golang.org,direct $(GO) mod download
	cd $(FRONTEND_DIR) && npm ci

.PHONY: tidy
tidy: ## Tidy Go modules and regenerate go.sum
	cd $(BACKEND_DIR) && GOPROXY=https://proxy.golang.org,direct $(GO) mod tidy

# ─── Build ───────────────────────────────────────────────────────────────────

.PHONY: build-backend
build-backend: ## Build Go binary to backend/bin/
	mkdir -p $(BACKEND_DIR)/bin
	cd $(BACKEND_DIR) && CGO_ENABLED=0 $(GO) build -o bin/budget-go .

.PHONY: build-frontend
build-frontend: ## Build Vue app to frontend/dist/
	cd $(FRONTEND_DIR) && npm run build

.PHONY: build
build: build-backend build-frontend ## Build everything

# ─── Help ────────────────────────────────────────────────────────────────────

.PHONY: help
help:
	@echo "Usage: make <target>"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Go toolchain: $(GO)"
