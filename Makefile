BACKEND_DIR  := backend
FRONTEND_DIR := frontend
ANDROID_DIR  := android

# Yarn pinned via `packageManager` in frontend/package.json — corepack routes to the right version
# regardless of what's globally installed (so users don't need to `corepack enable` first).
YARN := corepack yarn

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

# ─── Telegram bot (dev) ──────────────────────────────────────────────────────
# Lives under `profiles: ["bot"]` in docker-compose.yml so the plain `make up`
# stack ignores it. These targets enable the profile + scope the action to the
# bot service.
#
# `set -a; . ./.env; set +a` exports every key in .env into the make-shell so
# docker compose's substitution sees them. Without this, shell-empty vars
# (e.g. an unset HTTPS_PROXY) win over the .env values because docker
# compose's interpolation priority is shell > .env. The Telegram bot needs
# HTTPS_PROXY end-to-end on this dev box — a silent fallback to "no proxy"
# breaks polling against api.telegram.org with a "Request timeout".

.PHONY: bot-up
bot-up: ## Build + start the telegram-bot service in dev compose
	set -a; . ./.env; set +a; \
	  docker compose --profile bot up -d --build telegram-bot

.PHONY: bot-logs
bot-logs: ## Tail telegram-bot logs
	docker compose --profile bot logs -f telegram-bot

.PHONY: bot-restart
bot-restart: ## Restart telegram-bot (re-reads .env without rebuild)
	docker compose --profile bot restart telegram-bot

.PHONY: bot-down
bot-down: ## Stop telegram-bot (keeps image)
	docker compose --profile bot stop telegram-bot

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

# ─── Raspberry Pi (home server) ──────────────────────────────────────────────
# Pulls multi-arch images from GHCR; see docs/RPI_DEPLOY.md.

# API_VERSION / WEB_VERSION default to the VERSION files in the repo. Override
# explicitly on the Pi if you want to pin to a different release tag than the
# one currently checked out (e.g. API_VERSION=1.19.2 make rpi-up).
RPI_API_VERSION ?= $(shell cat $(BACKEND_DIR)/VERSION)
RPI_WEB_VERSION ?= $(shell cat $(FRONTEND_DIR)/VERSION)
RPI_COMPOSE     := API_VERSION=$(RPI_API_VERSION) WEB_VERSION=$(RPI_WEB_VERSION) docker compose -f docker-compose.rpi.yml

.PHONY: rpi-pull
rpi-pull: ## Pull GHCR images for the pinned API/WEB_VERSION
	$(RPI_COMPOSE) pull

.PHONY: rpi-up
rpi-up: ## Start Pi stack (detached); pulls images if missing
	$(RPI_COMPOSE) up -d

.PHONY: rpi-down
rpi-down: ## Stop Pi stack (keeps volumes)
	$(RPI_COMPOSE) down

.PHONY: rpi-logs
rpi-logs: ## Tail Pi logs
	$(RPI_COMPOSE) logs -f

.PHONY: rpi-apk-fetch
rpi-apk-fetch: ## Download signed APK for android/VERSION from GitHub Releases into ./apks/
	tools/rpi-fetch-apk.sh

.PHONY: rpi-update
rpi-update: ## git pull → rpi-pull → rpi-apk-fetch → rpi-up (the only command you need after a release)
	git pull --ff-only
	$(MAKE) rpi-pull
	$(MAKE) rpi-apk-fetch
	$(MAKE) rpi-up

.PHONY: rpi-backup-now
rpi-backup-now: ## Run a one-off MongoDB backup right now
	sudo tools/rpi-backup.sh

.PHONY: rpi-backup-install
rpi-backup-install: ## Install the systemd timer for daily 03:30 backups
	sudo install -m 0644 deploy/systemd/budget-backup.service /etc/systemd/system/budget-backup.service
	sudo install -m 0644 deploy/systemd/budget-backup.timer   /etc/systemd/system/budget-backup.timer
	sudo systemctl daemon-reload
	sudo systemctl enable --now budget-backup.timer
	@echo "Installed. Next run: $$(systemctl list-timers budget-backup.timer --no-pager | awk 'NR==2 {print $$1,$$2}')"

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
	cd $(FRONTEND_DIR) && $(YARN) dev

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

# ─── Lint / Test ─────────────────────────────────────────────────────────────

GOLANGCI_LINT := $(shell command -v golangci-lint 2>/dev/null || { command -v go >/dev/null 2>&1 && echo $$(go env GOPATH)/bin/golangci-lint; })

.PHONY: lint-backend
lint-backend: ## Run gofmt + go vet + golangci-lint on the Go backend
	@cd $(BACKEND_DIR) && \
	  diff=$$(gofmt -l . 2>&1); \
	  if [ -n "$$diff" ]; then echo "gofmt drift:"; echo "$$diff"; exit 1; fi
	cd $(BACKEND_DIR) && $(GO) vet ./...
	@test -x "$(GOLANGCI_LINT)" || { \
	  echo "golangci-lint not found — install: go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest"; \
	  exit 1; }
	cd $(BACKEND_DIR) && $(GOLANGCI_LINT) run ./...

.PHONY: lint-web
lint-web: ## Run ESLint + Prettier check on the Vue frontend
	cd $(FRONTEND_DIR) && $(YARN) lint
	cd $(FRONTEND_DIR) && $(YARN) format:check

# Android Gradle нуждается в JAVA_HOME/ANDROID_HOME + SOCKS5 для подкачки
# артефактов. Источник — android/local.env (gitignored).
ANDROID_GRADLE := cd $(ANDROID_DIR) && set -a && . ./local.env && set +a && \
  GRADLE_OPTS="$${SOCKS_PROXY_HOST:+-DsocksProxyHost=$$SOCKS_PROXY_HOST -DsocksProxyPort=$$SOCKS_PROXY_PORT -DsocksProxyVersion=5} -Dorg.gradle.internal.http.socketTimeout=300000" \
  ./gradlew --no-daemon

.PHONY: lint-android
lint-android: ## Run ktlint + detekt on the Android app
	@$(ANDROID_GRADLE) :app:ktlintCheck :app:detekt

.PHONY: format-android
format-android: ## Auto-format Kotlin sources via ktlint
	@$(ANDROID_GRADLE) :app:ktlintFormat

.PHONY: lint
lint: ## Run all linters and produce reports/lint.html (uses tools/aggregate-reports.py)
	@python3 tools/aggregate-reports.py lint

.PHONY: test-android
test-android: ## Run Android unit tests (JUnit 4 + Robolectric + MockWebServer)
	@$(ANDROID_GRADLE) :app:testDebugUnitTest

.PHONY: test-android-cover
test-android-cover: ## Run Android unit tests with JaCoCo coverage (app/build/reports/jacoco/jacocoTestReport/html/index.html)
	@$(ANDROID_GRADLE) :app:jacocoTestReport
	@echo "Coverage report: $(ANDROID_DIR)/app/build/reports/jacoco/jacocoTestReport/html/index.html"

.PHONY: test
test: ## Run all test suites and produce reports/test.html (uses tools/aggregate-reports.py)
	@python3 tools/aggregate-reports.py test

.PHONY: test-backend
test-backend: ## Run Go unit tests (skips integration tests requiring Docker)
	cd $(BACKEND_DIR) && $(GO) test -race -short ./...

# Coverage scope: HTTP API surface (handlers + middleware + repos + helpers).
# Исключено как покрываемое e2e или нетестируемое в unit'ах:
#   - cmd/* — CLI-утилиты (seed_loadtest, migrate, create_user) гоняются вручную
#     через make-таргеты, валидация их по факту использования;
#   - internal/mongotest — сами тестовые хелперы, считать своё покрытие нет смысла;
#   - main.go — фактически только wiring, проверяется через build/smoke в CI;
#   - handlers/export.go (Excel/PDF) — XLSX/PDF golden-tests лучше делать в e2e
#     (см. docs/E2E_PLAN.md), unit-проверка cellName/txTypeLabel уже есть;
#   - handlers/icons.go (multipart upload + filesystem serve) — multipart + IO,
#     e2e perekriaet realnym fail'om.
COVER_PKGS := budget-go,budget-go/config,budget-go/handlers,budget-go/middleware,budget-go/models,budget-go/repository

.PHONY: test-backend-cover
test-backend-cover: ## Run Go tests with coverage profile (backend/coverage.out + cover.html)
	cd $(BACKEND_DIR) && $(GO) test -race -covermode=atomic -coverpkg=$(COVER_PKGS) -coverprofile=coverage.out.raw ./...
	@# Drop e2e-territory files (Excel/PDF/icons upload) — see COVER_PKGS comment.
	@cd $(BACKEND_DIR) && grep -v -E '^budget-go/(handlers/(export|icons)\.go|main\.go):' coverage.out.raw > coverage.out
	@rm -f $(BACKEND_DIR)/coverage.out.raw
	cd $(BACKEND_DIR) && $(GO) tool cover -func=coverage.out | tail -1
	cd $(BACKEND_DIR) && $(GO) tool cover -html=coverage.out -o cover.html
	@echo "Coverage report: $(BACKEND_DIR)/cover.html"

.PHONY: test-backend-integration
test-backend-integration: ## Run Go integration tests (requires Docker for testcontainers)
	cd $(BACKEND_DIR) && $(GO) test -race -tags=integration -run Integration ./...

.PHONY: test-web
test-web: ## Run Vitest unit tests on the Vue frontend
	cd $(FRONTEND_DIR) && $(YARN) test

.PHONY: test-web-cover
test-web-cover: ## Run Vitest with coverage (frontend/coverage/index.html)
	cd $(FRONTEND_DIR) && $(YARN) test:coverage
	@echo "Coverage report: $(FRONTEND_DIR)/coverage/index.html"

# ─── Install / Update ────────────────────────────────────────────────────────

.PHONY: install
install: ## Install all dependencies (Go + Node)
	cd $(BACKEND_DIR)  && GOPROXY=https://proxy.golang.org,direct $(GO) mod download
	cd $(FRONTEND_DIR) && $(YARN) install --immutable

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
	cd $(FRONTEND_DIR) && $(YARN) build

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

SWAG := $(shell command -v swag 2>/dev/null || { command -v go >/dev/null 2>&1 && echo $$(go env GOPATH)/bin/swag; })

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
