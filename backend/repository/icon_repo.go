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

type CategoryIconRepository struct {
	col *mongo.Collection
}

func NewCategoryIconRepository(db *mongo.Database) *CategoryIconRepository {
	return &CategoryIconRepository{col: db.Collection("category_icons")}
}

// Create persists the icon and returns the assigned ID.
func (r *CategoryIconRepository) Create(ctx context.Context, mimeType string, data []byte, uploadedBy *models.UserInfo) (*models.CategoryIcon, error) {
	icon := &models.CategoryIcon{
		ID:         uuid.NewString(),
		MimeType:   mimeType,
		SizeBytes:  len(data),
		Data:       data,
		UploadedBy: uploadedBy,
		UploadedAt: time.Now(),
	}
	if _, err := r.col.InsertOne(ctx, icon); err != nil {
		return nil, err
	}
	return icon, nil
}

// FindByID returns the icon including its binary payload.
func (r *CategoryIconRepository) FindByID(ctx context.Context, id string) (*models.CategoryIcon, error) {
	var icon models.CategoryIcon
	if err := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&icon); err != nil {
		return nil, err
	}
	return &icon, nil
}

// List returns icon metadata (without payload) for the admin gallery.
func (r *CategoryIconRepository) List(ctx context.Context) ([]models.CategoryIcon, error) {
	opts := options.Find().
		SetProjection(bson.M{"data": 0}).
		SetSort(bson.D{{Key: "uploaded_at", Value: -1}})
	cur, err := r.col.Find(ctx, bson.M{}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.CategoryIcon
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Delete is a hard delete — icons aren't versioned and aren't sync targets.
// Returns mongo.ErrNoDocuments if the row didn't exist.
func (r *CategoryIconRepository) Delete(ctx context.Context, id string) error {
	res, err := r.col.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if res.DeletedCount == 0 {
		return mongo.ErrNoDocuments
	}
	return nil
}
