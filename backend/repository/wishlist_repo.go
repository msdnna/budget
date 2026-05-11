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

type WishlistRepository struct {
	col *mongo.Collection
}

func NewWishlistRepository(db *mongo.Database) *WishlistRepository {
	col := db.Collection("wishlist")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "purchased", Value: 1}}},
		{Keys: bson.D{{Key: "priority", Value: 1}}},
		{Keys: bson.D{{Key: "updated_at", Value: 1}}},
		{Keys: bson.D{{Key: "deleted_at", Value: 1}}},
	})

	return &WishlistRepository{col: col}
}

func (r *WishlistRepository) Create(ctx context.Context, item *models.WishlistItem) error {
	now := time.Now()
	if item.ID == "" {
		item.ID = uuid.NewString()
	}
	if item.CreatedAt.IsZero() {
		item.CreatedAt = now
	}
	item.UpdatedAt = now
	item.Version = 1
	item.DeletedAt = nil
	item.LastModifiedBy = item.CreatedBy
	_, err := r.col.InsertOne(ctx, item)
	return err
}

func (r *WishlistRepository) FindByID(ctx context.Context, id string) (*models.WishlistItem, error) {
	var item models.WishlistItem
	err := r.col.FindOne(ctx, bson.M{"_id": id, "deleted_at": nil}).Decode(&item)
	if err != nil {
		return nil, err
	}
	return &item, nil
}

func (r *WishlistRepository) Update(ctx context.Context, id string, update bson.M, baseVersion int, modifiedBy *models.UserInfo) (*models.WishlistItem, error) {
	now := time.Now()
	update["updated_at"] = now
	if modifiedBy != nil {
		update["last_modified_by"] = modifiedBy
	}
	filter := bson.M{"_id": id, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	res := r.col.FindOneAndUpdate(ctx, filter,
		bson.M{"$set": update, "$inc": bson.M{"version": 1}},
		options.FindOneAndUpdate().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			var existing models.WishlistItem
			if findErr := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&existing); findErr == nil {
				return nil, ErrConflict
			}
			return nil, err
		}
		return nil, err
	}
	var item models.WishlistItem
	if err := res.Decode(&item); err != nil {
		return nil, err
	}
	return &item, nil
}

func (r *WishlistRepository) Delete(ctx context.Context, id string, baseVersion int, modifiedBy *models.UserInfo) (*models.WishlistItem, error) {
	now := time.Now()
	filter := bson.M{"_id": id, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	update := bson.M{
		"$set": bson.M{
			"deleted_at":       now,
			"updated_at":       now,
			"last_modified_by": modifiedBy,
		},
		"$inc": bson.M{"version": 1},
	}
	res := r.col.FindOneAndUpdate(ctx, filter, update,
		options.FindOneAndUpdate().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			var existing models.WishlistItem
			if findErr := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&existing); findErr == nil {
				return nil, ErrConflict
			}
			return nil, err
		}
		return nil, err
	}
	var item models.WishlistItem
	if err := res.Decode(&item); err != nil {
		return nil, err
	}
	return &item, nil
}

func (r *WishlistRepository) Upsert(ctx context.Context, item *models.WishlistItem, baseVersion int, isCreate bool) (*models.WishlistItem, error) {
	now := time.Now()
	item.UpdatedAt = now

	if isCreate {
		if item.CreatedAt.IsZero() {
			item.CreatedAt = now
		}
		item.Version = 1
		item.DeletedAt = nil
		_, err := r.col.InsertOne(ctx, item)
		if err != nil {
			if mongo.IsDuplicateKeyError(err) {
				return nil, ErrConflict
			}
			return nil, err
		}
		return item, nil
	}

	filter := bson.M{"_id": item.ID, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	item.Version = baseVersion + 1
	res := r.col.FindOneAndReplace(ctx, filter, item,
		options.FindOneAndReplace().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrConflict
		}
		return nil, err
	}
	var out models.WishlistItem
	if err := res.Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (r *WishlistRepository) FindAll(ctx context.Context) ([]models.WishlistItem, error) {
	opts := options.Find().SetSort(bson.D{{Key: "priority", Value: 1}, {Key: "created_at", Value: -1}})
	cur, err := r.col.Find(ctx, bson.M{"deleted_at": nil}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	var items []models.WishlistItem
	if err := cur.All(ctx, &items); err != nil {
		return nil, err
	}
	return items, nil
}

func (r *WishlistRepository) FindUnpurchased(ctx context.Context) ([]models.WishlistItem, error) {
	opts := options.Find().SetSort(bson.D{{Key: "priority", Value: 1}, {Key: "created_at", Value: -1}})
	cur, err := r.col.Find(ctx, bson.M{"purchased": false, "deleted_at": nil}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	var items []models.WishlistItem
	if err := cur.All(ctx, &items); err != nil {
		return nil, err
	}
	return items, nil
}

func (r *WishlistRepository) FindModifiedSince(ctx context.Context, since time.Time) ([]models.WishlistItem, error) {
	filter := bson.M{}
	if !since.IsZero() {
		filter["updated_at"] = bson.M{"$gt": since}
	} else {
		filter["deleted_at"] = nil
	}
	cur, err := r.col.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.WishlistItem
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}
