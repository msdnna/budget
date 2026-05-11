package config

import (
	"log"
	"os"
	"strings"
)

type Config struct {
	MongoURI  string
	DBName    string
	Port      string
	FontPath  string
	JWTSecret string
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

	mongoURI := os.Getenv("MONGO_URI")
	if mongoURI == "" {
		if prod {
			log.Fatal("MONGO_URI is required in production (APP_ENV=production)")
		}
		mongoURI = "mongodb://admin:password@localhost:27017/budget?authSource=admin"
		log.Println("WARNING: MONGO_URI not set — using local dev default")
	}

	return &Config{
		MongoURI:  mongoURI,
		DBName:    getEnv("DB_NAME", "budget"),
		Port:      getEnv("PORT", "8080"),
		FontPath:  getEnv("PDF_FONT_PATH", "/usr/share/fonts/dejavu/DejaVuSans.ttf"),
		JWTSecret: jwt,
	}
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
