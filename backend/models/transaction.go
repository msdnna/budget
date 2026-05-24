package models

import (
	"time"
)

type TransactionType string

const (
	Income         TransactionType = "income"
	Expense        TransactionType = "expense"
	InitialBalance TransactionType = "initial_balance"
)

// DepositType splits the budget into independent scopes so cash spending
// doesn't pollute card-balance reports (and vice versa). Filters across
// statistics/income/expenses/forecast accept it as an optional query param.
type DepositType string

const (
	DepositBank DepositType = "bank"
	DepositCash DepositType = "cash"
)

// NormalizeDeposit defaults blank/unknown values to bank — the historical
// scope every existing record predates this field with, and the most common
// case for new entries.
func NormalizeDeposit(d DepositType) DepositType {
	if d == DepositCash {
		return DepositCash
	}
	return DepositBank
}

type Transaction struct {
	ID             string          `bson:"_id" json:"id"`
	Type           TransactionType `bson:"type" json:"type"`
	Amount         float64         `bson:"amount" json:"amount"`
	Date           time.Time       `bson:"date" json:"date"`
	Category       string          `bson:"category" json:"category"`
	Source         string          `bson:"source,omitempty" json:"source,omitempty"`
	Purpose        string          `bson:"purpose,omitempty" json:"purpose,omitempty"`
	Description    string          `bson:"description,omitempty" json:"description,omitempty"`
	Hidden         bool            `bson:"hidden,omitempty" json:"hidden,omitempty"`
	Deposit        DepositType     `bson:"deposit" json:"deposit"`
	CreatedBy      *UserInfo       `bson:"created_by,omitempty" json:"created_by,omitempty"`
	CreatedAt      time.Time       `bson:"created_at" json:"created_at"`
	Version        int             `bson:"version" json:"version"`
	UpdatedAt      time.Time       `bson:"updated_at" json:"updated_at"`
	DeletedAt      *time.Time      `bson:"deleted_at,omitempty" json:"deleted_at,omitempty"`
	LastModifiedBy *UserInfo       `bson:"last_modified_by,omitempty" json:"last_modified_by,omitempty"`

	// Detail-request linkage. ParentID is set on child transactions; it
	// references the lump-sum parent. DetailRequestID/Status are set on the
	// parent transaction once a detail-request is opened over it.
	// ExcludedFromStats is denormalized so aggregation queries stay simple:
	// while a request is open, children are excluded; once closed, the parent
	// is excluded and the children become canonical.
	ParentID            string `bson:"parent_id,omitempty" json:"parent_id,omitempty"`
	DetailRequestID     string `bson:"detail_request_id,omitempty" json:"detail_request_id,omitempty"`
	DetailRequestStatus string `bson:"detail_request_status,omitempty" json:"detail_request_status,omitempty"`
	ExcludedFromStats   bool   `bson:"excluded_from_stats,omitempty" json:"excluded_from_stats,omitempty"`

	// WishlistID links an expense transaction to a recurring wishlist item.
	// Empty when the transaction is not a recurring-payment fulfillment.
	// Forecast aggregation uses it to decide whether a recurring item is
	// "paid this period" and should be excluded from the projected total.
	WishlistID string `bson:"wishlist_id,omitempty" json:"wishlist_id,omitempty"`
}

type CreateTransactionRequest struct {
	Type        TransactionType `json:"type" binding:"required"`
	Amount      float64         `json:"amount" binding:"required,gt=0"`
	Date        string          `json:"date" binding:"required"`
	Category    string          `json:"category" binding:"required"`
	Source      string          `json:"source"`
	Purpose     string          `json:"purpose"`
	Description string          `json:"description"`
	Deposit     DepositType     `json:"deposit"`
	WishlistID  string          `json:"wishlist_id"`
}

type UpdateTransactionRequest struct {
	Amount      float64     `json:"amount"`
	Date        string      `json:"date"`
	Category    string      `json:"category"`
	Source      string      `json:"source"`
	Purpose     string      `json:"purpose"`
	Description string      `json:"description"`
	Hidden      *bool       `json:"hidden"`
	Deposit     DepositType `json:"deposit"`
	CreatedBy   *UserInfo   `json:"created_by"`
	// Pointer so an empty string ("") is distinguishable from absent — empty
	// string unlinks the transaction from its wishlist item.
	WishlistID *string `json:"wishlist_id"`
}

type TransactionFilter struct {
	Type       string
	From       *time.Time
	To         *time.Time
	Category   string
	Categories []string
	// Deposit restricts the result to a single scope (bank|cash). Empty
	// string means "no filter" — return both.
	Deposit string
	Limit   int64
	Skip    int64
	// IncludeDetailed: when false, parents of closed detail-requests are
	// hidden (they're historical and superseded by their children in stats).
	IncludeDetailed bool
	// Unlinked: when true, restrict to expense transactions that are NOT
	// already attached to a wishlist item, NOT a child of a parent, and NOT
	// closed/parent-of-closed DR. Backs the «привязать к существующему
	// расходу» picker on wishlist/regular items.
	Unlinked bool
}

type CategoryData struct {
	Category   string  `json:"category"`
	Amount     float64 `json:"amount"`
	Percentage float64 `json:"percentage"`
	Count      int32   `json:"count"`
}

type MonthlyData struct {
	Year           int     `json:"year"`
	Month          int     `json:"month"`
	Income         float64 `json:"income"`
	Expense        float64 `json:"expense"`
	Balance        float64 `json:"balance"`
	InitialBalance float64 `json:"initial_balance,omitempty"`
}

type SummaryData struct {
	TotalIncome         float64 `json:"total_income"`
	TotalExpense        float64 `json:"total_expense"`
	Balance             float64 `json:"balance"`
	IncomeCount         int64   `json:"income_count"`
	ExpenseCount        int64   `json:"expense_count"`
	InitialBalance      float64 `json:"initial_balance,omitempty"`
	InitialBalanceCount int64   `json:"initial_balance_count,omitempty"`
}
