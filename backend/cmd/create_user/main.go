// create_user creates a new user in the budget database.
// Usage: go run ./cmd/create_user -login alice -password secret -name "Alice Smith" [-avatar "https://..."]
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/joho/godotenv"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"golang.org/x/crypto/bcrypt"
)

func main() {
	_ = godotenv.Load()

	login := flag.String("login", "", "User login (required)")
	password := flag.String("password", "", "User password (required)")
	name := flag.String("name", "", "Display name, e.g. 'Ivan Petrov' (required)")
	avatar := flag.String("avatar", "", "Avatar URL (optional)")
	flag.Parse()

	if *login == "" || *password == "" || *name == "" {
		flag.Usage()
		os.Exit(1)
	}

	mongoURI := getEnv("MONGO_URI", "mongodb://admin:password@localhost:27017/budget?authSource=admin")
	dbName := getEnv("DB_NAME", "budget")

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(mongoURI))
	if err != nil {
		log.Fatalf("connect: %v", err) //nolint:gocritic // short-lived CLI; leaked ctx is harmless
	}
	defer func() { _ = client.Disconnect(ctx) }()

	if err := client.Ping(ctx, nil); err != nil {
		log.Fatalf("ping: %v", err)
	}

	col := client.Database(dbName).Collection("users")

	// Ensure unique index exists
	col.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{Key: "login", Value: 1}},
		Options: options.Index().SetUnique(true),
	})

	hash, err := bcrypt.GenerateFromPassword([]byte(*password), 12)
	if err != nil {
		log.Fatalf("bcrypt: %v", err)
	}

	user := bson.M{
		"_id":           primitive.NewObjectID(),
		"login":         *login,
		"password_hash": string(hash),
		"display_name":  *name,
		"created_at":    time.Now(),
	}
	if *avatar != "" {
		user["avatar_url"] = *avatar
	}

	if _, err := col.InsertOne(ctx, user); err != nil {
		log.Fatalf("insert: %v", err)
	}

	fmt.Printf("User '%s' (%s) created successfully.\n", *login, *name)
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
