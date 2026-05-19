package handlers_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"budget-go/handlers"
	"budget-go/models"
)

// TestLimits_ProgressEndpoint creates an expense category with a
// monthly_limit, books in-month spending, and verifies the
// /api/categories/limits-progress aggregation returns per-category +
// totals correctly.
func TestLimits_ProgressEndpoint(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	if err := f.catRepo.EnsureDefaults(ctx); err != nil {
		t.Fatal(err)
	}

	// Set a limit on the "Продукты" default expense category.
	w := f.do(t, http.MethodGet, "/api/categories?section=expense", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list cats: %d", w.Code)
	}
	cats := decodeBody[[]models.Category](t, w)
	var prodID string
	for _, c := range cats {
		if c.Name == "Продукты" {
			prodID = c.ID
			break
		}
	}
	if prodID == "" {
		t.Fatal("expected Продукты default category")
	}

	w = f.do(t, http.MethodPatch, "/api/categories/"+prodID, map[string]any{
		"monthly_limit": 1000,
	}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("patch limit: %d body=%s", w.Code, w.Body.String())
	}

	// Book 400₽ of in-month spending → 40% of limit.
	now := time.Now()
	mustCreateTx(t, ctx, f.txRepo, models.Expense, "Продукты", 400, now)

	w = f.do(t, http.MethodGet, "/api/categories/limits-progress", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("progress: %d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[handlers.LimitsProgressResponse](t, w)
	if resp.TotalLimit != 1000 {
		t.Errorf("TotalLimit = %v, want 1000", resp.TotalLimit)
	}
	if resp.TotalSpent != 400 {
		t.Errorf("TotalSpent = %v, want 400", resp.TotalSpent)
	}
	if len(resp.Categories) != 1 || resp.Categories[0].Name != "Продукты" {
		t.Errorf("Categories = %+v", resp.Categories)
	}
	if got := resp.Categories[0].Percent; got < 39.9 || got > 40.1 {
		t.Errorf("Percent = %v, want ~40", got)
	}

	// Clearing the limit via PATCH monthly_limit=null drops the category
	// from progress output entirely.
	w = f.do(t, http.MethodPatch, "/api/categories/"+prodID, map[string]any{
		"monthly_limit": nil,
	}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("clear limit: %d body=%s", w.Code, w.Body.String())
	}

	w = f.do(t, http.MethodGet, "/api/categories/limits-progress", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("progress 2: %d", w.Code)
	}
	resp = decodeBody[handlers.LimitsProgressResponse](t, w)
	if resp.TotalLimit != 0 || len(resp.Categories) != 0 {
		t.Errorf("after clear: TotalLimit=%v, Categories=%+v", resp.TotalLimit, resp.Categories)
	}
}

// TestLimits_NotificationOnOverflow creates a limit + an expense that
// crosses it, then waits for the async notification trigger to settle and
// verifies a category_limit_exceeded notification was generated. Marking
// it read updates the per-user state.
func TestLimits_NotificationOnOverflow(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	if err := f.catRepo.EnsureDefaults(ctx); err != nil {
		t.Fatal(err)
	}

	// Set a small limit on the default Транспорт expense category.
	cats, err := f.catRepo.List(ctx, "expense")
	if err != nil {
		t.Fatal(err)
	}
	var transportID string
	for _, c := range cats {
		if c.Name == "Транспорт" {
			transportID = c.ID
			break
		}
	}
	if transportID == "" {
		t.Fatal("missing default Транспорт")
	}
	w := f.do(t, http.MethodPatch, "/api/categories/"+transportID, map[string]any{
		"monthly_limit": 100,
	}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("set limit: %d body=%s", w.Code, w.Body.String())
	}

	// Book an in-month expense that overflows.
	now := time.Now()
	w = f.do(t, http.MethodPost, "/api/transactions", models.CreateTransactionRequest{
		Type:     models.Expense,
		Amount:   150,
		Date:     now.Format("2006-01-02"),
		Category: "Транспорт",
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create tx: %d body=%s", w.Code, w.Body.String())
	}

	// The trigger runs in a goroutine — poll until BOTH the category and
	// global notifications are visible (1 of each: category-limit alert
	// for Транспорт + the global rollup since 150₽ is also > total_limit
	// of 100₽). Capped at ~2s so the test isn't racy on slow CI hosts.
	var cat, global *models.Notification
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		w = f.do(t, http.MethodGet, "/api/notifications", nil, true)
		if w.Code == http.StatusOK {
			resp := decodeBody[handlers.NotificationsListResponse](t, w)
			cat, global = nil, nil
			for i := range resp.Data {
				n := resp.Data[i].Notification
				switch n.Type {
				case models.NotificationCategoryLimitExceeded:
					cat = &n
				case models.NotificationGlobalLimitExceeded:
					global = &n
				}
			}
			if cat != nil && global != nil {
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	if cat == nil {
		t.Fatal("category-limit notification not generated within 2s")
	}
	if cat.CategoryName != "Транспорт" {
		t.Errorf("category_name = %q", cat.CategoryName)
	}
	if cat.Limit != 100 || cat.Spent < 150 {
		t.Errorf("category limit=%v spent=%v", cat.Limit, cat.Spent)
	}
	if global == nil {
		t.Fatal("global-limit notification not generated within 2s")
	}

	// Mark all read — unread_count goes to 0 + each row's read=true on
	// subsequent fetch.
	w = f.do(t, http.MethodPost, "/api/notifications/read-all", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("read-all: %d", w.Code)
	}
	w = f.do(t, http.MethodGet, "/api/notifications", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list after read-all: %d", w.Code)
	}
	after := decodeBody[handlers.NotificationsListResponse](t, w)
	if after.UnreadCount != 0 {
		t.Errorf("UnreadCount after read-all = %d", after.UnreadCount)
	}
	for _, n := range after.Data {
		if !n.Read {
			t.Errorf("notification %s still unread for current user", n.ID)
		}
	}

	// Booking another over-limit expense shouldn't create a duplicate
	// notification — dedup key (type, period, category_id) holds.
	w = f.do(t, http.MethodPost, "/api/transactions", models.CreateTransactionRequest{
		Type:     models.Expense,
		Amount:   50,
		Date:     now.Format("2006-01-02"),
		Category: "Транспорт",
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("second over-limit tx: %d body=%s", w.Code, w.Body.String())
	}
	time.Sleep(300 * time.Millisecond)
	w = f.do(t, http.MethodGet, "/api/notifications", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list 3: %d", w.Code)
	}
	again := decodeBody[handlers.NotificationsListResponse](t, w)
	// Dedup holds across (category, global) — still exactly 2 rows
	// regardless of how many subsequent over-limit writes land.
	if len(again.Data) != 2 {
		t.Errorf("dedup broken — got %d notifications, want 2", len(again.Data))
	}
}
