package repository

import (
	"context"
	"time"

	"budget-go/models"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
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
		{Keys: bson.D{{"updated_at", 1}}},
		{Keys: bson.D{{"deleted_at", 1}}},
	})

	return &TransactionRepository{col: col}
}

func (r *TransactionRepository) Create(ctx context.Context, t *models.Transaction) error {
	now := time.Now()
	if t.ID == "" {
		t.ID = uuid.NewString()
	}
	if t.CreatedAt.IsZero() {
		t.CreatedAt = now
	}
	t.UpdatedAt = now
	t.Version = 1
	t.DeletedAt = nil
	t.LastModifiedBy = t.CreatedBy
	_, err := r.col.InsertOne(ctx, t)
	return err
}

func (r *TransactionRepository) FindByID(ctx context.Context, id string) (*models.Transaction, error) {
	var t models.Transaction
	err := r.col.FindOne(ctx, bson.M{"_id": id, "deleted_at": nil}).Decode(&t)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// Update applies the given partial update if the current version matches
// baseVersion; otherwise returns ErrConflict. Pass baseVersion=0 to skip the
// version check (used by legacy CRUD handlers that don't track versions yet).
func (r *TransactionRepository) Update(ctx context.Context, id string, update bson.M, baseVersion int, modifiedBy *models.UserInfo) (*models.Transaction, error) {
	now := time.Now()
	update["updated_at"] = now
	if modifiedBy != nil {
		update["last_modified_by"] = modifiedBy
	}

	filter := bson.M{"_id": id, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}

	res := r.col.FindOneAndUpdate(
		ctx,
		filter,
		bson.M{"$set": update, "$inc": bson.M{"version": 1}},
		options.FindOneAndUpdate().SetReturnDocument(options.After),
	)
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			// Distinguish "not found" from "version mismatch" by re-checking.
			var existing models.Transaction
			if findErr := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&existing); findErr == nil {
				return nil, ErrConflict
			}
			return nil, err
		}
		return nil, err
	}
	var t models.Transaction
	if err := res.Decode(&t); err != nil {
		return nil, err
	}
	return &t, nil
}

// Delete is a soft delete: sets deleted_at and bumps version. Returns ErrConflict
// when baseVersion is non-zero and does not match.
func (r *TransactionRepository) Delete(ctx context.Context, id string, baseVersion int, modifiedBy *models.UserInfo) (*models.Transaction, error) {
	now := time.Now()
	filter := bson.M{"_id": id, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	update := bson.M{
		"$set": bson.M{
			"deleted_at":       now,
			"updated_at":       now,
			"last_modified_by": modifiedBy,
		},
		"$inc": bson.M{"version": 1},
	}
	res := r.col.FindOneAndUpdate(ctx, filter, update,
		options.FindOneAndUpdate().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			var existing models.Transaction
			if findErr := r.col.FindOne(ctx, bson.M{"_id": id}).Decode(&existing); findErr == nil {
				return nil, ErrConflict
			}
			return nil, err
		}
		return nil, err
	}
	var t models.Transaction
	if err := res.Decode(&t); err != nil {
		return nil, err
	}
	return &t, nil
}

// Upsert inserts or replaces a record from a client's create/update payload,
// honoring optimistic concurrency. Used by the sync push handler.
func (r *TransactionRepository) Upsert(ctx context.Context, t *models.Transaction, baseVersion int, isCreate bool) (*models.Transaction, error) {
	now := time.Now()
	t.UpdatedAt = now

	if isCreate {
		if t.CreatedAt.IsZero() {
			t.CreatedAt = now
		}
		t.Version = 1
		t.DeletedAt = nil
		_, err := r.col.InsertOne(ctx, t)
		if err != nil {
			if mongo.IsDuplicateKeyError(err) {
				// Another client raced us; treat as conflict.
				existing, fErr := r.FindByID(ctx, t.ID)
				if fErr != nil {
					return nil, ErrConflict
				}
				_ = existing
				return nil, ErrConflict
			}
			return nil, err
		}
		return t, nil
	}

	filter := bson.M{"_id": t.ID, "deleted_at": nil}
	if baseVersion > 0 {
		filter["version"] = baseVersion
	}
	t.Version = baseVersion + 1
	res := r.col.FindOneAndReplace(ctx, filter, t,
		options.FindOneAndReplace().SetReturnDocument(options.After))
	if err := res.Err(); err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrConflict
		}
		return nil, err
	}
	var out models.Transaction
	if err := res.Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
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
	filter := bson.M{"deleted_at": nil}
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

// FindModifiedSince returns every transaction (including soft-deleted) whose
// updated_at strictly exceeds since. When since is zero, it returns the entire
// non-deleted set so a fresh client can warm its local cache.
func (r *TransactionRepository) FindModifiedSince(ctx context.Context, since time.Time) ([]models.Transaction, error) {
	filter := bson.M{}
	if !since.IsZero() {
		filter["updated_at"] = bson.M{"$gt": since}
	} else {
		filter["deleted_at"] = nil
	}
	cur, err := r.col.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.Transaction
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
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
	end := time.Date(year+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
	return r.AggregateMonthlyRange(ctx, start, end)
}

// AggregateMonthlyRange buckets transactions into year-month groups within
// [from, to]. Months without data are still emitted with zero totals so the
// chart x-axis is contiguous. The returned slice is ordered by year, month.
func (r *TransactionRepository) AggregateMonthlyRange(ctx context.Context, from, to time.Time) ([]models.MonthlyData, error) {
	if from.IsZero() || to.IsZero() || !from.Before(to) {
		return []models.MonthlyData{}, nil
	}

	pipeline := mongo.Pipeline{
		{{"$match", bson.D{
			{"date", bson.D{{"$gte", from}, {"$lte", to}}},
			{"hidden", bson.D{{"$ne", true}}},
			{"deleted_at", nil},
		}}},
		{{"$group", bson.D{
			{"_id", bson.D{
				{"year", bson.D{{"$year", "$date"}}},
				{"month", bson.D{{"$month", "$date"}}},
				{"type", "$type"},
			}},
			{"total", bson.D{{"$sum", "$amount"}}},
		}}},
	}

	cur, err := r.col.Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)

	type aggResult struct {
		ID struct {
			Year  int    `bson:"year"`
			Month int    `bson:"month"`
			Type  string `bson:"type"`
		} `bson:"_id"`
		Total float64 `bson:"total"`
	}

	var raw []aggResult
	if err := cur.All(ctx, &raw); err != nil {
		return nil, err
	}

	key := func(year, month int) int { return year*100 + month }
	bucket := make(map[int]*models.MonthlyData)
	for _, r := range raw {
		k := key(r.ID.Year, r.ID.Month)
		m, ok := bucket[k]
		if !ok {
			m = &models.MonthlyData{Year: r.ID.Year, Month: r.ID.Month}
			bucket[k] = m
		}
		switch r.ID.Type {
		case string(models.Income):
			m.Income += r.Total
		case string(models.InitialBalance):
			m.InitialBalance += r.Total
		default:
			m.Expense += r.Total
		}
	}

	startYM := time.Date(from.Year(), from.Month(), 1, 0, 0, 0, 0, time.UTC)
	endYM := time.Date(to.Year(), to.Month(), 1, 0, 0, 0, 0, time.UTC)
	result := make([]models.MonthlyData, 0, 12)
	for cur := startYM; !cur.After(endYM); cur = cur.AddDate(0, 1, 0) {
		y, m := cur.Year(), int(cur.Month())
		md, ok := bucket[key(y, m)]
		if !ok {
			md = &models.MonthlyData{Year: y, Month: m}
		}
		md.Balance = md.Income + md.InitialBalance - md.Expense
		result = append(result, *md)
	}
	return result, nil
}

func (r *TransactionRepository) GetSummary(ctx context.Context, from, to time.Time) (*models.SummaryData, error) {
	filter := bson.M{"hidden": bson.M{"$ne": true}, "deleted_at": nil}
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
		switch r.ID {
		case string(models.Income):
			summary.TotalIncome = r.Total
			summary.IncomeCount = r.Count
		case string(models.InitialBalance):
			summary.InitialBalance = r.Total
			summary.InitialBalanceCount = r.Count
		default:
			summary.TotalExpense = r.Total
			summary.ExpenseCount = r.Count
		}
	}
	summary.Balance = summary.TotalIncome + summary.InitialBalance - summary.TotalExpense
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
	filter := bson.M{"deleted_at": nil}
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
	filter := bson.M{"hidden": bson.M{"$ne": true}, "deleted_at": nil}
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
