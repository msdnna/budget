package repository

import (
	"context"
	"strings"
	"time"

	"budget-go/models"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

// IntentTriggerRepository хранит per-intent списки фраз-триггеров для
// telegram-бот-классификатора. Одна запись = одно намерение.
type IntentTriggerRepository struct {
	col *mongo.Collection
}

func NewIntentTriggerRepository(db *mongo.Database) *IntentTriggerRepository {
	col := db.Collection("intent_triggers")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// Unique on intent — ровно одна запись на намерение, upsert по нему.
	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "intent", Value: 1}}, Options: options.Index().SetUnique(true)},
	})
	return &IntentTriggerRepository{col: col}
}

// EnsureDefaults seeds the built-in trigger phrases as ordinary records, once.
// It only inserts a document for an intent that has NONE yet — so an admin who
// later edits or deletes seeded phrases is never overridden on the next boot
// (the seeded phrases behave like any hand-added record). Idempotent.
func (r *IntentTriggerRepository) EnsureDefaults(ctx context.Context) error {
	for _, intent := range models.IntentTriggerKinds {
		count, err := r.col.CountDocuments(ctx, bson.M{"intent": intent})
		if err != nil {
			return err
		}
		if count > 0 {
			continue
		}
		phrases := cleanPhrases(models.DefaultIntentPhrases[intent])
		doc := models.IntentTrigger{
			ID:        uuid.NewString(),
			Intent:    intent,
			Phrases:   phrases,
			UpdatedAt: time.Now(),
		}
		if _, err := r.col.InsertOne(ctx, doc); err != nil && !mongo.IsDuplicateKeyError(err) {
			return err
		}
	}
	return nil
}

// cleanPhrases trims, drops blanks, and de-dupes case-insensitively while
// preserving first-seen order. Keeps the stored list tidy so the bot prompt
// doesn't carry noise.
func cleanPhrases(in []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(in))
	for _, p := range in {
		t := strings.TrimSpace(p)
		if t == "" {
			continue
		}
		key := strings.ToLower(t)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, t)
	}
	return out
}

// List returns every known intent, synthesizing an empty entry for any intent
// that has no document yet — so the admin UI always renders all rows without a
// seeding step.
func (r *IntentTriggerRepository) List(ctx context.Context) ([]models.IntentTrigger, error) {
	cur, err := r.col.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var docs []models.IntentTrigger
	if err := cur.All(ctx, &docs); err != nil {
		return nil, err
	}
	byIntent := make(map[string]models.IntentTrigger, len(docs))
	for _, d := range docs {
		byIntent[d.Intent] = d
	}
	out := make([]models.IntentTrigger, 0, len(models.IntentTriggerKinds))
	for _, kind := range models.IntentTriggerKinds {
		if d, ok := byIntent[kind]; ok {
			if d.Phrases == nil {
				d.Phrases = []string{}
			}
			out = append(out, d)
		} else {
			out = append(out, models.IntentTrigger{Intent: kind, Phrases: []string{}})
		}
	}
	return out, nil
}

// AsMap returns intent → phrases for the telegram context endpoint. Only
// non-empty lists are included to keep the payload compact.
func (r *IntentTriggerRepository) AsMap(ctx context.Context) (map[string][]string, error) {
	items, err := r.List(ctx)
	if err != nil {
		return nil, err
	}
	out := make(map[string][]string, len(items))
	for _, it := range items {
		if len(it.Phrases) > 0 {
			out[it.Intent] = it.Phrases
		}
	}
	return out, nil
}

// Upsert replaces the phrase list for an intent (full-replace, PUT semantics).
func (r *IntentTriggerRepository) Upsert(ctx context.Context, intent string, phrases []string) (*models.IntentTrigger, error) {
	cleaned := cleanPhrases(phrases)
	now := time.Now()
	opts := options.FindOneAndUpdate().
		SetUpsert(true).
		SetReturnDocument(options.After)
	update := bson.M{
		"$set": bson.M{
			"intent":     intent,
			"phrases":    cleaned,
			"updated_at": now,
		},
		"$setOnInsert": bson.M{"_id": uuid.NewString()},
	}
	var doc models.IntentTrigger
	err := r.col.FindOneAndUpdate(ctx, bson.M{"intent": intent}, update, opts).Decode(&doc)
	if err != nil {
		return nil, err
	}
	if doc.Phrases == nil {
		doc.Phrases = []string{}
	}
	return &doc, nil
}
