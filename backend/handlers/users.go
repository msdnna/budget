package handlers

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/mongo"
	"golang.org/x/crypto/bcrypt"
)

// Контекст бэкфилла UserInfo-снимков ограничен отдельно: основная операция
// уже завершилась (avatar/display_name записан в users), а UpdateMany по
// 5 коллекциям должна успеть прожевать даже большую базу. Если родительский
// gin-контекст истекает раньше — backfill всё равно завершается, а 200 OK
// клиенту уже ушёл.
const backfillTimeout = 15 * time.Second

// Размер аватарок берём такой же, как у category-иконок — UI рендерит
// аватар максимум 96×96 (профиль) / 28-32 (списки), 512KB с большим запасом.
const maxAvatarBytes = 512 * 1024

type UserAdminHandler struct {
	repo *repository.UserRepository
	db   *mongo.Database
}

// NewUserAdminHandler. `db` нужен для `repository.BackfillUserInfo` —
// денормализованные snapshot'ы UserInfo в transactions/wishlist/categories/
// detail_requests/category_icons догоняются после смены аватара или
// display_name. Без db backfill вызывать не из чего; nil допустим только в
// тестах, где backfill не проверяется.
func NewUserAdminHandler(repo *repository.UserRepository, db *mongo.Database) *UserAdminHandler {
	return &UserAdminHandler{repo: repo, db: db}
}

// backfillSnapshots — обёртка над repository.BackfillUserInfo с отдельным
// контекстом и логированием. Ошибки не прокидываются клиенту: основная
// операция уже сохранилась, частично-стейлые snapshot'ы лечатся повторной
// заливкой/правкой профиля. `h.db == nil` — тесты, которые не настраивают
// backfill, пропускаем без шума.
func (h *UserAdminHandler) backfillSnapshots(u *models.User) {
	if h.db == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), backfillTimeout)
	defer cancel()
	if err := repository.BackfillUserInfo(ctx, h.db, u.ID.Hex(), u.DisplayName, u.AvatarURL); err != nil {
		// Лог, но не fail — клиент уже видит обновлённый users-документ.
		// Sentry/structured logger пока не подключён, fmt в stdout.
		fmt.Printf("backfill UserInfo for %s failed: %v\n", u.ID.Hex(), err)
	}
}

func toAdminUser(u *models.User) models.AdminUser {
	return models.AdminUser{
		ID:          u.ID.Hex(),
		Login:       u.Login,
		DisplayName: u.DisplayName,
		AvatarURL:   u.AvatarURL,
		IsAdmin:     u.IsAdmin,
		BlockedAt:   u.BlockedAt,
		CreatedAt:   u.CreatedAt,
	}
}

// AdminList godoc
// @Summary      Список пользователей с админ-полями (admin)
// @Description  Возвращает login/is_admin/blocked_at/created_at в дополнение к публичным полям. Не-удалённые пользователи (включая заблокированных).
// @Tags         users
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}  models.AdminUser
// @Router       /admin/users [get]
func (h *UserAdminHandler) AdminList(c *gin.Context) {
	users, err := h.repo.FindAll(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	out := make([]models.AdminUser, 0, len(users))
	for i := range users {
		out = append(out, toAdminUser(&users[i]))
	}
	c.JSON(http.StatusOK, out)
}

// Create godoc
// @Summary      Создать пользователя (admin)
// @Tags         users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateUserRequest  true  "Тело"
// @Success      201   {object}  models.AdminUser
// @Failure      400   {object}  map[string]string
// @Failure      409   {object}  map[string]string  "login already taken"
// @Router       /admin/users [post]
func (h *UserAdminHandler) Create(c *gin.Context) {
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
		IsAdmin:      req.IsAdmin,
	}
	if err := h.repo.Create(c.Request.Context(), u); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			c.JSON(http.StatusConflict, gin.H{"error": "Логин уже используется"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, toAdminUser(u))
}

// Update godoc
// @Summary      Обновить пользователя (admin)
// @Description  Partial PATCH: login / display_name / is_admin / blocked. Safeguards: нельзя снять админку или заблокировать самого себя, нельзя снять админку с последнего админа.
// @Tags         users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                    true  "User ID"
// @Param        body  body      models.UpdateUserRequest  true  "Тело"
// @Success      200   {object}  models.AdminUser
// @Failure      400   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Failure      409   {object}  map[string]string
// @Router       /admin/users/{id} [patch]
func (h *UserAdminHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req models.UpdateUserRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if req.Login != nil {
		trimmed := strings.TrimSpace(*req.Login)
		req.Login = &trimmed
		if trimmed == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "login пустой"})
			return
		}
	}
	if req.DisplayName != nil {
		trimmed := strings.TrimSpace(*req.DisplayName)
		req.DisplayName = &trimmed
		if trimmed == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "display_name пустой"})
			return
		}
	}

	// Self-safety guards — админ не должен «выстрелить себе в ногу» снятием
	// прав/блокировкой собственной учётки. Управление выполняется через
	// другого админа.
	currentUserID := c.GetString("user_id")
	if id == currentUserID {
		if req.IsAdmin != nil && !*req.IsAdmin {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Нельзя снять с себя права администратора"})
			return
		}
		if req.Blocked != nil && *req.Blocked {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Нельзя заблокировать самого себя"})
			return
		}
	}

	// Снятие админки — проверить, что это не последний админ.
	if req.IsAdmin != nil && !*req.IsAdmin {
		count, err := h.repo.CountAdmins(c.Request.Context())
		if err == nil && count <= 1 {
			// Проверяем, что текущий юзер действительно админ — иначе
			// разрешать «снять флаг» нет смысла.
			target, err := h.repo.FindByID(c.Request.Context(), id)
			if err == nil && target.IsAdmin {
				c.JSON(http.StatusBadRequest, gin.H{"error": "Это последний администратор"})
				return
			}
		}
	}

	u, err := h.repo.ApplyUpdate(c.Request.Context(), id, req)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			c.JSON(http.StatusConflict, gin.H{"error": "Логин уже используется"})
			return
		}
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	// Денормализованные снимки догоняем только когда меняется display_name —
	// прочие поля (login/is_admin/blocked) в UserInfo не входят.
	if req.DisplayName != nil {
		h.backfillSnapshots(u)
	}
	c.JSON(http.StatusOK, toAdminUser(u))
}

// SetPassword godoc
// @Summary      Сменить пароль другого пользователя (admin)
// @Tags         users
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                      true  "User ID"
// @Param        body  body      models.SetPasswordRequest   true  "Тело"
// @Success      200   {object}  map[string]bool
// @Router       /admin/users/{id}/password [post]
func (h *UserAdminHandler) SetPassword(c *gin.Context) {
	id := c.Param("id")
	var req models.SetPasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if len(req.Password) < 4 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "пароль ≥ 4 символов"})
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), 12)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if err := h.repo.UpdatePassword(c.Request.Context(), id, string(hash)); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// Delete godoc
// @Summary      Soft-удалить пользователя (admin)
// @Description  Помечает `deleted_at`. UserInfo-снимки в существующих записях остаются валидными. Нельзя удалить самого себя или последнего админа.
// @Tags         users
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "User ID"
// @Success      200  {object}  map[string]bool
// @Router       /admin/users/{id} [delete]
func (h *UserAdminHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	currentUserID := c.GetString("user_id")
	if id == currentUserID {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Нельзя удалить самого себя"})
		return
	}
	target, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if target.IsAdmin {
		count, err := h.repo.CountAdmins(c.Request.Context())
		if err == nil && count <= 1 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Это последний администратор"})
			return
		}
	}
	if err := h.repo.SoftDelete(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// UploadAvatar godoc
// @Summary      Загрузить аватар пользователя (admin)
// @Description  PNG / JPEG / SVG, до 512KB. Сохраняется inline в коллекции users.
// @Tags         users
// @Accept       mpfd
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string  true  "User ID"
// @Param        file  formData  file    true  "Файл"
// @Success      200   {object}  models.AdminUser
// @Router       /admin/users/{id}/avatar [post]
func (h *UserAdminHandler) UploadAvatar(c *gin.Context) {
	id := c.Param("id")
	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "поле `file` обязательно"})
		return
	}
	defer func() { _ = file.Close() }()
	if header.Size > maxAvatarBytes {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "файл больше 512KB"})
		return
	}
	buf := &bytes.Buffer{}
	if _, err := io.CopyN(buf, file, maxAvatarBytes+1); err != nil && err != io.EOF {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	data := buf.Bytes()
	if len(data) > maxAvatarBytes {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "файл больше 512KB"})
		return
	}
	mime, err := sniffAvatarMime(data)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	u, err := h.repo.SetAvatar(ctx, id, mime, data)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	h.backfillSnapshots(u)
	c.JSON(http.StatusOK, toAdminUser(u))
}

// DeleteAvatar godoc
// @Summary      Удалить аватар (admin)
// @Tags         users
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "User ID"
// @Success      200  {object}  models.AdminUser
// @Router       /admin/users/{id}/avatar [delete]
func (h *UserAdminHandler) DeleteAvatar(c *gin.Context) {
	id := c.Param("id")
	u, err := h.repo.ClearAvatar(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	h.backfillSnapshots(u)
	c.JSON(http.StatusOK, toAdminUser(u))
}

// ServeAvatar godoc
// @Summary      Отдать аватар пользователя (raw bytes)
// @Tags         users
// @Produce      png
// @Security     BearerAuth
// @Param        id   path      string  true  "User ID"
// @Success      200  {file}    file
// @Router       /users/{id}/avatar [get]
func (h *UserAdminHandler) ServeAvatar(c *gin.Context) {
	id := c.Param("id")
	u, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if len(u.AvatarData) == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "no avatar"})
		return
	}
	// no-store: при загрузке нового файла `?v=` инвалидирует, но между сменами
	// аватара браузеру лучше не кешировать долго.
	c.Header("Cache-Control", "private, max-age=300")
	c.Data(http.StatusOK, u.AvatarMime, u.AvatarData)
}

// ChangeOwnPassword godoc
// @Summary      Сменить собственный пароль (self)
// @Description  Требует старый пароль. После смены текущая JWT-пара остаётся валидной до истечения; рекомендуется перелогиниться.
// @Tags         auth
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.ChangePasswordRequest  true  "Тело"
// @Success      200   {object}  map[string]bool
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string  "Старый пароль не подходит"
// @Router       /auth/password [post]
func (h *UserAdminHandler) ChangeOwnPassword(c *gin.Context) {
	var req models.ChangePasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if len(req.NewPassword) < 4 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "пароль ≥ 4 символов"})
		return
	}
	uid := c.GetString("user_id")
	user, err := h.repo.FindByID(c.Request.Context(), uid)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Пользователь не найден"})
		return
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.OldPassword)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "Старый пароль не подходит"})
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), 12)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if err := h.repo.UpdatePassword(c.Request.Context(), uid, string(hash)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// sniffAvatarMime — PNG/JPEG/SVG по magic-байтам / тегу. Симметрично
// sniffIconMime, но шире (фото-аватары в JPEG).
func sniffAvatarMime(data []byte) (string, error) {
	if len(data) == 0 {
		return "", errors.New("пустой файл")
	}
	if len(data) >= len(pngMagic) && bytes.Equal(data[:len(pngMagic)], pngMagic) {
		return pngMimeType, nil
	}
	if len(data) >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF {
		return "image/jpeg", nil
	}
	head := data
	if len(head) > 1024 {
		head = head[:1024]
	}
	lower := strings.ToLower(string(head))
	if strings.Contains(lower, "<svg") {
		return svgMimeType, nil
	}
	return "", errors.New("поддерживаются PNG, JPEG, SVG")
}
