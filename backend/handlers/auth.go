package handlers

import (
	"net/http"
	"time"

	"budget-go/config"
	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
)

type AuthHandler struct {
	repo *repository.UserRepository
	cfg  *config.Config
}

func NewAuthHandler(repo *repository.UserRepository, cfg *config.Config) *AuthHandler {
	return &AuthHandler{repo: repo, cfg: cfg}
}

const (
	accessTokenTTL  = 24 * time.Hour
	refreshTokenTTL = 30 * 24 * time.Hour // 30 days — covers a month of inactivity
)

// issueTokens mints fresh access + refresh JWTs for the user. Refresh
// tokens carry only the identity claims needed to re-mint the next access
// pair on `/auth/refresh`; we still embed the display/admin fields so the
// refresh response can be a drop-in replacement during the rotate.
func (h *AuthHandler) issueTokens(user *models.User) (accessStr, refreshStr string, accessExp time.Time, err error) {
	now := time.Now()
	accessExp = now.Add(accessTokenTTL)
	// Each token carries a fresh `jti` (JWT ID) so successive refreshes
	// — which often happen within the same wall-clock second — produce
	// distinct signed strings. Lets clients diff old/new tokens reliably.
	access := &models.Claims{
		UserID:      user.ID.Hex(),
		Login:       user.Login,
		DisplayName: user.DisplayName,
		AvatarURL:   user.AvatarURL,
		IsAdmin:     user.IsAdmin,
		TokenType:   models.TokenTypeAccess,
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.NewString(),
			ExpiresAt: jwt.NewNumericDate(accessExp),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	refresh := &models.Claims{
		UserID:      user.ID.Hex(),
		Login:       user.Login,
		DisplayName: user.DisplayName,
		AvatarURL:   user.AvatarURL,
		IsAdmin:     user.IsAdmin,
		TokenType:   models.TokenTypeRefresh,
		RegisteredClaims: jwt.RegisteredClaims{
			ID:        uuid.NewString(),
			ExpiresAt: jwt.NewNumericDate(now.Add(refreshTokenTTL)),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	accessStr, err = jwt.NewWithClaims(jwt.SigningMethodHS256, access).SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", time.Time{}, err
	}
	refreshStr, err = jwt.NewWithClaims(jwt.SigningMethodHS256, refresh).SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		return "", "", time.Time{}, err
	}
	return accessStr, refreshStr, accessExp, nil
}

// Login godoc
// @Summary      Логин и выдача access + refresh JWT
// @Description  Возвращает access (24ч) + refresh (30д). Refresh обменивается на новую пару через `/auth/refresh` пока он сам жив. Тот же текст ошибки для несуществующего логина и неверного пароля (anti-enumeration).
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      models.LoginRequest   true  "Креды"
// @Success      200   {object}  models.LoginResponse
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Router       /auth/login [post]
func (h *AuthHandler) Login(c *gin.Context) {
	var req models.LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user, err := h.repo.FindByLogin(c.Request.Context(), req.Login)
	if err != nil {
		// Same error message regardless of whether login exists — prevents user enumeration.
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Неверный логин или пароль"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Неверный логин или пароль"})
		return
	}

	if user.BlockedAt != nil {
		c.JSON(http.StatusForbidden, gin.H{"error": "Учётная запись заблокирована"})
		return
	}

	accessStr, refreshStr, accessExp, err := h.issueTokens(user)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Ошибка генерации токена"})
		return
	}

	c.JSON(http.StatusOK, models.LoginResponse{
		Token:        accessStr,
		RefreshToken: refreshStr,
		UserID:       user.ID.Hex(),
		DisplayName:  user.DisplayName,
		AvatarURL:    user.AvatarURL,
		IsAdmin:      user.IsAdmin,
		ExpiresAt:    accessExp,
	})
}

// Refresh godoc
// @Summary      Обмен refresh-токена на новую пару access + refresh
// @Description  Принимает refresh-JWT в теле, возвращает свежий access + новый refresh (rotation). Refresh должен быть валиден и иметь `token_type=refresh`.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Param        body  body      models.RefreshRequest   true  "refresh_token"
// @Success      200   {object}  models.RefreshResponse
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Router       /auth/refresh [post]
func (h *AuthHandler) Refresh(c *gin.Context) {
	var req models.RefreshRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	claims := &models.Claims{}
	token, err := jwt.ParseWithClaims(req.RefreshToken, claims, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, jwt.ErrSignatureInvalid
		}
		return []byte(h.cfg.JWTSecret), nil
	})
	if err != nil || !token.Valid {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Неверный или устаревший refresh-токен"})
		return
	}
	if claims.TokenType != models.TokenTypeRefresh {
		// Refuse access tokens here so a stolen access token (which is
		// shorter-lived but more frequently transmitted) can't be used
		// to indefinitely refresh sessions.
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Не refresh-токен"})
		return
	}

	user, err := h.repo.FindByID(c.Request.Context(), claims.UserID)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Пользователь не найден"})
		return
	}
	if user.DeletedAt != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Пользователь удалён"})
		return
	}
	if user.BlockedAt != nil {
		c.JSON(http.StatusForbidden, gin.H{"error": "Учётная запись заблокирована"})
		return
	}

	accessStr, refreshStr, accessExp, err := h.issueTokens(user)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Ошибка генерации токена"})
		return
	}
	c.JSON(http.StatusOK, models.RefreshResponse{
		Token:        accessStr,
		RefreshToken: refreshStr,
		ExpiresAt:    accessExp,
	})
}

// Me godoc
// @Summary      Текущий пользователь
// @Description  Возвращает claims текущего JWT.
// @Tags         auth
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Router       /auth/me [get]
func (h *AuthHandler) Me(c *gin.Context) {
	claims := c.MustGet("claims").(*models.Claims)
	c.JSON(http.StatusOK, gin.H{
		"user_id":      claims.UserID,
		"login":        claims.Login,
		"display_name": claims.DisplayName,
		"avatar_url":   claims.AvatarURL,
		"is_admin":     claims.IsAdmin,
	})
}

// ListUsers godoc
// @Summary      Список пользователей семьи
// @Description  Усечённая публичная информация — без password_hash и email.
// @Tags         auth
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}   models.UserInfo
// @Failure      401  {object}  map[string]string
// @Router       /users [get]
func (h *AuthHandler) ListUsers(c *gin.Context) {
	users, err := h.repo.FindAll(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	result := make([]models.UserInfo, 0, len(users))
	for _, u := range users {
		result = append(result, models.UserInfo{
			UserID:      u.ID.Hex(),
			DisplayName: u.DisplayName,
			AvatarURL:   u.AvatarURL,
		})
	}
	c.JSON(http.StatusOK, result)
}
