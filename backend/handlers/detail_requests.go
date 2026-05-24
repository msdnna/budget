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

type DetailRequestHandler struct {
	repo     *repository.DetailRequestRepository
	txRepo   *repository.TransactionRepository
	userRepo *repository.UserRepository
}

func NewDetailRequestHandler(repo *repository.DetailRequestRepository, txRepo *repository.TransactionRepository, userRepo *repository.UserRepository) *DetailRequestHandler {
	return &DetailRequestHandler{repo: repo, txRepo: txRepo, userRepo: userRepo}
}

// Create godoc
// @Summary      Открыть detail-request над существующей expense-транзакцией
// @Description  Создатель может назначить request на любого пользователя семьи (включая себя). Транзакция должна быть expense без уже открытого request.
// @Tags         detail-requests
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      models.CreateDetailRequestPayload  true  "transaction_id + assignee_id"
// @Success      201   {object}  models.DetailRequest
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      404   {object}  map[string]string
// @Failure      409   {object}  map[string]string
// @Router       /detail-requests [post]
func (h *DetailRequestHandler) Create(c *gin.Context) {
	var req models.CreateDetailRequestPayload
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	tx, err := h.txRepo.FindByID(c.Request.Context(), req.TransactionID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "transaction not found"})
		return
	}
	if tx.Type != models.Expense {
		c.JSON(http.StatusBadRequest, gin.H{"error": "detail-requests are only allowed on expenses"})
		return
	}
	if tx.ParentID != "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot open a detail-request over a child transaction"})
		return
	}
	if tx.DetailRequestID != "" {
		c.JSON(http.StatusConflict, gin.H{"error": "transaction already has a detail-request"})
		return
	}

	user, err := h.userRepo.FindByID(c.Request.Context(), req.AssigneeID)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "assignee not found"})
		return
	}
	assignee := &models.UserInfo{UserID: user.ID.Hex(), DisplayName: user.DisplayName, AvatarURL: user.AvatarURL}

	dr := &models.DetailRequest{
		ParentTransactionID: tx.ID,
		TargetAmount:        tx.Amount,
		Assignee:            assignee,
		Creator:             userInfoFromCtx(c),
		Status:              models.DetailRequestOpen,
	}
	if err := h.repo.Create(c.Request.Context(), dr); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if _, err := h.txRepo.Update(c.Request.Context(), tx.ID, bson.M{
		"detail_request_id":     dr.ID,
		"detail_request_status": string(models.DetailRequestOpen),
	}, 0, userInfoFromCtx(c)); err != nil {
		// Roll back the request to keep state consistent.
		_ = h.repo.Delete(c.Request.Context(), dr.ID)
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, dr)
}

// List godoc
// @Summary      Список detail-requests
// @Description  Фильтры комбинируются по И. `assignee_id=me` / `creator_id=me` — сокращение для текущего пользователя.
// @Tags         detail-requests
// @Produce      json
// @Security     BearerAuth
// @Param        assignee_id  query     string  false  "User ID или 'me'"
// @Param        creator_id   query     string  false  "User ID или 'me'"
// @Param        status       query     string  false  "open|closed"
// @Success      200          {array}   models.DetailRequest
// @Failure      401          {object}  map[string]string
// @Router       /detail-requests [get]
func (h *DetailRequestHandler) List(c *gin.Context) {
	uid := c.GetString("user_id")
	f := repository.DetailRequestFilter{
		AssigneeID: c.Query("assignee_id"),
		CreatorID:  c.Query("creator_id"),
		Status:     c.Query("status"),
	}
	if f.AssigneeID == "me" {
		f.AssigneeID = uid
	}
	if f.CreatorID == "me" {
		f.CreatorID = uid
	}
	out, err := h.repo.Find(c.Request.Context(), f)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, out)
}

// Get godoc
// @Summary      Detail-request с parent- и child-транзакциями
// @Tags         detail-requests
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Detail-request ID"
// @Success      200  {object}  models.DetailRequestView
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /detail-requests/{id} [get]
func (h *DetailRequestHandler) Get(c *gin.Context) {
	id := c.Param("id")
	dr, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	parent, err := h.txRepo.FindByID(c.Request.Context(), dr.ParentTransactionID)
	if err != nil && !errors.Is(err, mongo.ErrNoDocuments) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	children, err := h.txRepo.FindChildren(c.Request.Context(), dr.ParentTransactionID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, models.DetailRequestView{Request: dr, Parent: parent, Children: children})
}

// AddChild godoc
// @Summary      Добавить child-транзакцию в открытый detail-request
// @Description  Только assignee и только пока request `open`. Child создаётся с `excluded_from_stats=true` — попадёт в статистику после `Close`.
// @Tags         detail-requests
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        id    path      string                           true  "Detail-request ID"
// @Param        body  body      models.CreateTransactionRequest  true  "Тело child-транзакции"
// @Success      201   {object}  models.Transaction
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      403   {object}  map[string]string  "Только assignee может добавлять"
// @Failure      404   {object}  map[string]string
// @Failure      409   {object}  map[string]string  "Request не open"
// @Router       /detail-requests/{id}/transactions [post]
func (h *DetailRequestHandler) AddChild(c *gin.Context) {
	id := c.Param("id")
	dr, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	if dr.Status != models.DetailRequestOpen {
		c.JSON(http.StatusConflict, gin.H{"error": "request is not open"})
		return
	}
	uid := c.GetString("user_id")
	if dr.Assignee == nil || dr.Assignee.UserID != uid {
		c.JSON(http.StatusForbidden, gin.H{"error": "only the assignee can add child transactions"})
		return
	}

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

	// Child tx inherits its parent's deposit scope — splitting a lump-sum
	// expense across detail-request children doesn't change which scope it
	// drew from. Fall back to the request payload, then to bank default.
	parent, err := h.txRepo.FindByID(c.Request.Context(), dr.ParentTransactionID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	childDeposit := models.NormalizeDeposit(parent.Deposit)

	t := &models.Transaction{
		Type:              models.Expense,
		Amount:            req.Amount,
		Date:              date,
		Category:          req.Category,
		Source:            req.Source,
		Purpose:           req.Purpose,
		Description:       req.Description,
		Deposit:           childDeposit,
		CreatedBy:         userInfoFromCtx(c),
		ParentID:          dr.ParentTransactionID,
		ExcludedFromStats: true,
	}
	if err := h.txRepo.Create(c.Request.Context(), t); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, t)
}

// Close godoc
// @Summary      Закрыть detail-request (финализация)
// @Description  Parent помечается `excluded_from_stats=true`, дети — `false` (становятся каноничными). Требуется ≥1 child. Только assignee.
// @Tags         detail-requests
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Detail-request ID"
// @Success      200  {object}  models.DetailRequest
// @Failure      400   {object}  map[string]string  "Нет ни одного child"
// @Failure      401  {object}  map[string]string
// @Failure      403  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Failure      409  {object}  map[string]string
// @Router       /detail-requests/{id}/close [post]
func (h *DetailRequestHandler) Close(c *gin.Context) {
	id := c.Param("id")
	dr, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	if dr.Status != models.DetailRequestOpen {
		c.JSON(http.StatusConflict, gin.H{"error": "request is not open"})
		return
	}
	uid := c.GetString("user_id")
	if dr.Assignee == nil || dr.Assignee.UserID != uid {
		c.JSON(http.StatusForbidden, gin.H{"error": "only the assignee can close the request"})
		return
	}

	children, err := h.txRepo.FindChildren(c.Request.Context(), dr.ParentTransactionID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if len(children) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "add at least one child transaction before closing"})
		return
	}

	if err := h.txRepo.SetExcludedForChildren(c.Request.Context(), dr.ParentTransactionID, false, userInfoFromCtx(c)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if _, err := h.txRepo.Update(c.Request.Context(), dr.ParentTransactionID, bson.M{
		"excluded_from_stats":   true,
		"detail_request_status": string(models.DetailRequestClosed),
	}, 0, userInfoFromCtx(c)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	out, err := h.repo.SetStatus(c.Request.Context(), id, models.DetailRequestClosed)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, out)
}

// Cancel godoc
// @Summary      Отменить открытый detail-request
// @Description  Children soft-удаляются, флаги parent'а сбрасываются, request удаляется. Только creator.
// @Tags         detail-requests
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Detail-request ID"
// @Success      200  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      403  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Failure      409  {object}  map[string]string
// @Router       /detail-requests/{id}/cancel [post]
func (h *DetailRequestHandler) Cancel(c *gin.Context) {
	id := c.Param("id")
	dr, err := h.repo.FindByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	if dr.Status != models.DetailRequestOpen {
		c.JSON(http.StatusConflict, gin.H{"error": "only open requests can be cancelled"})
		return
	}
	uid := c.GetString("user_id")
	if dr.Creator == nil || dr.Creator.UserID != uid {
		c.JSON(http.StatusForbidden, gin.H{"error": "only the creator can cancel the request"})
		return
	}

	if err := h.txRepo.SoftDeleteChildren(c.Request.Context(), dr.ParentTransactionID, userInfoFromCtx(c)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if _, err := h.txRepo.Update(c.Request.Context(), dr.ParentTransactionID, bson.M{
		"detail_request_id":     "",
		"detail_request_status": "",
		"excluded_from_stats":   false,
	}, 0, userInfoFromCtx(c)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if err := h.repo.Delete(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "cancelled"})
}
