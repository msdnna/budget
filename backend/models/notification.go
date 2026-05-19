package models

import "time"

// NotificationType enumerates the events that produce a notification.
// Currently only spending-limit overflows; new types are additive.
type NotificationType string

const (
	NotificationCategoryLimitExceeded NotificationType = "category_limit_exceeded"
	NotificationGlobalLimitExceeded   NotificationType = "global_limit_exceeded"
)

// Notification is a family-wide event. Read-state is per-user via the
// ReadBy array; clients render "unread" if the current user's ID is absent.
//
// Deduplication key = (type, period, category_id). The repo upserts on
// this triple so each category can fire at most once per month for the
// current "exceeded 100%" MVP. The global notification uses an empty
// category_id.
type Notification struct {
	ID         string           `bson:"_id" json:"id"`
	Type       NotificationType `bson:"type" json:"type"`
	Period     string           `bson:"period" json:"period"` // YYYY-MM
	CategoryID string           `bson:"category_id,omitempty" json:"category_id,omitempty"`
	// CategoryName / Limit / Spent snapshot the values at the moment of
	// generation so historical notifications stay readable even after the
	// category is renamed or its limit changes.
	CategoryName string    `bson:"category_name,omitempty" json:"category_name,omitempty"`
	Limit        float64   `bson:"limit" json:"limit"`
	Spent        float64   `bson:"spent" json:"spent"`
	ReadBy       []string  `bson:"read_by,omitempty" json:"read_by,omitempty"`
	CreatedAt    time.Time `bson:"created_at" json:"created_at"`
}
