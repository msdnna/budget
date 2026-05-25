package config

import (
	"os"
	"testing"
)

// unsetEnv removes a variable for the duration of a single test (Setenv only
// sets to a value, never unsets; getEnv distinguishes "unset" from "empty").
func unsetEnv(t *testing.T, key string) {
	t.Helper()
	prev, had := os.LookupEnv(key)
	if err := os.Unsetenv(key); err != nil {
		t.Fatalf("unset %s: %v", key, err)
	}
	t.Cleanup(func() {
		if had {
			_ = os.Setenv(key, prev)
		} else {
			_ = os.Unsetenv(key)
		}
	})
}

func TestNew_DevDefaults(t *testing.T) {
	unsetEnv(t, "APP_ENV")
	unsetEnv(t, "JWT_SECRET")
	unsetEnv(t, "MONGO_URI")
	unsetEnv(t, "MONGO_USERNAME")
	unsetEnv(t, "MONGO_PASSWORD")
	unsetEnv(t, "MONGO_DB")
	unsetEnv(t, "MONGO_HOST")
	unsetEnv(t, "MONGO_PORT")
	unsetEnv(t, "DB_NAME")
	unsetEnv(t, "PORT")
	unsetEnv(t, "PDF_FONT_PATH")

	cfg := New()
	if cfg.JWTSecret == "" {
		t.Fatal("expected dev JWT default, got empty")
	}
	if cfg.MongoURI == "" {
		t.Fatal("expected dev MongoURI default, got empty")
	}
	if cfg.DBName != "budget" {
		t.Errorf("DBName = %q, want %q", cfg.DBName, "budget")
	}
	if cfg.Port != "8080" {
		t.Errorf("Port = %q, want %q", cfg.Port, "8080")
	}
	if cfg.FontPath == "" {
		t.Error("FontPath should fall back to default, got empty")
	}
}

func TestNew_RespectsEnv(t *testing.T) {
	t.Setenv("APP_ENV", "")
	t.Setenv("JWT_SECRET", "thisIsAReallyLongJWTSecretFor32+chars")
	t.Setenv("MONGO_URI", "mongodb://foo:bar@host:27017/db")
	t.Setenv("DB_NAME", "custom_db")
	t.Setenv("PORT", "9090")
	t.Setenv("PDF_FONT_PATH", "/tmp/font.ttf")

	cfg := New()
	if cfg.JWTSecret != "thisIsAReallyLongJWTSecretFor32+chars" {
		t.Errorf("JWTSecret not picked up from env: %q", cfg.JWTSecret)
	}
	if cfg.MongoURI != "mongodb://foo:bar@host:27017/db" {
		t.Errorf("MongoURI not picked up from env: %q", cfg.MongoURI)
	}
	if cfg.DBName != "custom_db" {
		t.Errorf("DBName = %q, want %q", cfg.DBName, "custom_db")
	}
	if cfg.Port != "9090" {
		t.Errorf("Port = %q, want %q", cfg.Port, "9090")
	}
	if cfg.FontPath != "/tmp/font.ttf" {
		t.Errorf("FontPath = %q", cfg.FontPath)
	}
}

func TestNew_StitchesMongoURIFromCreds(t *testing.T) {
	unsetEnv(t, "APP_ENV")
	unsetEnv(t, "JWT_SECRET")
	unsetEnv(t, "MONGO_URI")
	unsetEnv(t, "DB_NAME")
	t.Setenv("MONGO_USERNAME", "ad@min")
	t.Setenv("MONGO_PASSWORD", "p@ss/w?d")
	t.Setenv("MONGO_DB", "budget_loadtest")
	t.Setenv("MONGO_HOST", "db.example.com")
	t.Setenv("MONGO_PORT", "27018")

	cfg := New()
	// Both username and password must round-trip through url.QueryEscape so
	// at-signs / slashes / question-marks don't get parsed as URI delimiters.
	const want = "mongodb://ad%40min:p%40ss%2Fw%3Fd@db.example.com:27018/budget_loadtest?authSource=admin"
	if cfg.MongoURI != want {
		t.Errorf("MongoURI =\n  %q\n  want %q", cfg.MongoURI, want)
	}
	if cfg.DBName != "budget_loadtest" {
		t.Errorf("DBName = %q, want %q", cfg.DBName, "budget_loadtest")
	}
}

func TestNew_DBNameFallsBackToMongoDB(t *testing.T) {
	// DB_NAME unset, MONGO_DB set → DBName inherits MONGO_DB. Mirrors the
	// docker-compose convention where backend reads DB_NAME but the rest of
	// the .env only exposes MONGO_DB.
	unsetEnv(t, "APP_ENV")
	unsetEnv(t, "JWT_SECRET")
	unsetEnv(t, "DB_NAME")
	t.Setenv("MONGO_URI", "mongodb://x:y@h:1/zzz")
	t.Setenv("MONGO_DB", "custom_loadtest")

	cfg := New()
	if cfg.DBName != "custom_loadtest" {
		t.Errorf("DBName = %q, want %q", cfg.DBName, "custom_loadtest")
	}
}

func TestNew_ShortJWTSecretStillBoots(t *testing.T) {
	t.Setenv("APP_ENV", "")
	t.Setenv("JWT_SECRET", "short")
	t.Setenv("MONGO_URI", "mongodb://localhost")

	cfg := New()
	if cfg.JWTSecret != "short" {
		t.Errorf("expected short secret preserved, got %q", cfg.JWTSecret)
	}
}
