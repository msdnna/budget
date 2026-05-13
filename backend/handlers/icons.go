package handlers

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"go.mongodb.org/mongo-driver/mongo"
)

const (
	maxIconBytes = 512 * 1024 // 512KB — generous for 512×512 PNG with alpha
	pngMimeType  = "image/png"
	svgMimeType  = "image/svg+xml"
)

var pngMagic = []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}

type IconHandler struct {
	repo *repository.CategoryIconRepository
}

func NewIconHandler(repo *repository.CategoryIconRepository) *IconHandler {
	return &IconHandler{repo: repo}
}

// Upload godoc
// @Summary      Загрузить пользовательскую иконку категории (admin)
// @Description  Принимает PNG (с альфа-каналом, до 512×512, ≤512KB) или SVG. multipart/form-data, поле `file`. Возвращает метаданные иконки; ссылается на неё `Category.icon = "custom:<id>"`.
// @Tags         icons
// @Accept       mpfd
// @Produce      json
// @Security     BearerAuth
// @Param        file  formData  file  true  "PNG или SVG файл"
// @Success      201   {object}  models.CategoryIcon
// @Failure      400   {object}  map[string]string
// @Failure      401   {object}  map[string]string
// @Failure      403   {object}  map[string]string
// @Failure      413   {object}  map[string]string
// @Router       /icons [post]
func (h *IconHandler) Upload(c *gin.Context) {
	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "поле `file` обязательно"})
		return
	}
	defer func() { _ = file.Close() }()

	if header.Size > maxIconBytes {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "файл больше 512KB"})
		return
	}

	// Read into memory — bounded by maxIconBytes above.
	buf := &bytes.Buffer{}
	if _, err := io.CopyN(buf, file, maxIconBytes+1); err != nil && err != io.EOF {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	data := buf.Bytes()
	if len(data) > maxIconBytes {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "файл больше 512KB"})
		return
	}

	mimeType, err := sniffIconMime(data)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	icon, err := h.repo.Create(ctx, mimeType, data, userInfoFromCtx(c))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, icon)
}

// Serve godoc
// @Summary      Отдать пользовательскую иконку (raw bytes)
// @Description  Возвращает байты PNG/SVG с правильным Content-Type. Открыт всем авторизованным — иконка не несёт чувствительных данных, нужна на каждом графике.
// @Tags         icons
// @Produce      png
// @Security     BearerAuth
// @Param        id   path      string  true  "Icon ID"
// @Success      200  {file}    file
// @Failure      404  {object}  map[string]string
// @Router       /icons/{id} [get]
func (h *IconHandler) Serve(c *gin.Context) {
	id := c.Param("id")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	icon, err := h.repo.FindByID(ctx, id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.Header("Cache-Control", "public, max-age=86400, immutable")
	c.Data(http.StatusOK, icon.MimeType, icon.Data)
}

// List godoc
// @Summary      Список загруженных иконок (admin)
// @Tags         icons
// @Produce      json
// @Security     BearerAuth
// @Success      200  {array}  models.CategoryIconRef
// @Router       /icons [get]
func (h *IconHandler) List(c *gin.Context) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	icons, err := h.repo.List(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	refs := make([]models.CategoryIconRef, 0, len(icons))
	for _, i := range icons {
		refs = append(refs, models.CategoryIconRef{
			ID:         i.ID,
			MimeType:   i.MimeType,
			SizeBytes:  i.SizeBytes,
			UploadedBy: i.UploadedBy,
			UploadedAt: i.UploadedAt,
		})
	}
	c.JSON(http.StatusOK, refs)
}

// Delete godoc
// @Summary      Удалить пользовательскую иконку (admin)
// @Description  Категории, ссылающиеся на удалённую иконку, ничего не сломают — клиент откатится к colored-badge режиму.
// @Tags         icons
// @Produce      json
// @Security     BearerAuth
// @Param        id   path      string  true  "Icon ID"
// @Success      200  {object}  map[string]bool
// @Failure      404  {object}  map[string]string
// @Router       /icons/{id} [delete]
func (h *IconHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := h.repo.Delete(ctx, id); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

// sniffIconMime validates the byte stream as PNG or SVG and returns the
// canonical mime type. Magic-byte sniffing (PNG) + lightweight content
// check (SVG) — no XML parsing because we don't want to choke on namespaced
// or DOCTYPE-prefixed but otherwise valid SVGs.
func sniffIconMime(data []byte) (string, error) {
	if len(data) == 0 {
		return "", errors.New("пустой файл")
	}
	if len(data) >= len(pngMagic) && bytes.Equal(data[:len(pngMagic)], pngMagic) {
		return pngMimeType, nil
	}
	// SVG: leading whitespace, optional <?xml ...?>, optional <!DOCTYPE ...>,
	// eventually a `<svg` tag (case-insensitive). Search the first 1KB.
	head := data
	if len(head) > 1024 {
		head = head[:1024]
	}
	lower := strings.ToLower(string(head))
	if strings.Contains(lower, "<svg") {
		return svgMimeType, nil
	}
	return "", errors.New("поддерживаются только PNG и SVG")
}
