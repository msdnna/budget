package middleware

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"budget-go/config"
	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// Auth — основной middleware: проверяет либо обычный пользовательский
// Bearer-JWT, либо service-token + `X-Act-As-User` (используется
// telegram-ботом для проксирования действий пользователя).
//
// Если в запросе присутствует заголовок `X-Service-Token`, входим в
// service-ветку: token сравнивается constant-time с cfg.ServiceToken, и
// `X-Act-As-User: <user_id>` подгружает пользователя из БД. Контекст
// заполняется так же, как при обычном JWT — downstream-хендлерам нет
// разницы, кто отправил запрос.
//
// userRepo — опциональный (только для service-пути). Если nil, service-ветка
// фактически отключена: тесты, где не нужен бот, могут передавать nil.
func Auth(cfg *config.Config, userRepo *repository.UserRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		if svc := c.GetHeader("X-Service-Token"); svc != "" {
			authenticateService(c, cfg, userRepo, svc)
			return
		}
		authenticateJWT(c, cfg)
	}
}

func authenticateJWT(c *gin.Context, cfg *config.Config) {
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
	// Refresh tokens are short-circuited here — they're only valid at
	// `/auth/refresh`. Empty TokenType is treated as access for
	// backwards compatibility with pre-refresh-rollout tokens still
	// living in clients' localStorage.
	if claims.TokenType == models.TokenTypeRefresh {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Используйте access-токен"})
		return
	}

	c.Set("claims", claims)
	c.Set("user_id", claims.UserID)
	c.Set("display_name", claims.DisplayName)
	c.Set("avatar_url", claims.AvatarURL)
	c.Next()
}

func authenticateService(c *gin.Context, cfg *config.Config, userRepo *repository.UserRepository, presented string) {
	if cfg.ServiceToken == "" || userRepo == nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Service auth disabled"})
		return
	}
	if subtle.ConstantTimeCompare([]byte(presented), []byte(cfg.ServiceToken)) != 1 {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid service token"})
		return
	}
	actAs := c.GetHeader("X-Act-As-User")
	if actAs == "" {
		c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "X-Act-As-User required"})
		return
	}
	u, err := userRepo.FindByID(c.Request.Context(), actAs)
	if err != nil || u == nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "User not found"})
		return
	}
	if u.DeletedAt != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Пользователь удалён"})
		return
	}
	if u.BlockedAt != nil {
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "Учётная запись заблокирована"})
		return
	}
	// Synthesize claims so downstream handlers see the user identity
	// identically to a real JWT request. `TokenType` is left empty because
	// existing access-token middleware treats absent type as access.
	claims := &models.Claims{
		UserID:      u.ID.Hex(),
		Login:       u.Login,
		DisplayName: u.DisplayName,
		AvatarURL:   u.AvatarURL,
		IsAdmin:     u.IsAdmin,
	}
	c.Set("claims", claims)
	c.Set("user_id", claims.UserID)
	c.Set("display_name", claims.DisplayName)
	c.Set("avatar_url", claims.AvatarURL)
	c.Set("is_service", true)
	c.Next()
}

// ServiceOnly — для эндпоинтов, доступных только server-to-server
// (telegram/link/confirm, telegram/me). Проверяет только X-Service-Token,
// не требует X-Act-As-User.
func ServiceOnly(cfg *config.Config) gin.HandlerFunc {
	return func(c *gin.Context) {
		svc := c.GetHeader("X-Service-Token")
		if svc == "" || cfg.ServiceToken == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Service token required"})
			return
		}
		if subtle.ConstantTimeCompare([]byte(svc), []byte(cfg.ServiceToken)) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Invalid service token"})
			return
		}
		c.Set("is_service", true)
		c.Next()
	}
}
