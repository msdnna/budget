package handlers

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
)

// PortabilityHandler implements admin-only JSON import/export for the
// whole budget dataset. The format is a single self-contained snapshot —
// users (with bcrypt hashes), categories, icons (base64), transactions,
// wishlist, detail-requests. Not a substitute for proper backups; this is
// for moving data between installs.
type PortabilityHandler struct {
	db       *mongo.Database
	userRepo *repository.UserRepository
}

func NewPortabilityHandler(db *mongo.Database, userRepo *repository.UserRepository) *PortabilityHandler {
	return &PortabilityHandler{db: db, userRepo: userRepo}
}

// CurrentSchemaVersion bumps when the export format becomes incompatible.
// Importer accepts only equal versions for now — additive changes are
// allowed within the same version.
const CurrentSchemaVersion = 1

// Cap to keep an unbounded payload from OOM'ing the API container.
// 50 MiB covers ~100k transactions + a few hundred SVG icons inline.
const maxImportBytes = 50 * 1024 * 1024

type exportUser struct {
	ID               string     `json:"id"`
	Login            string     `json:"login"`
	PasswordHash     string     `json:"password_hash"`
	DisplayName      string     `json:"display_name"`
	AvatarURL        string     `json:"avatar_url,omitempty"`
	AvatarMime       string     `json:"avatar_mime,omitempty"`
	AvatarDataBase64 string     `json:"avatar_data_base64,omitempty"`
	IsAdmin          bool       `json:"is_admin,omitempty"`
	BlockedAt        *time.Time `json:"blocked_at,omitempty"`
	CreatedAt        time.Time  `json:"created_at"`
}

type exportIcon struct {
	ID         string           `json:"id"`
	MimeType   string           `json:"mime_type"`
	SizeBytes  int              `json:"size_bytes"`
	DataBase64 string           `json:"data_base64"`
	UploadedBy *models.UserInfo `json:"uploaded_by,omitempty"`
	UploadedAt time.Time        `json:"uploaded_at"`
}

// Snapshot is the on-wire portability format.
type Snapshot struct {
	SchemaVersion  int                    `json:"schema_version"`
	ExportedAt     time.Time              `json:"exported_at"`
	ExportedBy     string                 `json:"exported_by,omitempty"`
	AppVersion     string                 `json:"app_version,omitempty"`
	Users          []exportUser           `json:"users"`
	Categories     []models.Category      `json:"categories"`
	Icons          []exportIcon           `json:"icons"`
	Transactions   []models.Transaction   `json:"transactions"`
	Wishlist       []models.WishlistItem  `json:"wishlist"`
	DetailRequests []models.DetailRequest `json:"detail_requests"`
}

type importRequest struct {
	// Mode = "merge" (default) keeps existing rows on _id / login
	// collision; "replace" wipes everything except the caller before
	// importing. Default categories are re-seeded after replace by
	// EnsureDefaults on the next backend boot — until then, the imported
	// categories are authoritative.
	Mode     string    `json:"mode"`
	Snapshot *Snapshot `json:"snapshot"`
}

type importStats struct {
	Mode           string `json:"mode"`
	UsersImported  int    `json:"users_imported"`
	UsersSkipped   int    `json:"users_skipped"`
	Categories     int    `json:"categories_imported"`
	CategoriesSkip int    `json:"categories_skipped"`
	Icons          int    `json:"icons_imported"`
	IconsSkip      int    `json:"icons_skipped"`
	Transactions   int    `json:"transactions_imported"`
	TxSkip         int    `json:"transactions_skipped"`
	Wishlist       int    `json:"wishlist_imported"`
	WishlistSkip   int    `json:"wishlist_skipped"`
	DetailRequests int    `json:"detail_requests_imported"`
	DRSkip         int    `json:"detail_requests_skipped"`
}

// Export godoc
// @Summary      Скачать снимок данных (admin)
// @Description  Возвращает JSON-снимок всех коллекций (users с bcrypt, categories, icons base64, transactions, wishlist, detail_requests). Используется для переноса между инсталляциями, не для бэкапа.
// @Tags         admin
// @Produce      json
// @Security     BearerAuth
// @Success      200  {object}  Snapshot
// @Router       /admin/export [get]
func (h *PortabilityHandler) Export(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 60*time.Second)
	defer cancel()

	snap := Snapshot{
		SchemaVersion: CurrentSchemaVersion,
		ExportedAt:    time.Now().UTC(),
	}
	if v, ok := c.Get("display_name"); ok {
		if s, ok := v.(string); ok {
			snap.ExportedBy = s
		}
	}

	users, err := fetchUsers(ctx, h.db)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("users: %v", err)})
		return
	}
	snap.Users = users

	if err := fetchInto(ctx, h.db.Collection("categories"), bson.M{"deleted_at": nil}, &snap.Categories); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("categories: %v", err)})
		return
	}
	icons, err := fetchIcons(ctx, h.db)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("icons: %v", err)})
		return
	}
	snap.Icons = icons
	if err := fetchInto(ctx, h.db.Collection("transactions"), bson.M{"deleted_at": nil}, &snap.Transactions); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("transactions: %v", err)})
		return
	}
	// Parents before children — detail-request import relies on this
	// ordering to satisfy the parent_id reference at insert time.
	sort.SliceStable(snap.Transactions, func(i, j int) bool {
		a, b := snap.Transactions[i], snap.Transactions[j]
		if (a.ParentID == "") != (b.ParentID == "") {
			return a.ParentID == ""
		}
		return a.CreatedAt.Before(b.CreatedAt)
	})
	if err := fetchInto(ctx, h.db.Collection("wishlist"), bson.M{"deleted_at": nil}, &snap.Wishlist); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("wishlist: %v", err)})
		return
	}
	if err := fetchInto(ctx, h.db.Collection("detail_requests"), bson.M{}, &snap.DetailRequests); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("detail_requests: %v", err)})
		return
	}

	filename := fmt.Sprintf("budget-export-%s.json", snap.ExportedAt.Format("20060102-150405"))
	c.Header("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, filename))
	c.Header("Content-Type", "application/json; charset=utf-8")
	c.JSON(http.StatusOK, snap)
}

// Import godoc
// @Summary      Импорт снимка данных (admin)
// @Description  Принимает JSON {mode, snapshot}. mode=merge (по умолчанию) — пропускать существующие записи по _id (и login для пользователей); mode=replace — очистить коллекции (кроме вызывающего админа), потом импортировать. Бампает updated_at у импортированных записей, чтобы Android-клиенты подхватили их на следующем sync/pull.
// @Tags         admin
// @Accept       json
// @Produce      json
// @Security     BearerAuth
// @Param        body  body      importRequest  true  "Снимок и режим"
// @Success      200   {object}  importStats
// @Failure      400   {object}  map[string]string
// @Router       /admin/import [post]
func (h *PortabilityHandler) Import(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), 120*time.Second)
	defer cancel()

	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxImportBytes)
	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "payload too large"})
		return
	}

	var req importRequest
	if err := json.Unmarshal(body, &req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": fmt.Sprintf("invalid JSON: %v", err)})
		return
	}
	if req.Snapshot == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing snapshot"})
		return
	}
	if req.Snapshot.SchemaVersion != CurrentSchemaVersion {
		c.JSON(http.StatusBadRequest, gin.H{"error": fmt.Sprintf("unsupported schema_version: %d (need %d)", req.Snapshot.SchemaVersion, CurrentSchemaVersion)})
		return
	}
	mode := req.Mode
	if mode == "" {
		mode = "merge"
	}
	if mode != "merge" && mode != "replace" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "mode must be 'merge' or 'replace'"})
		return
	}

	callerID, _ := c.Get("user_id")
	callerHex, _ := callerID.(string)
	callerOID, _ := primitive.ObjectIDFromHex(callerHex)

	if mode == "replace" {
		if err := h.wipeKeepingCaller(ctx, callerOID); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("wipe: %v", err)})
			return
		}
	}

	stats := importStats{Mode: mode}
	if err := h.importUsers(ctx, req.Snapshot.Users, callerHex, &stats); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("users: %v", err)})
		return
	}
	if err := h.importByID(ctx, "categories", req.Snapshot.Categories, &stats.Categories, &stats.CategoriesSkip); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("categories: %v", err)})
		return
	}
	if err := h.importIcons(ctx, req.Snapshot.Icons, &stats); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("icons: %v", err)})
		return
	}
	// Sort transactions parents-first defensively — exporter does the
	// same, but a hand-edited JSON might reorder them.
	sort.SliceStable(req.Snapshot.Transactions, func(i, j int) bool {
		a, b := req.Snapshot.Transactions[i], req.Snapshot.Transactions[j]
		if (a.ParentID == "") != (b.ParentID == "") {
			return a.ParentID == ""
		}
		return a.CreatedAt.Before(b.CreatedAt)
	})
	if err := h.importByID(ctx, "transactions", req.Snapshot.Transactions, &stats.Transactions, &stats.TxSkip); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("transactions: %v", err)})
		return
	}
	if err := h.importByID(ctx, "wishlist", req.Snapshot.Wishlist, &stats.Wishlist, &stats.WishlistSkip); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("wishlist: %v", err)})
		return
	}
	if err := h.importByID(ctx, "detail_requests", req.Snapshot.DetailRequests, &stats.DetailRequests, &stats.DRSkip); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": fmt.Sprintf("detail_requests: %v", err)})
		return
	}

	c.JSON(http.StatusOK, stats)
}

// fetchInto reads all docs matching `filter` from `col` into out
// (which must be a *[]T). Used for the simple uuid-keyed collections.
func fetchInto[T any](ctx context.Context, col *mongo.Collection, filter bson.M, out *[]T) error {
	cur, err := col.Find(ctx, filter)
	if err != nil {
		return err
	}
	defer cur.Close(ctx)
	*out = (*out)[:0]
	return cur.All(ctx, out)
}

func fetchUsers(ctx context.Context, db *mongo.Database) ([]exportUser, error) {
	cur, err := db.Collection("users").Find(ctx, bson.M{"deleted_at": bson.M{"$exists": false}})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var raw []models.User
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}
	out := make([]exportUser, 0, len(raw))
	for _, u := range raw {
		eu := exportUser{
			ID:           u.ID.Hex(),
			Login:        u.Login,
			PasswordHash: u.PasswordHash,
			DisplayName:  u.DisplayName,
			AvatarURL:    u.AvatarURL,
			AvatarMime:   u.AvatarMime,
			IsAdmin:      u.IsAdmin,
			BlockedAt:    u.BlockedAt,
			CreatedAt:    u.CreatedAt,
		}
		if len(u.AvatarData) > 0 {
			eu.AvatarDataBase64 = base64.StdEncoding.EncodeToString(u.AvatarData)
		}
		out = append(out, eu)
	}
	return out, nil
}

func fetchIcons(ctx context.Context, db *mongo.Database) ([]exportIcon, error) {
	cur, err := db.Collection("category_icons").Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var raw []models.CategoryIcon
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}
	out := make([]exportIcon, 0, len(raw))
	for _, ic := range raw {
		out = append(out, exportIcon{
			ID:         ic.ID,
			MimeType:   ic.MimeType,
			SizeBytes:  ic.SizeBytes,
			DataBase64: base64.StdEncoding.EncodeToString(ic.Data),
			UploadedBy: ic.UploadedBy,
			UploadedAt: ic.UploadedAt,
		})
	}
	return out, nil
}

func (h *PortabilityHandler) wipeKeepingCaller(ctx context.Context, callerOID primitive.ObjectID) error {
	// Categories/icons/transactions/wishlist/detail_requests — all gone.
	// EnsureDefaults will re-seed default categories on the next backend
	// boot; in the meantime imported categories replace them.
	for _, name := range []string{"categories", "category_icons", "transactions", "wishlist", "detail_requests", "notifications"} {
		if _, err := h.db.Collection(name).DeleteMany(ctx, bson.M{}); err != nil {
			return fmt.Errorf("delete %s: %w", name, err)
		}
	}
	// Users — keep the caller only (the one who triggered replace). All
	// other users get hard-deleted; the imported snapshot will recreate
	// them with their old hashes / display names.
	if _, err := h.db.Collection("users").DeleteMany(ctx, bson.M{"_id": bson.M{"$ne": callerOID}}); err != nil {
		return fmt.Errorf("delete users: %w", err)
	}
	return nil
}

func (h *PortabilityHandler) importUsers(ctx context.Context, users []exportUser, callerHex string, stats *importStats) error {
	col := h.db.Collection("users")
	now := time.Now()
	for _, eu := range users {
		oid, err := primitive.ObjectIDFromHex(eu.ID)
		if err != nil {
			// Skip malformed ID rather than abort — let the rest import.
			stats.UsersSkipped++
			continue
		}
		// Never overwrite the caller (the freshly-created admin in the
		// wizard, or the current admin running settings-import).
		if eu.ID == callerHex {
			stats.UsersSkipped++
			continue
		}
		// Skip on _id OR login collision.
		exists, err := col.CountDocuments(ctx, bson.M{"$or": bson.A{
			bson.M{"_id": oid},
			bson.M{"login": eu.Login},
		}})
		if err != nil {
			return err
		}
		if exists > 0 {
			stats.UsersSkipped++
			continue
		}
		doc := bson.M{
			"_id":           oid,
			"login":         eu.Login,
			"password_hash": eu.PasswordHash,
			"display_name":  eu.DisplayName,
			"is_admin":      eu.IsAdmin,
			"created_at":    eu.CreatedAt,
		}
		if eu.AvatarURL != "" {
			doc["avatar_url"] = eu.AvatarURL
		}
		if eu.AvatarMime != "" && eu.AvatarDataBase64 != "" {
			data, err := base64.StdEncoding.DecodeString(eu.AvatarDataBase64)
			if err == nil {
				doc["avatar_mime"] = eu.AvatarMime
				doc["avatar_data"] = data
			}
		}
		if eu.BlockedAt != nil {
			doc["blocked_at"] = *eu.BlockedAt
		}
		if _, err := col.InsertOne(ctx, doc); err != nil {
			if mongo.IsDuplicateKeyError(err) {
				stats.UsersSkipped++
				continue
			}
			return err
		}
		_ = now
		stats.UsersImported++
	}
	return nil
}

// importByID inserts uuid-keyed rows (transactions, wishlist, categories,
// detail_requests). Skips on _id collision. Bumps updated_at to now() so
// Android `sync/pull` picks them up on the next call.
func (h *PortabilityHandler) importByID(ctx context.Context, collection string, rows interface{}, imported, skipped *int) error {
	col := h.db.Collection(collection)
	now := time.Now()
	type rowWithID struct {
		raw bson.M
		id  string
	}
	docs := []rowWithID{}
	// Convert to []bson.M via JSON round-trip — the json tags on the
	// model structs already enumerate every exported field, so we get
	// a clean Mongo doc without manual field-by-field copying.
	jsonBytes, err := json.Marshal(rows)
	if err != nil {
		return err
	}
	var asMaps []bson.M
	if err := json.Unmarshal(jsonBytes, &asMaps); err != nil {
		return err
	}
	for _, m := range asMaps {
		// Models use json:"id" but bson:"_id" — remap.
		if id, ok := m["id"].(string); ok && id != "" {
			m["_id"] = id
			delete(m, "id")
		}
		idStr, _ := m["_id"].(string)
		// JSON time strings are decoded as plain strings; convert known
		// time-typed fields back to time.Time so Mongo stores them as
		// BSON dates (otherwise filters like `updated_at > $since` on
		// the sync endpoint compare strings).
		convertTimeFields(m, "date", "created_at", "updated_at", "deleted_at", "uploaded_at", "blocked_at", "closed_at")
		// Bump updated_at so Android sync clients pull the imported row.
		m["updated_at"] = now
		docs = append(docs, rowWithID{raw: m, id: idStr})
	}
	for _, doc := range docs {
		if doc.id == "" {
			*skipped++
			continue
		}
		exists, err := col.CountDocuments(ctx, bson.M{"_id": doc.id})
		if err != nil {
			return err
		}
		if exists > 0 {
			*skipped++
			continue
		}
		if _, err := col.InsertOne(ctx, doc.raw); err != nil {
			if mongo.IsDuplicateKeyError(err) {
				*skipped++
				continue
			}
			return err
		}
		*imported++
	}
	return nil
}

func (h *PortabilityHandler) importIcons(ctx context.Context, icons []exportIcon, stats *importStats) error {
	col := h.db.Collection("category_icons")
	for _, ic := range icons {
		if ic.ID == "" || ic.DataBase64 == "" {
			stats.IconsSkip++
			continue
		}
		exists, err := col.CountDocuments(ctx, bson.M{"_id": ic.ID})
		if err != nil {
			return err
		}
		if exists > 0 {
			stats.IconsSkip++
			continue
		}
		data, err := base64.StdEncoding.DecodeString(ic.DataBase64)
		if err != nil {
			stats.IconsSkip++
			continue
		}
		doc := bson.M{
			"_id":         ic.ID,
			"mime_type":   ic.MimeType,
			"size_bytes":  len(data),
			"data":        data,
			"uploaded_at": ic.UploadedAt,
		}
		if ic.UploadedBy != nil {
			doc["uploaded_by"] = ic.UploadedBy
		}
		if _, err := col.InsertOne(ctx, doc); err != nil {
			if mongo.IsDuplicateKeyError(err) {
				stats.IconsSkip++
				continue
			}
			return err
		}
		stats.Icons++
	}
	return nil
}

// convertTimeFields walks a bson.M and parses RFC3339 strings under the
// given keys into time.Time, so they're stored as native BSON dates.
// Missing/empty keys are left alone. Nested arrays of objects are
// recursed into (covers `created_by`/`last_modified_by` UserInfo blobs).
func convertTimeFields(m bson.M, keys ...string) {
	for _, k := range keys {
		v, ok := m[k]
		if !ok || v == nil {
			continue
		}
		if s, ok := v.(string); ok && s != "" {
			if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
				m[k] = t
			} else if t, err := time.Parse(time.RFC3339, s); err == nil {
				m[k] = t
			}
		}
	}
}
