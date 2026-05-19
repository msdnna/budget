package models_test

import (
	"encoding/json"
	"testing"

	"budget-go/models"
)

func TestNullableFloat_TriState(t *testing.T) {
	cases := []struct {
		name        string
		body        string
		wantPresent bool
		wantValid   bool
		wantValue   float64
	}{
		{name: "absent", body: `{}`, wantPresent: false, wantValid: false, wantValue: 0},
		{name: "null", body: `{"monthly_limit":null}`, wantPresent: true, wantValid: false, wantValue: 0},
		{name: "number", body: `{"monthly_limit":1500}`, wantPresent: true, wantValid: true, wantValue: 1500},
		{name: "zero", body: `{"monthly_limit":0}`, wantPresent: true, wantValid: true, wantValue: 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var req models.UpdateCategoryRequest
			if err := json.Unmarshal([]byte(tc.body), &req); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			if req.MonthlyLimit.Present != tc.wantPresent {
				t.Errorf("Present = %v, want %v", req.MonthlyLimit.Present, tc.wantPresent)
			}
			if req.MonthlyLimit.Valid != tc.wantValid {
				t.Errorf("Valid = %v, want %v", req.MonthlyLimit.Valid, tc.wantValid)
			}
			if req.MonthlyLimit.Value != tc.wantValue {
				t.Errorf("Value = %v, want %v", req.MonthlyLimit.Value, tc.wantValue)
			}
		})
	}
}
