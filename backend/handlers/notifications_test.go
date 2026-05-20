package handlers_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"budget-go/models"
)

// seedNotification inserts a fresh limit-exceeded notification through the
// repo and returns its id — covers the "user-facing notification flow" without
// having to actually trigger the limit-checker.
func seedNotification(t *testing.T, f *fixture, category string) string {
	t.Helper()
	n, ok, err := f.notifRepo.EnsureExceeded(context.Background(), &models.Notification{
		Type:         "category_limit_exceeded",
		Period:       "2026-05",
		CategoryID:   category,
		CategoryName: category,
		Limit:        1000,
		Spent:        1500,
		CreatedAt:    time.Now(),
	})
	if err != nil {
		t.Fatalf("EnsureExceeded: %v", err)
	}
	if !ok || n == nil {
		t.Fatal("EnsureExceeded reported duplicate on first insert")
	}
	return n.ID
}

func TestNotifications_ListReflectsPerUserReadFlag(t *testing.T) {
	f := newFixture(t)
	id := seedNotification(t, f, "Транспорт")

	type notifRow struct {
		ID   string `json:"id"`
		Read bool   `json:"read"`
	}
	type listResp struct {
		Data        []notifRow `json:"data"`
		UnreadCount int64      `json:"unread_count"`
	}

	// Before marking — Read=false, unread_count=1.
	w := f.do(t, http.MethodGet, "/api/notifications", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("list: status=%d body=%s", w.Code, w.Body.String())
	}
	body := decodeBody[listResp](t, w)
	if body.UnreadCount != 1 || len(body.Data) != 1 || body.Data[0].Read {
		t.Fatalf("unexpected list: %+v", body)
	}
	if body.Data[0].ID != id {
		t.Fatalf("id mismatch: got %s want %s", body.Data[0].ID, id)
	}
}

func TestNotifications_ReadByIDMarksAsRead(t *testing.T) {
	f := newFixture(t)
	id := seedNotification(t, f, "Кафе")

	// POST /notifications/:id/read flips the per-user flag.
	w := f.do(t, http.MethodPost, "/api/notifications/"+id+"/read", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("read: status=%d body=%s", w.Code, w.Body.String())
	}

	type notifRow struct {
		ID   string `json:"id"`
		Read bool   `json:"read"`
	}
	type listResp struct {
		Data        []notifRow `json:"data"`
		UnreadCount int64      `json:"unread_count"`
	}
	w = f.do(t, http.MethodGet, "/api/notifications", nil, true)
	body := decodeBody[listResp](t, w)
	if body.UnreadCount != 0 {
		t.Errorf("UnreadCount=%d, want 0 after Read", body.UnreadCount)
	}
	if !body.Data[0].Read {
		t.Error("Read flag still false after POST /notifications/:id/read")
	}
}

func TestNotifications_ReadIsIdempotent(t *testing.T) {
	f := newFixture(t)
	id := seedNotification(t, f, "Х")

	// Same user reading the same id twice must not error.
	for i := 0; i < 3; i++ {
		w := f.do(t, http.MethodPost, "/api/notifications/"+id+"/read", nil, true)
		if w.Code != http.StatusOK {
			t.Fatalf("read pass %d: status=%d body=%s", i, w.Code, w.Body.String())
		}
	}
}

func TestNotifications_ReadAllMarksEverythingForCallingUser(t *testing.T) {
	f := newFixture(t)
	seedNotification(t, f, "A")
	seedNotification(t, f, "B")
	seedNotification(t, f, "C")

	w := f.do(t, http.MethodPost, "/api/notifications/read-all", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("read-all: status=%d body=%s", w.Code, w.Body.String())
	}

	type notifRow struct {
		ID   string `json:"id"`
		Read bool   `json:"read"`
	}
	type listResp struct {
		Data        []notifRow `json:"data"`
		UnreadCount int64      `json:"unread_count"`
	}
	w = f.do(t, http.MethodGet, "/api/notifications", nil, true)
	body := decodeBody[listResp](t, w)
	if body.UnreadCount != 0 {
		t.Errorf("UnreadCount=%d after read-all, want 0", body.UnreadCount)
	}
	for _, n := range body.Data {
		if !n.Read {
			t.Errorf("notif %s still unread", n.ID)
		}
	}
}

func TestNotifications_ListHonorsLimitQueryParam(t *testing.T) {
	f := newFixture(t)
	for _, cat := range []string{"x1", "x2", "x3", "x4"} {
		seedNotification(t, f, cat)
	}

	type listResp struct {
		Data        []struct{} `json:"data"`
		UnreadCount int64      `json:"unread_count"`
	}
	w := f.do(t, http.MethodGet, "/api/notifications?limit=2", nil, true)
	if w.Code != http.StatusOK {
		t.Fatalf("limited list: status=%d body=%s", w.Code, w.Body.String())
	}
	body := decodeBody[listResp](t, w)
	if len(body.Data) != 2 {
		t.Errorf("len(data) = %d, want 2", len(body.Data))
	}
	// UnreadCount is global, not limited.
	if body.UnreadCount != 4 {
		t.Errorf("UnreadCount = %d, want 4", body.UnreadCount)
	}
}

func TestNotifications_RequireAuth(t *testing.T) {
	f := newFixture(t)
	for _, route := range []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/api/notifications"},
		{http.MethodPost, "/api/notifications/read-all"},
		{http.MethodPost, "/api/notifications/some-id/read"},
	} {
		w := f.do(t, route.method, route.path, nil, false)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s %s: status=%d, want 401", route.method, route.path, w.Code)
		}
	}
}
