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

type WishlistRepository struct {
	col *mongo.Collection
}

func NewWishlistRepository(db *mongo.Database) *WishlistRepository {
	col := db.Collection("wishlist")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{"is_favorite", 1}}},
		{Keys: bson.D{{"purchased", 1}}},
		{Keys: bson.D{{"priority", 1}}},
	})

	return &WishlistRepository{col: col}
}

func (r *WishlistRepository) Create(ctx context.Context, item *models.WishlistItem) error {
	item.ID = primitive.NewObjectID()
	item.CreatedAt = time.Now()
	_, err := r.col.InsertOne(ctx, item)
	return err
}

func (r *WishlistRepository) FindByID(ctx context.Context, id string) (*models.WishlistItem, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	var item models.WishlistItem
	err = r.col.FindOne(ctx, bson.M{"_id": oid}).Decode(&item)
	if err != nil {
		return nil, err
	}
	return &item, nil
}

func (r *WishlistRepository) Update(ctx context.Context, id string, update bson.M) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	_, err = r.col.UpdateOne(ctx, bson.M{"_id": oid}, bson.M{"$set": update})
	return err
}

func (r *WishlistRepository) Delete(ctx context.Context, id string) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	_, err = r.col.DeleteOne(ctx, bson.M{"_id": oid})
	return err
}

func (r *WishlistRepository) FindAll(ctx context.Context) ([]models.WishlistItem, error) {
	opts := options.Find().SetSort(bson.D{{"priority", 1}, {"created_at", -1}})
	cur, err := r.col.Find(ctx, bson.M{}, opts)
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

func (r *WishlistRepository) FindFavorites(ctx context.Context) ([]models.WishlistItem, error) {
	cur, err := r.col.Find(ctx, bson.M{"is_favorite": true})
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
	opts := options.Find().SetSort(bson.D{{"priority", 1}, {"created_at", -1}})
	// All non-purchased items regardless of is_favorite
	cur, err := r.col.Find(ctx, bson.M{"purchased": false}, opts)
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
