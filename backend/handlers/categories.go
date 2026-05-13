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
	"golang.org/x/sync/errgroup"
)

type CategoriesAllResponse struct {
	Expense  []models.Category `json:"expense"`
	Income   []models.Category `json:"income"`
	Wishlist []models.Category `json:"wishlist"`
}

type CategoryHandler struct {
	repo *repository.CategoryRepository
}

func NewCategoryHandler(repo *repository.CategoryRepository) *CategoryHandler {
	return &CategoryHandler{repo: repo}
}

// List godoc
// @Summary      Категории одной секции
// @Tags         categories
// @Produce      json
// @Security     BearerAuth
// @Param        section  query     string  true  "expense|income|wishlist"
// @Success      200      {array}   models.Category
// @Failure      400      {object}  map[string]string
// @Failure      401      {object}  map[string]string
// @Router       /categories [get]
func (h *CategoryHandler) List(c *gin.Context) {
	section := c.Query("section")
	if section == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "section required"})
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	cats, err := h.repo.List(ctx, section)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if cats == nil {
		cats = []models.Category{}
	}
	c.JSON(http.StatusOK, cats)
}

// ListAll returns categories for all three sections in a single response so the
// Android client can warm a shared category cache without firing three parallel
// requests on cold start. Sections are queried concurrently.
// ListAll godoc
// @Summary      Категории всех секций одним запросом
// @Description  Прогревает кеш Android-клиента без 3 параллельных запросов. Секции запрашиваются конкурентно.
// @Tags         categories
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  CategoriesAllResponse
// @Failure      401  {object}  map[string]string
// @Router       /categories/all [get]
func (h *CategoryHandler) ListAll(c *gin.Context) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var (
		expense  []models.Category
		income   []models.Category
		wishlist []models.Category
	)

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error {
		cats, err := h.repo.List(gctx, "expense")
		if err != nil {
			return err
		}
		if cats == nil {
			cats = []models.Category{}
		}
		expense = cats
		return nil
	})
	g.Go(func() error {
		cats, err := h.repo.List(gctx, "income")
		if err != nil {
			return err
		}
		if cats == nil {
			cats = []models.Category{}
		}
		income = cats
		return nil
	})
	g.Go(func() error {
		cats, err := h.repo.List(gctx, "wishlist")
		if err != nil {
			return err
		}
		if cats == nil {
			cats = []models.Category{}
		}
		wishlist = cats
		return nil
	})

	if err := g.Wait(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, CategoriesAllResponse{
		Expense:  expense,
		Income:   income,
		Wishlist: wishlist,
	})
}

// Create godoc
// @Summary      Создать пользовательскую категорию
// @Tags         categories
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateCategoryRequest  true  "Тело"
// @Success      201   {object}  models.Category
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      409   {object}  map[string]string  "Категория уже существует"
// @Router       /categories [post]
func (h *CategoryHandler) Create(c *gin.Context) {
	var req models.CreateCategoryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	validSections := map[string]bool{"expense": true, "income": true, "wishlist": true}
	if !validSections[req.Section] {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid section"})
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	cat, err := h.repo.Create(ctx, req.Section, req.Name, req.Color, req.Icon, userInfoFromCtx(c))
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			c.JSON(http.StatusConflict, gin.H{"error": "category already exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, cat)
}

// Update godoc
// @Summary      Изменить категорию (color/icon/name)
// @Description  Только для администратора. Все три поля опциональны — обновляется то, что прислали. Можно править и дефолтные категории.
// @Tags         categories
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                        true  "Category ID"
// @Param        body  body      models.UpdateCategoryRequest  true  "Тело"
// @Success      200   {object}  models.Category
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      403   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Router       /categories/{id} [patch]
func (h *CategoryHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req models.UpdateCategoryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	cat, err := h.repo.Update(ctx, id, req, userInfoFromCtx(c))
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, cat)
}

// Delete godoc
// @Summary      Удалить пользовательскую категорию
// @Description  Дефолтные категории удалить нельзя — handler вернёт 404.
// @Tags         categories
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Category ID"
// @Success      200  {object}  map[string]bool
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /categories/{id} [delete]
func (h *CategoryHandler) Delete(c *gin.Context) {
	id := c.Param("id")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, err := h.repo.Delete(ctx, id, 0, userInfoFromCtx(c)); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found or cannot delete default category"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"ok": true})
}
