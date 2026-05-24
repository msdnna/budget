package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"golang.org/x/sync/errgroup"
)

type SyncHandler struct {
	txRepo  *repository.TransactionRepository
	wlRepo  *repository.WishlistRepository
	catRepo *repository.CategoryRepository
}

func NewSyncHandler(tx *repository.TransactionRepository, wl *repository.WishlistRepository, cat *repository.CategoryRepository) *SyncHandler {
	return &SyncHandler{txRepo: tx, wlRepo: wl, catRepo: cat}
}

// Pull godoc
// @Summary      Pull-фаза синка: всё, что менялось после `since`
// @Description  Возвращает все записи трёх syncable-коллекций (transactions, wishlist, categories), включая soft-deleted. Первый вызов без `since` отдаёт полный набор без удалённых.
// @Tags         sync
// @Produce      json
// @Security     BearerAuth
// @Param        since  query     string  false  "RFC3339(Nano) timestamp"
// @Success      200    {object}  models.SyncPullResponse
// @Failure      400    {object}  map[string]string
// @Failure      401    {object}  map[string]string
// @Router       /sync/pull [get]
func (h *SyncHandler) Pull(c *gin.Context) {
	var since time.Time
	if v := c.Query("since"); v != "" {
		t, err := time.Parse(time.RFC3339Nano, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid since (expected RFC3339)"})
			return
		}
		since = t
	}

	var (
		txs  []models.Transaction
		wls  []models.WishlistItem
		cats []models.Category
	)
	g, gctx := errgroup.WithContext(c.Request.Context())
	g.Go(func() error {
		out, err := h.txRepo.FindModifiedSince(gctx, since)
		if err == nil {
			txs = out
		}
		return err
	})
	g.Go(func() error {
		out, err := h.wlRepo.FindModifiedSince(gctx, since)
		if err == nil {
			wls = out
		}
		return err
	})
	g.Go(func() error {
		out, err := h.catRepo.FindModifiedSince(gctx, since)
		if err == nil {
			cats = out
		}
		return err
	})
	if err := g.Wait(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if txs == nil {
		txs = []models.Transaction{}
	}
	if wls == nil {
		wls = []models.WishlistItem{}
	}
	if cats == nil {
		cats = []models.Category{}
	}

	c.JSON(http.StatusOK, models.SyncPullResponse{
		Transactions: txs,
		Wishlist:     wls,
		Categories:   cats,
		ServerTime:   time.Now().UTC(),
	})
}

// Push godoc
// @Summary      Push-фаза синка: батч клиентских мутаций
// @Description  Каждая операция независима; результат на операцию позволяет клиенту пометить, что синканулось, а что — конфликт. `force=true` обходит version-check.
// @Tags         sync
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.SyncPushRequest  true  "Батч операций"
// @Success      200   {object}  models.SyncPushResponse
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Router       /sync/push [post]
func (h *SyncHandler) Push(c *gin.Context) {
	var req models.SyncPushRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	modifiedBy := userInfoFromCtx(c)
	results := make([]models.SyncOperationResult, 0, len(req.Operations))

	for _, op := range req.Operations {
		results = append(results, h.applyOp(c.Request.Context(), op, modifiedBy))
	}

	c.JSON(http.StatusOK, models.SyncPushResponse{
		Results:    results,
		ServerTime: time.Now().UTC(),
	})
}

func (h *SyncHandler) applyOp(ctx context.Context, op models.SyncOperation, modifiedBy *models.UserInfo) models.SyncOperationResult {
	res := models.SyncOperationResult{OpID: op.OpID}

	if op.ID == "" {
		res.Status = models.SyncStatusError
		res.Error = "missing id"
		return res
	}

	// On force, we treat the op as a non-version-checked overwrite by passing
	// baseVersion=0 (which the repo Upsert/Update path interprets as "skip
	// version filter"). The repo still increments version atomically.
	baseVersion := op.BaseVersion
	if op.Force {
		baseVersion = 0
	}

	switch op.Entity {
	case models.SyncEntityTransaction:
		return h.applyTransaction(ctx, op, baseVersion, modifiedBy)
	case models.SyncEntityWishlist:
		return h.applyWishlist(ctx, op, baseVersion, modifiedBy)
	case models.SyncEntityCategory:
		return h.applyCategory(ctx, op, baseVersion, modifiedBy)
	}
	res.Status = models.SyncStatusError
	res.Error = "unknown entity"
	return res
}

func recordToMap(v any) map[string]any {
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}

func (h *SyncHandler) applyTransaction(ctx context.Context, op models.SyncOperation, baseVersion int, modifiedBy *models.UserInfo) models.SyncOperationResult {
	res := models.SyncOperationResult{OpID: op.OpID}

	switch op.Type {
	case models.SyncOpDelete:
		t, err := h.txRepo.Delete(ctx, op.ID, baseVersion, modifiedBy)
		return finishOp(ctx, res, t, err, h.txRepo.FindByID, op.ID)

	case models.SyncOpCreate, models.SyncOpUpdate:
		t, err := decodeTransactionPayload(op.Payload)
		if err != nil {
			res.Status = models.SyncStatusError
			res.Error = "invalid payload: " + err.Error()
			return res
		}
		t.ID = op.ID
		if t.LastModifiedBy == nil {
			t.LastModifiedBy = modifiedBy
		}
		if op.Type == models.SyncOpCreate && t.CreatedBy == nil {
			t.CreatedBy = modifiedBy
		}
		out, err := h.txRepo.Upsert(ctx, t, baseVersion, op.Type == models.SyncOpCreate)
		return finishOp(ctx, res, out, err, h.txRepo.FindByID, op.ID)
	}

	res.Status = models.SyncStatusError
	res.Error = "unknown op type"
	return res
}

// decodeTransactionPayload accepts either RFC3339 or "YYYY-MM-DD" for `date`,
// and either RFC3339Nano or empty for the timestamp fields, so that records
// created locally on Android (which uses the calendar-day format the existing
// REST API expects) round-trip cleanly through the sync endpoint.
func decodeTransactionPayload(raw json.RawMessage) (*models.Transaction, error) {
	type alias struct {
		ID                  string                 `json:"id"`
		Type                models.TransactionType `json:"type"`
		Amount              float64                `json:"amount"`
		Date                string                 `json:"date"`
		Category            string                 `json:"category"`
		Source              string                 `json:"source"`
		Purpose             string                 `json:"purpose"`
		Description         string                 `json:"description"`
		Hidden              bool                   `json:"hidden"`
		Deposit             models.DepositType     `json:"deposit"`
		CreatedBy           *models.UserInfo       `json:"created_by"`
		CreatedAt           string                 `json:"created_at"`
		Version             int                    `json:"version"`
		UpdatedAt           string                 `json:"updated_at"`
		DeletedAt           *string                `json:"deleted_at"`
		LastModifiedBy      *models.UserInfo       `json:"last_modified_by"`
		ParentID            string                 `json:"parent_id"`
		DetailRequestID     string                 `json:"detail_request_id"`
		DetailRequestStatus string                 `json:"detail_request_status"`
		ExcludedFromStats   bool                   `json:"excluded_from_stats"`
		WishlistID          string                 `json:"wishlist_id"`
	}
	var a alias
	if err := json.Unmarshal(raw, &a); err != nil {
		return nil, err
	}
	t := &models.Transaction{
		ID:                  a.ID,
		Type:                a.Type,
		Amount:              a.Amount,
		Date:                parseLooseTime(a.Date),
		Category:            a.Category,
		Source:              a.Source,
		Purpose:             a.Purpose,
		Description:         a.Description,
		Hidden:              a.Hidden,
		Deposit:             models.NormalizeDeposit(a.Deposit),
		CreatedBy:           a.CreatedBy,
		CreatedAt:           parseLooseTime(a.CreatedAt),
		Version:             a.Version,
		UpdatedAt:           parseLooseTime(a.UpdatedAt),
		LastModifiedBy:      a.LastModifiedBy,
		ParentID:            a.ParentID,
		DetailRequestID:     a.DetailRequestID,
		DetailRequestStatus: a.DetailRequestStatus,
		ExcludedFromStats:   a.ExcludedFromStats,
		WishlistID:          a.WishlistID,
	}
	if a.DeletedAt != nil && *a.DeletedAt != "" {
		dt := parseLooseTime(*a.DeletedAt)
		t.DeletedAt = &dt
	}
	return t, nil
}

func decodeWishlistPayload(raw json.RawMessage) (*models.WishlistItem, error) {
	type alias struct {
		ID             string             `json:"id"`
		Name           string             `json:"name"`
		EstimatedCost  float64            `json:"estimated_cost"`
		Category       string             `json:"category"`
		Priority       int                `json:"priority"`
		Frequency      models.Frequency   `json:"frequency"`
		Purchased      bool               `json:"purchased"`
		Deposit        models.DepositType `json:"deposit"`
		Notes          string             `json:"notes"`
		CreatedBy      *models.UserInfo   `json:"created_by"`
		CreatedAt      string             `json:"created_at"`
		Version        int                `json:"version"`
		UpdatedAt      string             `json:"updated_at"`
		DeletedAt      *string            `json:"deleted_at"`
		LastModifiedBy *models.UserInfo   `json:"last_modified_by"`
	}
	var a alias
	if err := json.Unmarshal(raw, &a); err != nil {
		return nil, err
	}
	w := &models.WishlistItem{
		ID:             a.ID,
		Name:           a.Name,
		EstimatedCost:  a.EstimatedCost,
		Category:       a.Category,
		Priority:       a.Priority,
		Frequency:      a.Frequency,
		Purchased:      a.Purchased,
		Deposit:        models.NormalizeDeposit(a.Deposit),
		Notes:          a.Notes,
		CreatedBy:      a.CreatedBy,
		CreatedAt:      parseLooseTime(a.CreatedAt),
		Version:        a.Version,
		UpdatedAt:      parseLooseTime(a.UpdatedAt),
		LastModifiedBy: a.LastModifiedBy,
	}
	if a.DeletedAt != nil && *a.DeletedAt != "" {
		dt := parseLooseTime(*a.DeletedAt)
		w.DeletedAt = &dt
	}
	return w, nil
}

func decodeCategoryPayload(raw json.RawMessage) (*models.Category, error) {
	type alias struct {
		ID             string           `json:"id"`
		Section        string           `json:"section"`
		Name           string           `json:"name"`
		IsDefault      bool             `json:"is_default"`
		CreatedAt      string           `json:"created_at"`
		Version        int              `json:"version"`
		UpdatedAt      string           `json:"updated_at"`
		DeletedAt      *string          `json:"deleted_at"`
		LastModifiedBy *models.UserInfo `json:"last_modified_by"`
	}
	var a alias
	if err := json.Unmarshal(raw, &a); err != nil {
		return nil, err
	}
	c := &models.Category{
		ID:             a.ID,
		Section:        a.Section,
		Name:           a.Name,
		IsDefault:      a.IsDefault,
		CreatedAt:      parseLooseTime(a.CreatedAt),
		Version:        a.Version,
		UpdatedAt:      parseLooseTime(a.UpdatedAt),
		LastModifiedBy: a.LastModifiedBy,
	}
	if a.DeletedAt != nil && *a.DeletedAt != "" {
		dt := parseLooseTime(*a.DeletedAt)
		c.DeletedAt = &dt
	}
	return c, nil
}

// parseLooseTime accepts RFC3339, RFC3339Nano, or "YYYY-MM-DD". Empty input
// returns the zero time.
func parseLooseTime(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t
	}
	if t, err := time.Parse("2006-01-02", s); err == nil {
		return t
	}
	return time.Time{}
}

func (h *SyncHandler) applyWishlist(ctx context.Context, op models.SyncOperation, baseVersion int, modifiedBy *models.UserInfo) models.SyncOperationResult {
	res := models.SyncOperationResult{OpID: op.OpID}

	switch op.Type {
	case models.SyncOpDelete:
		item, err := h.wlRepo.Delete(ctx, op.ID, baseVersion, modifiedBy)
		return finishOp(ctx, res, item, err, h.wlRepo.FindByID, op.ID)

	case models.SyncOpCreate, models.SyncOpUpdate:
		item, err := decodeWishlistPayload(op.Payload)
		if err != nil {
			res.Status = models.SyncStatusError
			res.Error = "invalid payload: " + err.Error()
			return res
		}
		item.ID = op.ID
		if item.LastModifiedBy == nil {
			item.LastModifiedBy = modifiedBy
		}
		if op.Type == models.SyncOpCreate && item.CreatedBy == nil {
			item.CreatedBy = modifiedBy
		}
		out, err := h.wlRepo.Upsert(ctx, item, baseVersion, op.Type == models.SyncOpCreate)
		return finishOp(ctx, res, out, err, h.wlRepo.FindByID, op.ID)
	}

	res.Status = models.SyncStatusError
	res.Error = "unknown op type"
	return res
}

func (h *SyncHandler) applyCategory(ctx context.Context, op models.SyncOperation, baseVersion int, modifiedBy *models.UserInfo) models.SyncOperationResult {
	res := models.SyncOperationResult{OpID: op.OpID}

	switch op.Type {
	case models.SyncOpDelete:
		cat, err := h.catRepo.Delete(ctx, op.ID, baseVersion, modifiedBy)
		return finishOp(ctx, res, cat, err, h.catRepo.FindByID, op.ID)

	case models.SyncOpCreate, models.SyncOpUpdate:
		cat, err := decodeCategoryPayload(op.Payload)
		if err != nil {
			res.Status = models.SyncStatusError
			res.Error = "invalid payload: " + err.Error()
			return res
		}
		cat.ID = op.ID
		if cat.LastModifiedBy == nil {
			cat.LastModifiedBy = modifiedBy
		}
		out, err := h.catRepo.Upsert(ctx, cat, baseVersion, op.Type == models.SyncOpCreate)
		return finishOp(ctx, res, out, err, h.catRepo.FindByID, op.ID)
	}

	res.Status = models.SyncStatusError
	res.Error = "unknown op type"
	return res
}

// finishOp packs the (record, err) tuple into a SyncOperationResult, fetching
// the current server document on conflict so the client can show the diff.
func finishOp[T any](ctx context.Context, res models.SyncOperationResult, ok any, err error, refetch func(context.Context, string) (*T, error), id string) models.SyncOperationResult {
	if err == nil {
		res.Status = models.SyncStatusOK
		res.Record = recordToMap(ok)
		return res
	}
	if errors.Is(err, repository.ErrConflict) {
		res.Status = models.SyncStatusConflict
		if cur, fErr := refetch(ctx, id); fErr == nil {
			res.Record = recordToMap(cur)
		}
		return res
	}
	res.Status = models.SyncStatusError
	res.Error = err.Error()
	return res
}
