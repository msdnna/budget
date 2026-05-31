package handlers

import (
	"crypto/rand"
	"errors"
	"net/http"
	"strconv"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
)

// linkCodeAlphabet — Crockford-style base32 без визуально-схожих символов
// (I/L/O/0/1) — пользователь будет переписывать код вручную в telegram.
const linkCodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

// linkCodeLength = 6 → 31^6 ≈ 8.9e8 вариантов, проверяется один раз за 5 минут,
// проверяющая сторона — наш собственный API. Достаточно против случайного
// угадывания и не утомляет пользователя при наборе.
const linkCodeLength = 6

func generateLinkCode() (string, error) {
	buf := make([]byte, linkCodeLength)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	out := make([]byte, linkCodeLength)
	for i, b := range buf {
		out[i] = linkCodeAlphabet[int(b)%len(linkCodeAlphabet)]
	}
	return string(out), nil
}

type TelegramHandler struct {
	repo           *repository.TelegramRepository
	userRepo       *repository.UserRepository
	catRepo        *repository.CategoryRepository
	txRepo         *repository.TransactionRepository
	glossaryRepo   *repository.GlossaryRepository
	intentTrigRepo *repository.IntentTriggerRepository
}

func NewTelegramHandler(
	repo *repository.TelegramRepository,
	userRepo *repository.UserRepository,
	catRepo *repository.CategoryRepository,
	txRepo *repository.TransactionRepository,
	glossaryRepo *repository.GlossaryRepository,
	intentTrigRepo *repository.IntentTriggerRepository,
) *TelegramHandler {
	return &TelegramHandler{
		repo:           repo,
		userRepo:       userRepo,
		catRepo:        catRepo,
		txRepo:         txRepo,
		glossaryRepo:   glossaryRepo,
		intentTrigRepo: intentTrigRepo,
	}
}

// LinkInit godoc
// @Summary      Сгенерировать одноразовый код привязки telegram
// @Description  Возвращает 6-символьный код (TTL 5 минут). Пользователь отправляет `/link <code>` боту, тот вызывает `/api/telegram/link/confirm` со своим service-token. Повторный init перезаписывает старый код и сбрасывает действующую привязку.
// @Tags         telegram
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  models.TelegramLinkInitResponse
// @Failure      401  {object}  map[string]string
// @Router       /telegram/link/init [post]
func (h *TelegramHandler) LinkInit(c *gin.Context) {
	uid := c.GetString("user_id")
	code, err := generateLinkCode()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Не удалось сгенерировать код"})
		return
	}
	expires, err := h.repo.UpsertCode(c.Request.Context(), uid, code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, models.TelegramLinkInitResponse{
		Code:      code,
		ExpiresAt: expires,
	})
}

// LinkStatus godoc
// @Summary      Текущая привязка telegram текущего пользователя
// @Tags         telegram
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  models.TelegramLinkStatus
// @Failure      401  {object}  map[string]string
// @Router       /telegram/link [get]
func (h *TelegramHandler) LinkStatus(c *gin.Context) {
	uid := c.GetString("user_id")
	link, err := h.repo.FindByUserID(c.Request.Context(), uid)
	if err != nil {
		if errors.Is(err, repository.ErrLinkNotFound) {
			c.JSON(http.StatusOK, models.TelegramLinkStatus{Linked: false})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	// Pending-привязка (есть code, но нет telegram_user_id) — для UI это
	// «ещё не привязано», показываем экран с кодом.
	if link.TelegramUserID == 0 {
		c.JSON(http.StatusOK, models.TelegramLinkStatus{Linked: false})
		return
	}
	c.JSON(http.StatusOK, models.TelegramLinkStatus{
		Linked:           true,
		TelegramUserID:   link.TelegramUserID,
		TelegramUsername: link.TelegramUsername,
		LinkedAt:         link.LinkedAt,
	})
}

// LinkDelete godoc
// @Summary      Отвязать текущий telegram
// @Tags         telegram
// @Produce      json
// @Security     BearerAuth
// @Success      204
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /telegram/link [delete]
func (h *TelegramHandler) LinkDelete(c *gin.Context) {
	uid := c.GetString("user_id")
	if err := h.repo.DeleteByUserID(c.Request.Context(), uid); err != nil {
		if errors.Is(err, repository.ErrLinkNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "Привязка не найдена"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

// LinkConfirm godoc
// @Summary      Подтвердить привязку telegram (service-only)
// @Description  Вызывается telegram-ботом со service-token'ом. Body содержит код, который пользователь ввёл в боте, и его telegram_user_id. Возвращает обновлённый link или 400 «invalid code».
// @Tags         telegram
// @Accept       json
// @Produce      json
// @Param        X-Service-Token  header   string  true  "Service shared secret"
// @Param        body             body     models.TelegramLinkConfirmRequest  true  "Код + telegram ids"
// @Success      200  {object}  models.TelegramLink
// @Failure      400  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Router       /telegram/link/confirm [post]
func (h *TelegramHandler) LinkConfirm(c *gin.Context) {
	var req models.TelegramLinkConfirmRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	link, err := h.repo.ConfirmCode(c.Request.Context(), req.Code, req.TelegramUserID, req.TelegramUsername)
	if err != nil {
		if errors.Is(err, repository.ErrLinkCodeInvalid) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Код недействителен или истёк"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, link)
}

// Context godoc
// @Summary      Полный контекст для LLM-парсера (service-only)
// @Description  Возвращает категории + keywords, глоссарий и топ-50 пар (контрагент, категория) из истории конкретного пользователя. Один эндпоинт = один промпт LLM. Aggregate-пайплайн mongo тяжёлый — telegram-бот кеширует ответ на стороне клиента (60 сек).
// @Tags         telegram
// @Produce      json
// @Param        X-Service-Token  header   string  true  "Service shared secret"
// @Param        user_id          query    string  true  "Budget user id"
// @Success      200  {object}  models.TelegramContextResponse
// @Failure      400  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /telegram/context [get]
func (h *TelegramHandler) Context(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user_id required"})
		return
	}
	// Verify user exists / not blocked — guard against the bot calling with
	// a stale id after admin deletion.
	if u, err := h.userRepo.FindByID(c.Request.Context(), userID); err != nil || u == nil || u.DeletedAt != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	// Categories (no wishlist — LLM doesn't categorize into wishlist items;
	// they're created through dedicated UI).
	expCats, _ := h.catRepo.List(c.Request.Context(), "expense")
	incCats, _ := h.catRepo.List(c.Request.Context(), "income")

	gloss, _ := h.glossaryRepo.List(c.Request.Context())

	// Counterparty history — capped at 50, mongo aggregate handles the sort
	// and limit. Errors here aren't fatal: a fresh user without history just
	// gets an empty list.
	cps, _ := h.txRepo.AggregateUserCounterparties(c.Request.Context(), userID, 50)

	// Intent triggers — admin-tuned phrases for the bot's intent classifier.
	// Non-fatal: bot falls back to its built-in defaults on an empty map.
	var triggers map[string][]string
	if h.intentTrigRepo != nil {
		triggers, _ = h.intentTrigRepo.AsMap(c.Request.Context())
	}

	resp := models.TelegramContextResponse{
		Expense:        toContextCategories(expCats),
		Income:         toContextCategories(incCats),
		Glossary:       toContextGlossary(gloss),
		Counterparties: toContextCounterparties(cps),
		IntentTriggers: triggers,
	}
	c.JSON(http.StatusOK, resp)
}

func toContextCategories(cats []models.Category) []models.TelegramContextCategory {
	out := make([]models.TelegramContextCategory, 0, len(cats))
	for _, c := range cats {
		out = append(out, models.TelegramContextCategory{Name: c.Name, Keywords: c.Keywords})
	}
	return out
}

func toContextGlossary(entries []models.GlossaryEntry) []models.TelegramContextGlossary {
	out := make([]models.TelegramContextGlossary, 0, len(entries))
	for _, g := range entries {
		out = append(out, models.TelegramContextGlossary{Term: g.Term, Meaning: g.Meaning})
	}
	return out
}

func toContextCounterparties(cps []repository.CounterpartyAgg) []models.TelegramContextCounterparty {
	out := make([]models.TelegramContextCounterparty, 0, len(cps))
	for _, c := range cps {
		out = append(out, models.TelegramContextCounterparty{
			Counterparty: c.Counterparty,
			Category:     c.Category,
			Type:         c.Type,
			Count:        c.Count,
		})
	}
	return out
}

// Me godoc
// @Summary      Найти budget-пользователя по telegram_user_id (service-only)
// @Description  Вызывается ботом при каждом сообщении, чтобы убедиться, что telegram-аккаунт привязан, и достать display_name/avatar для UserInfo-сниппетов. 404 если не привязан.
// @Tags         telegram
// @Produce      json
// @Param        X-Service-Token   header   string  true  "Service shared secret"
// @Param        telegram_user_id  query    int     true  "Telegram user id"
// @Success      200  {object}  models.UserInfo
// @Failure      400  {object}  map[string]string
// @Failure      401  {object}  map[string]string
// @Failure      404  {object}  map[string]string
// @Router       /telegram/me [get]
func (h *TelegramHandler) Me(c *gin.Context) {
	raw := c.Query("telegram_user_id")
	if raw == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "telegram_user_id required"})
		return
	}
	tgID, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || tgID <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "telegram_user_id must be positive int"})
		return
	}
	link, err := h.repo.FindByTelegramUserID(c.Request.Context(), tgID)
	if err != nil {
		if errors.Is(err, repository.ErrLinkNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not linked"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	user, err := h.userRepo.FindByID(c.Request.Context(), link.UserID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	if user.DeletedAt != nil || user.BlockedAt != nil {
		c.JSON(http.StatusForbidden, gin.H{"error": "Учётная запись недоступна"})
		return
	}
	c.JSON(http.StatusOK, models.UserInfo{
		UserID:      user.ID.Hex(),
		DisplayName: user.DisplayName,
		AvatarURL:   user.AvatarURL,
	})
}
