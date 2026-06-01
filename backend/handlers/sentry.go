package handlers

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// maxEnvelopeBytes caps the size of a tunnelled Sentry envelope. Browser error
// envelopes are small; this bounds abuse of the public tunnel endpoint.
const maxEnvelopeBytes = 1 << 20 // 1 MiB

// SentryConfigHandler serves the browser's Sentry runtime config and proxies
// ("tunnels") browser events to the upstream Sentry server.
//
// Why a tunnel: the self-hosted Sentry lives on the LAN only. If the browser
// posted events straight to the DSN host, any client off that LAN (or with an
// ad-blocker, which blocks "sentry" hosts) would fail with console errors. With
// the tunnel the browser only ever talks to this same-origin endpoint, and the
// backend — which can reach Sentry — forwards the envelope. The DSN host the
// browser sees is therefore irrelevant.
type SentryConfigHandler struct {
	dsn         string  // frontend DSN handed to the browser
	environment string  // environment tag
	tracesRate  float64 // frontend trace sample rate
	enabled     bool    // false when no frontend DSN configured

	upstreamEnvelope string // resolved <scheme>://<host>/api/<project>/envelope/
	client           *http.Client
}

// NewSentryConfigHandler builds the handler from the frontend DSN. A blank DSN
// (or one that can't be parsed) yields a disabled handler: /api/client-config
// reports Sentry off and /api/sentry-tunnel becomes a no-op 204.
func NewSentryConfigHandler(dsn, environment string, tracesRate float64) *SentryConfigHandler {
	h := &SentryConfigHandler{
		dsn:         dsn,
		environment: environment,
		tracesRate:  tracesRate,
		client:      &http.Client{Timeout: 10 * time.Second},
	}
	if dsn == "" {
		return h
	}
	envelope, err := envelopeURLFromDSN(dsn)
	if err != nil {
		log.Printf("WARNING: SENTRY_FRONTEND_DSN is set but unparseable (%v) — frontend Sentry disabled", err)
		return h
	}
	h.upstreamEnvelope = envelope
	h.enabled = true
	return h
}

// envelopeURLFromDSN turns a Sentry DSN (scheme://key@host/projectID) into the
// upstream envelope ingest URL (scheme://host/api/projectID/envelope/).
func envelopeURLFromDSN(dsn string) (string, error) {
	u, err := url.Parse(dsn)
	if err != nil {
		return "", err
	}
	projectID := strings.Trim(u.Path, "/")
	if u.Host == "" || projectID == "" {
		return "", &url.Error{Op: "parse", URL: dsn, Err: http.ErrNotSupported}
	}
	return u.Scheme + "://" + u.Host + "/api/" + projectID + "/envelope/", nil
}

// ClientConfig godoc
// @Summary      Рантайм-конфиг клиента (Sentry)
// @Description  Публичный. Фронт читает это на старте: если `sentry` не null — инициализирует Sentry с туннелем `/api/sentry-tunnel`, иначе не подключает телеметрию. DSN/окружение задаются env-переменными бэкенда (`SENTRY_FRONTEND_DSN`, `SENTRY_ENV`).
// @Tags         meta
// @Produce      json
// @Success      200  {object}  map[string]interface{}
// @Router       /client-config [get]
func (h *SentryConfigHandler) ClientConfig(c *gin.Context) {
	if !h.enabled {
		c.JSON(http.StatusOK, gin.H{"sentry": nil})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"sentry": gin.H{
			"dsn":              h.dsn,
			"environment":      h.environment,
			"tracesSampleRate": h.tracesRate,
			"tunnel":           "/api/sentry-tunnel",
		},
	})
}

// Tunnel godoc
// @Summary      Sentry tunnel (проксирование событий фронта)
// @Description  Публичный. Принимает Sentry-envelope от браузерного SDK и пересылает его на upstream Sentry, чтобы браузер не обращался к LAN-only Sentry напрямую (обход ad-блокеров + работа вне LAN). No-op (204), если фронтовый Sentry выключен.
// @Tags         meta
// @Accept       plain
// @Success      200  {string}  string  "forwarded"
// @Router       /sentry-tunnel [post]
func (h *SentryConfigHandler) Tunnel(c *gin.Context) {
	if !h.enabled {
		c.Status(http.StatusNoContent)
		return
	}

	body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxEnvelopeBytes))
	if err != nil {
		c.Status(http.StatusBadRequest)
		return
	}

	req, err := http.NewRequestWithContext(c.Request.Context(), http.MethodPost, h.upstreamEnvelope, bytes.NewReader(body))
	if err != nil {
		c.Status(http.StatusBadGateway)
		return
	}
	req.Header.Set("Content-Type", "application/x-sentry-envelope")

	resp, err := h.client.Do(req)
	if err != nil {
		// Upstream Sentry unreachable (e.g. desktop off). Swallow it — telemetry
		// must never surface as an error to the user's browser.
		c.Status(http.StatusOK)
		return
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, resp.Body)
	c.Status(resp.StatusCode)
}
