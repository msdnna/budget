package repository

import (
	"context"
	"time"

	"budget-go/models"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type NotificationRepository struct {
	col *mongo.Collection
}

func NewNotificationRepository(db *mongo.Database) *NotificationRepository {
	col := db.Collection("notifications")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		// Dedup index: one row per (type, period, category_id). category_id
		// is "" for the global notification, which still slots into the
		// triple cleanly because compound uniqueness covers exact match.
		{
			Keys: bson.D{
				{Key: "type", Value: 1},
				{Key: "period", Value: 1},
				{Key: "category_id", Value: 1},
			},
			Options: options.Index().SetUnique(true),
		},
		{Keys: bson.D{{Key: "created_at", Value: -1}}},
	})

	return &NotificationRepository{col: col}
}

// EnsureExceeded inserts a notification for the given (type, period,
// category_id) triple if none exists yet. On the dedup-conflict path it
// returns nil, false — the caller treats that as "already fired this month".
// Returns the inserted notification + true on a fresh insert so callers can
// log / push it.
func (r *NotificationRepository) EnsureExceeded(ctx context.Context, n *models.Notification) (*models.Notification, bool, error) {
	if n.ID == "" {
		n.ID = uuid.NewString()
	}
	if n.CreatedAt.IsZero() {
		n.CreatedAt = time.Now()
	}
	if n.ReadBy == nil {
		n.ReadBy = []string{}
	}
	_, err := r.col.InsertOne(ctx, n)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil, false, nil
		}
		return nil, false, err
	}
	return n, true, nil
}

// List returns notifications for the calling user, newest first. Limit is
// soft-capped at 100; pass 0 to use the default.
func (r *NotificationRepository) List(ctx context.Context, limit int64) ([]models.Notification, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	opts := options.Find().
		SetSort(bson.D{{Key: "created_at", Value: -1}}).
		SetLimit(limit)
	cur, err := r.col.Find(ctx, bson.M{}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.Notification
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// UnreadCount returns the number of notifications that don't yet include
// userID in their ReadBy array.
func (r *NotificationRepository) UnreadCount(ctx context.Context, userID string) (int64, error) {
	return r.col.CountDocuments(ctx, bson.M{"read_by": bson.M{"$ne": userID}})
}

// MarkAllRead adds userID to ReadBy on every notification that doesn't
// already contain it. Idempotent.
func (r *NotificationRepository) MarkAllRead(ctx context.Context, userID string) error {
	_, err := r.col.UpdateMany(ctx,
		bson.M{"read_by": bson.M{"$ne": userID}},
		bson.M{"$addToSet": bson.M{"read_by": userID}},
	)
	return err
}

// MarkRead adds userID to ReadBy on a single notification. Idempotent.
func (r *NotificationRepository) MarkRead(ctx context.Context, id, userID string) error {
	_, err := r.col.UpdateOne(ctx,
		bson.M{"_id": id},
		bson.M{"$addToSet": bson.M{"read_by": userID}},
	)
	return err
}
