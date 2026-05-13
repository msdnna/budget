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

// defaultCategoryPreset is the seed metadata for a single default category.
// Color is a hex string. Icon is a key from the shared icon dictionary
// (see frontend/src/utils/icons.js and android/.../ui/icons/CategoryIcons.kt).
type defaultCategoryPreset struct {
	Name  string
	Color string
	Icon  string
}

// defaultCategories defines the seed set per section. The order is preserved
// for the legacy "expense > Прочее last" ordering some screens rely on.
var defaultCategories = map[string][]defaultCategoryPreset{
	"expense": {
		{"Продукты", "#22C55E", "cart"},
		{"Транспорт", "#3B82F6", "car"},
		{"Жильё/ЖКХ", "#10B981", "home"},
		{"Рестораны", "#F59E0B", "restaurant"},
		{"Развлечения", "#8B5CF6", "game-controller"},
		{"Здоровье", "#EF4444", "medkit"},
		{"Образование", "#0EA5E9", "school"},
		{"Одежда", "#EC4899", "shirt"},
		{"Электроника", "#6366F1", "phone-portrait"},
		{"Путешествия", "#14B8A6", "airplane"},
		{"Связь", "#A855F7", "call"},
		{"Красота", "#F472B6", "rose"},
		{"Спорт", "#F97316", "barbell"},
		{"Прочее", "#64748B", "ellipsis-horizontal"},
	},
	"income": {
		{"Зарплата", "#22C55E", "cash"},
		{"Фриланс", "#3B82F6", "briefcase"},
		{"Инвестиции", "#10B981", "trending-up"},
		{"Бонус", "#F59E0B", "gift"},
		{"Подарок", "#EC4899", "gift"},
		{"Аренда", "#6366F1", "key"},
		{"Прочее", "#64748B", "ellipsis-horizontal"},
	},
	"wishlist": {
		{"Техника", "#6366F1", "desktop"},
		{"Одежда", "#EC4899", "shirt"},
		{"Путешествия", "#14B8A6", "airplane"},
		{"Дом", "#10B981", "home"},
		{"Здоровье", "#EF4444", "medkit"},
		{"Развлечения", "#8B5CF6", "game-controller"},
		{"Прочее", "#64748B", "ellipsis-horizontal"},
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
	for section, presets := range defaultCategories {
		for _, p := range presets {
			filter := bson.M{"section": section, "name": p.Name, "deleted_at": nil}
			now := time.Now()
			count, err := r.col.CountDocuments(ctx, filter)
			if err != nil {
				return err
			}
			if count == 0 {
				_, err = r.col.InsertOne(ctx, models.Category{
					ID:        uuid.NewString(),
					Section:   section,
					Name:      p.Name,
					Color:     p.Color,
					Icon:      p.Icon,
					IsDefault: true,
					CreatedAt: now,
					Version:   1,
					UpdatedAt: now,
				})
				if err != nil && !mongo.IsDuplicateKeyError(err) {
					return err
				}
				continue
			}
			// Backfill color/icon onto pre-existing default rows that were
			// seeded before these fields were introduced. Bumps `version` and
			// `updated_at` so sync clients pick up the metadata.
			backfill := bson.M{"$or": bson.A{
				bson.M{"color": bson.M{"$in": bson.A{nil, ""}}},
				bson.M{"icon": bson.M{"$in": bson.A{nil, ""}}},
			}}
			res, err := r.col.UpdateOne(ctx,
				bson.M{"$and": bson.A{filter, backfill}},
				bson.M{
					"$set": bson.M{
						"color":      p.Color,
						"icon":       p.Icon,
						"updated_at": now,
					},
					"$inc": bson.M{"version": 1},
				},
			)
			if err != nil {
				return err
			}
			_ = res
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

func (r *CategoryRepository) Create(ctx context.Context, section, name, color, icon string, modifiedBy *models.UserInfo) (models.Category, error) {
	now := time.Now()
	cat := models.Category{
		ID:             uuid.NewString(),
		Section:        section,
		Name:           name,
		Color:          color,
		Icon:           icon,
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
