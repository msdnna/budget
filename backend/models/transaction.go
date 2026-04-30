package models

import (
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"
)

type TransactionType string

const (
	Income  TransactionType = "income"
	Expense TransactionType = "expense"
)

type Transaction struct {
	ID          primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Type        TransactionType    `bson:"type" json:"type"`
	Amount      float64            `bson:"amount" json:"amount"`
	Date        time.Time          `bson:"date" json:"date"`
	Category    string             `bson:"category" json:"category"`
	Source      string             `bson:"source,omitempty" json:"source,omitempty"`
	Purpose     string             `bson:"purpose,omitempty" json:"purpose,omitempty"`
	Description string             `bson:"description,omitempty" json:"description,omitempty"`
	Hidden      bool               `bson:"hidden,omitempty" json:"hidden,omitempty"`
	CreatedBy   *UserInfo          `bson:"created_by,omitempty" json:"created_by,omitempty"`
	CreatedAt   time.Time          `bson:"created_at" json:"created_at"`
}

type CreateTransactionRequest struct {
	Type        TransactionType `json:"type" binding:"required"`
	Amount      float64         `json:"amount" binding:"required,gt=0"`
	Date        string          `json:"date" binding:"required"`
	Category    string          `json:"category" binding:"required"`
	Source      string          `json:"source"`
	Purpose     string          `json:"purpose"`
	Description string          `json:"description"`
}

type UpdateTransactionRequest struct {
	Amount      float64   `json:"amount"`
	Date        string    `json:"date"`
	Category    string    `json:"category"`
	Source      string    `json:"source"`
	Purpose     string    `json:"purpose"`
	Description string    `json:"description"`
	Hidden      *bool     `json:"hidden"`
	CreatedBy   *UserInfo `json:"created_by"`
}

type TransactionFilter struct {
	Type     string
	From     *time.Time
	To       *time.Time
	Category string
	Limit    int64
	Skip     int64
}

type CategoryData struct {
	Category   string  `json:"category"`
	Amount     float64 `json:"amount"`
	Percentage float64 `json:"percentage"`
	Count      int32   `json:"count"`
}

type MonthlyData struct {
	Month   int     `json:"month"`
	Income  float64 `json:"income"`
	Expense float64 `json:"expense"`
	Balance float64 `json:"balance"`
}

type SummaryData struct {
	TotalIncome   float64 `json:"total_income"`
	TotalExpense  float64 `json:"total_expense"`
	Balance       float64 `json:"balance"`
	IncomeCount   int64   `json:"income_count"`
	ExpenseCount  int64   `json:"expense_count"`
}
