package models

import (
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"
)

// TelegramLink — привязка telegram-аккаунта к budget-пользователю.
// До завершения привязки документ хранит только Code+CodeExpiresAt
// (без TelegramUserID); после `/link/confirm` Code очищается и
// заполняются TelegramUserID + LinkedAt. Уникальные индексы по user_id
// и telegram_user_id гарантируют 1:1.
type TelegramLink struct {
	ID               primitive.ObjectID `bson:"_id,omitempty" json:"-"`
	UserID           string             `bson:"user_id" json:"user_id"`
	TelegramUserID   int64              `bson:"telegram_user_id,omitempty" json:"telegram_user_id,omitempty"`
	TelegramUsername string             `bson:"telegram_username,omitempty" json:"telegram_username,omitempty"`
	Code             string             `bson:"code,omitempty" json:"-"`
	CodeExpiresAt    *time.Time         `bson:"code_expires_at,omitempty" json:"-"`
	LinkedAt         *time.Time         `bson:"linked_at,omitempty" json:"linked_at,omitempty"`
	CreatedAt        time.Time          `bson:"created_at" json:"created_at"`
}

// TelegramLinkInitResponse — выдаётся пользователю после `link/init`,
// показывается в UI вместе с инструкцией «отправьте `/link <code>` боту».
type TelegramLinkInitResponse struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expires_at"`
}

// TelegramLinkStatus — текущее состояние привязки для UI Settings.
// Linked=false → нет активной привязки; иначе показываем TelegramUsername
// + кнопку «Отвязать».
type TelegramLinkStatus struct {
	Linked           bool       `json:"linked"`
	TelegramUserID   int64      `json:"telegram_user_id,omitempty"`
	TelegramUsername string     `json:"telegram_username,omitempty"`
	LinkedAt         *time.Time `json:"linked_at,omitempty"`
}

// TelegramLinkConfirmRequest — приходит от bot-сервиса со service-token.
// Bot принимает `/link CODE` от пользователя и проксирует на бэкенд.
type TelegramLinkConfirmRequest struct {
	Code             string `json:"code" binding:"required"`
	TelegramUserID   int64  `json:"telegram_user_id" binding:"required"`
	TelegramUsername string `json:"telegram_username,omitempty"`
}

// TelegramContextCategory — категория в усечённом виде для bot-контекста.
// Иконку/цвет/limit'ы не отдаём — LLM-парсеру они не нужны.
type TelegramContextCategory struct {
	Name     string   `json:"name"`
	Keywords []string `json:"keywords,omitempty"`
}

// TelegramContextCounterparty — пара «контрагент → категория» из истории
// конкретного пользователя.
type TelegramContextCounterparty struct {
	Counterparty string `json:"counterparty"`
	Category     string `json:"category"`
	Type         string `json:"type"` // "income" | "expense"
	Count        int    `json:"count"`
}

// TelegramContextGlossary — пара term→meaning из общесемейного глоссария.
type TelegramContextGlossary struct {
	Term    string `json:"term"`
	Meaning string `json:"meaning"`
}

// TelegramContextResponse — единая выборка контекста для LLM-парсинга
// (`GET /api/telegram/context?user_id=...`). Один RPC = один промпт.
type TelegramContextResponse struct {
	Expense        []TelegramContextCategory     `json:"expense"`
	Income         []TelegramContextCategory     `json:"income"`
	Glossary       []TelegramContextGlossary     `json:"glossary"`
	Counterparties []TelegramContextCounterparty `json:"counterparties"`
	// IntentTriggers maps an intent name to admin-tuned trigger phrases for
	// the bot's classifier. Only intents with ≥1 phrase are present.
	IntentTriggers map[string][]string `json:"intent_triggers,omitempty"`
}
