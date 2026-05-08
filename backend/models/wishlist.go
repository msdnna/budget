package models

import (
	"time"
)

type Frequency string

const (
	FrequencyOnce      Frequency = "once"
	FrequencyMonthly   Frequency = "monthly"
	FrequencyQuarterly Frequency = "quarterly"
	FrequencyYearly    Frequency = "yearly"
)

type WishlistItem struct {
	ID             string     `bson:"_id" json:"id"`
	Name           string     `bson:"name" json:"name"`
	EstimatedCost  float64    `bson:"estimated_cost" json:"estimated_cost"`
	Category       string     `bson:"category" json:"category"`
	Priority       int        `bson:"priority" json:"priority"`
	Frequency      Frequency  `bson:"frequency" json:"frequency"`
	Purchased      bool       `bson:"purchased" json:"purchased"`
	Notes          string     `bson:"notes,omitempty" json:"notes,omitempty"`
	CreatedBy      *UserInfo  `bson:"created_by,omitempty" json:"created_by,omitempty"`
	CreatedAt      time.Time  `bson:"created_at" json:"created_at"`
	Version        int        `bson:"version" json:"version"`
	UpdatedAt      time.Time  `bson:"updated_at" json:"updated_at"`
	DeletedAt      *time.Time `bson:"deleted_at,omitempty" json:"deleted_at,omitempty"`
	LastModifiedBy *UserInfo  `bson:"last_modified_by,omitempty" json:"last_modified_by,omitempty"`
}

type CreateWishlistRequest struct {
	Name          string    `json:"name" binding:"required"`
	EstimatedCost float64   `json:"estimated_cost" binding:"required,gt=0"`
	Category      string    `json:"category" binding:"required"`
	Priority      int       `json:"priority"`
	Frequency     Frequency `json:"frequency"`
	Purchased     bool      `json:"purchased"`
	Notes         string    `json:"notes"`
}

type UpdateWishlistRequest struct {
	Name          string    `json:"name"`
	EstimatedCost float64   `json:"estimated_cost"`
	Category      string    `json:"category"`
	Priority      int       `json:"priority"`
	Frequency     Frequency `json:"frequency"`
	Purchased     *bool     `json:"purchased"`
	Notes         string    `json:"notes"`
	CreatedBy     *UserInfo `json:"created_by"`
}

type ForecastResponse struct {
	TotalMonthly  float64 `json:"total_monthly"`
	HistoricalAvg float64 `json:"historical_avg"`
	// WishlistContrib is the SUM of contributions from all wishlist items
	// (recurring + one-off). Kept for backward compat with older clients.
	WishlistContrib float64 `json:"wishlist_contrib"`
	// RegularContrib is the subset coming from recurring items only —
	// clients use it to render two separate summary cards
	// «Регулярные расходы / мес» (= regular_contrib) and
	// «Список желаний / мес» (= wishlist_contrib − regular_contrib).
	RegularContrib      float64               `json:"regular_contrib"`
	Breakdown           []CategoryData        `json:"breakdown"`
	RegularItems        []RegularItemForecast `json:"regular_items"`
	UnpurchasedWishlist []WishlistItem        `json:"unpurchased_wishlist"`
}

type RegularItemForecast struct {
	ID            string  `json:"id"`
	Name          string  `json:"name"`
	EstimatedCost float64 `json:"estimated_cost"`
	// MonthlyCost is now the FULL cost per period (not divided across
	// months) — clients display it with a frequency-specific suffix:
	// monthly → "₽/мес", quarterly → "₽/кв", yearly → "₽/год".
	MonthlyCost float64 `json:"monthly_cost"`
	Frequency   string  `json:"frequency"`
	Category    string  `json:"category"`
	// PaidThisPeriod is true when ≥1 linked expense exists in the current
	// calendar period (month/quarter/year per frequency). Used by the UI
	// for the "оплачено · X ₽" badge and strike-through styling.
	PaidThisPeriod bool    `json:"paid_this_period"`
	PaidAmount     float64 `json:"paid_amount"`
	PaidCount      int     `json:"paid_count"`
	// NextDueDate is when the item is next expected to be paid, derived
	// from latest linked transaction + period (or "now" if never paid).
	// Empty when not applicable. Forecast contribution = full
	// estimated_cost when the item is monthly, OR NextDueDate ≤ now+1
	// month — otherwise the recurring schedule is far enough out that
	// it doesn't affect the upcoming month's projection.
	NextDueDate string `json:"next_due_date,omitempty"`
	// Notes mirrors the wishlist item's notes verbatim. Surfaced here so
	// the «Оплачено» flow can prefill the expense form's «Описание»
	// (description) without an extra wishlist lookup on the client.
	Notes string `json:"notes,omitempty"`
}
