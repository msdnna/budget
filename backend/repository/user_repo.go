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
		{Keys: bson.D{{"login", 1}}, Options: options.Index().SetUnique(true)},
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
