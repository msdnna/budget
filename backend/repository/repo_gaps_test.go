package repository_test

import (
	"context"
	"testing"
	"time"

	"budget-go/internal/mongotest"
	"budget-go/models"
	"budget-go/repository"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

// ─── transaction_repo: FindAll / FindModifiedSince / GetAverageMonthlyCategoryExpenses ──

func TestTransactionRepo_FindAll_FiltersByDateAndType(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	jan := time.Date(2026, 1, 10, 0, 0, 0, 0, time.UTC)
	feb := time.Date(2026, 2, 10, 0, 0, 0, 0, time.UTC)
	mar := time.Date(2026, 3, 10, 0, 0, 0, 0, time.UTC)

	mkTx := func(when time.Time, amt float64, tp models.TransactionType) *models.Transaction {
		return &models.Transaction{
			Type:      tp,
			Amount:    amt,
			Date:      when,
			Category:  "C",
			CreatedBy: &models.UserInfo{UserID: "u1", DisplayName: "A"},
		}
	}
	for _, tx := range []*models.Transaction{
		mkTx(jan, 100, models.Expense),
		mkTx(feb, 200, models.Expense),
		mkTx(mar, 300, models.Expense),
		mkTx(feb, 999, models.Income),
	} {
		if err := repo.Create(ctx, tx); err != nil {
			t.Fatal(err)
		}
	}

	// No filter — returns everything non-deleted.
	all, err := repo.FindAll(ctx, time.Time{}, time.Time{}, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 4 {
		t.Errorf("len(all) = %d, want 4", len(all))
	}

	// Type filter — only expenses.
	exp, err := repo.FindAll(ctx, time.Time{}, time.Time{}, string(models.Expense), "")
	if err != nil {
		t.Fatal(err)
	}
	if len(exp) != 3 {
		t.Errorf("len(expense) = %d, want 3", len(exp))
	}

	// Date range filter — only Feb tx.
	from := time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 2, 28, 0, 0, 0, 0, time.UTC)
	feb1, err := repo.FindAll(ctx, from, to, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(feb1) != 2 {
		t.Errorf("len(feb) = %d, want 2", len(feb1))
	}
}

// TestTransactionRepo_FindModifiedSince already lives in repo_test.go.

func TestTransactionRepo_GetAverageMonthlyCategoryExpenses(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	from := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC) // ~3 months

	// 3 expenses 300 each across the window for "Транспорт" — avg = 900/3 = 300.
	for _, d := range []time.Time{
		time.Date(2026, 1, 15, 0, 0, 0, 0, time.UTC),
		time.Date(2026, 2, 15, 0, 0, 0, 0, time.UTC),
		time.Date(2026, 3, 15, 0, 0, 0, 0, time.UTC),
	} {
		if err := repo.Create(ctx, &models.Transaction{
			Type:      models.Expense,
			Amount:    300,
			Date:      d,
			Category:  "Транспорт",
			CreatedBy: &models.UserInfo{UserID: "u1", DisplayName: "A"},
		}); err != nil {
			t.Fatal(err)
		}
	}

	got, err := repo.GetAverageMonthlyCategoryExpenses(ctx, from, to, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 {
		t.Fatalf("len = %d, want 1", len(got))
	}
	// 900 / 3 = 300 (approximately, since "months" uses 30-day buckets).
	if got[0].Amount < 250 || got[0].Amount > 350 {
		t.Errorf("avg amount = %f, want ~300", got[0].Amount)
	}
}

// ─── deposit scope: filter + default normalization ──

func TestTransactionRepo_DepositFilter(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	now := time.Date(2026, 4, 10, 0, 0, 0, 0, time.UTC)
	mk := func(amt float64, dep models.DepositType) *models.Transaction {
		return &models.Transaction{
			Type:      models.Expense,
			Amount:    amt,
			Date:      now,
			Category:  "C",
			Deposit:   dep,
			CreatedBy: &models.UserInfo{UserID: "u1"},
		}
	}
	// Two bank + one cash + one without deposit (should default to bank).
	for _, tx := range []*models.Transaction{
		mk(100, models.DepositBank),
		mk(200, models.DepositBank),
		mk(50, models.DepositCash),
		mk(70, ""),
	} {
		if err := repo.Create(ctx, tx); err != nil {
			t.Fatal(err)
		}
	}

	bank, _, err := repo.Find(ctx, models.TransactionFilter{Deposit: "bank", Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(bank) != 3 {
		t.Errorf("bank scope = %d, want 3 (incl. empty-deposit row normalized to bank)", len(bank))
	}

	cash, _, err := repo.Find(ctx, models.TransactionFilter{Deposit: "cash", Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(cash) != 1 || cash[0].Amount != 50 {
		t.Errorf("cash scope = %+v, want one 50-rub row", cash)
	}

	// No filter → both scopes returned.
	all, _, err := repo.Find(ctx, models.TransactionFilter{Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 4 {
		t.Errorf("no-filter = %d, want 4", len(all))
	}

	// Aggregate by category with deposit filter — only bank slice.
	bySection, err := repo.GetSummary(ctx,
		now.AddDate(0, 0, -1), now.AddDate(0, 0, 1), "cash")
	if err != nil {
		t.Fatal(err)
	}
	if bySection.TotalExpense != 50 {
		t.Errorf("cash summary expense = %f, want 50", bySection.TotalExpense)
	}
}

func TestNormalizeDeposit_Defaults(t *testing.T) {
	cases := []struct {
		in   models.DepositType
		want models.DepositType
	}{
		{"", models.DepositBank},
		{"unknown", models.DepositBank},
		{models.DepositBank, models.DepositBank},
		{models.DepositCash, models.DepositCash},
	}
	for _, c := range cases {
		if got := models.NormalizeDeposit(c.in); got != c.want {
			t.Errorf("NormalizeDeposit(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// ─── category_repo: FindByID / FindModifiedSince ──

func TestCategoryRepo_FindByID(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	c, err := repo.Create(ctx, "expense", "Тест", "#22C55E", "", nil)
	if err != nil {
		t.Fatal(err)
	}

	got, err := repo.FindByID(ctx, c.ID)
	if err != nil {
		t.Fatalf("FindByID: %v", err)
	}
	if got.Name != "Тест" || got.Section != "expense" {
		t.Errorf("unexpected category: %+v", got)
	}

	// Soft-deleted → FindByID returns an error.
	if _, err := repo.Delete(ctx, c.ID, c.Version, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.FindByID(ctx, c.ID); err == nil {
		t.Error("FindByID returned a soft-deleted category")
	}
}

func TestCategoryRepo_FindModifiedSince(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	if _, err := repo.Create(ctx, "expense", "A", "", "", nil); err != nil {
		t.Fatal(err)
	}

	all, err := repo.FindModifiedSince(ctx, time.Time{})
	if err != nil {
		t.Fatal(err)
	}
	if len(all) == 0 {
		t.Fatal("warm-up returned 0 rows")
	}

	future := time.Now().Add(time.Hour)
	none, err := repo.FindModifiedSince(ctx, future)
	if err != nil {
		t.Fatal(err)
	}
	if len(none) != 0 {
		t.Errorf("future since: got %d rows, want 0", len(none))
	}
}

// ─── user_repo: BackfillUserInfo / SoftDelete ──

func TestUserRepo_BackfillUserInfo(t *testing.T) {
	db := mongotest.Start(t)
	uRepo := repository.NewUserRepository(db)
	txRepo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	// Seed a user + a couple of denormalized references.
	u := &models.User{Login: "bob", PasswordHash: "x", DisplayName: "Bob (old)"}
	if err := uRepo.Create(ctx, u); err != nil {
		t.Fatal(err)
	}
	tx := &models.Transaction{
		Type:     models.Expense,
		Amount:   10,
		Date:     time.Now(),
		Category: "C",
		CreatedBy: &models.UserInfo{
			UserID:      u.ID.Hex(),
			DisplayName: "Bob (old)",
		},
		LastModifiedBy: &models.UserInfo{
			UserID:      u.ID.Hex(),
			DisplayName: "Bob (old)",
			AvatarURL:   "https://old/avatar.png",
		},
	}
	if err := txRepo.Create(ctx, tx); err != nil {
		t.Fatal(err)
	}

	// Apply backfill — new display_name + new avatar URL.
	if err := repository.BackfillUserInfo(ctx, db, u.ID.Hex(), "Bob (new)", "https://new/avatar.png"); err != nil {
		t.Fatalf("BackfillUserInfo: %v", err)
	}

	got, err := txRepo.FindByID(ctx, tx.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.CreatedBy.DisplayName != "Bob (new)" {
		t.Errorf("CreatedBy display_name = %q, want %q", got.CreatedBy.DisplayName, "Bob (new)")
	}
	if got.LastModifiedBy.AvatarURL != "https://new/avatar.png" {
		t.Errorf("LastModifiedBy avatar_url = %q, want new url", got.LastModifiedBy.AvatarURL)
	}

	// "Avatar cleared" path: empty URL → $unset on avatar_url field.
	if err := repository.BackfillUserInfo(ctx, db, u.ID.Hex(), "Bob (no-avatar)", ""); err != nil {
		t.Fatal(err)
	}
	got, err = txRepo.FindByID(ctx, tx.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.LastModifiedBy.AvatarURL != "" {
		t.Errorf("AvatarURL = %q after clear, want empty", got.LastModifiedBy.AvatarURL)
	}
	if got.CreatedBy.DisplayName != "Bob (no-avatar)" {
		t.Errorf("CreatedBy display_name = %q, want %q", got.CreatedBy.DisplayName, "Bob (no-avatar)")
	}
}

func TestUserRepo_SoftDelete(t *testing.T) {
	db := mongotest.Start(t)
	uRepo := repository.NewUserRepository(db)
	ctx := testCtx(t)

	u := &models.User{Login: "to-delete", PasswordHash: "x", DisplayName: "X"}
	if err := uRepo.Create(ctx, u); err != nil {
		t.Fatal(err)
	}

	if err := uRepo.SoftDelete(ctx, u.ID.Hex()); err != nil {
		t.Fatalf("SoftDelete: %v", err)
	}

	// Re-apply — must be idempotent (no error).
	if err := uRepo.SoftDelete(ctx, u.ID.Hex()); err != nil {
		t.Errorf("idempotent SoftDelete: %v", err)
	}

	// Unknown id → ErrNoDocuments.
	bogusID := "ffffffffffffffffffffffff"
	if err := uRepo.SoftDelete(ctx, bogusID); err == nil {
		t.Error("SoftDelete on unknown id returned nil error")
	} else if err != mongo.ErrNoDocuments {
		// Other error wrappings are fine, just sanity-check that something is returned.
		_ = err
	}

	// Verify deleted_at is now set on the document directly.
	var raw bson.M
	if err := db.Collection("users").FindOne(ctx, bson.M{"_id": u.ID}).Decode(&raw); err != nil {
		t.Fatal(err)
	}
	if _, ok := raw["deleted_at"]; !ok {
		t.Error("deleted_at not persisted")
	}

	_ = context.Background()
}
