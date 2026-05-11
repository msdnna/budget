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

var defaultCategories = map[string][]string{
	"expense": {
		"Продукты", "Транспорт", "Жильё/ЖКХ", "Рестораны", "Развлечения",
		"Здоровье", "Образование", "Одежда", "Электроника", "Путешествия",
		"Связь", "Красота", "Спорт", "Прочее",
	},
	"income": {
		"Зарплата", "Фриланс", "Инвестиции", "Бонус", "Подарок", "Аренда", "Прочее",
	},
	"wishlist": {
		"Техника", "Одежда", "Путешествия", "Дом", "Здоровье", "Развлечения", "Прочее",
	},
}

type CategoryRepository struct {
	col *mongo.Collection
}

func NewCategoryRepository(db *mongo.Database) *CategoryRepository {
	col := db.Collection("categories")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "section", Value: 1}, {Key: "name", Value: 1}},
			Options: options.Index().SetUnique(true).SetPartialFilterExpression(bson.M{"deleted_at": nil}),
		},
		{Keys: bson.D{{Key: "updated_at", Value: 1}}},
		{Keys: bson.D{{Key: "deleted_at", Value: 1}}},
	})

	return &CategoryRepository{col: col}
}

func (r *CategoryRepository) EnsureDefaults(ctx context.Context) error {
	for section, names := range defaultCategories {
		for _, name := range names {
			filter := bson.M{"section": section, "name": name, "deleted_at": nil}
			count, err := r.col.CountDocuments(ctx, filter)
			if err != nil {
				return err
			}
			if count == 0 {
				now := time.Now()
				_, err = r.col.InsertOne(ctx, models.Category{
					ID:        uuid.NewString(),
					Section:   section,
					Name:      name,
					IsDefault: true,
					CreatedAt: now,
					Version:   1,
					UpdatedAt: now,
				})
				if err != nil && !mongo.IsDuplicateKeyError(err) {
					return err
				}
			}
		}
	}
	return nil
}

func (r *CategoryRepository) List(ctx context.Context, section string) ([]models.Category, error) {
	filter := bson.M{"section": section, "deleted_at": nil}
	opts := options.Find().SetSort(bson.D{{Key: "is_default", Value: -1}, {Key: "name", Value: 1}})

	cursor, err := r.col.Find(ctx, filter, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var cats []models.Category
	if err := cursor.All(ctx, &cats); err != nil {
		return nil, err
	}
	return cats, nil
}

func (r *CategoryRepository) Create(ctx context.Context, section, name string, modifiedBy *models.UserInfo) (models.Category, error) {
	now := time.Now()
	cat := models.Category{
		ID:             uuid.NewString(),
		Section:        section,
		Name:           name,
		IsDefault:      false,
		CreatedAt:      now,
		Version:        1,
		UpdatedAt:      now,
		LastModifiedBy: modifiedBy,
	}

	_, err := r.col.InsertOne(ctx, cat)
	if err != nil {
		return models.Category{}, err
	}
	return cat, nil
}

// Delete is a soft delete. Default categories may not be deleted.
func (r *CategoryRepository) Delete(ctx context.Context, id string, baseVersion int, modifiedBy *models.UserInfo) (*models.Category, error) {
	now := time.Now()
	filter := bson.M{"_id": id, "is_default": false, "deleted_at": nil}
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
			var existing models.Category
			if findErr := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&existing); findErr == nil {
				if existing.IsDefault {
					return nil, mongo.ErrNoDocuments
				}
				return nil, ErrConflict
			}
			return nil, mongo.ErrNoDocuments
		}
		return nil, err
	}
	var c models.Category
	if err := res.Decode(&c); err != nil {
		return nil, err
	}
	return &c, nil
}

func (r *CategoryRepository) FindByID(ctx context.Context, id string) (*models.Category, error) {
	var c models.Category
	err := r.col.FindOne(ctx, bson.M{"_id": id, "deleted_at": nil}).Decode(&c)
	if err != nil {
		return nil, err
	}
	return &c, nil
}

func (r *CategoryRepository) Upsert(ctx context.Context, c *models.Category, baseVersion int, isCreate bool) (*models.Category, error) {
	now := time.Now()
	c.UpdatedAt = now

	if isCreate {
		if c.CreatedAt.IsZero() {
			c.CreatedAt = now
		}
		c.Version = 1
		c.DeletedAt = nil
		_, err := r.col.InsertOne(ctx, c)
		if err != nil {
			if mongo.IsDuplicateKeyError(err) {
				return nil, ErrConflict
			}
			return nil, err
		}
		return c, nil
	}

	filter := bson.M{"_id": c.ID, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	c.Version = baseVersion + 1
	res := r.col.FindOneAndReplace(ctx, filter, c,
		options.FindOneAndReplace().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrConflict
		}
		return nil, err
	}
	var out models.Category
	if err := res.Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (r *CategoryRepository) FindModifiedSince(ctx context.Context, since time.Time) ([]models.Category, error) {
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
	var out []models.Category
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}
