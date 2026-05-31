package handlers

import (
	"context"
	"net/http"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
)

type IntentTriggerHandler struct {
	repo *repository.IntentTriggerRepository
}

func NewIntentTriggerHandler(repo *repository.IntentTriggerRepository) *IntentTriggerHandler {
	return &IntentTriggerHandler{repo: repo}
}

// List godoc
// @Summary      Список intent-триггеров telegram-бота
// @Description  Per-intent списки фраз-подсказок для классификатора бота. Возвращает все известные намерения (пустой список если фраз нет). Читать может любой авторизованный, менять — только админ.
// @Tags         intent-triggers
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}   models.IntentTrigger
// @Failure      401  {object}  map[string]string
// @Router       /intent-triggers [get]
func (h *IntentTriggerHandler) List(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	out, err := h.repo.List(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, out)
}

// Update godoc
// @Summary      Заменить фразы-триггеры намерения (admin)
// @Description  Полная замена списка фраз для одного намерения. Фразы триммятся и дедупятся на бэкенде. Бот мерджит их аддитивно поверх встроенных дефолтов.
// @Tags         intent-triggers
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        intent  path      string                              true  "Намерение (wishlist|recurring_payment|link_existing|detail_request)"
// @Param        body    body      models.UpdateIntentTriggerRequest   true  "Список фраз"
// @Success      200     {object}  models.IntentTrigger
// @Failure      400     {object}  map[string]string
// @Failure      401     {object}  map[string]string
// @Router       /intent-triggers/{intent} [put]
func (h *IntentTriggerHandler) Update(c *gin.Context) {
	intent := c.Param("intent")
	if !models.IsIntentTriggerKind(intent) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "unknown intent"})
		return
	}
	var req models.UpdateIntentTriggerRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	out, err := h.repo.Upsert(ctx, intent, req.Phrases)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, out)
}
