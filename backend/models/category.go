package models

import (
	"time"
)

type Category struct {
	ID             string     `bson:"_id" json:"id"`
	Section        string     `bson:"section" json:"section"`
	Name           string     `bson:"name" json:"name"`
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
}
