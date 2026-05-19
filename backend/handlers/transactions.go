package handlers

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

type TransactionHandler struct {
	repo    *repository.TransactionRepository
	limiter *LimitChecker // nil-safe; tests can leave it unset
}

func NewTransactionHandler(repo *repository.TransactionRepository) *TransactionHandler {
	return &TransactionHandler{repo: repo}
}

// SetLimitChecker wires the limit-overflow notification trigger. Kept
// separate from the constructor so existing tests that don't care about
// notifications still build cleanly with NewTransactionHandler(repo).
func (h *TransactionHandler) SetLimitChecker(c *LimitChecker) {
	h.limiter = c
}

// runLimitCheck fires the limit check in the background. Only expense
// transactions can move a spending limit, so income/initial_balance writes
// skip it.
func (h *TransactionHandler) runLimitCheck(txType models.TransactionType, category string) {
	if h.limiter == nil || txType != models.Expense {
		return
	}
	go func(cat string) {
		// Detached context — request context may already be cancelled by
		// the time the deferred limit check fires.
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		h.limiter.Run(ctx, cat)
	}(category)
}

func userInfoFromCtx(c *gin.Context) *models.UserInfo {
	uid := c.GetString("user_id")
	if uid == "" {
		return nil
	}
	return &models.UserInfo{
		UserID:      uid,
		DisplayName: c.GetString("display_name"),
		AvatarURL:   c.GetString("avatar_url"),
	}
}

// Create godoc
// @Summary      Создать транзакцию
// @Description  Создаёт доход / расход / начальный баланс. `date` принимается в формате YYYY-MM-DD.
// @Tags         transactions
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateTransactionRequest  true  "Тело"
// @Success      201   {object}  models.Transaction
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Router       /transactions [post]
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
		WishlistID:  req.WishlistID,
		CreatedBy:   userInfoFromCtx(c),
	}

	if err := h.repo.Create(c.Request.Context(), t); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	h.runLimitCheck(t.Type, t.Category)
	c.JSON(http.StatusCreated, t)
}

// List godoc
// @Summary      Список транзакций с пагинацией
// @Description  Фильтры комбинируются по И. `categories` — список через запятую (приоритетнее `category`). `include_detailed=true` показывает родителей закрытых detail-requests.
// @Tags         transactions
// @Produce      json
// @Security     BearerAuth
// @Param        type              query     string  false  "income|expense|initial_balance"
// @Param        category          query     string  false  "Имя категории"
// @Param        categories        query     string  false  "CSV категорий"
// @Param        from              query     string  false  "YYYY-MM-DD"
// @Param        to                query     string  false  "YYYY-MM-DD"
// @Param        page              query     int     false  "Страница (1-based)"  default(1)
// @Param        limit             query     int     false  "Размер страницы (1-100)"  default(20)
// @Param        include_detailed  query     bool    false  "Показывать родителей закрытых detail-requests"
// @Success      200  {object}  map[string]interface{}
// @Failure      401  {object}  map[string]string
// @Router       /transactions [get]
func (h *TransactionHandler) List(c *gin.Context) {
	filter := models.TransactionFilter{
		Type:     c.Query("type"),
		Category: c.Query("category"),
		Limit:    20,
		Skip:     0,
	}

	if cats := c.Query("categories"); cats != "" {
		for _, raw := range strings.Split(cats, ",") {
			if name := strings.TrimSpace(raw); name != "" {
				filter.Categories = append(filter.Categories, name)
			}
		}
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

	if v := c.Query("include_detailed"); v == "true" || v == "1" {
		filter.IncludeDetailed = true
	}

	if v := c.Query("unlinked"); v == "true" || v == "1" {
		filter.Unlinked = true
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

// Update godoc
// @Summary      Обновить транзакцию
// @Description  Patch-семантика — пустые поля игнорируются, кроме `wishlist_id` (пустая строка отвязывает от wishlist-итема).
// @Tags         transactions
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                            true  "Transaction ID (UUID)"
// @Param        body  body      models.UpdateTransactionRequest   true  "Поля для обновления"
// @Success      200   {object}  models.Transaction
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Router       /transactions/{id} [put]
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
	if req.WishlistID != nil {
		// Empty string unlinks the transaction from its wishlist item.
		update["wishlist_id"] = *req.WishlistID
	}

	t, err := h.repo.Update(c.Request.Context(), id, update, 0, userInfoFromCtx(c))
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	h.runLimitCheck(t.Type, t.Category)
	c.JSON(http.StatusOK, t)
}

// Delete godoc
// @Summary      Удалить транзакцию (soft-delete)
// @Description  Запись помечается `deleted_at`; синк-клиенты увидят её при следующем pull.
// @Tags         transactions
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Transaction ID"
// @Success      200  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /transactions/{id} [delete]
func (h *TransactionHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	deleted, err := h.repo.Delete(c.Request.Context(), id, 0, userInfoFromCtx(c))
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if deleted != nil {
		h.runLimitCheck(deleted.Type, deleted.Category)
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}
