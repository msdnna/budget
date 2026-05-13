package models

import "time"

// CategoryIcon is a user-uploaded glyph for a custom category. Referenced
// from `Category.icon` via the synthetic key `custom:<id>`.
//
// Stored inline as a Mongo binary doc rather than GridFS — a single icon is
// always small (PNG ≤ 512×512 with alpha ≈ 100KB worst case; SVG ≤ 64KB),
// and a flat collection keeps reads (which happen on every chart render)
// to a single document lookup.
type CategoryIcon struct {
	ID         string    `bson:"_id" json:"id"`
	MimeType   string    `bson:"mime_type" json:"mime_type"`
	SizeBytes  int       `bson:"size_bytes" json:"size_bytes"`
	Data       []byte    `bson:"data" json:"-"`
	UploadedBy *UserInfo `bson:"uploaded_by,omitempty" json:"uploaded_by,omitempty"`
	UploadedAt time.Time `bson:"uploaded_at" json:"uploaded_at"`
}

// CategoryIconRef is the public read-only view (no binary payload).
type CategoryIconRef struct {
	ID         string    `json:"id"`
	MimeType   string    `json:"mime_type"`
	SizeBytes  int       `json:"size_bytes"`
	UploadedBy *UserInfo `json:"uploaded_by,omitempty"`
	UploadedAt time.Time `json:"uploaded_at"`
}
