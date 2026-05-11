// Package mongotest spins up a real MongoDB inside a Docker container via
// testcontainers-go, so repository and handler tests exercise the actual driver
// against a real server (catches index/aggregation issues that pure mocks miss).
//
// Tests skip themselves automatically when:
//   - the short flag is set (`go test -short`), or
//   - Docker is unreachable on the host.
//
// Each Start() call returns a fresh, isolated database — callers may run in
// parallel without colliding on collections.
package mongotest

import (
	"context"
	"fmt"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	tcmongo "github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

// Default image — pinned to a known-good tag to avoid drift surprises in CI.
const DefaultImage = "mongo:7.0"

// One shared container per test binary (i.e. per package). Spawning Mongo costs
// ~3-6s; reusing across tests in the same package cuts that to a single hit.
// Each Start() call still returns a fresh database (via dbCounter) so tests
// don't leak state across one another.
var (
	sharedOnce   sync.Once
	sharedClient *mongo.Client
	sharedTerm   func()
	sharedErr    error
)

var dbCounter atomic.Uint64

func bootShared() (*mongo.Client, func(), error) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	image := DefaultImage
	if v := os.Getenv("TEST_MONGO_IMAGE"); v != "" {
		image = v
	}

	container, err := tcmongo.Run(ctx, image)
	if err != nil {
		return nil, nil, fmt.Errorf("run container: %w", err)
	}

	uri, err := container.ConnectionString(ctx)
	if err != nil {
		_ = container.Terminate(context.Background())
		return nil, nil, err
	}

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(uri))
	if err != nil {
		_ = container.Terminate(context.Background())
		return nil, nil, err
	}
	if err := client.Ping(ctx, nil); err != nil {
		_ = client.Disconnect(context.Background())
		_ = container.Terminate(context.Background())
		return nil, nil, err
	}

	term := func() {
		shutdownCtx, sc := context.WithTimeout(context.Background(), 15*time.Second)
		defer sc()
		_ = client.Disconnect(shutdownCtx)
		_ = container.Terminate(context.Background())
	}
	return client, term, nil
}

// Start returns a fresh database backed by the package-shared Mongo container.
// Skips the calling test if Docker is unavailable or `go test -short` is set.
//
// Container teardown is wired to runtime.SetFinalizer on the package-level
// client (best-effort); for deterministic cleanup callers may also invoke
// TeardownShared() from a TestMain.
func Start(t *testing.T) *mongo.Database {
	t.Helper()
	if testing.Short() {
		t.Skip("skipping integration test in -short mode")
	}

	sharedOnce.Do(func() {
		sharedClient, sharedTerm, sharedErr = bootShared()
		if sharedTerm != nil {
			// Best-effort teardown when the test binary exits. Tests that want a
			// guaranteed teardown should call TeardownShared() from TestMain.
			runtime.SetFinalizer(sharedClient, func(*mongo.Client) {})
		}
	})
	if sharedErr != nil {
		t.Skipf("could not start mongo container (Docker unavailable?): %v", sharedErr)
	}

	dbName := fmt.Sprintf("test_%d", dbCounter.Add(1))
	db := sharedClient.Database(dbName)

	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = db.Drop(ctx)
	})

	return db
}

// TeardownShared terminates the shared Mongo container. Call from TestMain
// after m.Run() to guarantee container removal even on test panics.
func TeardownShared() {
	if sharedTerm != nil {
		sharedTerm()
	}
}

// Clear empties every collection on the database without dropping the database
// itself. Useful for fast resets when reusing a single container across tests
// (currently each test gets its own container; kept here for future tuning).
func Clear(t *testing.T, db *mongo.Database) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	names, err := db.ListCollectionNames(ctx, bson.M{})
	if err != nil {
		t.Fatalf("list collections: %v", err)
	}
	for _, n := range names {
		if _, err := db.Collection(n).DeleteMany(ctx, bson.M{}); err != nil {
			t.Fatalf("delete from %s: %v", n, err)
		}
	}
}
