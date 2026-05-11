package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func init() {
	gin.SetMode(gin.TestMode)
}

func TestVersionHandler_Get(t *testing.T) {
	t.Setenv("ANDROID_LATEST", "1.29.2")
	t.Setenv("ANDROID_MIN_REQUIRED", "1.20.0")
	h := NewVersionHandler("1.14.2")

	r := gin.New()
	r.GET("/version", h.Get)

	req := httptest.NewRequest(http.MethodGet, "/version", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	var got map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("invalid json: %v", err)
	}
	if got["api"] != "1.14.2" {
		t.Errorf("api = %q, want 1.14.2", got["api"])
	}
	if got["android_latest"] != "1.29.2" {
		t.Errorf("android_latest = %q, want 1.29.2", got["android_latest"])
	}
	if got["android_min_required"] != "1.20.0" {
		t.Errorf("android_min_required = %q, want 1.20.0", got["android_min_required"])
	}
}

func TestVersionHandler_EmptyEnv(t *testing.T) {
	t.Setenv("ANDROID_LATEST", "")
	t.Setenv("ANDROID_MIN_REQUIRED", "")
	h := NewVersionHandler("1.0.0")

	r := gin.New()
	r.GET("/version", h.Get)

	req := httptest.NewRequest(http.MethodGet, "/version", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	var got map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &got)
	if got["android_latest"] != "" {
		t.Errorf("android_latest should be empty, got %q", got["android_latest"])
	}
}
