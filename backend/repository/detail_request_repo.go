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

type DetailRequestRepository struct {
	col *mongo.Collection
}

func NewDetailRequestRepository(db *mongo.Database) *DetailRequestRepository {
	col := db.Collection("detail_requests")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{"parent_transaction_id", 1}}},
		{Keys: bson.D{{"assignee.user_id", 1}, {"status", 1}}},
		{Keys: bson.D{{"creator.user_id", 1}}},
		{Keys: bson.D{{"status", 1}}},
	})
	return &DetailRequestRepository{col: col}
}

func (r *DetailRequestRepository) Create(ctx context.Context, dr *models.DetailRequest) error {
	now := time.Now()
	if dr.ID == "" {
		dr.ID = uuid.NewString()
	}
	if dr.CreatedAt.IsZero() {
		dr.CreatedAt = now
	}
	dr.UpdatedAt = now
	if dr.Status == "" {
		dr.Status = models.DetailRequestOpen
	}
	_, err := r.col.InsertOne(ctx, dr)
	return err
}

func (r *DetailRequestRepository) FindByID(ctx context.Context, id string) (*models.DetailRequest, error) {
	var dr models.DetailRequest
	err := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&dr)
	if err != nil {
		return nil, err
	}
	return &dr, nil
}

type DetailRequestFilter struct {
	AssigneeID string
	CreatorID  string
	Status     string
}

func (r *DetailRequestRepository) Find(ctx context.Context, f DetailRequestFilter) ([]models.DetailRequest, error) {
	filter := bson.M{}
	if f.AssigneeID != "" {
		filter["assignee.user_id"] = f.AssigneeID
	}
	if f.CreatorID != "" {
		filter["creator.user_id"] = f.CreatorID
	}
	if f.Status != "" {
		filter["status"] = f.Status
	}
	cur, err := r.col.Find(ctx, filter, options.Find().SetSort(bson.D{{"created_at", -1}}))
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.DetailRequest
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []models.DetailRequest{}
	}
	return out, nil
}

func (r *DetailRequestRepository) FindByParentTxID(ctx context.Context, parentID string) (*models.DetailRequest, error) {
	var dr models.DetailRequest
	err := r.col.FindOne(ctx, bson.M{"parent_transaction_id": parentID}).Decode(&dr)
	if err != nil {
		return nil, err
	}
	return &dr, nil
}

func (r *DetailRequestRepository) SetStatus(ctx context.Context, id string, status models.DetailRequestStatus) (*models.DetailRequest, error) {
	now := time.Now()
	update := bson.M{"status": status, "updated_at": now}
	if status == models.DetailRequestClosed {
		update["closed_at"] = now
	}
	res := r.col.FindOneAndUpdate(ctx, bson.M{"_id": id}, bson.M{"$set": update},
		options.FindOneAndUpdate().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		return nil, err
	}
	var dr models.DetailRequest
	if err := res.Decode(&dr); err != nil {
		return nil, err
	}
	return &dr, nil
}

func (r *DetailRequestRepository) Delete(ctx context.Context, id string) error {
	_, err := r.col.DeleteOne(ctx, bson.M{"_id": id})
	return err
}
