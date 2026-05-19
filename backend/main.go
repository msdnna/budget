package main

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
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

//go:embed docs/swagger.json
var swaggerSpec []byte

// @title       msdnna Budget API
// @version     1.0
// @description REST API семейного бюджет-приложения (Go + MongoDB). JWT (Bearer) — защищённые ручки требуют заголовок `Authorization: Bearer <token>`, полученный через `POST /api/auth/login`.
// @contact.name msdnna
// @contact.url  https://github.com/msdnna/budget
// @license.name Apache 2.0
// @license.url  https://www.apache.org/licenses/LICENSE-2.0
// @BasePath     /api
// @securityDefinitions.apikey BearerAuth
// @in           header
// @name         Authorization
// @description  Заголовок вида `Bearer <jwt>` (получить через `/auth/login`).
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
	iconRepo := repository.NewCategoryIconRepository(db)
	drRepo := repository.NewDetailRequestRepository(db)
	notifRepo := repository.NewNotificationRepository(db)

	seedCtx, seedCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer seedCancel()
	if err := catRepo.EnsureDefaults(seedCtx); err != nil {
		log.Printf("Warning: failed to seed default categories: %v", err)
	}
	if err := userRepo.EnsureAdmin(seedCtx); err != nil {
		log.Printf("Warning: failed to bootstrap admin user: %v", err)
	}

	txHandler := handlers.NewTransactionHandler(txRepo)
	statsHandler := handlers.NewStatisticsHandler(txRepo, wlRepo)
	wlHandler := handlers.NewWishlistHandler(wlRepo, txRepo, catRepo)
	exportHandler := handlers.NewExportHandler(txRepo, wlRepo, cfg.FontPath)
	authHandler := handlers.NewAuthHandler(userRepo, cfg)
	catHandler := handlers.NewCategoryHandler(catRepo)
	iconHandler := handlers.NewIconHandler(iconRepo)
	syncHandler := handlers.NewSyncHandler(txRepo, wlRepo, catRepo)
	versionHandler := handlers.NewVersionHandler(appVersion)
	drHandler := handlers.NewDetailRequestHandler(drRepo, txRepo, userRepo)
	userAdminHandler := handlers.NewUserAdminHandler(userRepo)
	limitsHandler := handlers.NewLimitsHandler(catRepo, txRepo)
	notifHandler := handlers.NewNotificationHandler(notifRepo)
	limitChecker := handlers.NewLimitChecker(catRepo, txRepo, notifRepo)
	txHandler.SetLimitChecker(limitChecker)
	setupHandler := handlers.NewSetupHandler(userRepo, authHandler)
	portabilityHandler := handlers.NewPortabilityHandler(db, userRepo)

	r := gin.Default()
	if err := r.SetTrustedProxies([]string{"127.0.0.1", "::1"}); err != nil {
		log.Printf("Warning: failed to set trusted proxies: %v", err)
	}
	r.Use(middleware.CORS())

	// OpenAPI 3.1 spec + Swagger UI (UI грузится с CDN, спека — embed).
	// Выключается env-флагом SWAGGER_ENABLED=false.
	if !strings.EqualFold(os.Getenv("SWAGGER_ENABLED"), "false") {
		spec := bytes.Replace(swaggerSpec, []byte(`"version": "1.0"`), []byte(fmt.Sprintf(`"version": %q`, appVersion)), 1)
		r.GET("/swagger/doc.json", func(c *gin.Context) {
			c.Data(http.StatusOK, "application/json; charset=utf-8", spec)
		})
		r.GET("/swagger/", func(c *gin.Context) { c.Redirect(http.StatusMovedPermanently, "/swagger/index.html") })
		r.GET("/swagger", func(c *gin.Context) { c.Redirect(http.StatusMovedPermanently, "/swagger/index.html") })
		r.GET("/swagger/index.html", func(c *gin.Context) {
			c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(swaggerUIHTML))
		})
	}

	api := r.Group("/api")
	{
		// Public — no auth required
		api.GET("/health", healthHandler)
		api.GET("/version", versionHandler.Get)
		api.POST("/auth/login", authHandler.Login)
		api.POST("/auth/refresh", authHandler.Refresh)
		api.GET("/setup/status", setupHandler.Status)
		api.POST("/setup/init", setupHandler.Init)

		// Protected routes
		protected := api.Group("/")
		protected.Use(middleware.Auth(cfg))
		{
			protected.GET("/auth/me", authHandler.Me)
			protected.POST("/auth/password", userAdminHandler.ChangeOwnPassword)
			protected.GET("/users", authHandler.ListUsers)
			protected.GET("/users/:id/avatar", userAdminHandler.ServeAvatar)

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
			protected.POST("/wishlist/:id/unlink-period", wlHandler.UnlinkPeriod)
			protected.POST("/wishlist/:id/link/:tx_id", wlHandler.LinkExisting)

			protected.GET("/categories", catHandler.List)
			protected.GET("/categories/all", catHandler.ListAll)
			protected.GET("/categories/limits-progress", limitsHandler.Progress)
			protected.POST("/categories", catHandler.Create)
			protected.DELETE("/categories/:id", catHandler.Delete)

			// Icon storage. GET /icons/:id is auth-only but not admin-gated —
			// every authed client renders icons in their charts. Mutating
			// endpoints sit behind admin.
			protected.GET("/icons/:id", iconHandler.Serve)

			admin := protected.Group("/")
			admin.Use(middleware.AdminRequired())
			{
				admin.PATCH("/categories/:id", catHandler.Update)
				admin.POST("/icons", iconHandler.Upload)
				admin.GET("/icons", iconHandler.List)
				admin.DELETE("/icons/:id", iconHandler.Delete)

				admin.GET("/admin/users", userAdminHandler.AdminList)
				admin.POST("/admin/users", userAdminHandler.Create)
				admin.PATCH("/admin/users/:id", userAdminHandler.Update)
				admin.DELETE("/admin/users/:id", userAdminHandler.Delete)
				admin.POST("/admin/users/:id/password", userAdminHandler.SetPassword)
				admin.POST("/admin/users/:id/avatar", userAdminHandler.UploadAvatar)
				admin.DELETE("/admin/users/:id/avatar", userAdminHandler.DeleteAvatar)

				admin.GET("/admin/export", portabilityHandler.Export)
				admin.POST("/admin/import", portabilityHandler.Import)
			}

			protected.GET("/export/excel", exportHandler.Excel)
			protected.GET("/export/pdf", exportHandler.PDF)

			protected.GET("/sync/pull", syncHandler.Pull)
			protected.POST("/sync/push", syncHandler.Push)

			protected.GET("/notifications", notifHandler.List)
			protected.POST("/notifications/read-all", notifHandler.ReadAll)
			protected.POST("/notifications/:id/read", notifHandler.Read)

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
		log.Fatal(err) //nolint:gocritic // seedCancel ran long before this; leaked ctx is harmless on shutdown
	}
}

// swaggerUIHTML — минимальный UI на Swagger UI 5 (поддерживает OpenAPI 3.1) с CDN.
// Спека грузится локально с `/swagger/doc.json`.
const swaggerUIHTML = `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <title>msdnna Budget API</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css">
  <style>body{margin:0}</style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    window.ui = SwaggerUIBundle({
      url: "/swagger/doc.json",
      dom_id: "#swagger-ui",
      deepLinking: true,
      persistAuthorization: true,
      tryItOutEnabled: true
    });
  </script>
</body>
</html>`

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
