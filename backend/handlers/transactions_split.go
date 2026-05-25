package handlers

import (
	"errors"
	"math"
	"net/http"

	"budget-go/models"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

// SplitPart is one slice of a split-income request: an amount and the deposit
// scope it lands in. Categories/date/source come from the parent transaction.
type SplitPart struct {
	Amount  float64            `json:"amount" binding:"required,gt=0"`
	Deposit models.DepositType `json:"deposit"`
}

// SplitRequest is the body of POST /transactions/:id/split.
type SplitRequest struct {
	Splits []SplitPart `json:"splits" binding:"required,min=2,dive"`
}

// Tolerated rounding gap between the parent amount and the sum of the parts.
// 0.01 RUB covers UI rounding (₽ has 2 decimals); anything bigger is a real
// validation error.
const splitSumEpsilon = 0.01

// Split godoc
// @Summary      Разделить доход на несколько частей по депозитам
// @Description  Атомарно создаёт N дочерних транзакций с заданными суммами и депозитами и скрывает родителя из стандартных списков (excluded_from_stats=true). Каждая часть наследует категорию, дату, источник и описание от родителя. Сумма частей должна точно равняться сумме родителя.
// @Tags         transactions
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string         true  "Transaction ID (income, ещё не разделённая)"
// @Param        body  body      SplitRequest   true  "Список частей: ≥2"
// @Success      200   {object}  map[string]interface{}  "parent + children"
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Failure      409   {object}  map[string]string  "Уже разделена / уже DR-parent / уже child"
// @Router       /transactions/{id}/split [post]
func (h *TransactionHandler) Split(c *gin.Context) {
	id := c.Param("id")
	var req SplitRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	parent, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	if parent.Type != models.Income {
		c.JSON(http.StatusBadRequest, gin.H{"error": "split is only allowed on income transactions"})
		return
	}
	if parent.ParentID != "" {
		c.JSON(http.StatusConflict, gin.H{"error": "cannot split a child transaction"})
		return
	}
	if parent.DetailRequestID != "" {
		c.JSON(http.StatusConflict, gin.H{"error": "cannot split a transaction with a detail-request"})
		return
	}
	if parent.ExcludedFromStats {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction is already split"})
		return
	}

	existing, err := h.repo.FindChildren(c.Request.Context(), parent.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if len(existing) > 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction already has children"})
		return
	}

	var sum float64
	for _, p := range req.Splits {
		sum += p.Amount
	}
	if math.Abs(sum-parent.Amount) > splitSumEpsilon {
		c.JSON(http.StatusBadRequest, gin.H{
			"error": "sum of parts must equal the parent amount",
		})
		return
	}

	modifier := userInfoFromCtx(c)
	created := make([]models.Transaction, 0, len(req.Splits))
	for _, p := range req.Splits {
		child := &models.Transaction{
			Type:        models.Income,
			Amount:      p.Amount,
			Date:        parent.Date,
			Category:    parent.Category,
			Source:      parent.Source,
			Purpose:     parent.Purpose,
			Description: parent.Description,
			Deposit:     models.NormalizeDeposit(p.Deposit),
			CreatedBy:   modifier,
			ParentID:    parent.ID,
		}
		if err := h.repo.Create(c.Request.Context(), child); err != nil {
			// Best-effort rollback: drop whatever children we already wrote
			// so the parent stays in a clean unsplit state.
			for _, prev := range created {
				_, _ = h.repo.Delete(c.Request.Context(), prev.ID, 0, modifier)
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		created = append(created, *child)
	}

	updatedParent, err := h.repo.Update(c.Request.Context(), parent.ID, bson.M{
		"excluded_from_stats": true,
	}, 0, modifier)
	if err != nil {
		for _, prev := range created {
			_, _ = h.repo.Delete(c.Request.Context(), prev.ID, 0, modifier)
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"parent":   updatedParent,
		"children": created,
	})
}

// Unsplit godoc
// @Summary      Отменить разделение дохода
// @Description  Soft-удаляет все дочерние транзакции и снимает с родителя `excluded_from_stats`. Идempotent: запрос на уже-неразделённую запись возвращает 409.
// @Tags         transactions
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Transaction ID (родитель ранее разделённый)"
// @Success      200  {object}  models.Transaction  "Восстановленный родитель"
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Failure      409  {object}  map[string]string  "Запись не была разделена"
// @Router       /transactions/{id}/unsplit [post]
func (h *TransactionHandler) Unsplit(c *gin.Context) {
	id := c.Param("id")
	parent, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if parent.Type != models.Income {
		c.JSON(http.StatusBadRequest, gin.H{"error": "unsplit is only allowed on income transactions"})
		return
	}
	if !parent.ExcludedFromStats || parent.ParentID != "" || parent.DetailRequestID != "" {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction is not split"})
		return
	}

	modifier := userInfoFromCtx(c)
	if err := h.repo.SoftDeleteChildren(c.Request.Context(), parent.ID, modifier); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	updated, err := h.repo.Update(c.Request.Context(), parent.ID, bson.M{
		"excluded_from_stats": false,
	}, 0, modifier)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, updated)
}
