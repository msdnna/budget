package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"budget-go/config"
	"budget-go/models"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

func init() {
	gin.SetMode(gin.TestMode)
}

func newTestRouter(cfg *config.Config) *gin.Engine {
	r := gin.New()
	// userRepo == nil here — the JWT path doesn't need it, and the
	// service-token branch is exercised separately in service_auth_test.go
	// (which spins up a real Mongo via testcontainers).
	r.GET("/protected", Auth(cfg, nil), func(c *gin.Context) {
		uid := c.GetString("user_id")
		c.JSON(http.StatusOK, gin.H{"user_id": uid})
	})
	return r
}

func signToken(t *testing.T, secret string, claims *models.Claims, method jwt.SigningMethod) string {
	t.Helper()
	if method == nil {
		method = jwt.SigningMethodHS256
	}
	tok := jwt.NewWithClaims(method, claims)
	var s string
	var err error
	if method == jwt.SigningMethodNone {
		s, err = tok.SignedString(jwt.UnsafeAllowNoneSignatureType)
	} else {
		s, err = tok.SignedString([]byte(secret))
	}
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

func TestAuth_NoHeader(t *testing.T) {
	cfg := &config.Config{JWTSecret: "test-secret"}
	r := newTestRouter(cfg)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestAuth_BadPrefix(t *testing.T) {
	cfg := &config.Config{JWTSecret: "test-secret"}
	r := newTestRouter(cfg)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Token abc")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestAuth_GarbageToken(t *testing.T) {
	cfg := &config.Config{JWTSecret: "test-secret"}
	r := newTestRouter(cfg)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer not.a.jwt")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestAuth_WrongSecret(t *testing.T) {
	cfg := &config.Config{JWTSecret: "the-server-secret"}
	r := newTestRouter(cfg)

	claims := &models.Claims{
		UserID: "u1",
		Login:  "alice",
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	}
	tok := signToken(t, "wrong-secret", claims, nil)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestAuth_Expired(t *testing.T) {
	cfg := &config.Config{JWTSecret: "the-server-secret"}
	r := newTestRouter(cfg)

	claims := &models.Claims{
		UserID: "u1",
		Login:  "alice",
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(-time.Hour)),
		},
	}
	tok := signToken(t, "the-server-secret", claims, nil)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestAuth_NonHMACAlgorithmRejected(t *testing.T) {
	cfg := &config.Config{JWTSecret: "the-server-secret"}
	r := newTestRouter(cfg)

	claims := &models.Claims{
		UserID: "u1",
		Login:  "alice",
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	}
	tok := signToken(t, "", claims, jwt.SigningMethodNone)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401 for non-HMAC alg", w.Code)
	}
}

func TestAuth_ValidToken_SetsContext(t *testing.T) {
	cfg := &config.Config{JWTSecret: "the-server-secret"}
	r := newTestRouter(cfg)

	claims := &models.Claims{
		UserID:      "user-123",
		Login:       "alice",
		DisplayName: "Alice",
		AvatarURL:   "https://example/a.png",
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	}
	tok := signToken(t, "the-server-secret", claims, nil)

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if want := `"user_id":"user-123"`; !contains(w.Body.String(), want) {
		t.Errorf("body = %s, want substring %q", w.Body.String(), want)
	}
}

func contains(haystack, needle string) bool {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
