package models

import "time"

type DetailRequestStatus string

const (
	DetailRequestOpen   DetailRequestStatus = "open"
	DetailRequestClosed DetailRequestStatus = "closed"
)

type DetailRequest struct {
	ID                  string              `bson:"_id" json:"id"`
	ParentTransactionID string              `bson:"parent_transaction_id" json:"parent_transaction_id"`
	TargetAmount        float64             `bson:"target_amount" json:"target_amount"`
	Assignee            *UserInfo           `bson:"assignee,omitempty" json:"assignee,omitempty"`
	Creator             *UserInfo           `bson:"creator,omitempty" json:"creator,omitempty"`
	Status              DetailRequestStatus `bson:"status" json:"status"`
	CreatedAt           time.Time           `bson:"created_at" json:"created_at"`
	ClosedAt            *time.Time          `bson:"closed_at,omitempty" json:"closed_at,omitempty"`
	UpdatedAt           time.Time           `bson:"updated_at" json:"updated_at"`
}

type CreateDetailRequestPayload struct {
	TransactionID string `json:"transaction_id" binding:"required"`
	AssigneeID    string `json:"assignee_id" binding:"required"`
}

type DetailRequestView struct {
	Request  *DetailRequest `json:"request"`
	Parent   *Transaction   `json:"parent"`
	Children []Transaction  `json:"children"`
}
