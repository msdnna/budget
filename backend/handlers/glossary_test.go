package handlers_test

import (
	"context"
	"net/http"
	"testing"

	"budget-go/models"
)

// promoteAdmin flips the fixture's seeded user to is_admin=true via the repo
// so claims minted by adminToken match DB state. Done here rather than in
// fixture so default tests stay non-admin (admin-only routes can be tested
// for the rejection path).
func promoteAdmin(t *testing.T, f *fixture) {
	t.Helper()
	if err := f.userRepo.SetAdmin(context.Background(), f.userID, true); err != nil {
		t.Fatalf("promote: %v", err)
	}
}

func TestGlossary_CRUD(t *testing.T) {
	f := newFixture(t)
	promoteAdmin(t, f)

	// Empty list initially
	w := f.doAdmin(t, http.MethodGet, "/api/glossary", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("list empty: %d body=%s", w.Code, w.Body.String())
	}
	items := decodeBody[[]models.GlossaryEntry](t, w)
	if len(items) != 0 {
		t.Errorf("expected empty, got %d", len(items))
	}

	// Create
	w = f.doAdmin(t, http.MethodPost, "/api/glossary",
		models.CreateGlossaryRequest{Term: "Магнит", Meaning: "Продукты (сеть магазинов)"})
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d body=%s", w.Code, w.Body.String())
	}
	created := decodeBody[models.GlossaryEntry](t, w)
	if created.ID == "" || created.Term != "Магнит" {
		t.Errorf("created: %+v", created)
	}

	// Duplicate (case-insensitive)
	w = f.doAdmin(t, http.MethodPost, "/api/glossary",
		models.CreateGlossaryRequest{Term: "магнит", Meaning: "что-то ещё"})
	if w.Code != http.StatusConflict {
		t.Errorf("dup status=%d, want 409", w.Code)
	}

	// Update meaning
	meaning := "сеть продуктовых магазинов"
	w = f.doAdmin(t, http.MethodPatch, "/api/glossary/"+created.ID,
		models.UpdateGlossaryRequest{Meaning: &meaning})
	if w.Code != http.StatusOK {
		t.Fatalf("update: %d body=%s", w.Code, w.Body.String())
	}
	updated := decodeBody[models.GlossaryEntry](t, w)
	if updated.Meaning != meaning {
		t.Errorf("meaning not updated: %+v", updated)
	}

	// List shows one entry
	w = f.doAdmin(t, http.MethodGet, "/api/glossary", nil)
	items = decodeBody[[]models.GlossaryEntry](t, w)
	if len(items) != 1 || items[0].Meaning != meaning {
		t.Errorf("after update: %+v", items)
	}

	// Delete
	w = f.doAdmin(t, http.MethodDelete, "/api/glossary/"+created.ID, nil)
	if w.Code != http.StatusNoContent {
		t.Errorf("delete: %d body=%s", w.Code, w.Body.String())
	}

	// Second delete = 404
	w = f.doAdmin(t, http.MethodDelete, "/api/glossary/"+created.ID, nil)
	if w.Code != http.StatusNotFound {
		t.Errorf("re-delete: %d, want 404", w.Code)
	}
}

func TestGlossary_MutationsAdminOnly(t *testing.T) {
	f := newFixture(t)
	// Non-admin user (alice — default newFixture doesn't promote).
	w := f.do(t, http.MethodPost, "/api/glossary",
		models.CreateGlossaryRequest{Term: "x", Meaning: "y"}, true)
	if w.Code != http.StatusForbidden {
		t.Errorf("status=%d, want 403", w.Code)
	}
	// But list is open to any authed user.
	w = f.do(t, http.MethodGet, "/api/glossary", nil, true)
	if w.Code != http.StatusOK {
		t.Errorf("list as non-admin: %d", w.Code)
	}
}
