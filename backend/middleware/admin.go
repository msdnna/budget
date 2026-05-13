package middleware

import (
	"net/http"

	"budget-go/models"

	"github.com/gin-gonic/gin"
)

// AdminRequired aborts with 403 unless the JWT claims declare is_admin.
// Always pair with Auth() — relies on the "claims" context key it sets.
func AdminRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		raw, ok := c.Get("claims")
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Требуется аутентификация"})
			return
		}
		claims, ok := raw.(*models.Claims)
		if !ok || !claims.IsAdmin {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "Доступ только для администратора"})
			return
		}
		c.Next()
	}
}
