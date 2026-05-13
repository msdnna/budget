package models

import (
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
	IconScale      float64    `bson:"icon_scale,omitempty" json:"icon_scale,omitempty"`
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
type UpdateCategoryRequest struct {
	Name      *string  `json:"name,omitempty"`
	Color     *string  `json:"color,omitempty"`
	Icon      *string  `json:"icon,omitempty"`
	IconScale *float64 `json:"icon_scale,omitempty"`
}
