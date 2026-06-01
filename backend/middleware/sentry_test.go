package middleware

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/getsentry/sentry-go"
	sentrygin "github.com/getsentry/sentry-go/gin"
	"github.com/gin-gonic/gin"
)

// mockTransport captures events in-memory so we can assert on what
// SentryReport sends without a real Sentry server.
type mockTransport struct {
	mu     sync.Mutex
	events []*sentry.Event
}

func (t *mockTransport) Configure(sentry.ClientOptions) {}
func (t *mockTransport) SendEvent(e *sentry.Event) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.events = append(t.events, e)
}
func (t *mockTransport) Flush(time.Duration) bool              { return true }
func (t *mockTransport) FlushWithContext(context.Context) bool { return true }
func (t *mockTransport) Close()                                {}
func (t *mockTransport) all() []*sentry.Event {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]*sentry.Event(nil), t.events...)
}

func newSentryRouter() *gin.Engine {
	r := gin.New()
	r.Use(sentrygin.New(sentrygin.Options{}))
	r.Use(SentryReport())
	r.GET("/ok", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	r.GET("/boom", func(c *gin.Context) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "kaboom"})
	})
	return r
}

func TestSentryReport_Captures5xx(t *testing.T) {
	transport := &mockTransport{}
	if err := sentry.Init(sentry.ClientOptions{Dsn: "http://key@localhost/1", Transport: transport}); err != nil {
		t.Fatalf("sentry init: %v", err)
	}
	t.Cleanup(func() { sentry.CurrentHub().BindClient(nil) }) // isolate global state

	r := newSentryRouter()

	// 2xx must not produce an event.
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/ok", nil))
	if n := len(transport.all()); n != 0 {
		t.Fatalf("200 produced %d events, want 0", n)
	}

	// 5xx must produce exactly one error event with the route context.
	w = httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/boom", nil))

	events := transport.all()
	if len(events) != 1 {
		t.Fatalf("500 produced %d events, want 1", len(events))
	}
	e := events[0]
	if e.Level != sentry.LevelError {
		t.Errorf("level = %v, want %v", e.Level, sentry.LevelError)
	}
	if e.Tags["http.status_code"] != "500" {
		t.Errorf("http.status_code tag = %q, want 500", e.Tags["http.status_code"])
	}
	if e.Tags["http.route"] != "/boom" {
		t.Errorf("http.route tag = %q, want /boom", e.Tags["http.route"])
	}
	if ctx, ok := e.Contexts["budget"]; !ok || ctx["response_body"] == nil {
		t.Errorf("response_body not captured in contexts: %+v", e.Contexts)
	}
}

func TestSentryReport_DisabledIsNoop(t *testing.T) {
	// No client bound → middleware must short-circuit and never panic.
	sentry.CurrentHub().BindClient(nil)

	r := newSentryRouter()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/boom", nil))
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500 (handler must still run)", w.Code)
	}
}
