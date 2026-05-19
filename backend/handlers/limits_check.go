package handlers

import (
	"context"
	"log"
	"time"

	"budget-go/models"
	"budget-go/repository"
)

// LimitChecker recomputes current-month spending for an expense category +
// the global total whenever a transaction touches it, and emits dedup'd
// notifications when a freshly-crossed threshold trips a limit. MVP rule:
// one alert per (category, month) at the 100% boundary; once fired, no
// further alerts for that pair until the next calendar month rolls over.
//
// Run async from request handlers — callers should fire-and-forget via
// `go checker.Run(...)` so write latency isn't held hostage by the dedup
// upsert.
type LimitChecker struct {
	catRepo   *repository.CategoryRepository
	txRepo    *repository.TransactionRepository
	notifRepo *repository.NotificationRepository
}

func NewLimitChecker(catRepo *repository.CategoryRepository, txRepo *repository.TransactionRepository, notifRepo *repository.NotificationRepository) *LimitChecker {
	return &LimitChecker{catRepo: catRepo, txRepo: txRepo, notifRepo: notifRepo}
}

// Run evaluates limits for the given category name. If categoryName is
// empty (e.g. on a delete that didn't expose the category) we still
// recompute the global total so a refund-style drop below 100% won't keep
// stale "exceeded" state pinned in the UI — though dedup keeps notifications
// idempotent regardless.
func (c *LimitChecker) Run(ctx context.Context, categoryName string) {
	if c == nil {
		return
	}
	// Compute current calendar month bounds.
	now := time.Now()
	from := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	to := from.AddDate(0, 1, 0).Add(-time.Second)
	period := from.Format("2006-01")

	// Pull all expense categories so we can match by name (transactions
	// store the category as a name string, not an ID — see Transaction.Category).
	cats, err := c.catRepo.List(ctx, "expense")
	if err != nil {
		log.Printf("LimitChecker: list categories: %v", err)
		return
	}

	agg, err := c.txRepo.AggregateByCategory(ctx, string(models.Expense), from, to)
	if err != nil {
		log.Printf("LimitChecker: aggregate: %v", err)
		return
	}
	spentByName := make(map[string]float64, len(agg))
	for _, a := range agg {
		spentByName[a.Category] = a.Amount
	}

	// Per-category check — only fire for the category that was touched
	// (avoids spamming all other untouched categories on every write).
	// Global check still runs unconditionally below.
	if categoryName != "" {
		for _, cat := range cats {
			if cat.Name != categoryName || cat.MonthlyLimit == nil {
				continue
			}
			limit := *cat.MonthlyLimit
			spent := spentByName[cat.Name]
			if limit > 0 && spent >= limit {
				_, _, err := c.notifRepo.EnsureExceeded(ctx, &models.Notification{
					Type:         models.NotificationCategoryLimitExceeded,
					Period:       period,
					CategoryID:   cat.ID,
					CategoryName: cat.Name,
					Limit:        limit,
					Spent:        spent,
				})
				if err != nil {
					log.Printf("LimitChecker: ensure category notification: %v", err)
				}
			}
			break
		}
	}

	// Global limit = sum of all category limits where set; spent =
	// sum of spending in those same categories (so categories without
	// limits don't inflate the ratio).
	var totalLimit, totalSpent float64
	for _, cat := range cats {
		if cat.MonthlyLimit == nil {
			continue
		}
		totalLimit += *cat.MonthlyLimit
		totalSpent += spentByName[cat.Name]
	}
	if totalLimit > 0 && totalSpent >= totalLimit {
		_, _, err := c.notifRepo.EnsureExceeded(ctx, &models.Notification{
			Type:   models.NotificationGlobalLimitExceeded,
			Period: period,
			Limit:  totalLimit,
			Spent:  totalSpent,
		})
		if err != nil {
			log.Printf("LimitChecker: ensure global notification: %v", err)
		}
	}
}
