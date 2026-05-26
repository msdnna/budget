package repository

import (
	"context"
	"errors"
	"strings"
	"time"

	"budget-go/models"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

var ErrGlossaryConflict = errors.New("glossary term already exists")

type GlossaryRepository struct {
	col *mongo.Collection
}

func NewGlossaryRepository(db *mongo.Database) *GlossaryRepository {
	col := db.Collection("glossary")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Unique on (case-folded) term so admin doesn't accidentally create
	// "Магнит" + "магнит" twice. We dedupe on the fly by storing the
	// lower-case form alongside the original; the unique index hits the
	// folded copy. Simpler than a partial collation index.
	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "term_lower", Value: 1}}, Options: options.Index().SetUnique(true)},
	})

	return &GlossaryRepository{col: col}
}

func termKey(term string) string { return strings.ToLower(strings.TrimSpace(term)) }

type glossaryDoc struct {
	ID        string    `bson:"_id"`
	Term      string    `bson:"term"`
	TermLower string    `bson:"term_lower"`
	Meaning   string    `bson:"meaning"`
	CreatedAt time.Time `bson:"created_at"`
	UpdatedAt time.Time `bson:"updated_at"`
}

func (d glossaryDoc) toModel() models.GlossaryEntry {
	return models.GlossaryEntry{
		ID:        d.ID,
		Term:      d.Term,
		Meaning:   d.Meaning,
		CreatedAt: d.CreatedAt,
		UpdatedAt: d.UpdatedAt,
	}
}

// List возвращает все записи, отсортированные по term (case-insensitive)
// для стабильного UI-порядка.
func (r *GlossaryRepository) List(ctx context.Context) ([]models.GlossaryEntry, error) {
	opts := options.Find().SetSort(bson.D{{Key: "term_lower", Value: 1}})
	cur, err := r.col.Find(ctx, bson.M{}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var docs []glossaryDoc
	if err := cur.All(ctx, &docs); err != nil {
		return nil, err
	}
	out := make([]models.GlossaryEntry, 0, len(docs))
	for _, d := range docs {
		out = append(out, d.toModel())
	}
	return out, nil
}

func (r *GlossaryRepository) Create(ctx context.Context, term, meaning string) (*models.GlossaryEntry, error) {
	term = strings.TrimSpace(term)
	meaning = strings.TrimSpace(meaning)
	if term == "" || meaning == "" {
		return nil, errors.New("term and meaning required")
	}
	now := time.Now()
	doc := glossaryDoc{
		ID:        uuid.NewString(),
		Term:      term,
		TermLower: termKey(term),
		Meaning:   meaning,
		CreatedAt: now,
		UpdatedAt: now,
	}
	if _, err := r.col.InsertOne(ctx, doc); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil, ErrGlossaryConflict
		}
		return nil, err
	}
	out := doc.toModel()
	return &out, nil
}

func (r *GlossaryRepository) Update(ctx context.Context, id string, req models.UpdateGlossaryRequest) (*models.GlossaryEntry, error) {
	set := bson.M{"updated_at": time.Now()}
	if req.Term != nil {
		t := strings.TrimSpace(*req.Term)
		if t == "" {
			return nil, errors.New("term cannot be empty")
		}
		set["term"] = t
		set["term_lower"] = termKey(t)
	}
	if req.Meaning != nil {
		m := strings.TrimSpace(*req.Meaning)
		if m == "" {
			return nil, errors.New("meaning cannot be empty")
		}
		set["meaning"] = m
	}
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var doc glossaryDoc
	err := r.col.FindOneAndUpdate(ctx, bson.M{"_id": id}, bson.M{"$set": set}, opts).Decode(&doc)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return nil, ErrGlossaryConflict
		}
		return nil, err
	}
	out := doc.toModel()
	return &out, nil
}

func (r *GlossaryRepository) Delete(ctx context.Context, id string) error {
	res, err := r.col.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if res.DeletedCount == 0 {
		return mongo.ErrNoDocuments
	}
	return nil
}
