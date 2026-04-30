package repository

import (
	"context"
	"time"

	"budget-go/models"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type TransactionRepository struct {
	col *mongo.Collection
}

func NewTransactionRepository(db *mongo.Database) *TransactionRepository {
	col := db.Collection("transactions")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{"date", -1}}},
		{Keys: bson.D{{"type", 1}}},
		{Keys: bson.D{{"category", 1}}},
	})

	return &TransactionRepository{col: col}
}

func (r *TransactionRepository) Create(ctx context.Context, t *models.Transaction) error {
	t.ID = primitive.NewObjectID()
	t.CreatedAt = time.Now()
	_, err := r.col.InsertOne(ctx, t)
	return err
}

func (r *TransactionRepository) FindByID(ctx context.Context, id string) (*models.Transaction, error) {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return nil, err
	}
	var t models.Transaction
	err = r.col.FindOne(ctx, bson.M{"_id": oid}).Decode(&t)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

func (r *TransactionRepository) Update(ctx context.Context, id string, update bson.M) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	_, err = r.col.UpdateOne(ctx, bson.M{"_id": oid}, bson.M{"$set": update})
	return err
}

func (r *TransactionRepository) Delete(ctx context.Context, id string) error {
	oid, err := primitive.ObjectIDFromHex(id)
	if err != nil {
		return err
	}
	_, err = r.col.DeleteOne(ctx, bson.M{"_id": oid})
	return err
}

func (r *TransactionRepository) Find(ctx context.Context, f models.TransactionFilter) ([]models.Transaction, int64, error) {
	filter := buildTransactionFilter(f)

	total, err := r.col.CountDocuments(ctx, filter)
	if err != nil {
		return nil, 0, err
	}

	opts := options.Find().
		SetSort(bson.D{{"date", -1}}).
		SetLimit(f.Limit).
		SetSkip(f.Skip)

	cur, err := r.col.Find(ctx, filter, opts)
	if err != nil {
		return nil, 0, err
	}
	defer cur.Close(ctx)

	var results []models.Transaction
	if err := cur.All(ctx, &results); err != nil {
		return nil, 0, err
	}
	return results, total, nil
}

func (r *TransactionRepository) FindAll(ctx context.Context, from, to time.Time, txType string) ([]models.Transaction, error) {
	filter := bson.M{}
	if txType != "" {
		filter["type"] = txType
	}
	if !from.IsZero() || !to.IsZero() {
		dateFilter := bson.M{}
		if !from.IsZero() {
			dateFilter["$gte"] = from
		}
		if !to.IsZero() {
			dateFilter["$lte"] = to
		}
		filter["date"] = dateFilter
	}

	cur, err := r.col.Find(ctx, filter, options.Find().SetSort(bson.D{{"date", -1}}))
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	var results []models.Transaction
	if err := cur.All(ctx, &results); err != nil {
		return nil, err
	}
	return results, nil
}

func (r *TransactionRepository) AggregateByCategory(ctx context.Context, txType string, from, to time.Time) ([]models.CategoryData, error) {
	matchStage := bson.D{{"$match", buildDateFilter(txType, from, to)}}

	groupStage := bson.D{{"$group", bson.D{
		{"_id", "$category"},
		{"total", bson.D{{"$sum", "$amount"}}},
		{"count", bson.D{{"$sum", 1}}},
	}}}

	sortStage := bson.D{{"$sort", bson.D{{"total", -1}}}}

	pipeline := mongo.Pipeline{matchStage, groupStage, sortStage}

	cur, err := r.col.Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	type aggResult struct {
		ID    string  `bson:"_id"`
		Total float64 `bson:"total"`
		Count int32   `bson:"count"`
	}

	var raw []aggResult
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}

	var grandTotal float64
	for _, r := range raw {
		grandTotal += r.Total
	}

	result := make([]models.CategoryData, len(raw))
	for i, r := range raw {
		pct := 0.0
		if grandTotal > 0 {
			pct = r.Total / grandTotal * 100
		}
		result[i] = models.CategoryData{
			Category:   r.ID,
			Amount:     r.Total,
			Percentage: pct,
			Count:      r.Count,
		}
	}
	return result, nil
}

func (r *TransactionRepository) AggregateMonthly(ctx context.Context, year int) ([]models.MonthlyData, error) {
	start := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC)
	end := time.Date(year+1, 1, 1, 0, 0, 0, 0, time.UTC)

	pipeline := mongo.Pipeline{
		{{"$match", bson.D{
			{"date", bson.D{{"$gte", start}, {"$lt", end}}},
			{"hidden", bson.D{{"$ne", true}}},
		}}},
		{{"$group", bson.D{
			{"_id", bson.D{
				{"month", bson.D{{"$month", "$date"}}},
				{"type", "$type"},
			}},
			{"total", bson.D{{"$sum", "$amount"}}},
		}}},
		{{"$sort", bson.D{{"_id.month", 1}}}},
	}

	cur, err := r.col.Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	type aggResult struct {
		ID struct {
			Month int    `bson:"month"`
			Type  string `bson:"type"`
		} `bson:"_id"`
		Total float64 `bson:"total"`
	}

	var raw []aggResult
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}

	monthly := make(map[int]*models.MonthlyData)
	for m := 1; m <= 12; m++ {
		monthly[m] = &models.MonthlyData{Month: m}
	}

	for _, r := range raw {
		m := monthly[r.ID.Month]
		if r.ID.Type == string(models.Income) {
			m.Income += r.Total
		} else {
			m.Expense += r.Total
		}
	}

	result := make([]models.MonthlyData, 12)
	for i := 1; i <= 12; i++ {
		m := monthly[i]
		m.Balance = m.Income - m.Expense
		result[i-1] = *m
	}
	return result, nil
}

func (r *TransactionRepository) GetSummary(ctx context.Context, from, to time.Time) (*models.SummaryData, error) {
	filter := bson.M{"hidden": bson.M{"$ne": true}}
	if !from.IsZero() || !to.IsZero() {
		dateFilter := bson.M{}
		if !from.IsZero() {
			dateFilter["$gte"] = from
		}
		if !to.IsZero() {
			dateFilter["$lte"] = to
		}
		filter["date"] = dateFilter
	}

	pipeline := mongo.Pipeline{
		{{"$match", filter}},
		{{"$group", bson.D{
			{"_id", "$type"},
			{"total", bson.D{{"$sum", "$amount"}}},
			{"count", bson.D{{"$sum", 1}}},
		}}},
	}

	cur, err := r.col.Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	type aggResult struct {
		ID    string  `bson:"_id"`
		Total float64 `bson:"total"`
		Count int64   `bson:"count"`
	}

	var raw []aggResult
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}

	summary := &models.SummaryData{}
	for _, r := range raw {
		if r.ID == string(models.Income) {
			summary.TotalIncome = r.Total
			summary.IncomeCount = r.Count
		} else {
			summary.TotalExpense = r.Total
			summary.ExpenseCount = r.Count
		}
	}
	summary.Balance = summary.TotalIncome - summary.TotalExpense
	return summary, nil
}

func (r *TransactionRepository) GetAverageMonthlyCategoryExpenses(ctx context.Context, from, to time.Time) ([]models.CategoryData, error) {
	months := to.Sub(from).Hours() / 24 / 30
	if months < 1 {
		months = 1
	}

	rawData, err := r.AggregateByCategory(ctx, string(models.Expense), from, to)
	if err != nil {
		return nil, err
	}

	for i := range rawData {
		rawData[i].Amount = rawData[i].Amount / months
	}

	return rawData, nil
}

func buildTransactionFilter(f models.TransactionFilter) bson.M {
	filter := bson.M{}
	if f.Type != "" {
		filter["type"] = f.Type
	}
	if f.Category != "" {
		filter["category"] = f.Category
	}
	if f.From != nil || f.To != nil {
		dateFilter := bson.M{}
		if f.From != nil {
			dateFilter["$gte"] = f.From
		}
		if f.To != nil {
			dateFilter["$lte"] = f.To
		}
		filter["date"] = dateFilter
	}
	return filter
}

func buildDateFilter(txType string, from, to time.Time) bson.M {
	filter := bson.M{"hidden": bson.M{"$ne": true}}
	if txType != "" {
		filter["type"] = txType
	}
	if !from.IsZero() || !to.IsZero() {
		dateFilter := bson.M{}
		if !from.IsZero() {
			dateFilter["$gte"] = from
		}
		if !to.IsZero() {
			dateFilter["$lte"] = to
		}
		filter["date"] = dateFilter
	}
	return filter
}
