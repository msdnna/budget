package models

import (
	"encoding/json"
	"time"
)

// SyncEntityType identifies the kind of record carried by a sync operation.
type SyncEntityType string

const (
	SyncEntityTransaction SyncEntityType = "transaction"
	SyncEntityWishlist    SyncEntityType = "wishlist"
	SyncEntityCategory    SyncEntityType = "category"
)

// SyncOpType is the kind of mutation a client wants to apply.
type SyncOpType string

const (
	SyncOpCreate SyncOpType = "create"
	SyncOpUpdate SyncOpType = "update"
	SyncOpDelete SyncOpType = "delete"
)

// SyncOpStatus is the per-operation outcome returned by /api/sync/push.
type SyncOpStatus string

const (
	SyncStatusOK       SyncOpStatus = "ok"
	SyncStatusConflict SyncOpStatus = "conflict"
	SyncStatusError    SyncOpStatus = "error"
)

// SyncOperation is a single mutation submitted by a client.
// Payload is a raw JSON object so each entity can have its own shape; the
// handler unmarshals it based on Entity.
type SyncOperation struct {
	OpID        string          `json:"op_id" binding:"required"`
	Type        SyncOpType      `json:"type" binding:"required"`
	Entity      SyncEntityType  `json:"entity" binding:"required"`
	ID          string          `json:"id" binding:"required"`
	BaseVersion int             `json:"base_version"`
	Force       bool            `json:"force,omitempty"`
	Payload     json.RawMessage `json:"payload,omitempty"`
}

// SyncPushRequest carries a batch of pending operations from a client.
type SyncPushRequest struct {
	Operations []SyncOperation `json:"operations" binding:"required"`
}

// SyncOperationResult is the per-op response in /api/sync/push.
// Record holds the authoritative server document for OK and conflict cases.
type SyncOperationResult struct {
	OpID   string         `json:"op_id"`
	Status SyncOpStatus   `json:"status"`
	Record map[string]any `json:"record,omitempty"`
	Error  string         `json:"error,omitempty"`
}

// SyncPushResponse is the response body of /api/sync/push.
type SyncPushResponse struct {
	Results    []SyncOperationResult `json:"results"`
	ServerTime time.Time             `json:"server_time"`
}

// SyncPullResponse is the response body of /api/sync/pull.
// Records include soft-deleted documents so clients can propagate deletes.
type SyncPullResponse struct {
	Transactions []Transaction  `json:"transactions"`
	Wishlist     []WishlistItem `json:"wishlist"`
	Categories   []Category     `json:"categories"`
	ServerTime   time.Time      `json:"server_time"`
}
