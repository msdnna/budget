package mongotest

import (
	"context"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson"
)

func TestStart_RoundTrip(t *testing.T) {
	db := Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col := db.Collection("smoke")
	if _, err := col.InsertOne(ctx, bson.M{"k": "v"}); err != nil {
		t.Fatalf("insert: %v", err)
	}
	var got bson.M
	if err := col.FindOne(ctx, bson.M{"k": "v"}).Decode(&got); err != nil {
		t.Fatalf("find: %v", err)
	}
	if got["k"] != "v" {
		t.Errorf("got %v", got)
	}
}
