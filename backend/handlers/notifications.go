package handlers

import (
	"net/http"
	"strconv"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
)

type NotificationHandler struct {
	repo *repository.NotificationRepository
}

func NewNotificationHandler(repo *repository.NotificationRepository) *NotificationHandler {
	return &NotificationHandler{repo: repo}
}

type NotificationDTO struct {
	models.Notification
	// Read is a per-user view layer computed from ReadBy; clients render
	// the badge state from this instead of having to scan the array.
	Read bool `json:"read"`
}

type NotificationsListResponse struct {
	Data        []NotificationDTO `json:"data"`
	UnreadCount int64             `json:"unread_count"`
}

// List godoc
// @Summary      Список уведомлений (newest-first)
// @Description  Каждое уведомление — family-wide; флаг `read` — per-user (учитывается, входит ли user_id в `read_by`).
// @Tags         notifications
// @Produce      json
// @Security     BearerAuth
// @Param        limit  query     int  false  "Сколько записей вернуть (≤100, default=50)"
// @Success      200    {object}  NotificationsListResponse
// @Failure      401    {object}  map[string]string
// @Router       /notifications [get]
func (h *NotificationHandler) List(c *gin.Context) {
	var limit int64
	if l := c.Query("limit"); l != "" {
		if v, err := strconv.ParseInt(l, 10, 64); err == nil {
			limit = v
		}
	}
	notifs, err := h.repo.List(c.Request.Context(), limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	uid := c.GetString("user_id")
	dtos := make([]NotificationDTO, 0, len(notifs))
	for _, n := range notifs {
		read := false
		for _, r := range n.ReadBy {
			if r == uid {
				read = true
				break
			}
		}
		dtos = append(dtos, NotificationDTO{Notification: n, Read: read})
	}

	unread, err := h.repo.UnreadCount(c.Request.Context(), uid)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, NotificationsListResponse{Data: dtos, UnreadCount: unread})
}

// ReadAll godoc
// @Summary      Пометить все уведомления прочитанными
// @Description  Добавляет user_id в `read_by` каждого уведомления, где его ещё нет.
// @Tags         notifications
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  map[string]bool
// @Failure      401  {object}  map[string]string
// @Router       /notifications/read-all [post]
func (h *NotificationHandler) ReadAll(c *gin.Context) {
	uid := c.GetString("user_id")
	if err := h.repo.MarkAllRead(c.Request.Context(), uid); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// Read godoc
// @Summary      Пометить одно уведомление прочитанным
// @Tags         notifications
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Notification ID"
// @Success      200  {object}  map[string]bool
// @Failure      401  {object}  map[string]string
// @Router       /notifications/{id}/read [post]
func (h *NotificationHandler) Read(c *gin.Context) {
	uid := c.GetString("user_id")
	id := c.Param("id")
	if err := h.repo.MarkRead(c.Request.Context(), id, uid); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}
