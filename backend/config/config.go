package config

import (
	"fmt"
	"log"
	"net/url"
	"os"
	"strings"
)

type Config struct {
	MongoURI     string
	DBName       string
	Port         string
	FontPath     string
	JWTSecret    string
	ServiceToken string // Shared secret for trusted server-side integrations (Telegram bot) acting on behalf of a user via X-Act-As-User. Empty = service-auth disabled.
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

	return &Config{
		MongoURI:     mongoURI,
		DBName:       dbName,
		Port:         getEnv("PORT", "8080"),
		FontPath:     getEnv("PDF_FONT_PATH", "/usr/share/fonts/dejavu/DejaVuSans.ttf"),
		JWTSecret:    jwt,
		ServiceToken: svcToken,
	}
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
