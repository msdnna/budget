package handlers

import (
	"net/http"
	"strconv"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"golang.org/x/sync/errgroup"
)

type StatisticsOverviewResponse struct {
	Summary           *models.SummaryData   `json:"summary"`
	ExpenseByCategory []models.CategoryData `json:"expenseByCategory"`
	IncomeByCategory  []models.CategoryData `json:"incomeByCategory"`
	Monthly           []models.MonthlyData  `json:"monthly"`
}

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
	from, to := parsePeriodParams(c)

	if from.IsZero() && to.IsZero() {
		year := time.Now().Year()
		from = time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC)
		to = time.Date(year+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
	} else if from.IsZero() {
		from = time.Date(to.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
	} else if to.IsZero() {
		to = time.Date(from.Year()+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
	}

	// For the "month" filter (a single month), expand to the full year so the
	// dynamics chart still shows context around the selected month.
	if c.Query("month") != "" && c.Query("from") == "" && c.Query("to") == "" {
		from = time.Date(from.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
		to = time.Date(from.Year()+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
	}

	data, err := h.txRepo.AggregateMonthlyRange(c.Request.Context(), from, to)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, data)
}

// Overview combines summary + by-category(expense) + by-category(income) + monthly
// into a single response so the Android Stats screen can fetch everything in one
// round-trip instead of four sequential calls. Sub-queries run in parallel.
func (h *StatisticsHandler) Overview(c *gin.Context) {
	from, to := parsePeriodParams(c)

	year := time.Now().Year()
	if monthStr := c.Query("month"); monthStr != "" {
		if t, err := time.Parse("2006-01", monthStr); err == nil {
			year = t.Year()
		}
	} else if yearStr := c.Query("year"); yearStr != "" {
		if y, err := strconv.Atoi(yearStr); err == nil && y >= 2000 {
			year = y
		}
	}

	var (
		summary *models.SummaryData
		expCat  []models.CategoryData
		incCat  []models.CategoryData
		monthly []models.MonthlyData
	)

	g, gctx := errgroup.WithContext(c.Request.Context())
	g.Go(func() error {
		s, err := h.txRepo.GetSummary(gctx, from, to)
		if err != nil {
			return err
		}
		summary = s
		return nil
	})
	g.Go(func() error {
		d, err := h.txRepo.AggregateByCategory(gctx, string(models.Expense), from, to)
		if err != nil {
			return err
		}
		if d == nil {
			d = []models.CategoryData{}
		}
		expCat = d
		return nil
	})
	g.Go(func() error {
		d, err := h.txRepo.AggregateByCategory(gctx, string(models.Income), from, to)
		if err != nil {
			return err
		}
		if d == nil {
			d = []models.CategoryData{}
		}
		incCat = d
		return nil
	})
	g.Go(func() error {
		d, err := h.txRepo.AggregateMonthly(gctx, year)
		if err != nil {
			return err
		}
		if d == nil {
			d = []models.MonthlyData{}
		}
		monthly = d
		return nil
	})

	if err := g.Wait(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, StatisticsOverviewResponse{
		Summary:           summary,
		ExpenseByCategory: expCat,
		IncomeByCategory:  incCat,
		Monthly:           monthly,
	})
}

func (h *StatisticsHandler) Forecast(c *gin.Context) {
	ctx := c.Request.Context()
	now := time.Now()
	threeMonthsAgo := now.AddDate(0, -3, 0)

	// Historical avg by category, EXCLUDING transactions linked to recurring
	// wishlist items — those are projected separately via the next-due model
	// below. Counting them in both places double-billed the forecast.
	historyCats, err := h.txRepo.GetAverageMonthlyCategoryExpensesUnlinked(ctx, threeMonthsAgo, now)
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

	// Pull every expense transaction linked to any recurring wishlist item
	// over the last ~2 years — far enough back to find the latest yearly
	// payment so we can compute next_due. Bucketing per period happens in Go.
	twoYearsAgo := now.AddDate(-2, 0, 0)
	var recurringIDs []string
	for _, item := range unpurchased {
		if item.Frequency != models.FrequencyOnce {
			recurringIDs = append(recurringIDs, item.ID)
		}
	}
	payments, err := h.txRepo.FindLinkedToWishlistMulti(ctx, recurringIDs, twoYearsAgo, time.Time{})
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

	// Calendar period bounds — drive only the UI badge "оплачено за …".
	monthStart := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	monthEnd := monthStart.AddDate(0, 1, 0)
	q := (int(now.Month()) - 1) / 3
	quarterStart := time.Date(now.Year(), time.Month(q*3+1), 1, 0, 0, 0, 0, time.UTC)
	quarterEnd := quarterStart.AddDate(0, 3, 0)
	yearStart := time.Date(now.Year(), 1, 1, 0, 0, 0, 0, time.UTC)
	yearEnd := yearStart.AddDate(1, 0, 0)

	// Aggregate per recurring item: latest payment date (drives next_due) and
	// the calendar-period paid_amount/count (drives the UI badge).
	type itemAgg struct {
		latest       time.Time
		paidInPeriod bool
		paidAmount   float64
		paidCount    int
	}
	byItem := make(map[string]*itemAgg)
	itemFreq := make(map[string]models.Frequency, len(unpurchased))
	for _, it := range unpurchased {
		itemFreq[it.ID] = it.Frequency
	}
	for _, p := range payments {
		s, ok := byItem[p.WishlistID]
		if !ok {
			s = &itemAgg{}
			byItem[p.WishlistID] = s
		}
		if p.Date.After(s.latest) {
			s.latest = p.Date
		}
		var inPeriod bool
		switch itemFreq[p.WishlistID] {
		case models.FrequencyMonthly:
			inPeriod = !p.Date.Before(monthStart) && p.Date.Before(monthEnd)
		case models.FrequencyQuarterly:
			inPeriod = !p.Date.Before(quarterStart) && p.Date.Before(quarterEnd)
		case models.FrequencyYearly:
			inPeriod = !p.Date.Before(yearStart) && p.Date.Before(yearEnd)
		}
		if inPeriod {
			s.paidInPeriod = true
			s.paidAmount += p.Amount
			s.paidCount++
		}
	}

	// Next-due model: an item contributes its FULL estimated_cost to the
	// next-month projection iff its next due date falls within the next
	// month. Monthly items always contribute (next_due always ≤ ~30d).
	// Unpaid items default to "due now".
	nextMonthCutoff := now.AddDate(0, 1, 0)

	// 'once' wishlist items still contribute their full cost (existing
	// semantics: planned one-off purchase shows up as upcoming spend).
	var regularItems []models.RegularItemForecast
	var wishlistTotal float64
	for _, item := range unpurchased {
		s := byItem[item.ID]
		var nextDue time.Time
		if s == nil || s.latest.IsZero() {
			nextDue = now // never paid → due now (Approach A)
		} else {
			switch item.Frequency {
			case models.FrequencyMonthly:
				nextDue = s.latest.AddDate(0, 1, 0)
			case models.FrequencyQuarterly:
				nextDue = s.latest.AddDate(0, 3, 0)
			case models.FrequencyYearly:
				nextDue = s.latest.AddDate(1, 0, 0)
			default:
				nextDue = now
			}
		}

		// Decide contribution.
		var contribution float64
		switch item.Frequency {
		case models.FrequencyOnce:
			// Wishlist one-off: planned purchase, always projected once.
			contribution = item.EstimatedCost
		case models.FrequencyMonthly:
			// Always due within the next month.
			contribution = item.EstimatedCost
		default: // quarterly | yearly
			if !nextDue.After(nextMonthCutoff) {
				contribution = item.EstimatedCost
			}
		}

		if contribution > 0 {
			catMap[item.Category] += contribution
			wishlistTotal += contribution
		}

		if item.Frequency != models.FrequencyOnce {
			paidThisPeriod := s != nil && s.paidInPeriod
			var paidAmount float64
			var paidCount int
			if s != nil {
				paidAmount = s.paidAmount
				paidCount = s.paidCount
			}
			var nextDueStr string
			if !nextDue.IsZero() {
				nextDueStr = nextDue.Format("2006-01-02")
			}
			regularItems = append(regularItems, models.RegularItemForecast{
				ID:             item.ID,
				Name:           item.Name,
				EstimatedCost:  item.EstimatedCost,
				MonthlyCost:    item.EstimatedCost, // full per-period cost; UI suffix varies
				Frequency:      string(item.Frequency),
				Category:       item.Category,
				PaidThisPeriod: paidThisPeriod,
				PaidAmount:     paidAmount,
				PaidCount:      paidCount,
				NextDueDate:    nextDueStr,
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
