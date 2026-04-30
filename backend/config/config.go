package config

import "os"

type Config struct {
	MongoURI  string
	DBName    string
	Port      string
	FontPath  string
	JWTSecret string
}

func New() *Config {
	return &Config{
		MongoURI:  getEnv("MONGO_URI", "mongodb://admin:password@localhost:27017/budget?authSource=admin"),
		DBName:    getEnv("DB_NAME", "budget"),
		Port:      getEnv("PORT", "8080"),
		FontPath:  getEnv("PDF_FONT_PATH", "/usr/share/fonts/dejavu/DejaVuSans.ttf"),
		JWTSecret: getEnv("JWT_SECRET", "dev-secret-change-in-production-min32chars!"),
	}
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
