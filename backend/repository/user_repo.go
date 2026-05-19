package repository

import (
	"context"
	"time"

	"budget-go/models"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type UserRepository struct {
	col *mongo.Collection
}

func NewUserRepository(db *mongo.Database) *UserRepository {
	col := db.Collection("users")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "login", Value: 1}}, Options: options.Index().SetUnique(true)},
	})

	return &UserRepository{col: col}
}

// FindByLogin — учётка для логина. Возвращает только не-удалённых
// пользователей; блокировку проверяет вызывающий (нам нужен PasswordHash
// даже для блокированных, например, для последующего split-API).
func (r *UserRepository) FindByLogin(ctx context.Context, login string) (*models.User, error) {
	var u models.User
	err := r.col.FindOne(ctx, bson.M{
		"login":      login,
		"deleted_at": bson.M{"$exists": false},
	}).Decode(&u)
	if err != nil {
		return nil, err
	}
	return &u, nil
}

func (r *UserRepository) Create(ctx context.Context, u *models.User) error {
	u.ID = primitive.NewObjectID()
	u.CreatedAt = time.Now()
	_, err := r.col.InsertOne(ctx, u)
	return err
}

// FindByID возвращает пользователя по hex-id даже если он soft-deleted —
// записи (transactions/wishlist) ссылаются на него через UserInfo, и для
// UI «удалён» лучше показывать актуальное display_name, чем заглушку.
func (r *UserRepository) FindByID(ctx context.Context, id string) (*models.User, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	var u models.User
	if err := r.col.FindOne(ctx, bson.M{"_id": oid}).Decode(&u); err != nil {
		return nil, err
	}
	return &u, nil
}

// FindAll возвращает всех неудалённых пользователей. Заблокированные —
// включены: админу нужно их видеть, чтобы разблокировать.
func (r *UserRepository) FindAll(ctx context.Context) ([]models.User, error) {
	cursor, err := r.col.Find(ctx, bson.M{"deleted_at": bson.M{"$exists": false}})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)
	var users []models.User
	if err := cursor.All(ctx, &users); err != nil {
		return nil, err
	}
	return users, nil
}

// EnsureAdmin promotes the earliest-created user to admin if none exists.
// Idempotent: subsequent calls are no-ops when an admin is already present.
// Called once on backend boot so existing single-user installs gain an admin
// without manual intervention.
func (r *UserRepository) EnsureAdmin(ctx context.Context) error {
	count, err := r.col.CountDocuments(ctx, bson.M{
		"is_admin":   true,
		"deleted_at": bson.M{"$exists": false},
	})
	if err != nil {
		return err
	}
	if count > 0 {
		return nil
	}
	// Earliest by _id — ObjectID embeds creation timestamp, so this works
	// even for users seeded before `created_at` existed.
	opts := options.FindOne().SetSort(bson.D{{Key: "_id", Value: 1}})
	var u models.User
	if err := r.col.FindOne(ctx, bson.M{"deleted_at": bson.M{"$exists": false}}, opts).Decode(&u); err != nil {
		if err == mongo.ErrNoDocuments {
			return nil
		}
		return err
	}
	_, err = r.col.UpdateOne(ctx, bson.M{"_id": u.ID}, bson.M{"$set": bson.M{"is_admin": true}})
	return err
}

// SetAdmin grants/revokes admin on a specific user.
func (r *UserRepository) SetAdmin(ctx context.Context, id string, admin bool) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	_, err = r.col.UpdateOne(ctx, bson.M{"_id": oid}, bson.M{"$set": bson.M{"is_admin": admin}})
	return err
}

// CountAdmins — число активных (не soft-deleted) администраторов. Используется
// safeguard'ом «нельзя снять/удалить последнего админа».
func (r *UserRepository) CountAdmins(ctx context.Context) (int64, error) {
	return r.col.CountDocuments(ctx, bson.M{
		"is_admin":   true,
		"deleted_at": bson.M{"$exists": false},
	})
}

// ApplyUpdate частично обновляет пользователя (login / display_name / is_admin
// / blocked_at). Возвращает обновлённый документ. Для blocked: true → выставить
// now(), false → unset.
func (r *UserRepository) ApplyUpdate(ctx context.Context, id string, req models.UpdateUserRequest) (*models.User, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	set := bson.M{}
	unset := bson.M{}
	if req.Login != nil {
		set["login"] = *req.Login
	}
	if req.DisplayName != nil {
		set["display_name"] = *req.DisplayName
	}
	if req.IsAdmin != nil {
		set["is_admin"] = *req.IsAdmin
	}
	if req.Blocked != nil {
		if *req.Blocked {
			set["blocked_at"] = time.Now()
		} else {
			unset["blocked_at"] = ""
		}
	}
	update := bson.M{}
	if len(set) > 0 {
		update["$set"] = set
	}
	if len(unset) > 0 {
		update["$unset"] = unset
	}
	if len(update) == 0 {
		return r.FindByID(ctx, id)
	}
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var out models.User
	if err := r.col.FindOneAndUpdate(ctx, bson.M{"_id": oid}, update, opts).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (r *UserRepository) UpdatePassword(ctx context.Context, id, hash string) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	res, err := r.col.UpdateOne(ctx, bson.M{"_id": oid}, bson.M{"$set": bson.M{"password_hash": hash}})
	if err != nil {
		return err
	}
	if res.MatchedCount == 0 {
		return mongo.ErrNoDocuments
	}
	return nil
}

// SetAvatar сохраняет байты аватара inline + ставит avatar_url на стабильный
// эндпоинт. `v=<ts>` ломает CDN/браузерный кеш после замены.
func (r *UserRepository) SetAvatar(ctx context.Context, id, mime string, data []byte) (*models.User, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	url := "/api/users/" + id + "/avatar?v=" + time.Now().UTC().Format("20060102150405")
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var out models.User
	err = r.col.FindOneAndUpdate(ctx, bson.M{"_id": oid}, bson.M{
		"$set": bson.M{
			"avatar_mime": mime,
			"avatar_data": data,
			"avatar_url":  url,
		},
	}, opts).Decode(&out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (r *UserRepository) ClearAvatar(ctx context.Context, id string) (*models.User, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var out models.User
	err = r.col.FindOneAndUpdate(ctx, bson.M{"_id": oid}, bson.M{
		"$unset": bson.M{"avatar_mime": "", "avatar_data": "", "avatar_url": ""},
	}, opts).Decode(&out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// SoftDelete — выставить deleted_at; record-references (UserInfo в транзакциях)
// продолжают работать. Идемпотентность — повторный вызов перезаписывает время.
func (r *UserRepository) SoftDelete(ctx context.Context, id string) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	res, err := r.col.UpdateOne(ctx, bson.M{"_id": oid}, bson.M{"$set": bson.M{"deleted_at": time.Now()}})
	if err != nil {
		return err
	}
	if res.MatchedCount == 0 {
		return mongo.ErrNoDocuments
	}
	return nil
}
