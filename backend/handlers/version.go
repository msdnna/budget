package handlers

import (
	"net/http"
	"os"
	"strings"

	"github.com/gin-gonic/gin"
)

type VersionHandler struct {
	apiVersion         string
	androidLatest      string
	androidMinRequired string
}

func NewVersionHandler(apiVersion string) *VersionHandler {
	return &VersionHandler{
		apiVersion:         apiVersion,
		androidLatest:      strings.TrimSpace(os.Getenv("ANDROID_LATEST")),
		androidMinRequired: strings.TrimSpace(os.Getenv("ANDROID_MIN_REQUIRED")),
	}
}

// Get godoc
// @Summary      Версии: API + последняя/минимальная Android
// @Description  Публичный эндпоинт. `android_latest` / `android_min_required` берутся из env-переменных бэкенда — клиент-обновление и gate-проверка.
// @Tags         meta
// @Produce      json
// @Success      200  {object}  map[string]string
// @Router       /version [get]
func (h *VersionHandler) Get(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"api":                  h.apiVersion,
		"android_latest":       h.androidLatest,
		"android_min_required": h.androidMinRequired,
	})
}
