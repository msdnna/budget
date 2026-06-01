package config

import (
	"fmt"
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	MongoURI     string
	DBName       string
	Port         string
	FontPath     string
	JWTSecret    string
	ServiceToken string // Shared secret for trusted server-side integrations (Telegram bot) acting on behalf of a user via X-Act-As-User. Empty = service-auth disabled.

	SentryDSN        string  // Sentry ingest endpoint. Empty = Sentry disabled (telemetry becomes a no-op).
	SentryEnv        string  // Sentry "environment" tag (e.g. development/production). Defaults to APP_ENV, else "development".
	SentryTracesRate float64 // Performance/tracing sample rate [0..1]. 0 disables tracing; defaults to 1.0 (low-traffic app).

	SentryFrontendDSN        string  // DSN for the web (Vue) Sentry project, handed to the browser via /api/client-config. Empty = frontend Sentry disabled. The host must be reachable FROM this backend (it's also the /api/sentry-tunnel upstream); the browser ignores it because events go through the tunnel.
	SentryFrontendTracesRate float64 // Frontend performance sample rate [0..1]. Defaults to 0.1 (browser traces are voluminous).
}

// New reads configuration from the environment. In production (`APP_ENV=production`)
// JWT_SECRET and MONGO_URI must be explicitly set — fail-closed prevents the binary
// from accidentally booting with the dev defaults.
func New() *Config {
	prod := strings.EqualFold(os.Getenv("APP_ENV"), "production")

	jwt := os.Getenv("JWT_SECRET")
	if jwt == "" {
		if prod {
			log.Fatal("JWT_SECRET is required in production (APP_ENV=production)")
		}
		jwt = "dev-secret-change-in-production-min32chars!"
		log.Println("WARNING: JWT_SECRET not set — using dev default (do NOT use in production)")
	} else if len(jwt) < 32 {
		log.Printf("WARNING: JWT_SECRET is shorter than 32 chars (%d) — generate a stronger one", len(jwt))
	}

	dbName := getEnv("DB_NAME", getEnv("MONGO_DB", "budget"))
	mongoURI := os.Getenv("MONGO_URI")
	if mongoURI == "" {
		// Compose files set MONGO_URI for the backend container, but dev
		// tooling (cmd/migrate, cmd/create_user, cmd/seed_loadtest) is
		// usually run from the host with just MONGO_USERNAME / MONGO_PASSWORD /
		// MONGO_DB in .env. Stitch a URI from those so we don't have to
		// duplicate creds — falling back to localhost:27017 (the exposed
		// dev-compose port) when host/port aren't provided. Prod still
		// requires MONGO_URI explicitly so we don't accidentally point
		// at localhost from a misconfigured server.
		if user := os.Getenv("MONGO_USERNAME"); user != "" {
			pass := os.Getenv("MONGO_PASSWORD")
			host := getEnv("MONGO_HOST", "localhost")
			port := getEnv("MONGO_PORT", "27017")
			mongoURI = fmt.Sprintf(
				"mongodb://%s:%s@%s:%s/%s?authSource=admin",
				url.QueryEscape(user), url.QueryEscape(pass), host, port, dbName,
			)
		}
	}
	if mongoURI == "" {
		if prod {
			log.Fatal("MONGO_URI is required in production (APP_ENV=production)")
		}
		mongoURI = "mongodb://admin:password@localhost:27017/budget?authSource=admin"
		log.Println("WARNING: MONGO_URI not set — using local dev default")
	}

	svcToken := os.Getenv("SERVICE_TOKEN")
	if svcToken != "" && len(svcToken) < 32 {
		log.Printf("WARNING: SERVICE_TOKEN is shorter than 32 chars (%d) — generate a stronger one", len(svcToken))
	}

	// Sentry environment tag — prefer an explicit SENTRY_ENV, otherwise mirror
	// APP_ENV so prod/dev are labelled consistently, falling back to "development".
	sentryEnv := getEnv("SENTRY_ENV", getEnv("APP_ENV", "development"))

	// Default to capturing every transaction: this is a low-traffic family app,
	// so full sampling gives complete traces without meaningful overhead. Bad
	// values fall back to the default rather than silently disabling tracing.
	tracesRate := parseRate("SENTRY_TRACES_SAMPLE_RATE", 1.0)
	frontendTracesRate := parseRate("SENTRY_FRONTEND_TRACES_SAMPLE_RATE", 0.1)

	return &Config{
		MongoURI:     mongoURI,
		DBName:       dbName,
		Port:         getEnv("PORT", "8080"),
		FontPath:     getEnv("PDF_FONT_PATH", "/usr/share/fonts/dejavu/DejaVuSans.ttf"),
		JWTSecret:    jwt,
		ServiceToken: svcToken,

		SentryDSN:        os.Getenv("SENTRY_DSN"),
		SentryEnv:        sentryEnv,
		SentryTracesRate: tracesRate,

		SentryFrontendDSN:        os.Getenv("SENTRY_FRONTEND_DSN"),
		SentryFrontendTracesRate: frontendTracesRate,
	}
}

// parseRate reads a [0..1] sample rate from env, falling back to def on an
// absent or invalid value (so a typo never silently disables sampling).
func parseRate(key string, def float64) float64 {
	raw := os.Getenv(key)
	if raw == "" {
		return def
	}
	if v, err := strconv.ParseFloat(raw, 64); err == nil && v >= 0 && v <= 1 {
		return v
	}
	log.Printf("WARNING: invalid %s %q — using default %.2f", key, raw, def)
	return def
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
