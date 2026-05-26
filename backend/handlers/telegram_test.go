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

	"budget-go/models"

	"go.mongodb.org/mongo-driver/bson"
)

// doService executes an HTTP request with the service token header set,
// optionally with X-Act-As-User. Mirrors fixture.do but for the bot path —
// keeps service-auth tests free of repeated header boilerplate.
func (f *fixture) doService(t *testing.T, method, path string, body any, actAsUserID string) *httptest.ResponseRecorder {
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
	req.Header.Set("X-Service-Token", f.cfg.ServiceToken)
	if actAsUserID != "" {
		req.Header.Set("X-Act-As-User", actAsUserID)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	return w
}

// ─── /telegram/link/init + /link (GET) ─────────────────────────────────────

func TestTelegram_LinkInit_ReturnsFreshCode(t *testing.T) {
	f := newFixture(t)

	w := f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("init: status=%d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[models.TelegramLinkInitResponse](t, w)
	if len(resp.Code) != 6 {
		t.Errorf("code length = %d, want 6 (body=%s)", len(resp.Code), w.Body.String())
	}
	// TTL > now+1min — covers clock skew without being brittle.
	if !resp.ExpiresAt.After(time.Now().Add(time.Minute)) {
		t.Errorf("ExpiresAt %v not in the future enough", resp.ExpiresAt)
	}

	// Re-init must replace the code (so a user who lost the chat can retry).
	w2 := f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true)
	resp2 := decodeBody[models.TelegramLinkInitResponse](t, w2)
	if resp2.Code == resp.Code {
		t.Errorf("re-init returned same code %q — must regenerate", resp.Code)
	}
}

func TestTelegram_LinkInit_RequiresAuth(t *testing.T) {
	f := newFixture(t)
	w := f.do(t, http.MethodPost, "/api/telegram/link/init", nil, false)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestTelegram_LinkStatus_NotLinkedInitially(t *testing.T) {
	f := newFixture(t)
	w := f.do(t, http.MethodGet, "/api/telegram/link", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d body=%s", w.Code, w.Body.String())
	}
	st := decodeBody[models.TelegramLinkStatus](t, w)
	if st.Linked {
		t.Error("expected Linked=false on fresh user")
	}
}

// After init but before confirm, the user still appears "not linked" — UI
// should keep showing the code-prompt screen, not a fake-success state.
func TestTelegram_LinkStatus_PendingCodeStillUnlinked(t *testing.T) {
	f := newFixture(t)
	f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true)

	w := f.do(t, http.MethodGet, "/api/telegram/link", nil, true)
	st := decodeBody[models.TelegramLinkStatus](t, w)
	if st.Linked {
		t.Error("Linked=true for a pending-only document — should be false")
	}
}

// ─── /telegram/link/confirm (service) ─────────────────────────────────────

func TestTelegram_LinkConfirm_HappyPath(t *testing.T) {
	f := newFixture(t)

	initResp := decodeBody[models.TelegramLinkInitResponse](t,
		f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true))

	body := models.TelegramLinkConfirmRequest{
		Code:             initResp.Code,
		TelegramUserID:   12345,
		TelegramUsername: "alice_tg",
	}
	w := f.doService(t, http.MethodPost, "/api/telegram/link/confirm", body, "")
	if w.Code != http.StatusOK {
		t.Fatalf("confirm: status=%d body=%s", w.Code, w.Body.String())
	}
	link := decodeBody[models.TelegramLink](t, w)
	if link.TelegramUserID != 12345 || link.TelegramUsername != "alice_tg" {
		t.Errorf("link mismatch: %+v", link)
	}
	if link.LinkedAt == nil {
		t.Error("LinkedAt must be set after confirm")
	}

	// Status now reflects the binding.
	st := decodeBody[models.TelegramLinkStatus](t,
		f.do(t, http.MethodGet, "/api/telegram/link", nil, true))
	if !st.Linked || st.TelegramUsername != "alice_tg" {
		t.Errorf("status after confirm: %+v", st)
	}
}

func TestTelegram_LinkConfirm_InvalidCode(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodPost, "/api/telegram/link/confirm",
		models.TelegramLinkConfirmRequest{Code: "BOGUS1", TelegramUserID: 1}, "")
	if w.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", w.Code)
	}
}

func TestTelegram_LinkConfirm_ExpiredCode(t *testing.T) {
	f := newFixture(t)
	// Generate via repo so we can force expiry directly — no need to
	// fake time via wrappers.
	expires, err := f.tgRepo.UpsertCode(context.Background(), f.userID, "EXPCOD")
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}
	_ = expires
	// Push expiry to the past so confirm refuses it.
	past := time.Now().Add(-time.Minute)
	if _, err := f.db.Collection("telegram_links").UpdateOne(context.Background(),
		bson.M{"user_id": f.userID},
		bson.M{"$set": bson.M{"code_expires_at": past}}); err != nil {
		t.Fatalf("expire: %v", err)
	}

	w := f.doService(t, http.MethodPost, "/api/telegram/link/confirm",
		models.TelegramLinkConfirmRequest{Code: "EXPCOD", TelegramUserID: 99}, "")
	if w.Code != http.StatusBadRequest {
		t.Errorf("expired code status = %d, want 400", w.Code)
	}
}

func TestTelegram_LinkConfirm_RequiresServiceToken(t *testing.T) {
	f := newFixture(t)
	body := models.TelegramLinkConfirmRequest{Code: "X", TelegramUserID: 1}
	buf, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/api/telegram/link/confirm", bytes.NewReader(buf))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("no token: status=%d want 401", w.Code)
	}

	// Wrong token = 401, not 200 — constant-time compare must reject.
	req2 := httptest.NewRequest(http.MethodPost, "/api/telegram/link/confirm", bytes.NewReader(buf))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-Service-Token", "wrong-secret-of-arbitrary-length-aaa")
	w2 := httptest.NewRecorder()
	f.router.ServeHTTP(w2, req2)
	if w2.Code != http.StatusUnauthorized {
		t.Errorf("wrong token: status=%d want 401", w2.Code)
	}
}

// ─── /telegram/link (DELETE) ──────────────────────────────────────────────

func TestTelegram_LinkDelete(t *testing.T) {
	f := newFixture(t)
	initResp := decodeBody[models.TelegramLinkInitResponse](t,
		f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true))
	f.doService(t, http.MethodPost, "/api/telegram/link/confirm",
		models.TelegramLinkConfirmRequest{Code: initResp.Code, TelegramUserID: 555}, "")

	w := f.do(t, http.MethodDelete, "/api/telegram/link", nil, true)
	if w.Code != http.StatusNoContent {
		t.Errorf("delete: status=%d body=%s", w.Code, w.Body.String())
	}

	// Subsequent delete = 404
	w2 := f.do(t, http.MethodDelete, "/api/telegram/link", nil, true)
	if w2.Code != http.StatusNotFound {
		t.Errorf("second delete: status=%d want 404", w2.Code)
	}
}

// ─── /telegram/me (service) ──────────────────────────────────────────────

func TestTelegram_Me_FindsLinkedUser(t *testing.T) {
	f := newFixture(t)
	initResp := decodeBody[models.TelegramLinkInitResponse](t,
		f.do(t, http.MethodPost, "/api/telegram/link/init", nil, true))
	f.doService(t, http.MethodPost, "/api/telegram/link/confirm",
		models.TelegramLinkConfirmRequest{Code: initResp.Code, TelegramUserID: 777, TelegramUsername: "alice_tg"}, "")

	w := f.doService(t, http.MethodGet, "/api/telegram/me?telegram_user_id=777", nil, "")
	if w.Code != http.StatusOK {
		t.Fatalf("me: status=%d body=%s", w.Code, w.Body.String())
	}
	info := decodeBody[models.UserInfo](t, w)
	if info.UserID != f.userID || info.DisplayName != f.displayName {
		t.Errorf("user info mismatch: %+v", info)
	}
}

func TestTelegram_Me_UnknownTelegramID(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodGet, "/api/telegram/me?telegram_user_id=999", nil, "")
	if w.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", w.Code)
	}
}

func TestTelegram_Me_RejectsMissingParam(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodGet, "/api/telegram/me", nil, "")
	if w.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", w.Code)
	}
}

// ─── /telegram/context (service) ─────────────────────────────────────────

func TestTelegram_Context_ReturnsCategoriesGlossaryAndCounterparties(t *testing.T) {
	f := newFixture(t)
	// Default categories aren't auto-seeded by NewCategoryRepository — main.go
	// kicks EnsureDefaults at boot. Tests need to opt in.
	if err := f.catRepo.EnsureDefaults(context.Background()); err != nil {
		t.Fatalf("seed cats: %v", err)
	}

	// Seed: a couple of transactions so AggregateUserCounterparties has data.
	// `Магнит` should dominate Продукты for alice.
	for i := 0; i < 3; i++ {
		w := f.do(t, http.MethodPost, "/api/transactions",
			models.CreateTransactionRequest{
				Type: models.Expense, Amount: 100, Date: "2026-05-20",
				Category: "Продукты", Purpose: "Магнит",
			}, true)
		if w.Code != http.StatusCreated {
			t.Fatalf("seed tx %d: %s", w.Code, w.Body.String())
		}
	}
	// One income from "ООО Драйв" to verify type discrimination.
	w := f.do(t, http.MethodPost, "/api/transactions",
		models.CreateTransactionRequest{
			Type: models.Income, Amount: 50000, Date: "2026-05-21",
			Category: "Подарок", Source: "ООО Драйв",
		}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("seed income: %s", w.Body.String())
	}

	// Seed a glossary entry (admin route; promote alice first).
	promoteAdmin(t, f)
	w = f.doAdmin(t, http.MethodPost, "/api/glossary",
		models.CreateGlossaryRequest{Term: "магаз", Meaning: "магазин"})
	if w.Code != http.StatusCreated {
		t.Fatalf("seed glossary: %s", w.Body.String())
	}

	// Fetch context.
	w = f.doService(t, http.MethodGet, "/api/telegram/context?user_id="+f.userID, nil, "")
	if w.Code != http.StatusOK {
		t.Fatalf("context: %d body=%s", w.Code, w.Body.String())
	}
	resp := decodeBody[models.TelegramContextResponse](t, w)

	// Categories (default seed includes Продукты/Подарок).
	hasExpenseCat := false
	for _, c := range resp.Expense {
		if c.Name == "Продукты" {
			hasExpenseCat = true
		}
	}
	if !hasExpenseCat {
		t.Errorf("expense missing 'Продукты': %+v", resp.Expense)
	}

	// Glossary present.
	if len(resp.Glossary) != 1 || resp.Glossary[0].Term != "магаз" {
		t.Errorf("glossary: %+v", resp.Glossary)
	}

	// Counterparties — exactly two pairs (Магнит→Продукты×3, ООО Драйв→Подарок×1).
	cps := resp.Counterparties
	if len(cps) != 2 {
		t.Fatalf("expected 2 counterparties, got %d: %+v", len(cps), cps)
	}
	// Sorted by count desc → Магнит first.
	if cps[0].Counterparty != "Магнит" || cps[0].Category != "Продукты" || cps[0].Type != "expense" || cps[0].Count != 3 {
		t.Errorf("first cp: %+v", cps[0])
	}
	if cps[1].Counterparty != "ООО Драйв" || cps[1].Type != "income" {
		t.Errorf("second cp: %+v", cps[1])
	}
}

func TestTelegram_Context_RequiresServiceToken(t *testing.T) {
	f := newFixture(t)
	req := httptest.NewRequest(http.MethodGet, "/api/telegram/context?user_id="+f.userID, nil)
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status=%d, want 401", w.Code)
	}
}

func TestTelegram_Context_UnknownUser(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodGet, "/api/telegram/context?user_id=000000000000000000000000", nil, "")
	if w.Code != http.StatusNotFound {
		t.Errorf("status=%d, want 404", w.Code)
	}
}

// ─── Service auth on regular endpoints (act-as) ───────────────────────────

// Bot creates a transaction via X-Service-Token + X-Act-As-User: alice. The
// resulting record's CreatedBy must mirror alice, not a service identity.
func TestServiceAuth_CreatesTransactionAsUser(t *testing.T) {
	f := newFixture(t)

	w := f.doService(t, http.MethodPost, "/api/transactions",
		models.CreateTransactionRequest{
			Type:     models.Expense,
			Amount:   1500,
			Date:     "2026-05-26",
			Category: "Продукты",
			Purpose:  "Магнит",
		}, f.userID)
	if w.Code != http.StatusCreated {
		t.Fatalf("create: status=%d body=%s", w.Code, w.Body.String())
	}
	tx := decodeBody[models.Transaction](t, w)
	if tx.CreatedBy == nil || tx.CreatedBy.UserID != f.userID {
		t.Errorf("CreatedBy = %+v, want user_id=%s", tx.CreatedBy, f.userID)
	}
	if tx.CreatedBy != nil && tx.CreatedBy.DisplayName != f.displayName {
		t.Errorf("CreatedBy.DisplayName = %q, want %q", tx.CreatedBy.DisplayName, f.displayName)
	}
}

func TestServiceAuth_RequiresActAsUser(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodPost, "/api/transactions",
		models.CreateTransactionRequest{Type: models.Expense, Amount: 1, Date: "2026-05-26", Category: "x"}, "")
	if w.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400 (missing X-Act-As-User)", w.Code)
	}
}

func TestServiceAuth_UnknownActAsUser(t *testing.T) {
	f := newFixture(t)
	w := f.doService(t, http.MethodPost, "/api/transactions",
		models.CreateTransactionRequest{Type: models.Expense, Amount: 1, Date: "2026-05-26", Category: "x"},
		"000000000000000000000000")
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestServiceAuth_BlockedUser(t *testing.T) {
	f := newFixture(t)
	// Block alice
	blocked := true
	if _, err := f.userRepo.ApplyUpdate(context.Background(), f.userID, models.UpdateUserRequest{Blocked: &blocked}); err != nil {
		t.Fatalf("block: %v", err)
	}

	w := f.doService(t, http.MethodPost, "/api/transactions",
		models.CreateTransactionRequest{Type: models.Expense, Amount: 1, Date: "2026-05-26", Category: "x"},
		f.userID)
	if w.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", w.Code)
	}
}

// Diagnostic helper — surfaces unexpected non-2xx fast.
func dumpBody(w *httptest.ResponseRecorder) string {
	return fmt.Sprintf("code=%d body=%s", w.Code, w.Body.String())
}

var _ = dumpBody // referenced for debug builds; suppress unused warning
