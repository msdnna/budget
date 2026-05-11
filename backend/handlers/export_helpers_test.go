package handlers

import (
	"testing"

	"budget-go/models"
)

func TestTruncate(t *testing.T) {
	cases := []struct {
		in     string
		maxLen int
		want   string
	}{
		{"short", 10, "short"},
		{"exact", 5, "exact"},
		{"too long string", 5, "too …"},
		// Multi-byte runes — len(runes) is what matters, not byte length.
		{"мультибайт", 5, "муль…"},
	}
	for _, c := range cases {
		got := truncate(c.in, c.maxLen)
		if got != c.want {
			t.Errorf("truncate(%q, %d) = %q, want %q", c.in, c.maxLen, got, c.want)
		}
	}
}

func TestTxTypeLabel(t *testing.T) {
	cases := []struct {
		in   models.TransactionType
		want string
	}{
		{models.Income, "Доход"},
		{models.InitialBalance, "Нач. баланс"},
		{models.Expense, "Расход"},
		{models.TransactionType("unknown"), "Расход"},
	}
	for _, c := range cases {
		got := txTypeLabel(c.in)
		if got != c.want {
			t.Errorf("txTypeLabel(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestCellName(t *testing.T) {
	cases := []struct {
		col, row int
		want     string
	}{
		{1, 1, "A1"},
		{2, 1, "B1"},
		{26, 10, "Z10"},
		{27, 1, "AA1"},
	}
	for _, c := range cases {
		if got := cellName(c.col, c.row); got != c.want {
			t.Errorf("cellName(%d,%d) = %q, want %q", c.col, c.row, got, c.want)
		}
	}
}
