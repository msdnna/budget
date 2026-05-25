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
		{Keys: bson.D{{Key: "date", Value: -1}}},
		{Keys: bson.D{{Key: "type", Value: 1}}},
		{Keys: bson.D{{Key: "category", Value: 1}}},
		{Keys: bson.D{{Key: "updated_at", Value: 1}}},
		{Keys: bson.D{{Key: "deleted_at", Value: 1}}},
		{Keys: bson.D{{Key: "wishlist_id", Value: 1}, {Key: "date", Value: -1}}},
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
	t.Deposit = models.NormalizeDeposit(t.Deposit)
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
		t.Deposit = models.NormalizeDeposit(t.Deposit)
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
	t.Deposit = models.NormalizeDeposit(t.Deposit)
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
		SetSort(bson.D{{Key: "date", Value: -1}}).
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

func (r *TransactionRepository) FindAll(ctx context.Context, from, to time.Time, txType, deposit string) ([]models.Transaction, error) {
	filter := bson.M{"deleted_at": nil}
	if txType != "" {
		filter["type"] = txType
	}
	if deposit != "" {
		filter["deposit"] = deposit
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

	cur, err := r.col.Find(ctx, filter, options.Find().SetSort(bson.D{{Key: "date", Value: -1}}))
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

// FindChildren returns the non-deleted child transactions of a parent.
func (r *TransactionRepository) FindChildren(ctx context.Context, parentID string) ([]models.Transaction, error) {
	cur, err := r.col.Find(ctx, bson.M{"parent_id": parentID, "deleted_at": nil},
		options.Find().SetSort(bson.D{{Key: "date", Value: -1}, {Key: "created_at", Value: -1}}))
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []models.Transaction
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []models.Transaction{}
	}
	return out, nil
}

// SetExcludedForChildren flips the excluded_from_stats flag on every child of
// the given parent. Used on detail-request close (false) to fold children into
// stats, and unused on cancel since children are deleted instead.
func (r *TransactionRepository) SetExcludedForChildren(ctx context.Context, parentID string, excluded bool, modifiedBy *models.UserInfo) error {
	now := time.Now()
	update := bson.M{
		"$set": bson.M{
			"excluded_from_stats": excluded,
			"updated_at":          now,
			"last_modified_by":    modifiedBy,
		},
		"$inc": bson.M{"version": 1},
	}
	_, err := r.col.UpdateMany(ctx, bson.M{"parent_id": parentID, "deleted_at": nil}, update)
	return err
}

// SoftDeleteChildren is used when a detail-request is cancelled.
func (r *TransactionRepository) SoftDeleteChildren(ctx context.Context, parentID string, modifiedBy *models.UserInfo) error {
	now := time.Now()
	update := bson.M{
		"$set": bson.M{
			"deleted_at":       now,
			"updated_at":       now,
			"last_modified_by": modifiedBy,
		},
		"$inc": bson.M{"version": 1},
	}
	_, err := r.col.UpdateMany(ctx, bson.M{"parent_id": parentID, "deleted_at": nil}, update)
	return err
}

func (r *TransactionRepository) AggregateByCategory(ctx context.Context, txType, deposit string, from, to time.Time) ([]models.CategoryData, error) {
	matchStage := bson.D{{Key: "$match", Value: buildDateFilter(txType, deposit, from, to)}}

	groupStage := bson.D{{Key: "$group", Value: bson.D{
		{Key: "_id", Value: "$category"},
		{Key: "total", Value: bson.D{{Key: "$sum", Value: "$amount"}}},
		{Key: "count", Value: bson.D{{Key: "$sum", Value: 1}}},
	}}}

	sortStage := bson.D{{Key: "$sort", Value: bson.D{{Key: "total", Value: -1}}}}

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

func (r *TransactionRepository) AggregateMonthly(ctx context.Context, year int, deposit string) ([]models.MonthlyData, error) {
	start := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC)
	end := time.Date(year+1, 1, 1, 0, 0, 0, 0, time.UTC).Add(-time.Second)
	return r.AggregateMonthlyRange(ctx, start, end, deposit)
}

// AggregateMonthlyRange buckets transactions into year-month groups within
// [from, to]. Months without data are still emitted with zero totals so the
// chart x-axis is contiguous. The returned slice is ordered by year, month.
func (r *TransactionRepository) AggregateMonthlyRange(ctx context.Context, from, to time.Time, deposit string) ([]models.MonthlyData, error) {
	if from.IsZero() || to.IsZero() || !from.Before(to) {
		return []models.MonthlyData{}, nil
	}

	match := bson.D{
		{Key: "date", Value: bson.D{{Key: "$gte", Value: from}, {Key: "$lte", Value: to}}},
		{Key: "hidden", Value: bson.D{{Key: "$ne", Value: true}}},
		{Key: "deleted_at", Value: nil},
		{Key: "excluded_from_stats", Value: bson.D{{Key: "$ne", Value: true}}},
	}
	if deposit != "" {
		match = append(match, bson.E{Key: "deposit", Value: deposit})
	}
	pipeline := mongo.Pipeline{
		{{Key: "$match", Value: match}},
		{{Key: "$group", Value: bson.D{
			{Key: "_id", Value: bson.D{
				{Key: "year", Value: bson.D{{Key: "$year", Value: "$date"}}},
				{Key: "month", Value: bson.D{{Key: "$month", Value: "$date"}}},
				{Key: "type", Value: "$type"},
			}},
			{Key: "total", Value: bson.D{{Key: "$sum", Value: "$amount"}}},
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

func (r *TransactionRepository) GetSummary(ctx context.Context, from, to time.Time, deposit string) (*models.SummaryData, error) {
	filter := bson.M{"hidden": bson.M{"$ne": true}, "deleted_at": nil, "excluded_from_stats": bson.M{"$ne": true}}
	if deposit != "" {
		filter["deposit"] = deposit
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

	pipeline := mongo.Pipeline{
		{{Key: "$match", Value: filter}},
		{{Key: "$group", Value: bson.D{
			{Key: "_id", Value: "$type"},
			{Key: "total", Value: bson.D{{Key: "$sum", Value: "$amount"}}},
			{Key: "count", Value: bson.D{{Key: "$sum", Value: 1}}},
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

// FindLinkedToWishlistMulti returns non-deleted expense transactions linked
// to any of the given wishlist IDs whose date falls within [from, to]. The
// forecast handler uses this to bucket payments per item's frequency-period
// in Go (since monthly/quarterly/yearly items have different windows).
func (r *TransactionRepository) FindLinkedToWishlistMulti(ctx context.Context, wishlistIDs []string, from, to time.Time) ([]models.Transaction, error) {
	if len(wishlistIDs) == 0 {
		return []models.Transaction{}, nil
	}
	filter := bson.M{
		"wishlist_id": bson.M{"$in": wishlistIDs},
		"deleted_at":  nil,
		"type":        models.Expense,
	}
	if !from.IsZero() || !to.IsZero() {
		df := bson.M{}
		if !from.IsZero() {
			df["$gte"] = from
		}
		if !to.IsZero() {
			df["$lte"] = to
		}
		filter["date"] = df
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

// FindLinkedToWishlist returns non-deleted expense transactions whose
// wishlist_id matches the given id, optionally restricted to a date window.
// Used by the bulk-unlink endpoint that backs the "Отменить" action.
func (r *TransactionRepository) FindLinkedToWishlist(ctx context.Context, wishlistID string, from, to time.Time) ([]models.Transaction, error) {
	filter := bson.M{
		"wishlist_id": wishlistID,
		"deleted_at":  nil,
	}
	if !from.IsZero() || !to.IsZero() {
		df := bson.M{}
		if !from.IsZero() {
			df["$gte"] = from
		}
		if !to.IsZero() {
			df["$lte"] = to
		}
		filter["date"] = df
	}
	cur, err := r.col.Find(ctx, filter, options.Find().SetSort(bson.D{{Key: "date", Value: -1}}))
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

// UnlinkFromWishlist clears wishlist_id on every non-deleted transaction
// linked to the given wishlist item within [from, to]. Bumps version and
// updated_at on each affected row so offline clients pull the change.
// Returns the number of transactions affected.
func (r *TransactionRepository) UnlinkFromWishlist(ctx context.Context, wishlistID string, from, to time.Time, modifiedBy *models.UserInfo) (int64, error) {
	filter := bson.M{
		"wishlist_id": wishlistID,
		"deleted_at":  nil,
	}
	if !from.IsZero() || !to.IsZero() {
		df := bson.M{}
		if !from.IsZero() {
			df["$gte"] = from
		}
		if !to.IsZero() {
			df["$lte"] = to
		}
		filter["date"] = df
	}
	now := time.Now()
	update := bson.M{
		"$set": bson.M{
			"wishlist_id":      "",
			"updated_at":       now,
			"last_modified_by": modifiedBy,
		},
		"$inc": bson.M{"version": 1},
	}
	res, err := r.col.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, err
	}
	return res.ModifiedCount, nil
}

func (r *TransactionRepository) GetAverageMonthlyCategoryExpenses(ctx context.Context, from, to time.Time, deposit string) ([]models.CategoryData, error) {
	months := to.Sub(from).Hours() / 24 / 30
	if months < 1 {
		months = 1
	}

	rawData, err := r.AggregateByCategory(ctx, string(models.Expense), deposit, from, to)
	if err != nil {
		return nil, err
	}

	for i := range rawData {
		rawData[i].Amount /= months
	}

	return rawData, nil
}

// GetAverageMonthlyCategoryExpensesUnlinked is GetAverageMonthlyCategoryExpenses
// minus transactions that are fulfillments of recurring wishlist items
// (wishlist_id != ""). Used by Forecast() so we don't double-count: the
// recurring schedule is already projected via wishlist_contrib's next-due
// model — including the actual payment in the 3-month avg would inflate
// the forecast for the next 3 months after every yearly bill.
func (r *TransactionRepository) GetAverageMonthlyCategoryExpensesUnlinked(ctx context.Context, from, to time.Time, deposit string) ([]models.CategoryData, error) {
	months := to.Sub(from).Hours() / 24 / 30
	if months < 1 {
		months = 1
	}

	match := bson.M{
		"type":                models.Expense,
		"deleted_at":          nil,
		"hidden":              bson.M{"$ne": true},
		"excluded_from_stats": bson.M{"$ne": true},
		// Empty/missing wishlist_id only — bson omitempty means unlinked
		// rows store no field at all, so $in handles both shapes.
		"wishlist_id": bson.M{"$in": []any{nil, ""}},
	}
	if deposit != "" {
		match["deposit"] = deposit
	}
	if !from.IsZero() || !to.IsZero() {
		df := bson.M{}
		if !from.IsZero() {
			df["$gte"] = from
		}
		if !to.IsZero() {
			df["$lte"] = to
		}
		match["date"] = df
	}

	pipeline := mongo.Pipeline{
		{{Key: "$match", Value: match}},
		{{Key: "$group", Value: bson.D{
			{Key: "_id", Value: "$category"},
			{Key: "total", Value: bson.D{{Key: "$sum", Value: "$amount"}}},
			{Key: "count", Value: bson.D{{Key: "$sum", Value: 1}}},
		}}},
		{{Key: "$sort", Value: bson.D{{Key: "total", Value: -1}}}},
	}

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
			Amount:     r.Total / months,
			Percentage: pct,
			Count:      r.Count,
		}
	}
	return result, nil
}

func buildTransactionFilter(f models.TransactionFilter) bson.M {
	filter := bson.M{"deleted_at": nil}
	// Hide pending children of an open detail-request: parent_id != '' AND
	// excluded_from_stats=true. Closed-request children (excluded_from_stats=
	// false) and regular transactions stay visible.
	filter["$or"] = []bson.M{
		{"parent_id": bson.M{"$in": []any{nil, ""}}},
		{"excluded_from_stats": bson.M{"$ne": true}},
	}
	// Closed-request parents are historical placeholders — superseded by
	// their children in stats. Hide them from the regular list unless the
	// caller explicitly opts in (e.g., "show closed requests" toggle).
	if !f.IncludeDetailed {
		filter["detail_request_status"] = bson.M{"$ne": "closed"}
	}
	// Split-income parents are the lump-sum source of per-deposit children.
	// Hide them from the income list by default; the «показать разделённые»
	// toggle flips IncludeSplit and they come back.
	if !f.IncludeSplit {
		filter["$nor"] = []bson.M{{
			"type":                  string(models.Income),
			"excluded_from_stats":   true,
			"parent_id":             bson.M{"$in": []any{nil, ""}},
			"detail_request_status": bson.M{"$in": []any{nil, ""}},
		}}
	}
	if f.Unlinked {
		filter["type"] = string(models.Expense)
		filter["wishlist_id"] = bson.M{"$in": []any{nil, ""}}
		filter["parent_id"] = bson.M{"$in": []any{nil, ""}}
		filter["excluded_from_stats"] = bson.M{"$ne": true}
		filter["detail_request_status"] = bson.M{"$ne": "closed"}
	} else if f.Type != "" {
		filter["type"] = f.Type
	}
	if len(f.Categories) > 0 {
		filter["category"] = bson.M{"$in": f.Categories}
	} else if f.Category != "" {
		filter["category"] = f.Category
	}
	if f.Deposit != "" {
		filter["deposit"] = f.Deposit
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

func buildDateFilter(txType, deposit string, from, to time.Time) bson.M {
	filter := bson.M{"hidden": bson.M{"$ne": true}, "deleted_at": nil, "excluded_from_stats": bson.M{"$ne": true}}
	if txType != "" {
		filter["type"] = txType
	}
	if deposit != "" {
		filter["deposit"] = deposit
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
