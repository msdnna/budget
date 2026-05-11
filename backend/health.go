package main

import "github.com/gin-gonic/gin"

// healthHandler godoc
// @Summary      Health-check
// @Description  Публичный probe — клиенты (Android `ServerDiscovery`, in-app updates) проверяют, что сервер «свой» по полю `app=msdnna-budget`.
// @Tags         meta
// @Produce      json
// @Success      200  {object}  map[string]interface{}
// @Router       /health [get]
func healthHandler(c *gin.Context) {
	c.JSON(200, gin.H{"ok": true, "app": "msdnna-budget"})
}
