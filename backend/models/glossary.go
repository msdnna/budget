package models

import "time"

// GlossaryEntry — общесемейный «псевдоним»: term → meaning.
// Bot подкидывает все записи в системный промпт LLM на каждом запросе,
// чтобы модель сразу разрешала жаргон / сокращения / клички.
//
// Хранится плоско — нет дерева, нет per-section фильтра. Если их станет
// много (>~100), вернёмся к scope/section, но для семейного MVP избыточно.
type GlossaryEntry struct {
	ID        string    `bson:"_id" json:"id"`
	Term      string    `bson:"term" json:"term"`
	Meaning   string    `bson:"meaning" json:"meaning"`
	CreatedAt time.Time `bson:"created_at" json:"created_at"`
	UpdatedAt time.Time `bson:"updated_at" json:"updated_at"`
}

type CreateGlossaryRequest struct {
	Term    string `json:"term" binding:"required"`
	Meaning string `json:"meaning" binding:"required"`
}

type UpdateGlossaryRequest struct {
	Term    *string `json:"term,omitempty"`
	Meaning *string `json:"meaning,omitempty"`
}
