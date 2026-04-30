package handlers

import (
	"net/http"
	"strconv"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
)

type StatisticsHandler struct {
	txRepo *repository.TransactionRepository
	wlRepo *repository.WishlistRepository
}

func NewStatisticsHandler(txRepo *repository.TransactionRepository, wlRepo *repository.WishlistRepository) *StatisticsHandler {
	return &StatisticsHandler{txRepo: txRepo, wlRepo: wlRepo}
}

func (h *StatisticsHandler) Summary(c *gin.Context) {
	from, to := parsePeriodParams(c)

	summary, err := h.txRepo.GetSummary(c.Request.Context(), from, to)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, summary)
}

func (h *StatisticsHandler) ByCategory(c *gin.Context) {
	txType := c.Query("type")
	if txType == "" {
		txType = string(models.Expense)
	}

	from, to := parsePeriodParams(c)

	data, err := h.txRepo.AggregateByCategory(c.Request.Context(), txType, from, to)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if data == nil {
		data = []models.CategoryData{}
	}

	c.JSON(http.StatusOK, data)
}

func (h *StatisticsHandler) Monthly(c *gin.Context) {
	yearStr := c.Query("year")
	year, err := strconv.Atoi(yearStr)
	if err != nil || year < 2000 {
		year = time.Now().Year()
	}

	data, err := h.txRepo.AggregateMonthly(c.Request.Context(), year)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, data)
}

func (h *StatisticsHandler) Forecast(c *gin.Context) {
	ctx := c.Request.Context()
	now := time.Now()
	threeMonthsAgo := now.AddDate(0, -3, 0)

	historyCats, err := h.txRepo.GetAverageMonthlyCategoryExpenses(ctx, threeMonthsAgo, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// All non-purchased wishlist items contribute to forecast
	unpurchased, err := h.wlRepo.FindUnpurchased(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	// Build history-based category map
	catMap := make(map[string]float64)
	var histTotal float64
	for _, cat := range historyCats {
		catMap[cat.Category] += cat.Amount
		histTotal += cat.Amount
	}

	// Calculate monthly contribution for every unpurchased wishlist item.
	// once / monthly  → full cost per month
	// quarterly       → cost / 3 per month
	// yearly          → cost / 12 per month
	var regularItems []models.RegularItemForecast
	var wishlistTotal float64
	for _, item := range unpurchased {
		var monthly float64
		switch item.Frequency {
		case models.FrequencyQuarterly:
			monthly = item.EstimatedCost / 3
		case models.FrequencyYearly:
			monthly = item.EstimatedCost / 12
		default: // once, monthly, or empty
			monthly = item.EstimatedCost
		}
		catMap[item.Category] += monthly
		wishlistTotal += monthly
		if item.Frequency != models.FrequencyOnce {
			regularItems = append(regularItems, models.RegularItemForecast{
				ID:          item.ID.Hex(),
				Name:        item.Name,
				MonthlyCost: monthly,
				Frequency:   string(item.Frequency),
				Category:    item.Category,
			})
		}
	}

	// Build breakdown with percentages
	total := histTotal + wishlistTotal
	var breakdown []models.CategoryData
	for cat, amount := range catMap {
		pct := 0.0
		if total > 0 {
			pct = amount / total * 100
		}
		breakdown = append(breakdown, models.CategoryData{
			Category:   cat,
			Amount:     amount,
			Percentage: pct,
		})
	}

	if regularItems == nil {
		regularItems = []models.RegularItemForecast{}
	}
	if unpurchased == nil {
		unpurchased = []models.WishlistItem{}
	}

	c.JSON(http.StatusOK, models.ForecastResponse{
		TotalMonthly:        total,
		HistoricalAvg:       histTotal,
		WishlistContrib:     wishlistTotal,
		Breakdown:           breakdown,
		RegularItems:        regularItems,
		UnpurchasedWishlist: unpurchased,
	})
}

func parsePeriodParams(c *gin.Context) (time.Time, time.Time) {
	var from, to time.Time

	if fromStr := c.Query("from"); fromStr != "" {
		from, _ = time.Parse("2006-01-02", fromStr)
	}
	if toStr := c.Query("to"); toStr != "" {
		to, _ = time.Parse("2006-01-02", toStr)
		to = to.Add(24*time.Hour - time.Second)
	}

	// If month param is provided, use that month's range
	if monthStr := c.Query("month"); monthStr != "" {
		t, err := time.Parse("2006-01", monthStr)
		if err == nil {
			from = t
			to = t.AddDate(0, 1, 0).Add(-time.Second)
		}
	}

	// If year param is provided (without month), use full year
	if yearStr := c.Query("year"); yearStr != "" && c.Query("month") == "" {
		year, err := strconv.Atoi(yearStr)
		if err == nil {
			from = time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC)
			to = time.Date(year+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
		}
	}

	return from, to
}
