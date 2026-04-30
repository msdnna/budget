package middleware

import (
	"net/http"
	"strings"

	"budget-go/config"
	"budget-go/models"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

func Auth(cfg *config.Config) gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Требуется аутентификация"})
			return
		}

		tokenStr := strings.TrimPrefix(header, "Bearer ")
		claims := &models.Claims{}
		token, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return []byte(cfg.JWTSecret), nil
		})

		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Неверный или устаревший токен"})
			return
		}

		c.Set("claims", claims)
		c.Set("user_id", claims.UserID)
		c.Set("display_name", claims.DisplayName)
		c.Set("avatar_url", claims.AvatarURL)
		c.Next()
	}
}
