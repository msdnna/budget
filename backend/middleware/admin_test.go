package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"budget-go/models"

	"github.com/gin-gonic/gin"
)

func newAdminRouter() *gin.Engine {
	r := gin.New()
	r.GET("/admin", AdminRequired(), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})
	return r
}

func newAdminRouterWithClaims(claims interface{}) *gin.Engine {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("claims", claims)
		c.Next()
	})
	r.GET("/admin", AdminRequired(), func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})
	return r
}

func TestAdminRequired_NoClaims_401(t *testing.T) {
	r := newAdminRouter()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/admin", nil))
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401 when claims missing", w.Code)
	}
}

func TestAdminRequired_NonAdmin_403(t *testing.T) {
	r := newAdminRouterWithClaims(&models.Claims{UserID: "u1", IsAdmin: false})
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/admin", nil))
	if w.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403 for non-admin", w.Code)
	}
}

func TestAdminRequired_WrongClaimsType_403(t *testing.T) {
	r := newAdminRouterWithClaims("not-a-claims-struct")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/admin", nil))
	if w.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403 when claims type is wrong", w.Code)
	}
}

func TestAdminRequired_Admin_Passes(t *testing.T) {
	r := newAdminRouterWithClaims(&models.Claims{UserID: "admin-1", IsAdmin: true})
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/admin", nil))
	if w.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 for admin claims; body=%s", w.Code, w.Body.String())
	}
}
