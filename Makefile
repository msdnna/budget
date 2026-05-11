BACKEND_DIR  := backend
FRONTEND_DIR := frontend
ANDROID_DIR  := android

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
HTTP_PROXY  ?=
HTTPS_PROXY ?=
NO_PROXY    ?= localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16

# Version bump type for bump-* targets
BUMP ?= patch

export HTTP_PROXY HTTPS_PROXY NO_PROXY

.DEFAULT_GOAL := help

# ─── Docker (development) ────────────────────────────────────────────────────

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

# ─── Production (Docker Compose) ─────────────────────────────────────────────

.PHONY: prod-build
prod-build: ## Build production images (distroless backend + nginx frontend)
	@API_VERSION=$$(cat $(BACKEND_DIR)/VERSION) \
	 WEB_VERSION=$$(cat $(FRONTEND_DIR)/VERSION) \
	 VCS_REF=$$(git rev-parse --short HEAD 2>/dev/null || echo unknown) \
	  docker compose -f docker-compose.prod.yml build

.PHONY: prod-up
prod-up: ## Start production environment (detached)
	docker compose -f docker-compose.prod.yml up -d

.PHONY: prod-down
prod-down: ## Stop production environment
	docker compose -f docker-compose.prod.yml down

.PHONY: prod-logs
prod-logs: ## Tail production logs
	docker compose -f docker-compose.prod.yml logs -f

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
build: build-backend build-frontend ## Build backend + frontend

# ─── Android ─────────────────────────────────────────────────────────────────

.PHONY: android
android: ## Build debug Android APK (android/msdnna-budget-app-v<version>.apk)
	cd $(ANDROID_DIR) && ./build.sh

.PHONY: android-release
android-release: ## Build release APK (requires ANDROID_KEYSTORE_FILE and signing env vars)
	./tools/build-android-release.sh

# ─── User management ─────────────────────────────────────────────────────────

.PHONY: create_user
create_user: ## Create a user: make create_user USER_LOGIN=alice USER_PASSWORD=secret USER_NAME="Alice"
	@[ -n "$(USER_LOGIN)" ] || { \
	  echo "Error: USER_LOGIN is required"; \
	  echo "Usage: make create_user USER_LOGIN=alice USER_PASSWORD=secret USER_NAME=\"Alice Smith\""; \
	  exit 1; }
	@[ -n "$(USER_PASSWORD)" ] || { echo "Error: USER_PASSWORD is required"; exit 1; }
	cd $(BACKEND_DIR) && \
	  MONGO_URI="$(MONGO_URI)" DB_NAME=$(DB_NAME) \
	  $(GO) run ./cmd/create_user \
	    -login "$(USER_LOGIN)" \
	    -password "$(USER_PASSWORD)" \
	    -name "$(USER_NAME)"

# ─── Load testing ────────────────────────────────────────────────────────────

LOADTEST_DB ?= budget_loadtest
LOADTEST_URI ?= mongodb://$(MONGO_USERNAME):$(MONGO_PASSWORD)@localhost:27017/?authSource=admin

.PHONY: seed-loadtest
seed-loadtest: ## Populate $(LOADTEST_DB) with realistic family-budget data (use CLEAR=1 to drop first)
	cd $(BACKEND_DIR) && \
	  $(GO) run ./cmd/seed_loadtest \
	    -mongo-uri "$(LOADTEST_URI)" \
	    -db $(LOADTEST_DB) \
	    $(if $(CLEAR),-clear,) \
	    $(if $(FROM),-from $(FROM),) \
	    $(if $(TO),-to $(TO),) \
	    $(if $(EXPENSES),-expenses $(EXPENSES),) \
	    $(if $(INCOMES),-incomes $(INCOMES),) \
	    $(if $(WISHLIST),-wishlist $(WISHLIST),)

.PHONY: loadtest-up
loadtest-up: ## Restart backend pointing at $(LOADTEST_DB) (frontend follows automatically)
	MONGO_DB=$(LOADTEST_DB) docker compose up -d --no-deps backend
	@echo "Backend now serving database: $(LOADTEST_DB)"
	@echo "Run 'make loadtest-restore' to switch back to '$(MONGO_DB)'."

.PHONY: loadtest-restore
loadtest-restore: ## Restart backend pointing at the production database ($(MONGO_DB))
	MONGO_DB=$(MONGO_DB) docker compose up -d --no-deps backend
	@echo "Backend now serving database: $(MONGO_DB)"

.PHONY: loadtest-drop
loadtest-drop: ## Drop the $(LOADTEST_DB) database entirely
	@docker exec budget-mongodb mongosh \
	  --username "$(MONGO_USERNAME)" --password "$(MONGO_PASSWORD)" \
	  --authenticationDatabase admin \
	  --quiet --eval 'db.getSiblingDB("$(LOADTEST_DB)").dropDatabase()'
	@echo "Dropped database: $(LOADTEST_DB)"

# ─── Swagger / OpenAPI ───────────────────────────────────────────────────────

SWAG := $(shell command -v swag 2>/dev/null || echo $$(go env GOPATH)/bin/swag)

.PHONY: swag-install
swag-install: ## Install swag v2 CLI (swaggo/swag/v2 — OpenAPI 3.1 support)
	GOPROXY=https://proxy.golang.org,direct $(GO) install github.com/swaggo/swag/v2/cmd/swag@latest

.PHONY: swag
swag: ## Regenerate backend/docs/swagger.{json,yaml} from handler annotations (OpenAPI 3.1)
	@test -x "$(SWAG)" || { echo "swag not found — run 'make swag-install'"; exit 1; }
	cd $(BACKEND_DIR) && $(SWAG) init --v3.1 --parseDependency --parseInternal --generatedTime=false -o ./docs -g main.go
	@# docs.go импортируется только при использовании swag.Register() runtime —
	@# мы embed-им swagger.json напрямую, поэтому удаляем сгенерированный stub.
	@rm -f $(BACKEND_DIR)/docs/docs.go

# ─── Versioning ──────────────────────────────────────────────────────────────

.PHONY: version
version: ## Show current versions of all services
	@printf "  API:     %s\n" "$$(cat $(BACKEND_DIR)/VERSION)"
	@printf "  Web:     %s\n" "$$(cat $(FRONTEND_DIR)/VERSION)"
	@printf "  Android: %s\n" "$$(cat $(ANDROID_DIR)/VERSION)"

.PHONY: bump-api
bump-api: ## Bump API version     (BUMP=patch|minor|major, default: patch)
	@./tools/bump-version.sh api $(BUMP)

.PHONY: bump-web
bump-web: ## Bump Web version     (BUMP=patch|minor|major, default: patch)
	@./tools/bump-version.sh web $(BUMP)

.PHONY: bump-android
bump-android: ## Bump Android version (BUMP=patch|minor|major, default: patch)
	@./tools/bump-version.sh android $(BUMP)

# ─── Help ────────────────────────────────────────────────────────────────────

.PHONY: help
help:
	@echo "Usage: make <target>"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Go toolchain: $(GO)"
