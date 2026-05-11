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

func TestNew_ShortJWTSecretStillBoots(t *testing.T) {
	t.Setenv("APP_ENV", "")
	t.Setenv("JWT_SECRET", "short")
	t.Setenv("MONGO_URI", "mongodb://localhost")

	cfg := New()
	if cfg.JWTSecret != "short" {
		t.Errorf("expected short secret preserved, got %q", cfg.JWTSecret)
	}
}
