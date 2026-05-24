package repository_test

import (
	"bytes"
	"context"
	"testing"
	"time"

	"budget-go/internal/mongotest"
	"budget-go/models"
	"budget-go/repository"

	"go.mongodb.org/mongo-driver/bson"
)

// testCtx returns a context with a sane timeout for individual repo operations.
func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func newTx(category string, amount float64, when time.Time) *models.Transaction {
	return &models.Transaction{
		Type:     models.Expense,
		Amount:   amount,
		Date:     when,
		Category: category,
		CreatedBy: &models.UserInfo{
			UserID:      "u1",
			DisplayName: "Alice",
		},
	}
}

// ─── Transaction ────────────────────────────────────────────────────────────

func TestTransactionRepo_CreateAndFind(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	tx := newTx("Продукты", 1500, time.Date(2026, 1, 15, 0, 0, 0, 0, time.UTC))
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if tx.ID == "" {
		t.Fatal("Create did not assign ID")
	}
	if tx.Version != 1 {
		t.Errorf("Version = %d, want 1", tx.Version)
	}
	if tx.LastModifiedBy == nil || tx.LastModifiedBy.UserID != "u1" {
		t.Error("LastModifiedBy not derived from CreatedBy")
	}

	got, err := repo.FindByID(ctx, tx.ID)
	if err != nil {
		t.Fatalf("FindByID: %v", err)
	}
	if got.Amount != 1500 || got.Category != "Продукты" {
		t.Errorf("unexpected tx: %+v", got)
	}
}

func TestTransactionRepo_Update_OK(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	tx := newTx("X", 100, time.Now())
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatal(err)
	}

	updated, err := repo.Update(ctx, tx.ID, bson.M{"amount": 200}, tx.Version, nil)
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if updated.Amount != 200 {
		t.Errorf("amount = %v, want 200", updated.Amount)
	}
	if updated.Version != tx.Version+1 {
		t.Errorf("version = %d, want %d", updated.Version, tx.Version+1)
	}
}

func TestTransactionRepo_Update_VersionConflict(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	tx := newTx("X", 100, time.Now())
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatal(err)
	}
	// Bump version once
	if _, err := repo.Update(ctx, tx.ID, bson.M{"amount": 200}, tx.Version, nil); err != nil {
		t.Fatal(err)
	}
	// Stale baseVersion → conflict
	_, err := repo.Update(ctx, tx.ID, bson.M{"amount": 999}, 1, nil)
	if err != repository.ErrConflict {
		t.Errorf("err = %v, want ErrConflict", err)
	}
}

func TestTransactionRepo_Delete_SoftAndConflict(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	tx := newTx("X", 50, time.Now())
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatal(err)
	}
	deleted, err := repo.Delete(ctx, tx.ID, tx.Version, nil)
	if err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if deleted.DeletedAt == nil {
		t.Error("DeletedAt not set after soft delete")
	}
	// FindByID excludes soft-deleted
	if _, err := repo.FindByID(ctx, tx.ID); err == nil {
		t.Error("FindByID should not return soft-deleted row")
	}
	// Stale-version delete returns conflict
	tx2 := newTx("Y", 10, time.Now())
	if err := repo.Create(ctx, tx2); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.Update(ctx, tx2.ID, bson.M{"amount": 11}, tx2.Version, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.Delete(ctx, tx2.ID, 1, nil); err != repository.ErrConflict {
		t.Errorf("err = %v, want ErrConflict on stale delete", err)
	}
}

func TestTransactionRepo_Upsert_CreateAndUpdate(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	tx := newTx("Z", 333, time.Now())
	tx.ID = "fixed-id-upsert"

	created, err := repo.Upsert(ctx, tx, 0, true)
	if err != nil {
		t.Fatalf("Upsert(create): %v", err)
	}
	if created.Version != 1 {
		t.Errorf("Version = %d, want 1", created.Version)
	}

	// Re-upsert with isCreate=true → duplicate _id → conflict
	dup := newTx("Z", 9, time.Now())
	dup.ID = "fixed-id-upsert"
	if _, err := repo.Upsert(ctx, dup, 0, true); err != repository.ErrConflict {
		t.Errorf("duplicate upsert err = %v, want ErrConflict", err)
	}

	// Update path with correct baseVersion
	upd := *created
	upd.Amount = 999
	out, err := repo.Upsert(ctx, &upd, created.Version, false)
	if err != nil {
		t.Fatalf("Upsert(update): %v", err)
	}
	if out.Amount != 999 || out.Version != 2 {
		t.Errorf("unexpected: %+v", out)
	}

	// Update with stale baseVersion → conflict
	upd2 := *created
	upd2.Amount = 1
	if _, err := repo.Upsert(ctx, &upd2, 1, false); err != repository.ErrConflict {
		t.Errorf("stale upsert err = %v, want ErrConflict", err)
	}
}

func TestTransactionRepo_FindWithFilter(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	base := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	mustCreate(t, ctx, repo, "Food", 100, base)
	mustCreate(t, ctx, repo, "Food", 200, base.AddDate(0, 0, 1))
	mustCreate(t, ctx, repo, "Transport", 300, base.AddDate(0, 0, 2))
	mustCreate(t, ctx, repo, "Food", 400, base.AddDate(0, 1, 0)) // April

	from := base
	to := base.AddDate(0, 0, 7)
	filter := models.TransactionFilter{
		Type:       "expense",
		From:       &from,
		To:         &to,
		Categories: []string{"Food"},
		Limit:      10,
	}
	got, total, err := repo.Find(ctx, filter)
	if err != nil {
		t.Fatalf("Find: %v", err)
	}
	if total != 2 {
		t.Errorf("total = %d, want 2", total)
	}
	if len(got) != 2 {
		t.Errorf("len(got) = %d, want 2", len(got))
	}
	// sorted by date desc
	if !got[0].Date.After(got[1].Date) {
		t.Errorf("not sorted desc: %v then %v", got[0].Date, got[1].Date)
	}
}

func TestTransactionRepo_GetSummary(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	now := time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC)
	mustCreateTyped(t, ctx, repo, models.Income, "Salary", 5000, now)
	mustCreateTyped(t, ctx, repo, models.Income, "Bonus", 500, now.AddDate(0, 0, 1))
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 200, now)
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 300, now)
	mustCreateTyped(t, ctx, repo, models.InitialBalance, "Init", 10000, now)

	from := now.AddDate(0, 0, -1)
	to := now.AddDate(0, 0, 7)
	s, err := repo.GetSummary(ctx, from, to, "")
	if err != nil {
		t.Fatalf("GetSummary: %v", err)
	}
	if s.TotalIncome != 5500 {
		t.Errorf("income = %v, want 5500", s.TotalIncome)
	}
	if s.TotalExpense != 500 {
		t.Errorf("expense = %v, want 500", s.TotalExpense)
	}
	if s.InitialBalance != 10000 {
		t.Errorf("initial = %v, want 10000", s.InitialBalance)
	}
	if s.Balance != 5500+10000-500 {
		t.Errorf("balance = %v, want %v", s.Balance, 5500.0+10000.0-500.0)
	}
}

func TestTransactionRepo_FindModifiedSince(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	t1 := newTx("A", 1, time.Now())
	if err := repo.Create(ctx, t1); err != nil {
		t.Fatal(err)
	}
	// Mongo Date is millisecond-precision; pause so cutoff falls strictly
	// after Create's updated_at and strictly before Update's.
	time.Sleep(5 * time.Millisecond)
	cutoff := time.Now()
	time.Sleep(5 * time.Millisecond)

	out, err := repo.FindModifiedSince(ctx, cutoff)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 0 {
		t.Errorf("expected 0 modified, got %d", len(out))
	}

	if _, err := repo.Update(ctx, t1.ID, bson.M{"amount": 2}, t1.Version, nil); err != nil {
		t.Fatal(err)
	}
	out, err = repo.FindModifiedSince(ctx, cutoff)
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 {
		t.Errorf("expected 1 modified, got %d", len(out))
	}
}

func TestTransactionRepo_WishlistLinkAndUnlink(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	wid := "wishlist-1"
	now := time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC)
	linked := newTx("Подписка", 1000, now)
	linked.WishlistID = wid
	if err := repo.Create(ctx, linked); err != nil {
		t.Fatal(err)
	}
	other := newTx("Подписка", 50, now)
	other.WishlistID = "wishlist-other"
	if err := repo.Create(ctx, other); err != nil {
		t.Fatal(err)
	}

	rows, err := repo.FindLinkedToWishlist(ctx, wid, time.Time{}, time.Time{})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("got %d rows, want 1", len(rows))
	}
	if rows[0].ID != linked.ID {
		t.Errorf("ID = %q, want %q", rows[0].ID, linked.ID)
	}

	n, err := repo.UnlinkFromWishlist(ctx, wid, time.Time{}, time.Time{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Errorf("unlinked %d, want 1", n)
	}
	after, _ := repo.FindByID(ctx, linked.ID)
	if after.WishlistID != "" {
		t.Errorf("wishlist_id = %q, want empty", after.WishlistID)
	}
}

func TestTransactionRepo_AggregateByCategory(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	now := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 100, now)
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 200, now)
	mustCreateTyped(t, ctx, repo, models.Expense, "Transport", 50, now)

	from := now.AddDate(0, -1, 0)
	to := now.AddDate(0, 1, 0)
	cats, err := repo.AggregateByCategory(ctx, string(models.Expense), "", from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(cats) != 2 {
		t.Fatalf("got %d cats, want 2", len(cats))
	}
	// Sorted desc by total
	if cats[0].Category != "Food" || cats[0].Amount != 300 {
		t.Errorf("cats[0] = %+v", cats[0])
	}
	if cats[0].Percentage < 80 || cats[0].Percentage > 86 {
		t.Errorf("Food percentage = %v, want ~85", cats[0].Percentage)
	}
}

// ─── Wishlist ──────────────────────────────────────────────────────────────

func TestWishlistRepo_CRUD(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewWishlistRepository(db)
	ctx := testCtx(t)

	item := &models.WishlistItem{
		Name:          "Сноуборд",
		EstimatedCost: 50000,
		Category:      "Спорт",
		Priority:      2,
		Frequency:     models.FrequencyOnce,
	}
	if err := repo.Create(ctx, item); err != nil {
		t.Fatal(err)
	}
	if item.ID == "" || item.Version != 1 {
		t.Errorf("after create: %+v", item)
	}

	all, err := repo.FindAll(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 1 {
		t.Errorf("FindAll len = %d, want 1", len(all))
	}

	updated, err := repo.Update(ctx, item.ID, bson.M{"purchased": true}, item.Version, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !updated.Purchased {
		t.Error("Purchased not set")
	}

	unpurchased, err := repo.FindUnpurchased(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(unpurchased) != 0 {
		t.Errorf("FindUnpurchased = %d, want 0", len(unpurchased))
	}

	// Soft delete
	if _, err := repo.Delete(ctx, item.ID, updated.Version, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.FindByID(ctx, item.ID); err == nil {
		t.Error("FindByID should fail after delete")
	}
}

// ─── Category ──────────────────────────────────────────────────────────────

func TestCategoryRepo_EnsureDefaultsIdempotent(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatalf("EnsureDefaults(1): %v", err)
	}
	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatalf("EnsureDefaults(2): %v", err)
	}
	expense, err := repo.List(ctx, "expense")
	if err != nil {
		t.Fatal(err)
	}
	if len(expense) < 5 {
		t.Errorf("expense defaults len = %d, want ≥5", len(expense))
	}
	for _, c := range expense {
		if !c.IsDefault {
			t.Errorf("seed category %q is_default=false", c.Name)
		}
		if c.Color == "" || c.Icon == "" {
			t.Errorf("seed category %q missing color/icon (color=%q icon=%q)", c.Name, c.Color, c.Icon)
		}
	}
}

// Verifies that EnsureDefaults backfills color/icon on default rows that
// were seeded by an older version of the app (before these fields existed).
func TestCategoryRepo_EnsureDefaultsBackfillsColorIcon(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	now := time.Now()
	col := db.Collection("categories")
	if _, err := col.InsertOne(ctx, bson.M{
		"_id":        "legacy-prods",
		"section":    "expense",
		"name":       "Продукты",
		"is_default": true,
		"created_at": now,
		"version":    1,
		"updated_at": now,
		"deleted_at": nil,
		// Note: no color/icon fields — simulates pre-migration row.
	}); err != nil {
		t.Fatalf("insert legacy: %v", err)
	}

	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatalf("EnsureDefaults: %v", err)
	}

	expense, err := repo.List(ctx, "expense")
	if err != nil {
		t.Fatal(err)
	}
	var found *models.Category
	for i := range expense {
		if expense[i].ID == "legacy-prods" {
			found = &expense[i]
			break
		}
	}
	if found == nil {
		t.Fatal("legacy row missing after EnsureDefaults")
	}
	if found.Color == "" || found.Icon == "" {
		t.Errorf("legacy row not backfilled: color=%q icon=%q", found.Color, found.Icon)
	}
	if found.Version < 2 {
		t.Errorf("legacy row version should bump on backfill: got %d", found.Version)
	}
}

// Legacy seeds had wishlist "Дом" + "Техника"; current seeds use
// "Жильё/ЖКХ" + "Электроника" (matching expense names so wishlist→expense
// promotion doesn't fork into duplicate category rows). EnsureDefaults
// must rename existing default rows in place, not double-seed.
func TestCategoryRepo_EnsureDefaultsRenamesLegacyWishlistNames(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	now := time.Now()
	col := db.Collection("categories")
	if _, err := col.InsertOne(ctx, bson.M{
		"_id":        "legacy-dom",
		"section":    "wishlist",
		"name":       "Дом",
		"is_default": true,
		"created_at": now,
		"version":    1,
		"updated_at": now,
		"deleted_at": nil,
	}); err != nil {
		t.Fatalf("insert legacy: %v", err)
	}

	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatalf("EnsureDefaults: %v", err)
	}

	wishlist, err := repo.List(ctx, "wishlist")
	if err != nil {
		t.Fatal(err)
	}
	// Legacy row should have been renamed (same ID), and no separate
	// "Дом" row should remain.
	var renamed *models.Category
	for i := range wishlist {
		if wishlist[i].ID == "legacy-dom" {
			renamed = &wishlist[i]
		}
		if wishlist[i].Name == "Дом" {
			t.Errorf("legacy name 'Дом' still present in wishlist after rename")
		}
	}
	if renamed == nil {
		t.Fatal("legacy row vanished")
	}
	if renamed.Name != "Жильё/ЖКХ" {
		t.Errorf("rename target = %q, want 'Жильё/ЖКХ'", renamed.Name)
	}
	if renamed.Version < 2 {
		t.Errorf("rename should bump version, got %d", renamed.Version)
	}

	// Idempotent — second pass is a no-op.
	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestCategoryRepo_UpdateAppliesPartialFields(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	c, err := repo.Create(ctx, "expense", "Origin", "#000000", "tag", nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.Version != 1 {
		t.Fatalf("initial version = %d, want 1", c.Version)
	}

	newName := "Renamed"
	newColor := "#FF00FF"
	updated, err := repo.Update(ctx, c.ID, models.UpdateCategoryRequest{
		Name:  &newName,
		Color: &newColor,
		// Icon left as nil — must remain "tag".
	}, nil)
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if updated.Name != "Renamed" || updated.Color != "#FF00FF" {
		t.Errorf("partial update lost fields: %+v", updated)
	}
	if updated.Icon != "tag" {
		t.Errorf("Icon must remain unchanged when not in patch: got %q", updated.Icon)
	}
	if updated.Version != 2 {
		t.Errorf("Update should bump version: got %d", updated.Version)
	}

	// Clearing icon via empty-string pointer.
	empty := ""
	cleared, err := repo.Update(ctx, c.ID, models.UpdateCategoryRequest{Icon: &empty}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if cleared.Icon != "" {
		t.Errorf("empty-string icon patch must clear: got %q", cleared.Icon)
	}

	// icon_scale: write 1.5, then reset to 0 (= use default).
	scaled := 1.5
	got, err := repo.Update(ctx, c.ID, models.UpdateCategoryRequest{IconScale: &scaled}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got.IconScale != 1.5 {
		t.Errorf("IconScale = %v, want 1.5", got.IconScale)
	}
	zero := 0.0
	reset, err := repo.Update(ctx, c.ID, models.UpdateCategoryRequest{IconScale: &zero}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if reset.IconScale != 0 {
		t.Errorf("IconScale reset = %v, want 0 (default)", reset.IconScale)
	}
}

func TestCategoryRepo_CreateDeleteAndProtectDefault(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryRepository(db)
	ctx := testCtx(t)

	if err := repo.EnsureDefaults(ctx); err != nil {
		t.Fatal(err)
	}

	c, err := repo.Create(ctx, "expense", "MyCategory", "#FF0000", "tag", nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.IsDefault {
		t.Error("user-created category should not be is_default")
	}
	if c.Color != "#FF0000" || c.Icon != "tag" {
		t.Errorf("Create did not persist color/icon: got color=%q icon=%q", c.Color, c.Icon)
	}
	// Cannot recreate same name in same section (unique index on non-deleted)
	if _, err := repo.Create(ctx, "expense", "MyCategory", "", "", nil); err == nil {
		t.Error("duplicate Create should fail")
	}

	// Delete user category — succeeds
	if _, err := repo.Delete(ctx, c.ID, c.Version, nil); err != nil {
		t.Fatalf("Delete: %v", err)
	}

	// Delete default — refused (mongo.ErrNoDocuments because filter excludes is_default=true)
	list, _ := repo.List(ctx, "expense")
	var def models.Category
	for _, x := range list {
		if x.IsDefault {
			def = x
			break
		}
	}
	if def.ID == "" {
		t.Fatal("no default to attempt-delete")
	}
	if _, err := repo.Delete(ctx, def.ID, def.Version, nil); err == nil {
		t.Error("Delete of default should fail")
	}
}

// ─── User ──────────────────────────────────────────────────────────────────

func TestUserRepo_EnsureAdminPromotesEarliestAndIsIdempotent(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewUserRepository(db)
	ctx := testCtx(t)

	// EnsureAdmin on empty collection is a no-op.
	if err := repo.EnsureAdmin(ctx); err != nil {
		t.Fatal(err)
	}

	first := &models.User{Login: "first", DisplayName: "First", PasswordHash: "x"}
	if err := repo.Create(ctx, first); err != nil {
		t.Fatal(err)
	}
	// Brief pause + second user so their ObjectIDs differ in timestamp order.
	time.Sleep(20 * time.Millisecond)
	second := &models.User{Login: "second", DisplayName: "Second", PasswordHash: "x"}
	if err := repo.Create(ctx, second); err != nil {
		t.Fatal(err)
	}

	if err := repo.EnsureAdmin(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := repo.FindByID(ctx, first.ID.Hex())
	if err != nil {
		t.Fatal(err)
	}
	if !got.IsAdmin {
		t.Error("earliest user should be promoted to admin")
	}
	other, _ := repo.FindByID(ctx, second.ID.Hex())
	if other.IsAdmin {
		t.Error("second user should NOT be admin")
	}

	// Idempotent: a second call doesn't shift admin to a different user.
	if err := repo.EnsureAdmin(ctx); err != nil {
		t.Fatal(err)
	}
	other, _ = repo.FindByID(ctx, second.ID.Hex())
	if other.IsAdmin {
		t.Error("EnsureAdmin must not grant admin when one already exists")
	}
}

func TestUserRepo_SetAdminToggles(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewUserRepository(db)
	ctx := testCtx(t)

	u := &models.User{Login: "x", DisplayName: "X", PasswordHash: "y"}
	if err := repo.Create(ctx, u); err != nil {
		t.Fatal(err)
	}
	if err := repo.SetAdmin(ctx, u.ID.Hex(), true); err != nil {
		t.Fatal(err)
	}
	got, _ := repo.FindByID(ctx, u.ID.Hex())
	if !got.IsAdmin {
		t.Fatal("SetAdmin(true) did not stick")
	}
	if err := repo.SetAdmin(ctx, u.ID.Hex(), false); err != nil {
		t.Fatal(err)
	}
	got, _ = repo.FindByID(ctx, u.ID.Hex())
	if got.IsAdmin {
		t.Fatal("SetAdmin(false) did not stick")
	}
}

// ─── CategoryIcon ──────────────────────────────────────────────────────────

func TestCategoryIconRepo_RoundTrip(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewCategoryIconRepository(db)
	ctx := testCtx(t)

	payload := []byte("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
	icon, err := repo.Create(ctx, "image/svg+xml", payload, &models.UserInfo{UserID: "u1", DisplayName: "Alice"})
	if err != nil {
		t.Fatal(err)
	}
	if icon.ID == "" {
		t.Fatal("ID not assigned")
	}
	if icon.SizeBytes != len(payload) {
		t.Errorf("SizeBytes = %d, want %d", icon.SizeBytes, len(payload))
	}

	fetched, err := repo.FindByID(ctx, icon.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(fetched.Data, payload) {
		t.Error("FindByID: bytes mismatch")
	}

	list, err := repo.List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].ID != icon.ID {
		t.Errorf("List: unexpected = %+v", list)
	}
	if list[0].Data != nil {
		t.Error("List must omit binary payload")
	}

	if err := repo.Delete(ctx, icon.ID); err != nil {
		t.Fatal(err)
	}
	if err := repo.Delete(ctx, icon.ID); err == nil {
		t.Error("second Delete must return mongo.ErrNoDocuments")
	}
}

func TestUserRepo_CreateFindByLoginAndID(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewUserRepository(db)
	ctx := testCtx(t)

	u := &models.User{
		Login:        "alice",
		PasswordHash: "$2a$12$xxx",
		DisplayName:  "Alice",
	}
	if err := repo.Create(ctx, u); err != nil {
		t.Fatal(err)
	}
	if u.ID.IsZero() {
		t.Error("ID not assigned")
	}
	if u.CreatedAt.IsZero() {
		t.Error("CreatedAt not set")
	}

	byLogin, err := repo.FindByLogin(ctx, "alice")
	if err != nil {
		t.Fatal(err)
	}
	if byLogin.DisplayName != "Alice" {
		t.Errorf("display_name = %q", byLogin.DisplayName)
	}

	if _, err := repo.FindByLogin(ctx, "missing"); err == nil {
		t.Error("expected error for missing login")
	}

	byID, err := repo.FindByID(ctx, u.ID.Hex())
	if err != nil {
		t.Fatal(err)
	}
	if byID.Login != "alice" {
		t.Errorf("login = %q", byID.Login)
	}

	if _, err := repo.FindByID(ctx, "not-a-hex"); err == nil {
		t.Error("expected error for invalid hex id")
	}

	all, err := repo.FindAll(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 1 {
		t.Errorf("FindAll = %d, want 1", len(all))
	}
}

// ─── DetailRequest ─────────────────────────────────────────────────────────

func TestDetailRequestRepo_CRUDAndStatus(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewDetailRequestRepository(db)
	ctx := testCtx(t)

	dr := &models.DetailRequest{
		ParentTransactionID: "tx-parent-1",
		TargetAmount:        1000,
		Creator:             &models.UserInfo{UserID: "u-creator", DisplayName: "Boss"},
		Assignee:            &models.UserInfo{UserID: "u-assignee", DisplayName: "Worker"},
	}
	if err := repo.Create(ctx, dr); err != nil {
		t.Fatal(err)
	}
	if dr.ID == "" {
		t.Fatal("no ID")
	}
	if dr.Status != models.DetailRequestOpen {
		t.Errorf("default status = %q, want open", dr.Status)
	}

	got, err := repo.FindByID(ctx, dr.ID)
	if err != nil || got.TargetAmount != 1000 {
		t.Fatalf("FindByID: %+v err=%v", got, err)
	}

	byParent, err := repo.FindByParentTxID(ctx, "tx-parent-1")
	if err != nil || byParent.ID != dr.ID {
		t.Fatalf("FindByParentTxID: %+v err=%v", byParent, err)
	}

	// Filter by status open
	list, err := repo.Find(ctx, repository.DetailRequestFilter{Status: "open"})
	if err != nil || len(list) != 1 {
		t.Fatalf("Find open: len=%d err=%v", len(list), err)
	}

	// Filter by assignee
	list, _ = repo.Find(ctx, repository.DetailRequestFilter{AssigneeID: "u-assignee"})
	if len(list) != 1 {
		t.Errorf("by assignee got %d", len(list))
	}

	// Close it — closed_at populated
	closed, err := repo.SetStatus(ctx, dr.ID, models.DetailRequestClosed)
	if err != nil {
		t.Fatal(err)
	}
	if closed.Status != models.DetailRequestClosed {
		t.Errorf("status not closed: %q", closed.Status)
	}
	if closed.ClosedAt == nil {
		t.Error("ClosedAt not set")
	}

	// Delete
	if err := repo.Delete(ctx, dr.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.FindByID(ctx, dr.ID); err == nil {
		t.Error("should not find deleted")
	}
}

// ─── Aggregations: monthly / averages / children ───────────────────────────

func TestTransactionRepo_AggregateMonthlyRange(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	jan := time.Date(2026, 1, 15, 0, 0, 0, 0, time.UTC)
	feb := time.Date(2026, 2, 15, 0, 0, 0, 0, time.UTC)
	mustCreateTyped(t, ctx, repo, models.Income, "X", 1000, jan)
	mustCreateTyped(t, ctx, repo, models.Expense, "Y", 200, jan)
	mustCreateTyped(t, ctx, repo, models.Expense, "Y", 300, feb)

	out, err := repo.AggregateMonthlyRange(ctx,
		time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
		time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC), "")
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 3 {
		t.Fatalf("len=%d, want 3 (jan,feb,mar)", len(out))
	}
	if out[0].Year != 2026 || out[0].Month != 1 || out[0].Income != 1000 || out[0].Expense != 200 {
		t.Errorf("jan: %+v", out[0])
	}
	if out[1].Month != 2 || out[1].Expense != 300 {
		t.Errorf("feb: %+v", out[1])
	}
	if out[2].Balance != 0 {
		t.Errorf("mar empty bucket: %+v", out[2])
	}
}

func TestTransactionRepo_ChildrenAndExclude(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	parent := mustCreate(t, ctx, repo, "Big", 1000, time.Now())
	child1 := mustCreate(t, ctx, repo, "Food", 400, time.Now())
	child2 := mustCreate(t, ctx, repo, "Transport", 600, time.Now())
	// Tag children
	if _, err := repo.Update(ctx, child1.ID, map[string]any{"parent_id": parent.ID, "excluded_from_stats": true}, child1.Version, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.Update(ctx, child2.ID, map[string]any{"parent_id": parent.ID, "excluded_from_stats": true}, child2.Version, nil); err != nil {
		t.Fatal(err)
	}

	kids, err := repo.FindChildren(ctx, parent.ID)
	if err != nil || len(kids) != 2 {
		t.Fatalf("kids=%d err=%v", len(kids), err)
	}

	// Flip excluded_from_stats to false on all children
	if err := repo.SetExcludedForChildren(ctx, parent.ID, false, nil); err != nil {
		t.Fatal(err)
	}
	kids, _ = repo.FindChildren(ctx, parent.ID)
	for _, k := range kids {
		if k.ExcludedFromStats {
			t.Errorf("%s still excluded", k.ID)
		}
	}

	// Soft delete children
	if err := repo.SoftDeleteChildren(ctx, parent.ID, nil); err != nil {
		t.Fatal(err)
	}
	kids, _ = repo.FindChildren(ctx, parent.ID)
	if len(kids) != 0 {
		t.Errorf("expected 0 after SoftDeleteChildren, got %d", len(kids))
	}
}

func TestTransactionRepo_LinkedToWishlistMulti(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	in := time.Date(2026, 6, 15, 0, 0, 0, 0, time.UTC)
	out := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)

	t1 := newTx("X", 100, in)
	t1.WishlistID = "w-a"
	if err := repo.Create(ctx, t1); err != nil {
		t.Fatal(err)
	}
	t2 := newTx("X", 200, in)
	t2.WishlistID = "w-b"
	if err := repo.Create(ctx, t2); err != nil {
		t.Fatal(err)
	}
	tOut := newTx("X", 999, out)
	tOut.WishlistID = "w-a"
	if err := repo.Create(ctx, tOut); err != nil {
		t.Fatal(err)
	}

	got, err := repo.FindLinkedToWishlistMulti(ctx, []string{"w-a", "w-b"},
		time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC),
		time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Errorf("len=%d, want 2 (in window only)", len(got))
	}

	// Empty IDs → empty slice, no DB hit
	empty, err := repo.FindLinkedToWishlistMulti(ctx, nil, time.Time{}, time.Time{})
	if err != nil || len(empty) != 0 {
		t.Errorf("empty IDs: got %v err=%v", empty, err)
	}
}

func TestTransactionRepo_AverageMonthlyCategoryExpensesUnlinked(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewTransactionRepository(db)
	ctx := testCtx(t)

	from := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC) // ~3 months

	// 2 unlinked expenses in Food
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 300, from)
	mustCreateTyped(t, ctx, repo, models.Expense, "Food", 600, from.AddDate(0, 1, 0))
	// 1 linked expense — should be excluded
	linked := newTx("Food", 1000, from.AddDate(0, 2, 0))
	linked.WishlistID = "wl-1"
	if err := repo.Create(ctx, linked); err != nil {
		t.Fatal(err)
	}

	cats, err := repo.GetAverageMonthlyCategoryExpensesUnlinked(ctx, from, to, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(cats) != 1 || cats[0].Category != "Food" {
		t.Fatalf("cats=%+v", cats)
	}
	// 900 / ~3 months ≈ 300/mo (allow wide tolerance — repo uses 30d-month math)
	if cats[0].Amount < 250 || cats[0].Amount > 400 {
		t.Errorf("monthly avg = %v, want ~300", cats[0].Amount)
	}
}

func TestWishlistRepo_UpsertAndModifiedSince(t *testing.T) {
	db := mongotest.Start(t)
	repo := repository.NewWishlistRepository(db)
	ctx := testCtx(t)

	item := &models.WishlistItem{
		ID:            "wl-fixed",
		Name:          "Holiday",
		EstimatedCost: 5000,
		Category:      "Путешествия",
		Frequency:     models.FrequencyYearly,
	}
	created, err := repo.Upsert(ctx, item, 0, true)
	if err != nil {
		t.Fatal(err)
	}
	if created.Version != 1 {
		t.Errorf("v=%d", created.Version)
	}

	// Duplicate create → conflict
	if _, err := repo.Upsert(ctx, &models.WishlistItem{ID: "wl-fixed", Name: "Dup"}, 0, true); err != repository.ErrConflict {
		t.Errorf("dup err=%v, want ErrConflict", err)
	}

	// Update path
	updPayload := *created
	updPayload.Name = "Renamed"
	updated, err := repo.Upsert(ctx, &updPayload, created.Version, false)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Name != "Renamed" || updated.Version != 2 {
		t.Errorf("upsert update: %+v", updated)
	}

	// Stale version → conflict
	if _, err := repo.Upsert(ctx, &updPayload, 1, false); err != repository.ErrConflict {
		t.Errorf("stale err=%v", err)
	}

	// FindByID
	if _, err := repo.FindByID(ctx, "wl-fixed"); err != nil {
		t.Fatal(err)
	}

	// FindModifiedSince — should include the item since it was modified after epoch
	since := time.Time{}
	mod, err := repo.FindModifiedSince(ctx, since)
	if err != nil {
		t.Fatal(err)
	}
	if len(mod) != 1 {
		t.Errorf("FindModifiedSince(zero) len=%d, want 1", len(mod))
	}
}

// ─── helpers ───────────────────────────────────────────────────────────────

func mustCreate(t *testing.T, ctx context.Context, repo *repository.TransactionRepository, category string, amount float64, when time.Time) *models.Transaction {
	t.Helper()
	tx := newTx(category, amount, when)
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatalf("Create(%s, %v): %v", category, amount, err)
	}
	return tx
}

func mustCreateTyped(t *testing.T, ctx context.Context, repo *repository.TransactionRepository, typ models.TransactionType, category string, amount float64, when time.Time) *models.Transaction {
	t.Helper()
	tx := newTx(category, amount, when)
	tx.Type = typ
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatalf("Create(%s, %v): %v", category, amount, err)
	}
	return tx
}
