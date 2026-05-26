package models

import (
	"bytes"
	"encoding/json"
	"strconv"
	"time"
)

type Category struct {
	ID      string `bson:"_id" json:"id"`
	Section string `bson:"section" json:"section"`
	Name    string `bson:"name" json:"name"`
	Color   string `bson:"color,omitempty" json:"color,omitempty"`
	Icon    string `bson:"icon,omitempty" json:"icon,omitempty"`
	// IconScale lets the admin enlarge a category icon inside its colored
	// badge (legend/lists). 0 (omitempty) = client uses default; 1.0 =
	// default size; >1 = upscaled and clipped to badge shape; <1 = shrunk.
	// Pie chart slice icons ignore this — they're sized for arc readability.
	IconScale float64 `bson:"icon_scale,omitempty" json:"icon_scale,omitempty"`
	// MonthlyLimit caps spending for an expense category over the current
	// calendar month (1st–end). Nil = no limit tracked. Only meaningful for
	// section="expense"; ignored for income/wishlist.
	MonthlyLimit *float64 `bson:"monthly_limit,omitempty" json:"monthly_limit,omitempty"`
	// Keywords — подсказки для LLM-парсера (telegram-бот). Каждое слово
	// повышает приоритет этой категории, если встречается в тексте. Поле
	// видно всем (отдаётся через /categories/all), но редактируется только
	// админом — категории общие для семьи.
	Keywords       []string   `bson:"keywords,omitempty" json:"keywords,omitempty"`
	IsDefault      bool       `bson:"is_default" json:"is_default"`
	CreatedAt      time.Time  `bson:"created_at" json:"created_at"`
	Version        int        `bson:"version" json:"version"`
	UpdatedAt      time.Time  `bson:"updated_at" json:"updated_at"`
	DeletedAt      *time.Time `bson:"deleted_at,omitempty" json:"deleted_at,omitempty"`
	LastModifiedBy *UserInfo  `bson:"last_modified_by,omitempty" json:"last_modified_by,omitempty"`
}

type CreateCategoryRequest struct {
	Section string `json:"section" binding:"required"`
	Name    string `json:"name" binding:"required"`
	Color   string `json:"color,omitempty"`
	Icon    string `json:"icon,omitempty"`
}

// UpdateCategoryRequest carries the editable fields. All are optional:
// nil pointer = leave unchanged; empty string / zero scale = clear back to
// client default. Used by the admin PATCH /categories/:id endpoint.
//
// MonthlyLimit uses NullableFloat so the JSON tri-state is meaningful:
// key absent = leave unchanged; `null` = clear the limit; number = set.
// (Go's stdlib json folds absent + null into nil for plain pointer types,
// so we need a custom unmarshaler to tell them apart.)
type UpdateCategoryRequest struct {
	Name         *string       `json:"name,omitempty"`
	Color        *string       `json:"color,omitempty"`
	Icon         *string       `json:"icon,omitempty"`
	IconScale    *float64      `json:"icon_scale,omitempty"`
	MonthlyLimit NullableFloat `json:"monthly_limit,omitempty"`
	// Keywords — полная замена списка подсказок. nil = не трогать;
	// `[]` (пустой массив) = очистить. JSON-разница absent vs `[]`
	// различается на уровне декодера: encoding/json даёт nil-slice для
	// absent и empty-slice для `[]`, чем мы и пользуемся.
	Keywords *[]string `json:"keywords,omitempty"`
}

// NullableFloat preserves the JSON tri-state needed for "set / clear /
// leave alone" PATCH semantics. Present = true means the key appeared in
// the body; Valid = false means it was explicitly `null` (i.e. clear).
type NullableFloat struct {
	Present bool
	Valid   bool
	Value   float64
}

func (n *NullableFloat) UnmarshalJSON(b []byte) error {
	n.Present = true
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		n.Valid = false
		return nil
	}
	v, err := strconv.ParseFloat(string(bytes.TrimSpace(b)), 64)
	if err != nil {
		// Fallback for JSON numbers that strconv refuses (e.g. exponent
		// notation edge cases — let encoding/json handle it).
		var f float64
		if err2 := json.Unmarshal(b, &f); err2 != nil {
			return err
		}
		v = f
	}
	n.Valid = true
	n.Value = v
	return nil
}
