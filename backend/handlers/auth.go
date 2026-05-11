package handlers

import (
	"net/http"
	"time"

	"budget-go/config"
	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

type AuthHandler struct {
	repo *repository.UserRepository
	cfg  *config.Config
}

func NewAuthHandler(repo *repository.UserRepository, cfg *config.Config) *AuthHandler {
	return &AuthHandler{repo: repo, cfg: cfg}
}

// Login godoc
// @Summary      Логин и выдача JWT
// @Description  Возвращает 24-часовой JWT. Тот же текст ошибки для несуществующего логина и неверного пароля (anti-enumeration).
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

	expiresAt := time.Now().Add(24 * time.Hour)
	claims := &models.Claims{
		UserID:      user.ID.Hex(),
		Login:       user.Login,
		DisplayName: user.DisplayName,
		AvatarURL:   user.AvatarURL,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenStr, err := token.SignedString([]byte(h.cfg.JWTSecret))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Ошибка генерации токена"})
		return
	}

	c.JSON(http.StatusOK, models.LoginResponse{
		Token:       tokenStr,
		UserID:      user.ID.Hex(),
		DisplayName: user.DisplayName,
		AvatarURL:   user.AvatarURL,
		ExpiresAt:   expiresAt,
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
