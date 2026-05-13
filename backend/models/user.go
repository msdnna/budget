package models

import (
	"time"

	"github.com/golang-jwt/jwt/v5"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

type User struct {
	ID           primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Login        string             `bson:"login" json:"login"`
	PasswordHash string             `bson:"password_hash" json:"-"`
	DisplayName  string             `bson:"display_name" json:"display_name"`
	AvatarURL    string             `bson:"avatar_url,omitempty" json:"avatar_url,omitempty"`
	IsAdmin      bool               `bson:"is_admin,omitempty" json:"is_admin"`
	CreatedAt    time.Time          `bson:"created_at" json:"created_at"`
}

// UserInfo is embedded in records (denormalized snapshot at creation time).
type UserInfo struct {
	UserID      string `bson:"user_id" json:"user_id"`
	DisplayName string `bson:"display_name" json:"display_name"`
	AvatarURL   string `bson:"avatar_url,omitempty" json:"avatar_url,omitempty"`
}

type LoginRequest struct {
	Login    string `json:"login" binding:"required"`
	Password string `json:"password" binding:"required"`
}

type LoginResponse struct {
	Token        string    `json:"token"`
	RefreshToken string    `json:"refresh_token"`
	UserID       string    `json:"user_id"`
	DisplayName  string    `json:"display_name"`
	AvatarURL    string    `json:"avatar_url,omitempty"`
	IsAdmin      bool      `json:"is_admin"`
	ExpiresAt    time.Time `json:"expires_at"`
}

// Claims is the JWT payload for both access and refresh tokens.
// `TokenType` differentiates them — access is required for protected
// endpoints, refresh only for `POST /auth/refresh`.
type Claims struct {
	UserID      string `json:"user_id"`
	Login       string `json:"login"`
	DisplayName string `json:"display_name"`
	AvatarURL   string `json:"avatar_url,omitempty"`
	IsAdmin     bool   `json:"is_admin,omitempty"`
	TokenType   string `json:"token_type,omitempty"` // "access" | "refresh"
	jwt.RegisteredClaims
}

// Token types — empty/absent counts as "access" so old (pre-refresh)
// tokens stay valid until they expire naturally.
const (
	TokenTypeAccess  = "access"
	TokenTypeRefresh = "refresh"
)

type RefreshRequest struct {
	RefreshToken string `json:"refresh_token" binding:"required"`
}

type RefreshResponse struct {
	Token        string    `json:"token"`
	RefreshToken string    `json:"refresh_token"`
	ExpiresAt    time.Time `json:"expires_at"`
}
