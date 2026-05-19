package handlers_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"budget-go/config"
	"budget-go/handlers"
	"budget-go/internal/mongotest"
	"budget-go/middleware"
	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"go.mongodb.org/mongo-driver/mongo"
	"golang.org/x/crypto/bcrypt"
)

// ─── Test harness ──────────────────────────────────────────────────────────

func init() {
	gin.SetMode(gin.TestMode)
}

type fixture struct {
	db          *mongo.Database
	cfg         *config.Config
	txRepo      *repository.TransactionRepository
	wlRepo      *repository.WishlistRepository
	catRepo     *repository.CategoryRepository
	userRepo    *repository.UserRepository
	drRepo      *repository.DetailRequestRepository
	notifRepo   *repository.NotificationRepository
	router      *gin.Engine
	userID      string
	displayName string
}

// newFixture spins up a fresh Mongo DB, builds all repos+handlers, mounts the
// same /api router shape as main.go (minus PDF export and detail-requests, which
// don't need handler-level integration coverage at this scale), and seeds an
// authenticated user. Returns ready-to-use httptest fixtures.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	db := mongotest.Start(t)

	cfg := &config.Config{JWTSecret: "test-jwt-secret-min-32-chars-please!"}
	txRepo := repository.NewTransactionRepository(db)
	wlRepo := repository.NewWishlistRepository(db)
	catRepo := repository.NewCategoryRepository(db)
	userRepo := repository.NewUserRepository(db)
	drRepo := repository.NewDetailRequestRepository(db)
	notifRepo := repository.NewNotificationRepository(db)

	// Seed a real user with bcrypt-hashed password so /auth/login round-trips.
	hash, err := bcrypt.GenerateFromPassword([]byte("hunter2"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	u := &models.User{
		Login:        "alice",
		PasswordHash: string(hash),
		DisplayName:  "Alice",
	}
	if err := userRepo.Create(context.Background(), u); err != nil {
		t.Fatal(err)
	}

	authH := handlers.NewAuthHandler(userRepo, cfg)
	txH := handlers.NewTransactionHandler(txRepo)
	wlH := handlers.NewWishlistHandler(wlRepo, txRepo, catRepo)
	catH := handlers.NewCategoryHandler(catRepo)
	syncH := handlers.NewSyncHandler(txRepo, wlRepo, catRepo)
	statsH := handlers.NewStatisticsHandler(txRepo, wlRepo)
	verH := handlers.NewVersionHandler("test-1.0.0")
	drH := handlers.NewDetailRequestHandler(drRepo, txRepo, userRepo)
	limitsH := handlers.NewLimitsHandler(catRepo, txRepo)
	notifH := handlers.NewNotificationHandler(notifRepo)
	txH.SetLimitChecker(handlers.NewLimitChecker(catRepo, txRepo, notifRepo))

	r := gin.New()
	api := r.Group("/api")
	{
		api.GET("/version", verH.Get)
		api.POST("/auth/login", authH.Login)
		api.POST("/auth/refresh", authH.Refresh)

		auth := api.Group("/")
		auth.Use(middleware.Auth(cfg))
		{
			auth.GET("/auth/me", authH.Me)
			auth.GET("/users", authH.ListUsers)

			auth.POST("/transactions", txH.Create)
			auth.GET("/transactions", txH.List)
			auth.PUT("/transactions/:id", txH.Update)
			auth.DELETE("/transactions/:id", txH.Delete)

			auth.POST("/wishlist", wlH.Create)
			auth.GET("/wishlist", wlH.List)
			auth.PUT("/wishlist/:id", wlH.Update)
			auth.DELETE("/wishlist/:id", wlH.Delete)
			auth.POST("/wishlist/:id/unlink-period", wlH.UnlinkPeriod)
			auth.POST("/wishlist/:id/link/:tx_id", wlH.LinkExisting)

			auth.GET("/categories", catH.List)
			auth.GET("/categories/all", catH.ListAll)
			auth.GET("/categories/limits-progress", limitsH.Progress)
			auth.POST("/categories", catH.Create)
			auth.PATCH("/categories/:id", catH.Update)
			auth.DELETE("/categories/:id", catH.Delete)

			auth.GET("/notifications", notifH.List)
			auth.POST("/notifications/read-all", notifH.ReadAll)
			auth.POST("/notifications/:id/read", notifH.Read)

			auth.GET("/statistics/summary", statsH.Summary)
			auth.GET("/statistics/by-category", statsH.ByCategory)
			auth.GET("/statistics/monthly", statsH.Monthly)
			auth.GET("/statistics/overview", statsH.Overview)

			auth.GET("/sync/pull", syncH.Pull)
			auth.POST("/sync/push", syncH.Push)

			auth.GET("/statistics/forecast", statsH.Forecast)

			auth.POST("/detail-requests", drH.Create)
			auth.GET("/detail-requests", drH.List)
			auth.GET("/detail-requests/:id", drH.Get)
			auth.POST("/detail-requests/:id/transactions", drH.AddChild)
			auth.POST("/detail-requests/:id/close", drH.Close)
			auth.POST("/detail-requests/:id/cancel", drH.Cancel)
		}
	}

	return &fixture{
		db:          db,
		cfg:         cfg,
		txRepo:      txRepo,
		wlRepo:      wlRepo,
		catRepo:     catRepo,
		userRepo:    userRepo,
		drRepo:      drRepo,
		notifRepo:   notifRepo,
		router:      r,
		userID:      u.ID.Hex(),
		displayName: u.DisplayName,
	}
}

func (f *fixture) token(t *testing.T) string {
	t.Helper()
	claims := &models.Claims{
		UserID:      f.userID,
		Login:       "alice",
		DisplayName: f.displayName,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour)),
		},
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, err := tok.SignedString([]byte(f.cfg.JWTSecret))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func (f *fixture) do(t *testing.T, method, path string, body any, authed bool) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if authed {
		req.Header.Set("Authorization", "Bearer "+f.token(t))
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	return w
}

func decodeBody[T any](t *testing.T, w *httptest.ResponseRecorder) T {
	t.Helper()
	var out T
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode %s: %v\nbody=%s", w.Body.String(), err, w.Body.String())
	}
	return out
}

// ─── Auth ──────────────────────────────────────────────────────────────────

func TestAuth_LoginAndMe(t *testing.T) {
	f := newFixture(t)

	// Login OK
	w := f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "alice", Password: "hunter2"}, false)
	if w.Code != http.StatusOK {
		t.Fatalf("login: status=%d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[models.LoginResponse](t, w)
	if resp.Token == "" {
		t.Fatal("empty token")
	}
	if resp.DisplayName != "Alice" {
		t.Errorf("display_name = %q", resp.DisplayName)
	}

	// Bad password — same 401 message as missing login (anti-enumeration)
	w = f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "alice", Password: "wrong"}, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("bad pw status = %d, want 401", w.Code)
	}

	// Missing login
	w = f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "ghost", Password: "x"}, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("missing login status = %d, want 401", w.Code)
	}

	// Me requires auth — without token = 401
	w = f.do(t, http.MethodGet, "/api/auth/me", nil, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("/me unauth status = %d, want 401", w.Code)
	}
	// With token — claims echoed
	w = f.do(t, http.MethodGet, "/api/auth/me", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("/me status = %d, body=%s", w.Code, w.Body.String())
	}
	// /me returns a mixed-type map (is_admin is bool), so decode as any.
	me := decodeBody[map[string]any](t, w)
	if me["login"] != "alice" {
		t.Errorf("login = %v", me["login"])
	}
}

func TestAuth_Refresh(t *testing.T) {
	f := newFixture(t)

	// Login returns both access + refresh.
	w := f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "alice", Password: "hunter2"}, false)
	if w.Code != http.StatusOK {
		t.Fatalf("login: status=%d body=%s", w.Code, w.Body.String())
	}
	login := decodeBody[models.LoginResponse](t, w)
	if login.Token == "" || login.RefreshToken == "" {
		t.Fatalf("login must return both tokens: %+v", login)
	}
	if login.Token == login.RefreshToken {
		t.Error("access and refresh tokens must differ")
	}

	// Refresh with valid refresh token → fresh pair.
	w = f.do(t, http.MethodPost, "/api/auth/refresh",
		models.RefreshRequest{RefreshToken: login.RefreshToken}, false)
	if w.Code != http.StatusOK {
		t.Fatalf("refresh: status=%d body=%s", w.Code, w.Body.String())
	}
	refreshed := decodeBody[models.RefreshResponse](t, w)
	if refreshed.Token == "" || refreshed.RefreshToken == "" {
		t.Fatalf("refresh must return both tokens: %+v", refreshed)
	}
	if refreshed.Token == login.Token {
		t.Error("refresh should mint a new access token (got same)")
	}

	// Refresh with the *access* token must fail — wrong token_type.
	w = f.do(t, http.MethodPost, "/api/auth/refresh",
		models.RefreshRequest{RefreshToken: login.Token}, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("access-as-refresh: status=%d, want 401", w.Code)
	}

	// Refresh with garbage must fail.
	w = f.do(t, http.MethodPost, "/api/auth/refresh",
		models.RefreshRequest{RefreshToken: "not.a.jwt"}, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("garbage refresh: status=%d, want 401", w.Code)
	}

	// And the *refresh* token must NOT be accepted at protected endpoints
	// — middleware rejects token_type=refresh on `/auth/me`.
	req := httptest.NewRequest(http.MethodGet, "/api/auth/me", nil)
	req.Header.Set("Authorization", "Bearer "+login.RefreshToken)
	rec := httptest.NewRecorder()
	f.router.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("refresh-as-access: status=%d, want 401", rec.Code)
	}
}

func TestAuth_ListUsers(t *testing.T) {
	f := newFixture(t)
	w := f.do(t, http.MethodGet, "/api/users", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	users := decodeBody[[]models.UserInfo](t, w)
	if len(users) != 1 || users[0].DisplayName != "Alice" {
		t.Errorf("users=%+v", users)
	}
}

// ─── Transactions ──────────────────────────────────────────────────────────

func TestTransactions_CRUDFlow(t *testing.T) {
	f := newFixture(t)

	// Create
	w := f.do(t, http.MethodPost, "/api/transactions", models.CreateTransactionRequest{
		Type:     models.Expense,
		Amount:   1234.5,
		Date:     "2026-04-15",
		Category: "Food",
		Source:   "Coffee shop",
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: status=%d body=%s", w.Code, w.Body.String())
	}
	created := decodeBody[models.Transaction](t, w)
	if created.ID == "" {
		t.Fatal("no ID on created tx")
	}
	if created.CreatedBy == nil || created.CreatedBy.UserID != f.userID {
		t.Errorf("CreatedBy missing/mismatched: %+v", created.CreatedBy)
	}

	// Bad date
	w = f.do(t, http.MethodPost, "/api/transactions", models.CreateTransactionRequest{
		Type: models.Expense, Amount: 1, Date: "nope", Category: "X",
	}, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("bad-date status = %d, want 400", w.Code)
	}

	// List with filter
	w = f.do(t, http.MethodGet, "/api/transactions?type=expense&category=Food", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list: status=%d", w.Code)
	}
	listResp := decodeBody[map[string]any](t, w)
	if listResp["total"].(float64) != 1 {
		t.Errorf("total = %v, want 1", listResp["total"])
	}

	// Update
	w = f.do(t, http.MethodPut, "/api/transactions/"+created.ID, models.UpdateTransactionRequest{
		Amount: 999,
	}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("update: %d body=%s", w.Code, w.Body.String())
	}
	upd := decodeBody[models.Transaction](t, w)
	if upd.Amount != 999 {
		t.Errorf("amount=%v", upd.Amount)
	}

	// Update non-existent
	w = f.do(t, http.MethodPut, "/api/transactions/does-not-exist", models.UpdateTransactionRequest{Amount: 1}, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("update-missing status = %d, want 404", w.Code)
	}

	// Delete
	w = f.do(t, http.MethodDelete, "/api/transactions/"+created.ID, nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("delete: %d", w.Code)
	}
	// Delete non-existent → 404
	w = f.do(t, http.MethodDelete, "/api/transactions/never-existed", nil, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("delete-missing status = %d, want 404", w.Code)
	}
}

// ─── Wishlist ──────────────────────────────────────────────────────────────

func TestWishlist_CRUDAndUnlinkPeriod(t *testing.T) {
	f := newFixture(t)

	// Create
	w := f.do(t, http.MethodPost, "/api/wishlist", models.CreateWishlistRequest{
		Name:          "Mortgage",
		EstimatedCost: 50000,
		Category:      "Жильё/ЖКХ",
		Frequency:     models.FrequencyMonthly,
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d body=%s", w.Code, w.Body.String())
	}
	item := decodeBody[models.WishlistItem](t, w)

	// Default priority kicks in when 0 provided
	if item.Priority != 5 {
		t.Errorf("Priority default = %d, want 5", item.Priority)
	}

	// List
	w = f.do(t, http.MethodGet, "/api/wishlist", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list status=%d", w.Code)
	}
	items := decodeBody[[]models.WishlistItem](t, w)
	if len(items) != 1 {
		t.Errorf("len(items)=%d", len(items))
	}

	// Create a linked expense in this month
	now := time.Now()
	linked := &models.Transaction{
		Type:       models.Expense,
		Amount:     50000,
		Date:       time.Date(now.Year(), now.Month(), 5, 0, 0, 0, 0, time.UTC),
		Category:   "Жильё/ЖКХ",
		WishlistID: item.ID,
		CreatedBy:  &models.UserInfo{UserID: f.userID},
	}
	if err := f.txRepo.Create(context.Background(), linked); err != nil {
		t.Fatal(err)
	}

	// UnlinkPeriod — should unlink the in-month transaction
	w = f.do(t, http.MethodPost, "/api/wishlist/"+item.ID+"/unlink-period", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("unlink: %d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[map[string]int](t, w)
	if resp["unlinked"] != 1 {
		t.Errorf("unlinked=%d", resp["unlinked"])
	}

	// Update wishlist (purchased toggle + name)
	purchased := true
	newName := "Renamed"
	w = f.do(t, http.MethodPut, "/api/wishlist/"+item.ID, models.UpdateWishlistRequest{
		Name:      newName,
		Purchased: &purchased,
	}, true)
	if w.Code != http.StatusOK {
		t.Fatalf("update: %d body=%s", w.Code, w.Body.String())
	}
	updated := decodeBody[models.WishlistItem](t, w)
	if updated.Name != newName || !updated.Purchased {
		t.Errorf("update result: %+v", updated)
	}
	// Update non-existent → 404
	w = f.do(t, http.MethodPut, "/api/wishlist/nope", models.UpdateWishlistRequest{Name: "x"}, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("update-missing status=%d, want 404", w.Code)
	}

	// Delete wishlist
	w = f.do(t, http.MethodDelete, "/api/wishlist/"+item.ID, nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("delete: %d", w.Code)
	}

	// UnlinkPeriod for non-existent → 404
	w = f.do(t, http.MethodPost, "/api/wishlist/nope/unlink-period", nil, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("unlink-missing status=%d, want 404", w.Code)
	}
}

// TestWishlist_LinkExisting covers the «привязать существующий расход» flow:
// happy-path attach, category clone when the wishlist category doesn't exist
// in the expense section, once-frequency `purchased` flip, and rejection of
// already-linked / non-expense transactions.
func TestWishlist_LinkExisting(t *testing.T) {
	f := newFixture(t)
	if err := f.catRepo.EnsureDefaults(context.Background()); err != nil {
		t.Fatal(err)
	}

	// Seed a wishlist-only category that doesn't exist in expense section, so
	// the link handler must clone it.
	if _, err := f.catRepo.Create(context.Background(), "wishlist", "Хобби", "#FF00FF", "rocket", nil); err != nil {
		t.Fatal(err)
	}

	// once-item with that wishlist-only category
	w := f.do(t, http.MethodPost, "/api/wishlist", models.CreateWishlistRequest{
		Name:          "Гитара",
		EstimatedCost: 30000,
		Category:      "Хобби",
		Frequency:     models.FrequencyOnce,
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create item: %d body=%s", w.Code, w.Body.String())
	}
	item := decodeBody[models.WishlistItem](t, w)

	// Unrelated expense already in the system, categorised differently. We'll
	// attach it to the wishlist item and expect the category to flip to "Хобби"
	// (and the "Хобби" category to spawn in the expense section).
	tx := &models.Transaction{
		Type:      models.Expense,
		Amount:    28000,
		Date:      time.Now(),
		Category:  "Развлечения",
		CreatedBy: &models.UserInfo{UserID: f.userID},
	}
	if err := f.txRepo.Create(context.Background(), tx); err != nil {
		t.Fatal(err)
	}

	// Link
	w = f.do(t, http.MethodPost, "/api/wishlist/"+item.ID+"/link/"+tx.ID, nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("link: %d body=%s", w.Code, w.Body.String())
	}
	linked := decodeBody[models.Transaction](t, w)
	if linked.WishlistID != item.ID {
		t.Errorf("WishlistID=%q want=%q", linked.WishlistID, item.ID)
	}
	if linked.Category != "Хобби" {
		t.Errorf("Category=%q want=Хобби", linked.Category)
	}

	// Expense-section "Хобби" must now exist with cloned visuals.
	cat, err := f.catRepo.FindByName(context.Background(), "expense", "Хобби")
	if err != nil || cat == nil {
		t.Fatalf("expense Хобби missing: %v", err)
	}
	if cat.Color != "#FF00FF" || cat.Icon != "rocket" {
		t.Errorf("clone visuals: color=%q icon=%q", cat.Color, cat.Icon)
	}

	// Once-item should be flipped to purchased.
	items, _ := f.wlRepo.FindAll(context.Background())
	var found *models.WishlistItem
	for i := range items {
		if items[i].ID == item.ID {
			found = &items[i]
		}
	}
	if found == nil || !found.Purchased {
		t.Errorf("expected purchased=true after link, got %+v", found)
	}

	// Re-linking the same tx → 409
	w = f.do(t, http.MethodPost, "/api/wishlist/"+item.ID+"/link/"+tx.ID, nil, true)
	if w.Code != http.StatusConflict {
		t.Errorf("relink status=%d want=409", w.Code)
	}

	// Linking a non-expense tx → 400
	income := &models.Transaction{
		Type:     models.Income,
		Amount:   1000,
		Date:     time.Now(),
		Category: "Зарплата",
	}
	if err := f.txRepo.Create(context.Background(), income); err != nil {
		t.Fatal(err)
	}
	w = f.do(t, http.MethodPost, "/api/wishlist/"+item.ID+"/link/"+income.ID, nil, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("link income status=%d want=400", w.Code)
	}

	// Linking an unknown tx → 404
	w = f.do(t, http.MethodPost, "/api/wishlist/"+item.ID+"/link/nope", nil, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("link missing status=%d want=404", w.Code)
	}
}

// TestTransactions_UnlinkedFilter ensures GET /transactions?unlinked=true
// hides linked, parent, closed-DR, and non-expense rows so the picker only
// surfaces eligible candidates.
func TestTransactions_UnlinkedFilter(t *testing.T) {
	f := newFixture(t)
	now := time.Now()

	mk := func(tx *models.Transaction) *models.Transaction {
		if err := f.txRepo.Create(context.Background(), tx); err != nil {
			t.Fatal(err)
		}
		return tx
	}

	eligible := mk(&models.Transaction{
		Type: models.Expense, Amount: 100, Date: now, Category: "Продукты",
	})
	linked := mk(&models.Transaction{
		Type: models.Expense, Amount: 200, Date: now, Category: "Продукты",
		WishlistID: "some-wl",
	})
	income := mk(&models.Transaction{
		Type: models.Income, Amount: 500, Date: now, Category: "Зарплата",
	})
	closedParent := mk(&models.Transaction{
		Type: models.Expense, Amount: 300, Date: now, Category: "Прочее",
		DetailRequestStatus: "closed", ExcludedFromStats: true,
	})

	w := f.do(t, http.MethodGet, "/api/transactions?unlinked=true&limit=50", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list: %d body=%s", w.Code, w.Body.String())
	}
	var resp struct {
		Data []models.Transaction `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}

	seen := map[string]bool{}
	for _, tx := range resp.Data {
		seen[tx.ID] = true
	}
	if !seen[eligible.ID] {
		t.Error("eligible expense missing")
	}
	if seen[linked.ID] {
		t.Error("linked expense leaked into unlinked list")
	}
	if seen[income.ID] {
		t.Error("income leaked into unlinked list")
	}
	if seen[closedParent.ID] {
		t.Error("closed-DR parent leaked into unlinked list")
	}
}

// ─── Categories ─────────────────────────────────────────────────────────────

func TestCategories_ListAllCreateDelete(t *testing.T) {
	f := newFixture(t)
	if err := f.catRepo.EnsureDefaults(context.Background()); err != nil {
		t.Fatal(err)
	}

	// List one section
	w := f.do(t, http.MethodGet, "/api/categories?section=expense", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list status=%d body=%s", w.Code, w.Body.String())
	}
	cats := decodeBody[[]models.Category](t, w)
	if len(cats) < 5 {
		t.Errorf("expected ≥5 expense cats, got %d", len(cats))
	}

	// List without section → 400
	w = f.do(t, http.MethodGet, "/api/categories", nil, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("no-section status=%d, want 400", w.Code)
	}

	// List-all
	w = f.do(t, http.MethodGet, "/api/categories/all", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("listall status=%d", w.Code)
	}
	all := decodeBody[handlers.CategoriesAllResponse](t, w)
	if len(all.Expense) == 0 || len(all.Income) == 0 || len(all.Wishlist) == 0 {
		t.Errorf("listall missing sections: %+v", all)
	}

	// Create
	w = f.do(t, http.MethodPost, "/api/categories",
		models.CreateCategoryRequest{Section: "expense", Name: "Custom"}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d body=%s", w.Code, w.Body.String())
	}
	created := decodeBody[models.Category](t, w)

	// Duplicate Create → 409
	w = f.do(t, http.MethodPost, "/api/categories",
		models.CreateCategoryRequest{Section: "expense", Name: "Custom"}, true)
	if w.Code != http.StatusConflict {
		t.Errorf("dup status=%d, want 409", w.Code)
	}

	// Bad section
	w = f.do(t, http.MethodPost, "/api/categories",
		models.CreateCategoryRequest{Section: "bogus", Name: "X"}, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("bad-section status=%d, want 400", w.Code)
	}

	// Delete custom
	w = f.do(t, http.MethodDelete, "/api/categories/"+created.ID, nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("delete: %d", w.Code)
	}

	// Delete missing
	w = f.do(t, http.MethodDelete, "/api/categories/nope", nil, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("delete-missing status=%d, want 404", w.Code)
	}
}

// ─── Statistics ────────────────────────────────────────────────────────────

func TestStatistics_OverviewAndComponents(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	now := time.Date(time.Now().Year(), 6, 15, 0, 0, 0, 0, time.UTC)
	mustCreateTx(t, ctx, f.txRepo, models.Income, "Salary", 5000, now)
	mustCreateTx(t, ctx, f.txRepo, models.Expense, "Food", 200, now)
	mustCreateTx(t, ctx, f.txRepo, models.Expense, "Food", 300, now.AddDate(0, 0, 1))

	// Summary endpoint
	from := now.AddDate(0, 0, -1).Format("2006-01-02")
	to := now.AddDate(0, 0, 5).Format("2006-01-02")
	w := f.do(t, http.MethodGet, fmt.Sprintf("/api/statistics/summary?from=%s&to=%s", from, to), nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("summary: %d body=%s", w.Code, w.Body.String())
	}
	s := decodeBody[models.SummaryData](t, w)
	if s.TotalIncome != 5000 || s.TotalExpense != 500 {
		t.Errorf("summary=%+v", s)
	}

	// By category
	w = f.do(t, http.MethodGet, fmt.Sprintf("/api/statistics/by-category?from=%s&to=%s&type=expense", from, to), nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("by-category: %d", w.Code)
	}
	cats := decodeBody[[]models.CategoryData](t, w)
	if len(cats) != 1 || cats[0].Category != "Food" {
		t.Errorf("by-category=%+v", cats)
	}

	// Monthly with year
	w = f.do(t, http.MethodGet, fmt.Sprintf("/api/statistics/monthly?year=%d", now.Year()), nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("monthly: %d", w.Code)
	}

	// Overview combines them
	w = f.do(t, http.MethodGet, fmt.Sprintf("/api/statistics/overview?from=%s&to=%s", from, to), nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("overview: %d body=%s", w.Code, w.Body.String())
	}
	ov := decodeBody[handlers.StatisticsOverviewResponse](t, w)
	if ov.Summary == nil || ov.Summary.TotalIncome != 5000 {
		t.Errorf("overview summary=%+v", ov.Summary)
	}
	if len(ov.ExpenseByCategory) != 1 {
		t.Errorf("overview expByCat=%+v", ov.ExpenseByCategory)
	}
}

// ─── Sync ──────────────────────────────────────────────────────────────────

func TestSync_PushAndPull(t *testing.T) {
	f := newFixture(t)

	// Push: create a category, transaction, wishlist item
	catID := "cat-uuid-1"
	txID := "tx-uuid-1"
	wlID := "wl-uuid-1"

	payloadCat, _ := json.Marshal(models.Category{
		ID: catID, Section: "expense", Name: "SyncTest",
	})
	payloadTx, _ := json.Marshal(map[string]any{
		"id":       txID,
		"type":     "expense",
		"amount":   42.0,
		"date":     "2026-05-01",
		"category": "SyncTest",
	})
	payloadWL, _ := json.Marshal(map[string]any{
		"id":             wlID,
		"name":           "ViaSync",
		"estimated_cost": 100,
		"category":       "Прочее",
		"frequency":      "once",
		"priority":       5,
	})

	push := models.SyncPushRequest{
		Operations: []models.SyncOperation{
			{OpID: "op1", Type: models.SyncOpCreate, Entity: models.SyncEntityCategory, ID: catID, Payload: payloadCat},
			{OpID: "op2", Type: models.SyncOpCreate, Entity: models.SyncEntityTransaction, ID: txID, Payload: payloadTx},
			{OpID: "op3", Type: models.SyncOpCreate, Entity: models.SyncEntityWishlist, ID: wlID, Payload: payloadWL},
		},
	}

	w := f.do(t, http.MethodPost, "/api/sync/push", push, true)
	if w.Code != http.StatusOK {
		t.Fatalf("push: %d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[models.SyncPushResponse](t, w)
	if len(resp.Results) != 3 {
		t.Fatalf("results=%d, want 3", len(resp.Results))
	}
	for _, r := range resp.Results {
		if r.Status != models.SyncStatusOK {
			t.Errorf("op %s status=%s err=%s", r.OpID, r.Status, r.Error)
		}
	}

	// Push again with same IDs as create → conflicts (duplicate _id)
	w = f.do(t, http.MethodPost, "/api/sync/push", push, true)
	if w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
	conflictResp := decodeBody[models.SyncPushResponse](t, w)
	for _, r := range conflictResp.Results {
		if r.Status != models.SyncStatusConflict {
			t.Errorf("op %s expected conflict, got %s", r.OpID, r.Status)
		}
		if r.Record == nil {
			t.Errorf("op %s conflict has no record", r.OpID)
		}
	}

	// Pull
	w = f.do(t, http.MethodGet, "/api/sync/pull", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("pull: %d", w.Code)
	}
	pull := decodeBody[models.SyncPullResponse](t, w)
	if len(pull.Transactions) != 1 || len(pull.Wishlist) != 1 || len(pull.Categories) != 1 {
		t.Errorf("pull counts: tx=%d wl=%d cat=%d",
			len(pull.Transactions), len(pull.Wishlist), len(pull.Categories))
	}

	// Pull with malformed since → 400
	w = f.do(t, http.MethodGet, "/api/sync/pull?since=not-a-time", nil, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("pull bad since status=%d, want 400", w.Code)
	}

	// Push with missing ID → per-op error
	push2 := models.SyncPushRequest{Operations: []models.SyncOperation{
		{OpID: "bad", Type: models.SyncOpCreate, Entity: models.SyncEntityTransaction, ID: ""},
	}}
	w = f.do(t, http.MethodPost, "/api/sync/push", push2, true)
	if w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
	r2 := decodeBody[models.SyncPushResponse](t, w)
	if r2.Results[0].Status != models.SyncStatusError {
		t.Errorf("missing-id status=%s", r2.Results[0].Status)
	}
}

// ─── Version (public, no auth) ─────────────────────────────────────────────

func TestVersionEndpoint(t *testing.T) {
	f := newFixture(t)
	w := f.do(t, http.MethodGet, "/api/version", nil, false)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d", w.Code)
	}
	v := decodeBody[map[string]string](t, w)
	if v["api"] != "test-1.0.0" {
		t.Errorf("api=%q", v["api"])
	}
}

// ─── Forecast smoke ────────────────────────────────────────────────────────

func TestStatistics_Forecast(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	// Seed a few expense transactions and one recurring wishlist item.
	now := time.Now()
	mustCreateTx(t, ctx, f.txRepo, models.Expense, "Food", 100, now.AddDate(0, -1, 0))
	mustCreateTx(t, ctx, f.txRepo, models.Expense, "Food", 200, now.AddDate(0, -2, 0))
	wl := &models.WishlistItem{
		Name:          "Internet",
		EstimatedCost: 500,
		Category:      "Связь",
		Frequency:     models.FrequencyMonthly,
		Priority:      5,
	}
	if err := f.wlRepo.Create(ctx, wl); err != nil {
		t.Fatal(err)
	}

	w := f.do(t, http.MethodGet, "/api/statistics/forecast", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("forecast: %d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[models.ForecastResponse](t, w)
	if resp.TotalMonthly <= 0 {
		t.Errorf("expected TotalMonthly > 0, got %v", resp.TotalMonthly)
	}
	// At least the recurring item should appear
	if len(resp.RegularItems) == 0 {
		t.Errorf("RegularItems empty, expected ≥1")
	}
}

// ─── Detail-requests ──────────────────────────────────────────────────────

func TestDetailRequest_FullFlow(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	parent := mustCreateTx(t, ctx, f.txRepo, models.Expense, "Big", 1000, time.Now())

	// Create — assignee=self (so we can also AddChild + Close)
	w := f.do(t, http.MethodPost, "/api/detail-requests",
		models.CreateDetailRequestPayload{TransactionID: parent.ID, AssigneeID: f.userID}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d body=%s", w.Code, w.Body.String())
	}
	dr := decodeBody[models.DetailRequest](t, w)
	if dr.Status != models.DetailRequestOpen {
		t.Errorf("status=%q", dr.Status)
	}

	// Duplicate create → 409
	w = f.do(t, http.MethodPost, "/api/detail-requests",
		models.CreateDetailRequestPayload{TransactionID: parent.ID, AssigneeID: f.userID}, true)
	if w.Code != http.StatusConflict {
		t.Errorf("dup status=%d, want 409", w.Code)
	}

	// Create on missing tx
	w = f.do(t, http.MethodPost, "/api/detail-requests",
		models.CreateDetailRequestPayload{TransactionID: "no-such-tx", AssigneeID: f.userID}, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("missing-tx status=%d", w.Code)
	}

	// List by assignee=me
	w = f.do(t, http.MethodGet, "/api/detail-requests?assignee_id=me", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list: %d", w.Code)
	}
	list := decodeBody[[]models.DetailRequest](t, w)
	if len(list) != 1 {
		t.Errorf("list len=%d", len(list))
	}

	// Get with children
	w = f.do(t, http.MethodGet, "/api/detail-requests/"+dr.ID, nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("get: %d", w.Code)
	}
	view := decodeBody[models.DetailRequestView](t, w)
	if view.Parent == nil || view.Parent.ID != parent.ID {
		t.Errorf("Parent: %+v", view.Parent)
	}

	// Close with no children → 400
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/close", nil, true)
	if w.Code != http.StatusBadRequest {
		t.Errorf("close-empty status=%d, want 400", w.Code)
	}

	// AddChild
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/transactions",
		models.CreateTransactionRequest{
			Type:     models.Expense,
			Amount:   600,
			Date:     "2026-04-15",
			Category: "Food",
		}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("AddChild: %d body=%s", w.Code, w.Body.String())
	}
	child := decodeBody[models.Transaction](t, w)
	if child.ParentID != parent.ID || !child.ExcludedFromStats {
		t.Errorf("child not flagged correctly: %+v", child)
	}

	// Close
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/close", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("close: %d body=%s", w.Code, w.Body.String())
	}
	closed := decodeBody[models.DetailRequest](t, w)
	if closed.Status != models.DetailRequestClosed {
		t.Errorf("not closed: %q", closed.Status)
	}

	// AddChild after close → 409
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/transactions",
		models.CreateTransactionRequest{Type: models.Expense, Amount: 1, Date: "2026-04-16", Category: "X"}, true)
	if w.Code != http.StatusConflict {
		t.Errorf("AddChild-after-close status=%d, want 409", w.Code)
	}
}

func TestDetailRequest_Cancel(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	parent := mustCreateTx(t, ctx, f.txRepo, models.Expense, "Big", 100, time.Now())
	w := f.do(t, http.MethodPost, "/api/detail-requests",
		models.CreateDetailRequestPayload{TransactionID: parent.ID, AssigneeID: f.userID}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d body=%s", w.Code, w.Body.String())
	}
	dr := decodeBody[models.DetailRequest](t, w)

	// Cancel by creator → ok
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/cancel", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("cancel: %d body=%s", w.Code, w.Body.String())
	}

	// Cancel again → 404 (deleted)
	w = f.do(t, http.MethodPost, "/api/detail-requests/"+dr.ID+"/cancel", nil, true)
	if w.Code != http.StatusNotFound {
		t.Errorf("re-cancel status=%d, want 404", w.Code)
	}

	// Parent tx detail flags reset
	tx, err := f.txRepo.FindByID(ctx, parent.ID)
	if err != nil {
		t.Fatal(err)
	}
	if tx.DetailRequestID != "" || tx.ExcludedFromStats {
		t.Errorf("parent flags not reset: %+v", tx)
	}
}

// ─── helpers ───────────────────────────────────────────────────────────────

func mustCreateTx(t *testing.T, ctx context.Context, repo *repository.TransactionRepository, typ models.TransactionType, category string, amount float64, when time.Time) *models.Transaction {
	t.Helper()
	tx := &models.Transaction{
		Type:      typ,
		Amount:    amount,
		Date:      when,
		Category:  category,
		CreatedBy: &models.UserInfo{UserID: "test"},
	}
	if err := repo.Create(ctx, tx); err != nil {
		t.Fatalf("create %s/%s: %v", typ, category, err)
	}
	return tx
}
