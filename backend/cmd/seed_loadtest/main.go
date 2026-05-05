// seed_loadtest populates a MongoDB database with realistic family-budget data
// for performance testing. Generates multiple users, thousands of transactions
// across a configurable date range, and wishlist items.
//
// Usage:
//
//	go run ./cmd/seed_loadtest \
//	  -mongo-uri "mongodb://admin:password@localhost:27017/?authSource=admin" \
//	  -db budget_loadtest \
//	  -from 2024-01 -to 2026-04 \
//	  -expenses 4000 -incomes 1800 -wishlist 60 \
//	  -clear
//
// Defaults are picked so running with no flags is reasonable for local dev.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math/rand"
	"os"
	"time"

	"budget-go/models"

	"github.com/google/uuid"
	"github.com/joho/godotenv"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
	"golang.org/x/crypto/bcrypt"
)

// ─── Family users ────────────────────────────────────────────────────────────

type seedUser struct {
	Login       string
	Password    string
	DisplayName string
	AvatarURL   string
	// Weight of how often this user is the author of a generated record.
	Weight int
}

var seedUsers = []seedUser{
	{Login: "ivan", Password: "ivan123", DisplayName: "Иван Петров", Weight: 35},
	{Login: "anna", Password: "anna123", DisplayName: "Анна Петрова", Weight: 35},
	{Login: "misha", Password: "misha123", DisplayName: "Миша Петров", Weight: 15},
	{Login: "babushka", Password: "baba123", DisplayName: "Бабушка Зина", Weight: 10},
	{Login: "loadtest", Password: "loadtest", DisplayName: "Load Tester", Weight: 5},
}

// ─── Templates: ~10 expense, ~5 income, ~10 wishlist ─────────────────────────

type expenseTemplate struct {
	Category   string
	Source     string  // "shop / payee" – Source field on transaction
	Purpose    string  // free text
	MinAmount  float64
	MaxAmount  float64
	Weight     int     // relative frequency
}

type incomeTemplate struct {
	Category  string
	Source    string
	Purpose   string
	MinAmount float64
	MaxAmount float64
	Weight    int
}

type wishlistTemplate struct {
	Name          string
	Category      string
	Frequency     models.Frequency
	MinCost       float64
	MaxCost       float64
	PriorityRange [2]int
	Notes         string
}

var expenseTemplates = []expenseTemplate{
	{Category: "Жильё/ЖКУ", Source: "Сбербанк", Purpose: "Ипотека", MinAmount: 45000, MaxAmount: 47000, Weight: 5},
	{Category: "Жильё/ЖКУ", Source: "ГосУслуги ЖКХ", Purpose: "Коммуналка", MinAmount: 7500, MaxAmount: 12500, Weight: 8},
	{Category: "Продукты", Source: "Пятёрочка", Purpose: "Закупка на неделю", MinAmount: 1800, MaxAmount: 6500, Weight: 35},
	{Category: "Продукты", Source: "Перекрёсток", Purpose: "Продукты", MinAmount: 1200, MaxAmount: 5800, Weight: 22},
	{Category: "Продукты", Source: "ВкусВилл", Purpose: "Овощи и сыр", MinAmount: 800, MaxAmount: 3200, Weight: 14},
	{Category: "Транспорт", Source: "Лукойл", Purpose: "Бензин", MinAmount: 2500, MaxAmount: 4200, Weight: 18},
	{Category: "Транспорт", Source: "Яндекс.Такси", Purpose: "Поездка", MinAmount: 250, MaxAmount: 1400, Weight: 16},
	{Category: "Рестораны", Source: "Кафе", Purpose: "Обед", MinAmount: 600, MaxAmount: 2800, Weight: 18},
	{Category: "Рестораны", Source: "Доставка", Purpose: "Ужин дома", MinAmount: 900, MaxAmount: 3500, Weight: 12},
	{Category: "Связь", Source: "МТС", Purpose: "Тариф", MinAmount: 450, MaxAmount: 950, Weight: 6},
	{Category: "Связь", Source: "Ростелеком", Purpose: "Интернет", MinAmount: 600, MaxAmount: 900, Weight: 5},
	{Category: "Развлечения", Source: "Кинотеатр", Purpose: "Сеанс", MinAmount: 600, MaxAmount: 2400, Weight: 10},
	{Category: "Здоровье", Source: "Аптека", Purpose: "Лекарства", MinAmount: 300, MaxAmount: 4500, Weight: 9},
	{Category: "Здоровье", Source: "Клиника", Purpose: "Приём врача", MinAmount: 2000, MaxAmount: 9000, Weight: 4},
	{Category: "Спорт", Source: "Фитнес-клуб", Purpose: "Абонемент", MinAmount: 2200, MaxAmount: 3800, Weight: 5},
	{Category: "Одежда", Source: "Lamoda", Purpose: "Одежда", MinAmount: 1500, MaxAmount: 12000, Weight: 7},
	{Category: "Электроника", Source: "DNS", Purpose: "Аксессуары", MinAmount: 800, MaxAmount: 25000, Weight: 4},
	{Category: "Образование", Source: "Курсы", Purpose: "Обучение", MinAmount: 3000, MaxAmount: 18000, Weight: 3},
	{Category: "Прочее", Source: "Wildberries", Purpose: "Хозтовары", MinAmount: 400, MaxAmount: 6500, Weight: 12},
}

var incomeTemplates = []incomeTemplate{
	{Category: "Зарплата", Source: "ООО Ромашка", Purpose: "Зарплата основная", MinAmount: 145000, MaxAmount: 165000, Weight: 30},
	{Category: "Зарплата", Source: "ООО Лютик", Purpose: "Зарплата супруги", MinAmount: 95000, MaxAmount: 110000, Weight: 30},
	{Category: "Фриланс", Source: "Upwork", Purpose: "Проект", MinAmount: 15000, MaxAmount: 65000, Weight: 12},
	{Category: "Бонус", Source: "ООО Ромашка", Purpose: "Премия", MinAmount: 25000, MaxAmount: 80000, Weight: 6},
	{Category: "Подарок", Source: "Родители", Purpose: "На день рождения", MinAmount: 3000, MaxAmount: 30000, Weight: 5},
	{Category: "Аренда", Source: "Арендатор", Purpose: "Сдача квартиры", MinAmount: 30000, MaxAmount: 38000, Weight: 8},
	{Category: "Инвестиции", Source: "Брокер", Purpose: "Дивиденды", MinAmount: 1500, MaxAmount: 25000, Weight: 4},
	{Category: "Прочее", Source: "Авито", Purpose: "Продажа вещей", MinAmount: 1000, MaxAmount: 18000, Weight: 5},
}

var wishlistTemplates = []wishlistTemplate{
	{Name: "iPhone (новый)", Category: "Техника", Frequency: models.FrequencyOnce, MinCost: 90000, MaxCost: 130000, PriorityRange: [2]int{4, 5}, Notes: "Заменить старый"},
	{Name: "Ноутбук для работы", Category: "Техника", Frequency: models.FrequencyOnce, MinCost: 80000, MaxCost: 180000, PriorityRange: [2]int{3, 5}},
	{Name: "Стиральная машина", Category: "Дом", Frequency: models.FrequencyOnce, MinCost: 35000, MaxCost: 70000, PriorityRange: [2]int{2, 4}},
	{Name: "Отпуск летом", Category: "Путешествия", Frequency: models.FrequencyYearly, MinCost: 120000, MaxCost: 280000, PriorityRange: [2]int{4, 5}},
	{Name: "Зимний отпуск", Category: "Путешествия", Frequency: models.FrequencyYearly, MinCost: 80000, MaxCost: 180000, PriorityRange: [2]int{2, 4}},
	{Name: "Подарок на ДР", Category: "Прочее", Frequency: models.FrequencyYearly, MinCost: 5000, MaxCost: 30000, PriorityRange: [2]int{3, 4}},
	{Name: "Стоматолог (плановый)", Category: "Здоровье", Frequency: models.FrequencyQuarterly, MinCost: 8000, MaxCost: 25000, PriorityRange: [2]int{3, 5}},
	{Name: "Книги", Category: "Развлечения", Frequency: models.FrequencyMonthly, MinCost: 800, MaxCost: 4500, PriorityRange: [2]int{1, 3}},
	{Name: "Обновление гардероба", Category: "Одежда", Frequency: models.FrequencyQuarterly, MinCost: 8000, MaxCost: 35000, PriorityRange: [2]int{2, 4}},
	{Name: "Пылесос робот", Category: "Дом", Frequency: models.FrequencyOnce, MinCost: 25000, MaxCost: 60000, PriorityRange: [2]int{2, 3}},
	{Name: "Велосипед", Category: "Развлечения", Frequency: models.FrequencyOnce, MinCost: 35000, MaxCost: 90000, PriorityRange: [2]int{1, 3}},
	{Name: "Подписка стриминги", Category: "Развлечения", Frequency: models.FrequencyMonthly, MinCost: 500, MaxCost: 1500, PriorityRange: [2]int{1, 2}, Notes: "Регулярка"},
}

// Default categories the backend would seed on startup. We replicate them here
// so the load-test script does not depend on backend startup order.
var defaultCategories = map[string][]string{
	"expense": {
		"Продукты", "Транспорт", "Жильё/ЖКУ", "Рестораны", "Развлечения",
		"Здоровье", "Образование", "Одежда", "Электроника", "Путешествия",
		"Связь", "Красота", "Спорт", "Прочее",
	},
	"income": {
		"Зарплата", "Фриланс", "Инвестиции", "Бонус", "Подарок", "Аренда", "Прочее",
	},
	"wishlist": {
		"Техника", "Одежда", "Путешествия", "Дом", "Здоровье", "Развлечения", "Прочее",
	},
}

// ─── Main ────────────────────────────────────────────────────────────────────

func main() {
	_ = godotenv.Load()

	mongoURI := flag.String("mongo-uri", getEnv("MONGO_URI", "mongodb://admin:password@localhost:27017/?authSource=admin"), "MongoDB URI")
	dbName := flag.String("db", getEnv("LOADTEST_DB", "budget_loadtest"), "Target database")
	from := flag.String("from", "2024-01", "Start month, YYYY-MM")
	to := flag.String("to", "2026-04", "End month, YYYY-MM")
	expensesN := flag.Int("expenses", 4000, "Number of expense transactions")
	incomesN := flag.Int("incomes", 1800, "Number of income transactions")
	wishlistN := flag.Int("wishlist", 60, "Number of wishlist items")
	clear := flag.Bool("clear", false, "Drop the target database before seeding")
	seed := flag.Int64("seed", 42, "Random seed for reproducible runs")
	flag.Parse()

	rng := rand.New(rand.NewSource(*seed))

	fromTime, err := time.Parse("2006-01", *from)
	if err != nil {
		log.Fatalf("invalid -from: %v", err)
	}
	toTime, err := time.Parse("2006-01", *to)
	if err != nil {
		log.Fatalf("invalid -to: %v", err)
	}
	// extend to end of -to month
	toTime = toTime.AddDate(0, 1, -1).Add(24*time.Hour - time.Second)

	if !toTime.After(fromTime) {
		log.Fatalf("-to must be after -from")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	client, err := mongo.Connect(ctx, options.Client().ApplyURI(*mongoURI))
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer client.Disconnect(ctx)

	if err := client.Ping(ctx, nil); err != nil {
		log.Fatalf("ping: %v", err)
	}

	db := client.Database(*dbName)

	if *clear {
		log.Printf("Dropping database %q…", *dbName)
		if err := db.Drop(ctx); err != nil {
			log.Fatalf("drop: %v", err)
		}
	}

	// 1. Users
	users, err := upsertUsers(ctx, db)
	if err != nil {
		log.Fatalf("users: %v", err)
	}
	log.Printf("Users ready: %d", len(users))

	// 2. Categories
	if err := seedCategories(ctx, db); err != nil {
		log.Fatalf("categories: %v", err)
	}

	// 3. Initial balance per user (one record near fromTime)
	if err := seedInitialBalances(ctx, db, users, fromTime, rng); err != nil {
		log.Fatalf("initial balances: %v", err)
	}

	// 4. Expenses
	expCount, err := seedTransactions(ctx, db, models.Expense, *expensesN, fromTime, toTime, users, rng)
	if err != nil {
		log.Fatalf("expenses: %v", err)
	}
	log.Printf("Expenses inserted: %d", expCount)

	// 5. Incomes
	incCount, err := seedTransactions(ctx, db, models.Income, *incomesN, fromTime, toTime, users, rng)
	if err != nil {
		log.Fatalf("incomes: %v", err)
	}
	log.Printf("Incomes inserted: %d", incCount)

	// 6. Wishlist
	wlCount, err := seedWishlist(ctx, db, *wishlistN, users, rng)
	if err != nil {
		log.Fatalf("wishlist: %v", err)
	}
	log.Printf("Wishlist inserted: %d", wlCount)

	fmt.Println()
	fmt.Println("=== Loadtest seed complete ===")
	fmt.Printf("Database: %s\n", *dbName)
	fmt.Printf("Users (login / password / display name):\n")
	for _, u := range seedUsers {
		fmt.Printf("  %-10s  %-10s  %s\n", u.Login, u.Password, u.DisplayName)
	}
}

// ─── Users ───────────────────────────────────────────────────────────────────

func upsertUsers(ctx context.Context, db *mongo.Database) ([]models.UserInfo, error) {
	col := db.Collection("users")
	col.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{Key: "login", Value: 1}},
		Options: options.Index().SetUnique(true),
	})

	out := make([]models.UserInfo, 0, len(seedUsers))
	for _, su := range seedUsers {
		// Find or insert
		var existing struct {
			ID          primitive.ObjectID `bson:"_id"`
			DisplayName string             `bson:"display_name"`
			AvatarURL   string             `bson:"avatar_url"`
		}
		err := col.FindOne(ctx, bson.M{"login": su.Login}).Decode(&existing)
		if err == nil {
			out = append(out, models.UserInfo{
				UserID:      existing.ID.Hex(),
				DisplayName: existing.DisplayName,
				AvatarURL:   existing.AvatarURL,
			})
			continue
		}
		if err != mongo.ErrNoDocuments {
			return nil, err
		}

		hash, err := bcrypt.GenerateFromPassword([]byte(su.Password), 10)
		if err != nil {
			return nil, err
		}
		id := primitive.NewObjectID()
		doc := bson.M{
			"_id":           id,
			"login":         su.Login,
			"password_hash": string(hash),
			"display_name":  su.DisplayName,
			"created_at":    time.Now(),
		}
		if su.AvatarURL != "" {
			doc["avatar_url"] = su.AvatarURL
		}
		if _, err := col.InsertOne(ctx, doc); err != nil {
			return nil, err
		}
		out = append(out, models.UserInfo{
			UserID:      id.Hex(),
			DisplayName: su.DisplayName,
			AvatarURL:   su.AvatarURL,
		})
	}
	return out, nil
}

// pickWeightedUser picks a user index according to seedUsers[].Weight.
func pickWeightedUser(rng *rand.Rand) int {
	total := 0
	for _, u := range seedUsers {
		total += u.Weight
	}
	r := rng.Intn(total)
	for i, u := range seedUsers {
		if r < u.Weight {
			return i
		}
		r -= u.Weight
	}
	return len(seedUsers) - 1
}

// ─── Categories ──────────────────────────────────────────────────────────────

func seedCategories(ctx context.Context, db *mongo.Database) error {
	col := db.Collection("categories")
	col.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "section", Value: 1}, {Key: "name", Value: 1}},
			Options: options.Index().SetUnique(true).SetPartialFilterExpression(bson.M{"deleted_at": nil}),
		},
		{Keys: bson.D{{Key: "updated_at", Value: 1}}},
		{Keys: bson.D{{Key: "deleted_at", Value: 1}}},
	})

	now := time.Now()
	for section, names := range defaultCategories {
		for _, name := range names {
			filter := bson.M{"section": section, "name": name, "deleted_at": nil}
			cnt, err := col.CountDocuments(ctx, filter)
			if err != nil {
				return err
			}
			if cnt > 0 {
				continue
			}
			_, err = col.InsertOne(ctx, models.Category{
				ID:        uuid.NewString(),
				Section:   section,
				Name:      name,
				IsDefault: true,
				CreatedAt: now,
				Version:   1,
				UpdatedAt: now,
			})
			if err != nil && !mongo.IsDuplicateKeyError(err) {
				return err
			}
		}
	}
	return nil
}

// ─── Initial balances ────────────────────────────────────────────────────────

func seedInitialBalances(ctx context.Context, db *mongo.Database, users []models.UserInfo, fromTime time.Time, rng *rand.Rand) error {
	col := db.Collection("transactions")
	now := time.Now()
	docs := make([]any, 0, len(users))
	// One per user, dated 1 day before fromTime
	balDate := fromTime.Add(-24 * time.Hour)
	for i := range users {
		amount := float64(50000+rng.Intn(450000)) / 1.0 // 50k–500k
		docs = append(docs, models.Transaction{
			ID:          uuid.NewString(),
			Type:        models.InitialBalance,
			Amount:      amount,
			Date:        balDate,
			Category:    "Прочее",
			Source:      "Стартовый баланс",
			Purpose:     "Начальный баланс счёта",
			Description: "",
			CreatedBy:   &users[i],
			LastModifiedBy: &users[i],
			CreatedAt:   now,
			UpdatedAt:   now,
			Version:     1,
		})
	}
	_, err := col.InsertMany(ctx, docs)
	return err
}

// ─── Transactions (income / expense) ─────────────────────────────────────────

func seedTransactions(
	ctx context.Context,
	db *mongo.Database,
	txType models.TransactionType,
	count int,
	fromTime, toTime time.Time,
	users []models.UserInfo,
	rng *rand.Rand,
) (int, error) {
	col := db.Collection("transactions")

	rangeNs := toTime.UnixNano() - fromTime.UnixNano()

	const batchSize = 500
	totalInserted := 0
	batch := make([]any, 0, batchSize)

	for i := 0; i < count; i++ {
		date := time.Unix(0, fromTime.UnixNano()+rng.Int63n(rangeNs)).UTC()

		var category, source, purpose string
		var amount float64

		if txType == models.Expense {
			t := pickExpenseTemplate(rng)
			category = t.Category
			source = t.Source
			purpose = t.Purpose
			amount = roundRub(t.MinAmount + rng.Float64()*(t.MaxAmount-t.MinAmount))
		} else {
			t := pickIncomeTemplate(rng)
			category = t.Category
			source = t.Source
			purpose = t.Purpose
			amount = roundRub(t.MinAmount + rng.Float64()*(t.MaxAmount-t.MinAmount))
		}

		userIdx := pickWeightedUser(rng)
		author := users[userIdx]

		now := time.Now()
		doc := models.Transaction{
			ID:             uuid.NewString(),
			Type:           txType,
			Amount:         amount,
			Date:           date,
			Category:       category,
			Source:         source,
			Purpose:        purpose,
			Description:    maybeDescription(rng, purpose),
			CreatedBy:      &author,
			LastModifiedBy: &author,
			CreatedAt:      now,
			UpdatedAt:      now,
			Version:        1,
		}
		batch = append(batch, doc)

		if len(batch) >= batchSize {
			if _, err := col.InsertMany(ctx, batch); err != nil {
				return totalInserted, err
			}
			totalInserted += len(batch)
			batch = batch[:0]
		}
	}

	if len(batch) > 0 {
		if _, err := col.InsertMany(ctx, batch); err != nil {
			return totalInserted, err
		}
		totalInserted += len(batch)
	}

	return totalInserted, nil
}

func pickExpenseTemplate(rng *rand.Rand) expenseTemplate {
	total := 0
	for _, t := range expenseTemplates {
		total += t.Weight
	}
	r := rng.Intn(total)
	for _, t := range expenseTemplates {
		if r < t.Weight {
			return t
		}
		r -= t.Weight
	}
	return expenseTemplates[len(expenseTemplates)-1]
}

func pickIncomeTemplate(rng *rand.Rand) incomeTemplate {
	total := 0
	for _, t := range incomeTemplates {
		total += t.Weight
	}
	r := rng.Intn(total)
	for _, t := range incomeTemplates {
		if r < t.Weight {
			return t
		}
		r -= t.Weight
	}
	return incomeTemplates[len(incomeTemplates)-1]
}

// ─── Wishlist ────────────────────────────────────────────────────────────────

func seedWishlist(ctx context.Context, db *mongo.Database, count int, users []models.UserInfo, rng *rand.Rand) (int, error) {
	col := db.Collection("wishlist")

	now := time.Now()
	docs := make([]any, 0, count)
	for i := 0; i < count; i++ {
		t := wishlistTemplates[rng.Intn(len(wishlistTemplates))]
		userIdx := pickWeightedUser(rng)
		author := users[userIdx]

		cost := roundRub(t.MinCost + rng.Float64()*(t.MaxCost-t.MinCost))
		priority := t.PriorityRange[0]
		if span := t.PriorityRange[1] - t.PriorityRange[0]; span > 0 {
			priority += rng.Intn(span + 1)
		}

		// 30% of items are already purchased (gives forecast realistic mix)
		purchased := rng.Float64() < 0.3

		// Add a small variation suffix to the name to avoid duplicate-looking rows
		name := t.Name
		if rng.Float64() < 0.4 {
			name = fmt.Sprintf("%s #%d", t.Name, rng.Intn(900)+100)
		}

		docs = append(docs, models.WishlistItem{
			ID:             uuid.NewString(),
			Name:           name,
			EstimatedCost:  cost,
			Category:       t.Category,
			Priority:       priority,
			Frequency:      t.Frequency,
			Purchased:      purchased,
			Notes:          t.Notes,
			CreatedBy:      &author,
			LastModifiedBy: &author,
			CreatedAt:      now,
			UpdatedAt:      now,
			Version:        1,
		})
	}

	if len(docs) == 0 {
		return 0, nil
	}
	if _, err := col.InsertMany(ctx, docs); err != nil {
		return 0, err
	}
	return len(docs), nil
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

func maybeDescription(rng *rand.Rand, purpose string) string {
	if rng.Float64() < 0.7 {
		return ""
	}
	notes := []string{
		"Срочно", "По акции", "С кэшбэком", "Семейная закупка",
		"Поездка по работе", "С детьми", "Подарок", "Замена сломанного",
		"Раз в неделю", "Регулярная оплата",
	}
	return notes[rng.Intn(len(notes))]
}

// roundRub rounds to whole rubles.
func roundRub(v float64) float64 {
	return float64(int64(v + 0.5))
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
