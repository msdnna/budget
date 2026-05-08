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

// UnlinkPeriod clears wishlist_id on every linked expense in the recurring
// item's current period. Period is derived from the item's frequency:
// monthly = current calendar month, quarterly = current calendar quarter,
// yearly = current calendar year. Backs the "Отменить" action.
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
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "item is not recurring"})
		return
	}
	n, err := h.txRepo.UnlinkFromWishlist(c.Request.Context(), id, from, to, userInfoFromCtx(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"unlinked": n})
}
