package handlers_test

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"budget-go/handlers"
	"budget-go/middleware"
	"budget-go/models"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

// newAdminFixture builds a Gin engine with the admin-user routes mounted under
// regular Auth() middleware (not AdminRequired), then promotes alice to admin.
// `AdminRequired` itself is tested in middleware_test.go; here we exercise the
// handler logic with admin claims.
func newAdminFixture(t *testing.T) *fixture {
	t.Helper()
	f := newFixture(t)
	// Promote alice to admin in DB so claims minted via f.adminToken reflect
	// actual repo state (Update checks CountAdmins).
	if err := f.userRepo.SetAdmin(context.Background(), f.userID, true); err != nil {
		t.Fatalf("seed admin: %v", err)
	}

	userAdminH := handlers.NewUserAdminHandler(f.userRepo, f.db)
	api := f.router.Group("/api")
	auth := api.Group("/")
	auth.Use(middleware.Auth(f.cfg))
	{
		auth.GET("/admin/users", userAdminH.AdminList)
		auth.POST("/admin/users", userAdminH.Create)
		auth.PATCH("/admin/users/:id", userAdminH.Update)
		auth.DELETE("/admin/users/:id", userAdminH.Delete)
		auth.POST("/admin/users/:id/password", userAdminH.SetPassword)
		auth.POST("/admin/users/:id/avatar", userAdminH.UploadAvatar)
		auth.DELETE("/admin/users/:id/avatar", userAdminH.DeleteAvatar)
		auth.GET("/users/:id/avatar", userAdminH.ServeAvatar)
		auth.POST("/auth/password", userAdminH.ChangeOwnPassword)
	}
	return f
}

// adminToken — токен, у которого claims несут IsAdmin=true. Handler'ы внутри
// admin-группы middleware-чейн не дёргают AdminRequired, но `id == currentUserID`
// safeguards используют c.Get("user_id"), который Auth() выставляет из claims.
func (f *fixture) adminToken(t *testing.T) string {
	t.Helper()
	claims := &models.Claims{
		UserID:      f.userID,
		Login:       "alice",
		DisplayName: f.displayName,
		IsAdmin:     true,
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

func (f *fixture) doAdmin(t *testing.T, method, path string, body any) *httptest.ResponseRecorder {
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
	req.Header.Set("Authorization", "Bearer "+f.adminToken(t))
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	return w
}

func TestUserAdmin_CRUDFlow(t *testing.T) {
	f := newAdminFixture(t)

	// List: alice only.
	w := f.doAdmin(t, http.MethodGet, "/api/admin/users", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("list: %d %s", w.Code, w.Body.String())
	}
	list := decodeBody[[]models.AdminUser](t, w)
	if len(list) != 1 || list[0].Login != "alice" || !list[0].IsAdmin {
		t.Fatalf("initial list: %+v", list)
	}

	// Create bob.
	w = f.doAdmin(t, http.MethodPost, "/api/admin/users", models.CreateUserRequest{
		Login:       "bob",
		Password:    "secret",
		DisplayName: "Bob",
	})
	if w.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", w.Code, w.Body.String())
	}
	bob := decodeBody[models.AdminUser](t, w)
	if bob.Login != "bob" || bob.IsAdmin {
		t.Fatalf("created bob: %+v", bob)
	}

	// Duplicate login → 409.
	w = f.doAdmin(t, http.MethodPost, "/api/admin/users", models.CreateUserRequest{
		Login: "bob", Password: "secret", DisplayName: "Bob 2",
	})
	if w.Code != http.StatusConflict {
		t.Errorf("dup login: %d %s", w.Code, w.Body.String())
	}

	// Patch bob — rename + admin grant.
	newName := "Bob B."
	tru := true
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+bob.ID,
		models.UpdateUserRequest{DisplayName: &newName, IsAdmin: &tru})
	if w.Code != http.StatusOK {
		t.Fatalf("patch: %d %s", w.Code, w.Body.String())
	}
	bob2 := decodeBody[models.AdminUser](t, w)
	if bob2.DisplayName != "Bob B." || !bob2.IsAdmin {
		t.Errorf("patched: %+v", bob2)
	}

	// Self-demote forbidden.
	fal := false
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+f.userID,
		models.UpdateUserRequest{IsAdmin: &fal})
	if w.Code != http.StatusBadRequest {
		t.Errorf("self-demote: %d %s", w.Code, w.Body.String())
	}

	// Self-block forbidden.
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+f.userID,
		models.UpdateUserRequest{Blocked: &tru})
	if w.Code != http.StatusBadRequest {
		t.Errorf("self-block: %d %s", w.Code, w.Body.String())
	}

	// Block bob → 200; bob then can't refresh.
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+bob.ID,
		models.UpdateUserRequest{Blocked: &tru})
	if w.Code != http.StatusOK {
		t.Fatalf("block bob: %d %s", w.Code, w.Body.String())
	}
	bob3 := decodeBody[models.AdminUser](t, w)
	if bob3.BlockedAt == nil {
		t.Errorf("blocked_at not set: %+v", bob3)
	}

	// Unblock + reset password as admin.
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+bob.ID,
		models.UpdateUserRequest{Blocked: &fal})
	if w.Code != http.StatusOK {
		t.Fatalf("unblock: %d %s", w.Code, w.Body.String())
	}
	w = f.doAdmin(t, http.MethodPost, "/api/admin/users/"+bob.ID+"/password",
		models.SetPasswordRequest{Password: "newpass"})
	if w.Code != http.StatusOK {
		t.Fatalf("set pw: %d %s", w.Code, w.Body.String())
	}
	// Verify the new hash works.
	bobUser, err := f.userRepo.FindByLogin(context.Background(), "bob")
	if err != nil {
		t.Fatal(err)
	}
	if err := bcrypt.CompareHashAndPassword([]byte(bobUser.PasswordHash), []byte("newpass")); err != nil {
		t.Errorf("new pw doesn't match: %v", err)
	}

	// Self-delete forbidden.
	w = f.doAdmin(t, http.MethodDelete, "/api/admin/users/"+f.userID, nil)
	if w.Code != http.StatusBadRequest {
		t.Errorf("self-delete: %d %s", w.Code, w.Body.String())
	}

	// Delete bob (now also admin, but alice remains so count > 1 → OK).
	w = f.doAdmin(t, http.MethodDelete, "/api/admin/users/"+bob.ID, nil)
	if w.Code != http.StatusOK {
		t.Fatalf("delete: %d %s", w.Code, w.Body.String())
	}
	// List now skips deleted bob.
	w = f.doAdmin(t, http.MethodGet, "/api/admin/users", nil)
	list = decodeBody[[]models.AdminUser](t, w)
	if len(list) != 1 || list[0].Login != "alice" {
		t.Errorf("post-delete list: %+v", list)
	}
}

func TestUserAdmin_AvatarUpload(t *testing.T) {
	f := newAdminFixture(t)

	// 1×1 transparent PNG (89 50 4E 47 ... magic).
	png := []byte{
		0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
		0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
		0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
		0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
		0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
		0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
		0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
		0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
		0x42, 0x60, 0x82,
	}

	body := &bytes.Buffer{}
	mw := multipart.NewWriter(body)
	fw, err := mw.CreateFormFile("file", "a.png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := fw.Write(png); err != nil {
		t.Fatal(err)
	}
	mw.Close()

	req := httptest.NewRequest(http.MethodPost, "/api/admin/users/"+f.userID+"/avatar", body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+f.adminToken(t))
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("upload: %d %s", w.Code, w.Body.String())
	}
	got := decodeBody[models.AdminUser](t, w)
	if got.AvatarURL == "" {
		t.Fatalf("avatar_url empty: %+v", got)
	}

	// Serve raw bytes — Content-Type must be image/png.
	req2 := httptest.NewRequest(http.MethodGet, "/api/users/"+f.userID+"/avatar", nil)
	req2.Header.Set("Authorization", "Bearer "+f.adminToken(t))
	w2 := httptest.NewRecorder()
	f.router.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("serve: %d %s", w2.Code, w2.Body.String())
	}
	if ct := w2.Header().Get("Content-Type"); ct != "image/png" {
		t.Errorf("content-type = %q, want image/png", ct)
	}
	if !bytes.Equal(w2.Body.Bytes(), png) {
		t.Errorf("served bytes differ")
	}

	// Delete avatar.
	w = f.doAdmin(t, http.MethodDelete, "/api/admin/users/"+f.userID+"/avatar", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("delete avatar: %d %s", w.Code, w.Body.String())
	}
	after := decodeBody[models.AdminUser](t, w)
	if after.AvatarURL != "" {
		t.Errorf("avatar still set: %+v", after)
	}
}

// TestUserAdmin_AvatarBackfillsSnapshots проверяет, что после загрузки/удаления
// аватара денормализованные снимки UserInfo в `transactions.created_by`
// догоняют актуальное состояние пользователя. Иначе старые записи остаются с
// пустым `avatar_url`, и UI рисует инициалы вместо аватара (баг с до-1.24).
func TestUserAdmin_AvatarBackfillsSnapshots(t *testing.T) {
	f := newAdminFixture(t)

	// Создаём транзакцию ДО заливки аватара — snapshot должен быть пустым.
	w := f.do(t, http.MethodPost, "/api/transactions", models.CreateTransactionRequest{
		Type: models.Income, Amount: 100, Date: "2026-05-01", Category: "Salary",
	}, true)
	if w.Code != http.StatusCreated {
		t.Fatalf("create tx: %d %s", w.Code, w.Body.String())
	}
	tx := decodeBody[models.Transaction](t, w)
	if tx.CreatedBy == nil || tx.CreatedBy.AvatarURL != "" {
		t.Fatalf("pre-upload snapshot expected empty avatar: %+v", tx.CreatedBy)
	}

	// Загрузка аватара → backfill должен дотянуться до txn.
	png := []byte{
		0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
		0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
		0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
		0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
		0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
		0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
		0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
		0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
		0x42, 0x60, 0x82,
	}
	body := &bytes.Buffer{}
	mw := multipart.NewWriter(body)
	fw, _ := mw.CreateFormFile("file", "a.png")
	_, _ = fw.Write(png)
	mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/admin/users/"+f.userID+"/avatar", body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+f.adminToken(t))
	w = httptest.NewRecorder()
	f.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("upload avatar: %d %s", w.Code, w.Body.String())
	}
	user := decodeBody[models.AdminUser](t, w)

	// Перечитываем tx через repo — snapshot должен совпадать с новым URL.
	got, err := f.txRepo.FindByID(context.Background(), tx.ID)
	if err != nil {
		t.Fatalf("re-read tx: %v", err)
	}
	if got.CreatedBy == nil || got.CreatedBy.AvatarURL != user.AvatarURL {
		t.Errorf("after upload: snapshot.avatar=%q, user.avatar=%q",
			got.CreatedBy.AvatarURL, user.AvatarURL)
	}

	// Удаление аватара → snapshot должен очиститься.
	w = f.doAdmin(t, http.MethodDelete, "/api/admin/users/"+f.userID+"/avatar", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("delete avatar: %d %s", w.Code, w.Body.String())
	}
	got, err = f.txRepo.FindByID(context.Background(), tx.ID)
	if err != nil {
		t.Fatalf("re-read tx after clear: %v", err)
	}
	if got.CreatedBy != nil && got.CreatedBy.AvatarURL != "" {
		t.Errorf("after delete: snapshot.avatar=%q, want empty", got.CreatedBy.AvatarURL)
	}

	// Переименование display_name через PATCH /admin/users/:id → snapshot тоже.
	newName := "Alice Reborn"
	w = f.doAdmin(t, http.MethodPatch, "/api/admin/users/"+f.userID,
		models.UpdateUserRequest{DisplayName: &newName})
	if w.Code != http.StatusOK {
		t.Fatalf("rename: %d %s", w.Code, w.Body.String())
	}
	got, err = f.txRepo.FindByID(context.Background(), tx.ID)
	if err != nil {
		t.Fatalf("re-read tx after rename: %v", err)
	}
	if got.CreatedBy == nil || got.CreatedBy.DisplayName != newName {
		t.Errorf("after rename: snapshot.display_name=%q, want %q",
			got.CreatedBy.DisplayName, newName)
	}
}

func TestUserAdmin_ChangeOwnPassword(t *testing.T) {
	f := newAdminFixture(t)

	// Wrong old → 401.
	w := f.doAdmin(t, http.MethodPost, "/api/auth/password",
		models.ChangePasswordRequest{OldPassword: "wrong", NewPassword: "newone"})
	if w.Code != http.StatusUnauthorized {
		t.Errorf("wrong old: %d %s", w.Code, w.Body.String())
	}

	// Correct old → 200, login with new password works.
	w = f.doAdmin(t, http.MethodPost, "/api/auth/password",
		models.ChangePasswordRequest{OldPassword: "hunter2", NewPassword: "brandnew"})
	if w.Code != http.StatusOK {
		t.Fatalf("change pw: %d %s", w.Code, w.Body.String())
	}
	// Login with new password.
	w = f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "alice", Password: "brandnew"}, false)
	if w.Code != http.StatusOK {
		t.Errorf("login with new pw: %d %s", w.Code, w.Body.String())
	}
}

func TestAuth_LoginBlocked(t *testing.T) {
	f := newFixture(t)
	// Block alice in repo.
	tru := true
	if _, err := f.userRepo.ApplyUpdate(context.Background(), f.userID,
		models.UpdateUserRequest{Blocked: &tru}); err != nil {
		t.Fatalf("block: %v", err)
	}
	w := f.do(t, http.MethodPost, "/api/auth/login",
		models.LoginRequest{Login: "alice", Password: "hunter2"}, false)
	if w.Code != http.StatusForbidden {
		t.Errorf("blocked login status = %d, want 403", w.Code)
	}
}

func TestUserRepo_FindByLoginExcludesDeleted(t *testing.T) {
	f := newFixture(t)
	if err := f.userRepo.SoftDelete(context.Background(), f.userID); err != nil {
		t.Fatalf("delete: %v", err)
	}
	_, err := f.userRepo.FindByLogin(context.Background(), "alice")
	if err == nil {
		t.Errorf("expected ErrNoDocuments after soft-delete, got nil")
	}
}

// Ensure init() runs only in this file independently of the existing
// handlers_test.go init.
var _ = gin.Mode
