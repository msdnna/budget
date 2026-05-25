// Migration: convert existing Mongo records (transactions, wishlist, categories)
// from ObjectID `_id` to UUID string `_id`, and backfill the new sync metadata
// fields: version=1, updated_at=created_at (or now), deleted_at=null,
// last_modified_by=created_by.
//
// Idempotent: documents whose _id is already a string are skipped. Run any
// number of times safely.
//
// Usage:
//
//	go run ./cmd/migrate
//
// Reads MongoDB URI/DB from .env or env (MONGO_URI, MONGO_DB).
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"budget-go/config"

	"github.com/google/uuid"
	"github.com/joho/godotenv"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

var collectionsToMigrate = []string{"transactions", "wishlist", "categories"}

func main() {
	// .env lives in the repo root, but `go run ./cmd/migrate` from backend/
	// has CWD=backend/. Call Load() per-path because godotenv returns early
	// on the first missing file when a list is passed — a single bad path
	// would silently skip the rest. godotenv won't overwrite already-set
	// keys, so the first hit wins. Inside the Docker image neither file
	// exists and config falls back to process env (compose env_file).
	for _, p := range []string{".env", "../.env"} {
		_ = godotenv.Load(p)
	}
	cfg := config.New()

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(cfg.MongoURI))
	if err != nil {
		log.Fatalf("connect: %v", err) //nolint:gocritic // short-lived CLI; leaked ctx is harmless
	}
	defer client.Disconnect(context.Background())

	if err := client.Ping(ctx, nil); err != nil {
		log.Fatalf("ping: %v", err)
	}

	db := client.Database(cfg.DBName)

	for _, name := range collectionsToMigrate {
		if err := migrateCollection(ctx, db.Collection(name), name); err != nil {
			log.Fatalf("migrate %s: %v", name, err)
		}
	}

	// Deposit backfill — all transactions and wishlist items predating the
	// deposit split land in the "bank" scope by default.
	for _, name := range []string{"transactions", "wishlist"} {
		if err := backfillDeposit(ctx, db.Collection(name), name); err != nil {
			log.Fatalf("backfill deposit %s: %v", name, err)
		}
	}

	fmt.Println("✅ Migration complete.")
	os.Exit(0)
}

// backfillDeposit sets deposit="bank" on any record where the field is
// absent. Idempotent and safe to re-run after every schema change that
// expects this default.
func backfillDeposit(ctx context.Context, col *mongo.Collection, name string) error {
	filter := bson.M{"$or": []bson.M{
		{"deposit": bson.M{"$exists": false}},
		{"deposit": ""},
		{"deposit": nil},
	}}
	res, err := col.UpdateMany(ctx, filter, bson.M{"$set": bson.M{"deposit": "bank"}})
	if err != nil {
		return err
	}
	fmt.Printf("  %s: deposit backfilled=%d\n", name, res.ModifiedCount)
	return nil
}

func migrateCollection(ctx context.Context, col *mongo.Collection, name string) error {
	cursor, err := col.Find(ctx, bson.M{})
	if err != nil {
		return err
	}
	defer cursor.Close(ctx)

	migrated, skipped := 0, 0
	now := time.Now()

	for cursor.Next(ctx) {
		var doc bson.M
		if err := cursor.Decode(&doc); err != nil {
			return err
		}

		// Skip if _id is already a string (UUID from previous run).
		if _, isStr := doc["_id"].(string); isStr {
			// Backfill sync metadata if missing.
			if _, has := doc["version"]; !has {
				if err := backfillMetadata(ctx, col, doc, now); err != nil {
					return err
				}
				migrated++
			} else {
				skipped++
			}
			continue
		}

		oldID, ok := doc["_id"].(primitive.ObjectID)
		if !ok {
			fmt.Printf("  %s: unexpected _id type %T, skipping\n", name, doc["_id"])
			skipped++
			continue
		}

		// If a UUID-keyed counterpart already exists (e.g. backend booted and
		// re-seeded defaults via EnsureDefaults before migration ran), drop the
		// old ObjectID-keyed row instead of inserting a duplicate that would
		// trip the unique (section, name) index on categories.
		if name == "categories" {
			section, _ := doc["section"].(string)
			catName, _ := doc["name"].(string)
			if section != "" && catName != "" {
				dupFilter := bson.M{
					"_id":     bson.M{"$type": "string"},
					"section": section,
					"name":    catName,
				}
				if cnt, err := col.CountDocuments(ctx, dupFilter); err == nil && cnt > 0 {
					if _, err := col.DeleteOne(ctx, bson.M{"_id": oldID}); err != nil {
						return fmt.Errorf("delete legacy duplicate: %w", err)
					}
					skipped++
					continue
				}
			}
		}

		newID := uuid.NewString()
		doc["_id"] = newID

		// Backfill sync metadata.
		if _, has := doc["version"]; !has {
			doc["version"] = 1
		}
		if _, has := doc["updated_at"]; !has {
			if ca, ok := doc["created_at"].(primitive.DateTime); ok {
				doc["updated_at"] = ca.Time()
			} else {
				doc["updated_at"] = now
			}
		}
		if _, has := doc["deleted_at"]; !has {
			doc["deleted_at"] = nil
		}
		if _, has := doc["last_modified_by"]; !has {
			if cb, ok := doc["created_by"]; ok {
				doc["last_modified_by"] = cb
			}
		}

		// Insert under new ID + delete the old ObjectID-keyed doc. If a
		// duplicate-key error fires (e.g. backend already booted and
		// re-seeded a UUID-keyed equivalent via EnsureDefaults), the old
		// row is now redundant — drop it and move on.
		if _, err := col.InsertOne(ctx, doc); err != nil {
			if mongo.IsDuplicateKeyError(err) {
				if _, dErr := col.DeleteOne(ctx, bson.M{"_id": oldID}); dErr != nil {
					return fmt.Errorf("delete legacy duplicate after dup-key: %w", dErr)
				}
				skipped++
				fmt.Printf("  %s: dup-key on insert, dropped legacy ObjectID row %v\n", name, oldID.Hex())
				continue
			}
			return fmt.Errorf("insert new doc: %w", err)
		}
		if _, err := col.DeleteOne(ctx, bson.M{"_id": oldID}); err != nil {
			return fmt.Errorf("delete old doc: %w", err)
		}
		migrated++
	}

	if err := cursor.Err(); err != nil {
		return err
	}

	fmt.Printf("  %s: migrated=%d, skipped=%d\n", name, migrated, skipped)
	return nil
}

func backfillMetadata(ctx context.Context, col *mongo.Collection, doc bson.M, now time.Time) error {
	update := bson.M{}
	if _, has := doc["version"]; !has {
		update["version"] = 1
	}
	if _, has := doc["updated_at"]; !has {
		if ca, ok := doc["created_at"].(primitive.DateTime); ok {
			update["updated_at"] = ca.Time()
		} else {
			update["updated_at"] = now
		}
	}
	if _, has := doc["deleted_at"]; !has {
		update["deleted_at"] = nil
	}
	if _, has := doc["last_modified_by"]; !has {
		if cb, ok := doc["created_by"]; ok {
			update["last_modified_by"] = cb
		}
	}
	if len(update) == 0 {
		return nil
	}
	_, err := col.UpdateOne(ctx, bson.M{"_id": doc["_id"]}, bson.M{"$set": update})
	return err
}
