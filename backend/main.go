package main

import (
	"context"
	"log"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"budget-go/config"
	"budget-go/handlers"
	"budget-go/middleware"
	"budget-go/repository"
)

func main() {
	_ = godotenv.Load()

	cfg := config.New()

	client := connectMongo(cfg.MongoURI)
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		client.Disconnect(ctx)
	}()

	db := client.Database(cfg.DBName)

	txRepo := repository.NewTransactionRepository(db)
	wlRepo := repository.NewWishlistRepository(db)
	userRepo := repository.NewUserRepository(db)
	catRepo := repository.NewCategoryRepository(db)
	drRepo := repository.NewDetailRequestRepository(db)

	seedCtx, seedCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer seedCancel()
	if err := catRepo.EnsureDefaults(seedCtx); err != nil {
		log.Printf("Warning: failed to seed default categories: %v", err)
	}

	txHandler := handlers.NewTransactionHandler(txRepo)
	statsHandler := handlers.NewStatisticsHandler(txRepo, wlRepo)
	wlHandler := handlers.NewWishlistHandler(wlRepo)
	exportHandler := handlers.NewExportHandler(txRepo, wlRepo, cfg.FontPath)
	authHandler := handlers.NewAuthHandler(userRepo, cfg)
	catHandler := handlers.NewCategoryHandler(catRepo)
	syncHandler := handlers.NewSyncHandler(txRepo, wlRepo, catRepo)
	versionHandler := handlers.NewVersionHandler(appVersion)
	drHandler := handlers.NewDetailRequestHandler(drRepo, txRepo, userRepo)

	r := gin.Default()
	r.SetTrustedProxies([]string{"127.0.0.1", "::1"})
	r.Use(middleware.CORS())

	api := r.Group("/api")
	{
		// Public — no auth required
		api.GET("/health", func(c *gin.Context) {
			c.JSON(200, gin.H{"ok": true, "app": "msdnna-budget"})
		})
		api.GET("/version", versionHandler.Get)
		api.POST("/auth/login", authHandler.Login)

		// Protected routes
		protected := api.Group("/")
		protected.Use(middleware.Auth(cfg))
		{
			protected.GET("/auth/me", authHandler.Me)
			protected.GET("/users", authHandler.ListUsers)

			protected.POST("/transactions", txHandler.Create)
			protected.GET("/transactions", txHandler.List)
			protected.PUT("/transactions/:id", txHandler.Update)
			protected.DELETE("/transactions/:id", txHandler.Delete)

			protected.GET("/statistics/summary", statsHandler.Summary)
			protected.GET("/statistics/by-category", statsHandler.ByCategory)
			protected.GET("/statistics/monthly", statsHandler.Monthly)
			protected.GET("/statistics/forecast", statsHandler.Forecast)
			protected.GET("/statistics/overview", statsHandler.Overview)

			protected.POST("/wishlist", wlHandler.Create)
			protected.GET("/wishlist", wlHandler.List)
			protected.PUT("/wishlist/:id", wlHandler.Update)
			protected.DELETE("/wishlist/:id", wlHandler.Delete)

			protected.GET("/categories", catHandler.List)
			protected.GET("/categories/all", catHandler.ListAll)
			protected.POST("/categories", catHandler.Create)
			protected.DELETE("/categories/:id", catHandler.Delete)

			protected.GET("/export/excel", exportHandler.Excel)
			protected.GET("/export/pdf", exportHandler.PDF)

			protected.GET("/sync/pull", syncHandler.Pull)
			protected.POST("/sync/push", syncHandler.Push)

			protected.POST("/detail-requests", drHandler.Create)
			protected.GET("/detail-requests", drHandler.List)
			protected.GET("/detail-requests/:id", drHandler.Get)
			protected.POST("/detail-requests/:id/transactions", drHandler.AddChild)
			protected.POST("/detail-requests/:id/close", drHandler.Close)
			protected.POST("/detail-requests/:id/cancel", drHandler.Cancel)
		}
	}

	log.Printf("Server starting on :%s", cfg.Port)
	if err := r.Run(":" + cfg.Port); err != nil {
		log.Fatal(err)
	}
}

func connectMongo(uri string) *mongo.Client {
	var client *mongo.Client
	var err error

	for i := 0; i < 10; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		client, err = mongo.Connect(ctx, options.Client().ApplyURI(uri))
		cancel()
		if err != nil {
			log.Printf("MongoDB connect attempt %d failed: %v", i+1, err)
			time.Sleep(3 * time.Second)
			continue
		}

		ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
		err = client.Ping(ctx, nil)
		cancel()
		if err == nil {
			log.Println("Connected to MongoDB")
			return client
		}

		log.Printf("MongoDB ping attempt %d failed: %v", i+1, err)
		time.Sleep(3 * time.Second)
	}

	log.Fatal("Failed to connect to MongoDB after 10 attempts")
	return nil
}
