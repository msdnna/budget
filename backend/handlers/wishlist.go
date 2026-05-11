package handlers

import (
	"errors"
	"net/http"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

type WishlistHandler struct {
	repo   *repository.WishlistRepository
	txRepo *repository.TransactionRepository
}

func NewWishlistHandler(repo *repository.WishlistRepository, txRepo *repository.TransactionRepository) *WishlistHandler {
	return &WishlistHandler{repo: repo, txRepo: txRepo}
}

// Create godoc
// @Summary      Создать wishlist-итем (одноразовый или регулярный)
// @Description  `frequency=once` — обычная покупка из списка желаний. `monthly|quarterly|yearly` — регулярный платёж (коммуналка, связь и т.п.).
// @Tags         wishlist
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateWishlistRequest  true  "Тело"
// @Success      201   {object}  models.WishlistItem
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Router       /wishlist [post]
func (h *WishlistHandler) Create(c *gin.Context) {
	var req models.CreateWishlistRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	item := &models.WishlistItem{
		Name:          req.Name,
		EstimatedCost: req.EstimatedCost,
		Category:      req.Category,
		Priority:      req.Priority,
		Frequency:     req.Frequency,
		Purchased:     req.Purchased,
		Notes:         req.Notes,
		CreatedBy:     userInfoFromCtx(c),
	}

	if item.Priority == 0 {
		item.Priority = 5
	}

	if err := h.repo.Create(c.Request.Context(), item); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, item)
}

// List godoc
// @Summary      Все wishlist-итемы (без soft-deleted)
// @Tags         wishlist
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}   models.WishlistItem
// @Failure      401  {object}  map[string]string
// @Router       /wishlist [get]
func (h *WishlistHandler) List(c *gin.Context) {
	items, err := h.repo.FindAll(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if items == nil {
		items = []models.WishlistItem{}
	}

	c.JSON(http.StatusOK, items)
}

// Update godoc
// @Summary      Обновить wishlist-итем
// @Description  Patch-семантика. `purchased=true` помечает «куплено» (используется UI-кнопкой); транзакция списания создаётся отдельным `POST /transactions`.
// @Tags         wishlist
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                       true  "Wishlist ID"
// @Param        body  body      models.UpdateWishlistRequest true  "Поля"
// @Success      200   {object}  models.WishlistItem
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Router       /wishlist/{id} [put]
func (h *WishlistHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req models.UpdateWishlistRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	update := bson.M{}
	if req.Name != "" {
		update["name"] = req.Name
	}
	if req.EstimatedCost > 0 {
		update["estimated_cost"] = req.EstimatedCost
	}
	if req.Category != "" {
		update["category"] = req.Category
	}
	if req.Priority > 0 {
		update["priority"] = req.Priority
	}
	if req.Frequency != "" {
		update["frequency"] = req.Frequency
	}
	if req.Purchased != nil {
		update["purchased"] = *req.Purchased
	}
	if req.Notes != "" {
		update["notes"] = req.Notes
	}
	if req.CreatedBy != nil {
		update["created_by"] = req.CreatedBy
	}

	item, err := h.repo.Update(c.Request.Context(), id, update, 0, userInfoFromCtx(c))
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, item)
}

// Delete godoc
// @Summary      Удалить wishlist-итем (soft-delete)
// @Tags         wishlist
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Wishlist ID"
// @Success      200  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /wishlist/{id} [delete]
func (h *WishlistHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	if _, err := h.repo.Delete(c.Request.Context(), id, 0, userInfoFromCtx(c)); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

// UnlinkPeriod clears wishlist_id on linked expense transactions whose date
// falls in the item's current period. Period is derived from the item's
// frequency:
//
//	once       → no date filter — wishlist purchases are one-off, all
//	             linked tx are cleared (used by «Не куплено»).
//	monthly    → current calendar month
//	quarterly  → current calendar quarter
//	yearly     → current calendar year
//
// Backs the "Отменить" action on regular расходы AND the "Не куплено"
// action on wishlist items.
// UnlinkPeriod godoc
// @Summary      Отвязать транзакции wishlist-итема за текущий период
// @Description  Очищает `wishlist_id` у транзакций в текущем месяце/квартале/году (по `frequency`). Для `once` — отвязывает единственную привязанную транзакцию без date-filter. Бэкенд для кнопок «Не куплено» и «Отменить».
// @Tags         wishlist
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Wishlist ID"
// @Success      200  {object}  map[string]int
// @Failure      400  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /wishlist/{id}/unlink-period [post]
func (h *WishlistHandler) UnlinkPeriod(c *gin.Context) {
	id := c.Param("id")
	item, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	now := time.Now()
	var from, to time.Time
	switch item.Frequency {
	case models.FrequencyMonthly:
		from = time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
		to = from.AddDate(0, 1, 0).Add(-time.Second)
	case models.FrequencyQuarterly:
		q := (int(now.Month()) - 1) / 3
		from = time.Date(now.Year(), time.Month(q*3+1), 1, 0, 0, 0, 0, time.UTC)
		to = from.AddDate(0, 3, 0).Add(-time.Second)
	case models.FrequencyYearly:
		from = time.Date(now.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
		to = from.AddDate(1, 0, 0).Add(-time.Second)
	case models.FrequencyOnce, "":
		// Wishlist one-off — only ever has a single linked tx; clear it
		// regardless of date so reverting «Куплено» works at any point.
		from = time.Time{}
		to = time.Time{}
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "unknown frequency"})
		return
	}
	n, err := h.txRepo.UnlinkFromWishlist(c.Request.Context(), id, from, to, userInfoFromCtx(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"unlinked": n})
}
