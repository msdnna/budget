package handlers

import (
	"context"
	"errors"
	"net/http"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/mongo"
)

type GlossaryHandler struct {
	repo *repository.GlossaryRepository
}

func NewGlossaryHandler(repo *repository.GlossaryRepository) *GlossaryHandler {
	return &GlossaryHandler{repo: repo}
}

// List godoc
// @Summary      Список глоссария (псевдонимов)
// @Description  Общесемейный словарь term→meaning, используется telegram-ботом как подсказки LLM. Видно всем авторизованным.
// @Tags         glossary
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}   models.GlossaryEntry
// @Failure      401  {object}  map[string]string
// @Router       /glossary [get]
func (h *GlossaryHandler) List(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	out, err := h.repo.List(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if out == nil {
		out = []models.GlossaryEntry{}
	}
	c.JSON(http.StatusOK, out)
}

// Create godoc
// @Summary      Добавить запись глоссария (admin)
// @Tags         glossary
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateGlossaryRequest  true  "Тело"
// @Success      201   {object}  models.GlossaryEntry
// @Failure      400   {object}  map[string]string
// @Failure      409   {object}  map[string]string
// @Router       /glossary [post]
func (h *GlossaryHandler) Create(c *gin.Context) {
	var req models.CreateGlossaryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	out, err := h.repo.Create(ctx, req.Term, req.Meaning)
	if err != nil {
		if errors.Is(err, repository.ErrGlossaryConflict) {
			c.JSON(http.StatusConflict, gin.H{"error": "термин уже есть"})
			return
		}
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, out)
}

// Update godoc
// @Summary      Обновить запись глоссария (admin)
// @Tags         glossary
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                        true  "Glossary ID"
// @Param        body  body      models.UpdateGlossaryRequest  true  "Тело"
// @Success      200   {object}  models.GlossaryEntry
// @Failure      400   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Router       /glossary/{id} [patch]
func (h *GlossaryHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req models.UpdateGlossaryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	out, err := h.repo.Update(ctx, id, req)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		if errors.Is(err, repository.ErrGlossaryConflict) {
			c.JSON(http.StatusConflict, gin.H{"error": "термин уже есть"})
			return
		}
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, out)
}

// Delete godoc
// @Summary      Удалить запись глоссария (admin)
// @Tags         glossary
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Glossary ID"
// @Success      204
// @Failure      404  {object}  map[string]string
// @Router       /glossary/{id} [delete]
func (h *GlossaryHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()
	if err := h.repo.Delete(ctx, id); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}
