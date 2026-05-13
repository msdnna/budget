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

func (r *UserRepository) FindByLogin(ctx context.Context, login string) (*models.User, error) {
	var u models.User
	err := r.col.FindOne(ctx, bson.M{"login": login}).Decode(&u)
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

func (r *UserRepository) FindAll(ctx context.Context) ([]models.User, error) {
	cursor, err := r.col.Find(ctx, bson.M{})
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
	count, err := r.col.CountDocuments(ctx, bson.M{"is_admin": true})
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
	if err := r.col.FindOne(ctx, bson.M{}, opts).Decode(&u); err != nil {
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
