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
	repo    *repository.WishlistRepository
	txRepo  *repository.TransactionRepository
	catRepo *repository.CategoryRepository
}

func NewWishlistHandler(repo *repository.WishlistRepository, txRepo *repository.TransactionRepository, catRepo *repository.CategoryRepository) *WishlistHandler {
	return &WishlistHandler{repo: repo, txRepo: txRepo, catRepo: catRepo}
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

// LinkExisting attaches an existing expense transaction to a wishlist/regular
// item. The transaction's category is overwritten with the wishlist item's
// category (so it surfaces under the same expense pie slice that «Куплено»/
// «Оплачено» would produce). If that category does not yet exist in the
// expense section, it is cloned with the wishlist category's color/icon. For
// `once`-frequency items the wishlist's `purchased` flag is also set.
//
// Validates: tx exists and not soft-deleted, tx.Type == expense, tx is not
// already linked to another wishlist item, tx is neither a child of a
// detail-request nor a closed-request parent.
//
// @Summary      Привязать существующий расход к wishlist/regular-итему
// @Description  Фиксирует существующую транзакцию как оплату/покупку. Категория транзакции приводится к категории wishlist-итема; недостающая категория клонируется в expense с цветом/иконкой. `once`-итемы дополнительно помечаются `purchased=true`.
// @Tags         wishlist
// @Produce      json
// @Security     BearerAuth
// @Param        id     path      string  true  "Wishlist ID"
// @Param        tx_id  path      string  true  "Transaction ID"
// @Success      200    {object}  models.Transaction
// @Failure      400    {object}  map[string]string
// @Failure      401    {object}  map[string]string
// @Failure      404    {object}  map[string]string
// @Failure      409    {object}  map[string]string  "Транзакция уже связана"
// @Router       /wishlist/{id}/link/{tx_id} [post]
func (h *WishlistHandler) LinkExisting(c *gin.Context) {
	id := c.Param("id")
	txID := c.Param("tx_id")

	item, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "wishlist item not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	tx, err := h.txRepo.FindByID(c.Request.Context(), txID)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "transaction not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if tx.Type != models.Expense {
		c.JSON(http.StatusBadRequest, gin.H{"error": "only expense transactions can be linked"})
		return
	}
	if tx.WishlistID != "" {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction already linked to a wishlist item"})
		return
	}
	if tx.ParentID != "" || tx.ExcludedFromStats || tx.DetailRequestStatus == "closed" {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction is part of a detail-request"})
		return
	}

	// Clone the wishlist's category into expense if missing. Defensive lookups
	// on the wishlist-side category metadata so we have visuals to copy; if the
	// wishlist category row was deleted we fall back to empty color/icon so the
	// expense category at least exists (admin can re-color later).
	var color, icon string
	if h.catRepo != nil {
		if wlCat, lookupErr := h.catRepo.FindByName(c.Request.Context(), "wishlist", item.Category); lookupErr == nil && wlCat != nil {
			color = wlCat.Color
			icon = wlCat.Icon
		}
		if _, ensureErr := h.catRepo.EnsureInSection(c.Request.Context(), "expense", item.Category, color, icon, userInfoFromCtx(c)); ensureErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": ensureErr.Error()})
			return
		}
	}

	update := bson.M{
		"wishlist_id": item.ID,
		"category":    item.Category,
	}
	updated, err := h.txRepo.Update(c.Request.Context(), txID, update, 0, userInfoFromCtx(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// once-frequency items represent a single purchase — flip purchased on
	// link so the wishlist row reflects the new state. Recurring items stay
	// as-is (paid_this_period is derived from the linked tx itself).
	if item.Frequency == models.FrequencyOnce || item.Frequency == "" {
		purchased := true
		if !item.Purchased {
			_, _ = h.repo.Update(c.Request.Context(), item.ID, bson.M{"purchased": purchased}, 0, userInfoFromCtx(c))
		}
	}

	c.JSON(http.StatusOK, updated)
}
