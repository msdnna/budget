package handlers

import (
	"net/http"
	"strconv"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
)

type TransactionHandler struct {
	repo *repository.TransactionRepository
}

func NewTransactionHandler(repo *repository.TransactionRepository) *TransactionHandler {
	return &TransactionHandler{repo: repo}
}

func (h *TransactionHandler) Create(c *gin.Context) {
	var req models.CreateTransactionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	date, err := time.Parse("2006-01-02", req.Date)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid date format, use YYYY-MM-DD"})
		return
	}

	t := &models.Transaction{
		Type:        req.Type,
		Amount:      req.Amount,
		Date:        date,
		Category:    req.Category,
		Source:      req.Source,
		Purpose:     req.Purpose,
		Description: req.Description,
		CreatedBy: &models.UserInfo{
			UserID:      c.GetString("user_id"),
			DisplayName: c.GetString("display_name"),
			AvatarURL:   c.GetString("avatar_url"),
		},
	}

	if err := h.repo.Create(c.Request.Context(), t); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, t)
}

func (h *TransactionHandler) List(c *gin.Context) {
	filter := models.TransactionFilter{
		Type:     c.Query("type"),
		Category: c.Query("category"),
		Limit:    20,
		Skip:     0,
	}

	if p := c.Query("page"); p != "" {
		page, _ := strconv.ParseInt(p, 10, 64)
		if page > 1 {
			filter.Skip = (page - 1) * filter.Limit
		}
	}

	if l := c.Query("limit"); l != "" {
		limit, _ := strconv.ParseInt(l, 10, 64)
		if limit > 0 && limit <= 100 {
			filter.Limit = limit
		}
	}

	if from := c.Query("from"); from != "" {
		t, err := time.Parse("2006-01-02", from)
		if err == nil {
			filter.From = &t
		}
	}

	if to := c.Query("to"); to != "" {
		t, err := time.Parse("2006-01-02", to)
		if err == nil {
			end := t.Add(24*time.Hour - time.Second)
			filter.To = &end
		}
	}

	transactions, total, err := h.repo.Find(c.Request.Context(), filter)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"data":  transactions,
		"total": total,
		"page":  filter.Skip/filter.Limit + 1,
		"limit": filter.Limit,
	})
}

func (h *TransactionHandler) Update(c *gin.Context) {
	id := c.Param("id")
	var req models.UpdateTransactionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	update := bson.M{}
	if req.Amount > 0 {
		update["amount"] = req.Amount
	}
	if req.Date != "" {
		d, err := time.Parse("2006-01-02", req.Date)
		if err == nil {
			update["date"] = d
		}
	}
	if req.Category != "" {
		update["category"] = req.Category
	}
	if req.Source != "" {
		update["source"] = req.Source
	}
	if req.Purpose != "" {
		update["purpose"] = req.Purpose
	}
	if req.Description != "" {
		update["description"] = req.Description
	}
	if req.Hidden != nil {
		update["hidden"] = *req.Hidden
	}
	if req.CreatedBy != nil {
		update["created_by"] = req.CreatedBy
	}

	if err := h.repo.Update(c.Request.Context(), id, update); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	t, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}

	c.JSON(http.StatusOK, t)
}

func (h *TransactionHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.repo.Delete(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}
