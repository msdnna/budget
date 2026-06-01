package handlers

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func init() { gin.SetMode(gin.TestMode) }

func sentryRouter(h *SentryConfigHandler) *gin.Engine {
	r := gin.New()
	r.GET("/api/client-config", h.ClientConfig)
	r.POST("/api/sentry-tunnel", h.Tunnel)
	return r
}

func TestEnvelopeURLFromDSN(t *testing.T) {
	got, err := envelopeURLFromDSN("http://abc123@host.docker.internal:9100/3")
	if err != nil {
		t.Fatalf("unexpected err: %v", err)
	}
	if want := "http://host.docker.internal:9100/api/3/envelope/"; got != want {
		t.Errorf("got %q, want %q", got, want)
	}
	if _, err := envelopeURLFromDSN("http://nokey-no-project"); err == nil {
		t.Error("expected error for DSN without project id")
	}
}

func TestClientConfig_Disabled(t *testing.T) {
	r := sentryRouter(NewSentryConfigHandler("", "development", 0.1))

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/client-config", nil))

	var body struct {
		Sentry *map[string]any `json:"sentry"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Sentry != nil {
		t.Errorf("sentry should be null when disabled, got %v", *body.Sentry)
	}

	// Tunnel is a no-op 204 when disabled.
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/sentry-tunnel", strings.NewReader("x")))
	if w.Code != http.StatusNoContent {
		t.Errorf("disabled tunnel status = %d, want 204", w.Code)
	}
}

func TestClientConfig_Enabled(t *testing.T) {
	r := sentryRouter(NewSentryConfigHandler("http://key@localhost:9100/3", "production", 0.25))

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/api/client-config", nil))

	var body struct {
		Sentry struct {
			DSN              string  `json:"dsn"`
			Environment      string  `json:"environment"`
			TracesSampleRate float64 `json:"tracesSampleRate"`
			Tunnel           string  `json:"tunnel"`
		} `json:"sentry"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Sentry.DSN != "http://key@localhost:9100/3" {
		t.Errorf("dsn = %q", body.Sentry.DSN)
	}
	if body.Sentry.Environment != "production" {
		t.Errorf("environment = %q", body.Sentry.Environment)
	}
	if body.Sentry.TracesSampleRate != 0.25 {
		t.Errorf("tracesSampleRate = %v", body.Sentry.TracesSampleRate)
	}
	if body.Sentry.Tunnel != "/api/sentry-tunnel" {
		t.Errorf("tunnel = %q", body.Sentry.Tunnel)
	}
}

func TestTunnel_ForwardsToUpstream(t *testing.T) {
	var gotPath, gotBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		gotPath = req.URL.Path
		b, _ := io.ReadAll(req.Body)
		gotBody = string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	// DSN host = the fake upstream; project 7 → /api/7/envelope/.
	dsn := "http://pubkey@" + strings.TrimPrefix(upstream.URL, "http://") + "/7"
	r := sentryRouter(NewSentryConfigHandler(dsn, "development", 0.1))

	const envelope = `{"event_id":"abc"}` + "\n" + `{"type":"event"}`
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodPost, "/api/sentry-tunnel", strings.NewReader(envelope)))

	if w.Code != http.StatusOK {
		t.Fatalf("tunnel status = %d, want 200", w.Code)
	}
	if gotPath != "/api/7/envelope/" {
		t.Errorf("upstream path = %q, want /api/7/envelope/", gotPath)
	}
	if gotBody != envelope {
		t.Errorf("upstream body = %q, want %q", gotBody, envelope)
	}
}
