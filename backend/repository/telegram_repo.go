package repository

import (
	"context"
	"errors"
	"time"

	"budget-go/models"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

// linkCodeTTL — окно, в которое пользователь должен переслать сгенерированный
// код боту. 5 минут — компромисс между «успеть переключить окна» и «не
// держать живой код часами».
const linkCodeTTL = 5 * time.Minute

var (
	// ErrLinkNotFound — нет активной привязки для user_id / telegram_user_id.
	ErrLinkNotFound = errors.New("telegram link not found")
	// ErrLinkCodeInvalid — код не найден или истёк.
	ErrLinkCodeInvalid = errors.New("link code invalid or expired")
)

type TelegramRepository struct {
	col *mongo.Collection
}

func NewTelegramRepository(db *mongo.Database) *TelegramRepository {
	col := db.Collection("telegram_links")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// user_id и telegram_user_id — 1:1; sparse на telegram_user_id, чтобы
	// pending-привязки (с пустым telegram_user_id) не конфликтовали.
	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "user_id", Value: 1}}, Options: options.Index().SetUnique(true)},
		{
			Keys: bson.D{{Key: "telegram_user_id", Value: 1}},
			Options: options.Index().SetUnique(true).SetPartialFilterExpression(bson.M{
				"telegram_user_id": bson.M{"$gt": 0},
			}),
		},
		{
			Keys:    bson.D{{Key: "code", Value: 1}},
			Options: options.Index().SetSparse(true),
		},
	})

	return &TelegramRepository{col: col}
}

// CodeTTL — публичный геттер, чтобы handlers могли вернуть expires_at без
// дублирования константы.
func (r *TelegramRepository) CodeTTL() time.Duration { return linkCodeTTL }

// UpsertCode сохраняет (или перезаписывает) одноразовый код привязки для user_id.
// При повторном вызове старая привязка теряется (включая активную) — это и есть
// «перепривязать заново».
func (r *TelegramRepository) UpsertCode(ctx context.Context, userID, code string) (time.Time, error) {
	now := time.Now()
	expires := now.Add(linkCodeTTL)
	_, err := r.col.UpdateOne(
		ctx,
		bson.M{"user_id": userID},
		bson.M{
			"$set": bson.M{
				"code":            code,
				"code_expires_at": expires,
			},
			"$unset": bson.M{
				"telegram_user_id":  "",
				"telegram_username": "",
				"linked_at":         "",
			},
			"$setOnInsert": bson.M{
				"user_id":    userID,
				"created_at": now,
			},
		},
		options.Update().SetUpsert(true),
	)
	if err != nil {
		return time.Time{}, err
	}
	return expires, nil
}

// ConfirmCode атомарно привязывает telegram_user_id/username по сохранённому
// коду, очищая `code`+`code_expires_at`. Если кода нет, он истёк, или
// telegram_user_id уже привязан к другому user — вернёт ошибку.
func (r *TelegramRepository) ConfirmCode(ctx context.Context, code string, telegramUserID int64, telegramUsername string) (*models.TelegramLink, error) {
	now := time.Now()
	filter := bson.M{
		"code":            code,
		"code_expires_at": bson.M{"$gt": now},
	}
	set := bson.M{
		"telegram_user_id":  telegramUserID,
		"telegram_username": telegramUsername,
		"linked_at":         now,
	}
	update := bson.M{
		"$set":   set,
		"$unset": bson.M{"code": "", "code_expires_at": ""},
	}
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var out models.TelegramLink
	if err := r.col.FindOneAndUpdate(ctx, filter, update, opts).Decode(&out); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrLinkCodeInvalid
		}
		// Duplicate telegram_user_id — partial-unique index сработал.
		if mongo.IsDuplicateKeyError(err) {
			return nil, ErrLinkCodeInvalid
		}
		return nil, err
	}
	return &out, nil
}

// FindByUserID — текущая привязка пользователя (для UI Settings).
func (r *TelegramRepository) FindByUserID(ctx context.Context, userID string) (*models.TelegramLink, error) {
	var out models.TelegramLink
	if err := r.col.FindOne(ctx, bson.M{"user_id": userID}).Decode(&out); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrLinkNotFound
		}
		return nil, err
	}
	return &out, nil
}

// FindByTelegramUserID — поиск budget-пользователя по telegram-id (вызывается
// ботом перед отправкой команды).
func (r *TelegramRepository) FindByTelegramUserID(ctx context.Context, telegramUserID int64) (*models.TelegramLink, error) {
	var out models.TelegramLink
	if err := r.col.FindOne(ctx, bson.M{"telegram_user_id": telegramUserID}).Decode(&out); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrLinkNotFound
		}
		return nil, err
	}
	return &out, nil
}

// DeleteByUserID — пользователь нажал «Отвязать». Soft-delete не нужен:
// история сообщений в telegram остаётся у пользователя, а на нашей стороне
// привязка — просто пара id'шек.
func (r *TelegramRepository) DeleteByUserID(ctx context.Context, userID string) error {
	res, err := r.col.DeleteOne(ctx, bson.M{"user_id": userID})
	if err != nil {
		return err
	}
	if res.DeletedCount == 0 {
		return ErrLinkNotFound
	}
	return nil
}
