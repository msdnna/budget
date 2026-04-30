package models

import (
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"
)

type Frequency string

const (
	FrequencyOnce      Frequency = "once"
	FrequencyMonthly   Frequency = "monthly"
	FrequencyQuarterly Frequency = "quarterly"
	FrequencyYearly    Frequency = "yearly"
)

type WishlistItem struct {
	ID            primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Name          string             `bson:"name" json:"name"`
	EstimatedCost float64            `bson:"estimated_cost" json:"estimated_cost"`
	Category      string             `bson:"category" json:"category"`
	Priority      int                `bson:"priority" json:"priority"`
	Frequency     Frequency          `bson:"frequency" json:"frequency"`
	Purchased     bool               `bson:"purchased" json:"purchased"`
	Notes         string             `bson:"notes,omitempty" json:"notes,omitempty"`
	CreatedBy     *UserInfo          `bson:"created_by,omitempty" json:"created_by,omitempty"`
	CreatedAt     time.Time          `bson:"created_at" json:"created_at"`
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
	TotalMonthly        float64               `json:"total_monthly"`
	HistoricalAvg       float64               `json:"historical_avg"`
	WishlistContrib     float64               `json:"wishlist_contrib"`
	Breakdown           []CategoryData        `json:"breakdown"`
	RegularItems        []RegularItemForecast `json:"regular_items"`
	UnpurchasedWishlist []WishlistItem        `json:"unpurchased_wishlist"`
}

type RegularItemForecast struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	MonthlyCost float64 `json:"monthly_cost"`
	Frequency   string  `json:"frequency"`
	Category    string  `json:"category"`
}
