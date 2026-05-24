package handlers

import (
	"context"
	"net/http"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
)

type CategoryLimitProgress struct {
	CategoryID string  `json:"category_id"`
	Name       string  `json:"name"`
	Color      string  `json:"color,omitempty"`
	Icon       string  `json:"icon,omitempty"`
	Limit      float64 `json:"limit"`
	Spent      float64 `json:"spent"`
	Percent    float64 `json:"percent"`
}

type LimitsProgressResponse struct {
	Period       string                  `json:"period"`
	Categories   []CategoryLimitProgress `json:"categories"`
	TotalLimit   float64                 `json:"total_limit"`
	TotalSpent   float64                 `json:"total_spent"`
	TotalPercent float64                 `json:"total_percent"`
}

type LimitsHandler struct {
	catRepo *repository.CategoryRepository
	txRepo  *repository.TransactionRepository
}

func NewLimitsHandler(catRepo *repository.CategoryRepository, txRepo *repository.TransactionRepository) *LimitsHandler {
	return &LimitsHandler{catRepo: catRepo, txRepo: txRepo}
}

// Progress godoc
// @Summary      Прогресс по лимитам категорий за месяц
// @Description  Возвращает по каждой expense-категории с заданным `monthly_limit`: сколько потрачено за период, процент. Общая сумма лимитов и трат суммируется только по категориям с лимитом. По умолчанию — текущий календарный месяц.
// @Tags         categories
// @Produce      json
// @Security     BearerAuth
// @Param        month  query     string  false  "YYYY-MM (по умолчанию — текущий месяц)"
// @Success      200    {object}  LimitsProgressResponse
// @Failure      401    {object}  map[string]string
// @Router       /categories/limits-progress [get]
func (h *LimitsHandler) Progress(c *gin.Context) {
	from, to, periodLabel := monthBoundsFromQuery(c.Query("month"))

	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	cats, err := h.catRepo.List(ctx, "expense")
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	agg, err := h.txRepo.AggregateByCategory(ctx, string(models.Expense), "", from, to)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	spentByName := make(map[string]float64, len(agg))
	for _, a := range agg {
		spentByName[a.Category] = a.Amount
	}

	out := LimitsProgressResponse{Period: periodLabel, Categories: []CategoryLimitProgress{}}
	for _, cat := range cats {
		if cat.MonthlyLimit == nil {
			continue
		}
		limit := *cat.MonthlyLimit
		spent := spentByName[cat.Name]
		pct := 0.0
		if limit > 0 {
			pct = spent / limit * 100
		}
		out.Categories = append(out.Categories, CategoryLimitProgress{
			CategoryID: cat.ID,
			Name:       cat.Name,
			Color:      cat.Color,
			Icon:       cat.Icon,
			Limit:      limit,
			Spent:      spent,
			Percent:    pct,
		})
		out.TotalLimit += limit
		out.TotalSpent += spent
	}
	if out.TotalLimit > 0 {
		out.TotalPercent = out.TotalSpent / out.TotalLimit * 100
	}

	c.JSON(http.StatusOK, out)
}

// monthBoundsFromQuery resolves the inclusive [from, to] range for a
// "YYYY-MM" parameter. Empty / invalid input falls back to the current
// calendar month. The returned label is normalized to "YYYY-MM" so callers
// can mirror it back in the response.
func monthBoundsFromQuery(month string) (time.Time, time.Time, string) {
	now := time.Now()
	t := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	if month != "" {
		if parsed, err := time.Parse("2006-01", month); err == nil {
			t = parsed
		}
	}
	from := t
	to := t.AddDate(0, 1, 0).Add(-time.Second)
	return from, to, t.Format("2006-01")
}
