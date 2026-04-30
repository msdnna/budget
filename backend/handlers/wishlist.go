package handlers

import (
	"net/http"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
)

type WishlistHandler struct {
	repo *repository.WishlistRepository
}

func NewWishlistHandler(repo *repository.WishlistRepository) *WishlistHandler {
	return &WishlistHandler{repo: repo}
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
		CreatedBy: &models.UserInfo{
			UserID:      c.GetString("user_id"),
			DisplayName: c.GetString("display_name"),
			AvatarURL:   c.GetString("avatar_url"),
		},
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

	if err := h.repo.Update(c.Request.Context(), id, update); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	item, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}

	c.JSON(http.StatusOK, item)
}

func (h *WishlistHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.repo.Delete(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}
