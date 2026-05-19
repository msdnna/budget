package handlers

import (
	"net/http"
	"strings"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/mongo"
	"golang.org/x/crypto/bcrypt"
)

// SetupHandler exposes the bootstrap flow used by the web wizard on a
// fresh install. It is gated by `userRepo.CountAll == 0` — once any user
// exists, Init() refuses and the wizard's pre-flight Status() switches
// to needs_setup=false, sending the client to the normal login flow.
type SetupHandler struct {
	userRepo *repository.UserRepository
	auth     *AuthHandler
}

func NewSetupHandler(userRepo *repository.UserRepository, auth *AuthHandler) *SetupHandler {
	return &SetupHandler{userRepo: userRepo, auth: auth}
}

type SetupStatusResponse struct {
	NeedsSetup bool `json:"needs_setup"`
}

// Status godoc
// @Summary      Проверка first-run setup
// @Description  Возвращает needs_setup=true, если в БД нет ни одного активного пользователя. Публичный эндпоинт без auth — фронт вызывает его на старте.
// @Tags         setup
// @Produce      json
// @Success      200  {object}  SetupStatusResponse
// @Router       /setup/status [get]
func (h *SetupHandler) Status(c *gin.Context) {
	count, err := h.userRepo.CountAll(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, SetupStatusResponse{NeedsSetup: count == 0})
}

// Init godoc
// @Summary      Создать первого админа (first-run wizard)
// @Description  Доступен только когда в БД нет пользователей. Создаёт админа и сразу выдаёт access+refresh пару (как /auth/login). Повторный вызов после успеха отдаёт 409.
// @Tags         setup
// @Accept       json
// @Produce      json
// @Param        body  body      models.CreateUserRequest  true  "login/password/display_name"
// @Success      200   {object}  models.LoginResponse
// @Failure      400   {object}  map[string]string
// @Failure      409   {object}  map[string]string
// @Router       /setup/init [post]
func (h *SetupHandler) Init(c *gin.Context) {
	count, err := h.userRepo.CountAll(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if count > 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "Установка уже выполнена"})
		return
	}

	var req models.CreateUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	req.Login = strings.TrimSpace(req.Login)
	req.DisplayName = strings.TrimSpace(req.DisplayName)
	if req.Login == "" || req.DisplayName == "" || len(req.Password) < 4 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "login, display_name обязательны; пароль ≥ 4 символов"})
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), 12)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	u := &models.User{
		Login:        req.Login,
		PasswordHash: string(hash),
		DisplayName:  req.DisplayName,
		IsAdmin:      true,
		CreatedAt:    time.Now(),
	}
	if err := h.userRepo.Create(c.Request.Context(), u); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			c.JSON(http.StatusConflict, gin.H{"error": "Логин уже занят"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	access, refresh, expires, err := h.auth.issueTokens(u)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, models.LoginResponse{
		Token:        access,
		RefreshToken: refresh,
		UserID:       u.ID.Hex(),
		DisplayName:  u.DisplayName,
		AvatarURL:    u.AvatarURL,
		IsAdmin:      u.IsAdmin,
		ExpiresAt:    expires,
	})
}
