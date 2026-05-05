package handlers

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"budget-go/models"
	"budget-go/repository"

	"github.com/gin-gonic/gin"
	"github.com/go-pdf/fpdf"
	"github.com/xuri/excelize/v2"
)

type ExportHandler struct {
	txRepo   *repository.TransactionRepository
	wlRepo   *repository.WishlistRepository
	fontPath string
}

func NewExportHandler(txRepo *repository.TransactionRepository, wlRepo *repository.WishlistRepository, fontPath string) *ExportHandler {
	return &ExportHandler{txRepo: txRepo, wlRepo: wlRepo, fontPath: fontPath}
}

func (h *ExportHandler) Excel(c *gin.Context) {
	from, to := parsePeriodParams(c)
	txType := c.Query("type")

	themeKey := c.Query("theme")
	theme, ok := pdfThemes[themeKey]
	if !ok {
		theme = pdfThemes["blue"]
	}

	transactions, err := h.txRepo.FindAll(c.Request.Context(), from, to, txType)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	f := excelize.NewFile()
	defer f.Close()

	// Sheet 1: All transactions
	sheet1 := "Транзакции"
	f.SetSheetName("Sheet1", sheet1)

	headers := []string{"Дата", "Тип", "Категория", "Сумма (₽)", "Источник/Назначение", "Описание"}
	for i, h := range headers {
		cell, _ := excelize.CoordinatesToCellName(i+1, 1)
		f.SetCellValue(sheet1, cell, h)
	}

	headerStyle, _ := f.NewStyle(&excelize.Style{
		Font:      &excelize.Font{Bold: true, Size: 11},
		Fill:      excelize.Fill{Type: "pattern", Color: []string{fmt.Sprintf("#%02X%02X%02X", theme.headerR, theme.headerG, theme.headerB)}, Pattern: 1},
		Alignment: &excelize.Alignment{Horizontal: "center"},
	})
	f.SetRowStyle(sheet1, 1, 1, headerStyle)

	incomeStyle, _ := f.NewStyle(&excelize.Style{
		Font: &excelize.Font{Color: fmt.Sprintf("#%02X%02X%02X", theme.incomeR, theme.incomeG, theme.incomeB)},
	})
	expenseStyle, _ := f.NewStyle(&excelize.Style{
		Font: &excelize.Font{Color: fmt.Sprintf("#%02X%02X%02X", theme.incomeR/2, theme.incomeG/2, theme.incomeB/2)},
	})

	for i, t := range transactions {
		row := i + 2
		srcPurpose := t.Source
		if t.Type == models.Expense {
			srcPurpose = t.Purpose
		}

		f.SetCellValue(sheet1, cellName(1, row), t.Date.Format("02.01.2006"))
		f.SetCellValue(sheet1, cellName(2, row), txTypeLabel(t.Type))
		f.SetCellValue(sheet1, cellName(3, row), t.Category)
		f.SetCellValue(sheet1, cellName(4, row), t.Amount)
		f.SetCellValue(sheet1, cellName(5, row), srcPurpose)
		f.SetCellValue(sheet1, cellName(6, row), t.Description)

		style := incomeStyle
		if t.Type == models.Expense {
			style = expenseStyle
		}
		for col := 1; col <= 6; col++ {
			f.SetCellStyle(sheet1, cellName(col, row), cellName(col, row), style)
		}
	}

	for i := range headers {
		col, _ := excelize.ColumnNumberToName(i + 1)
		f.SetColWidth(sheet1, col, col, 36)
	}

	// Sheet 2: Income by category
	incomeSheet := "Доходы по категориям"
	f.NewSheet(incomeSheet)
	writeCategorySheet(f, incomeSheet, transactions, models.Income, theme)

	// Sheet 3: Expenses by category
	expenseSheet := "Расходы по категориям"
	f.NewSheet(expenseSheet)
	writeCategorySheet(f, expenseSheet, transactions, models.Expense, theme)

	// Sheet 4: Monthly summary
	summarySheet := "Месячная сводка"
	f.NewSheet(summarySheet)
	writeMonthlySheet(f, summarySheet, transactions, theme)

	c.Header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=budget_%s.xlsx", time.Now().Format("2006-01-02")))

	if err := f.Write(c.Writer); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}

type pdfTheme struct {
	headerR, headerG, headerB int // table header bg + title text
	incomeR, incomeG, incomeB int // income row text
	rowR, rowG, rowB          int // alternating row tint
}

var pdfThemes = map[string]pdfTheme{
	"blue":   {32, 128, 240, 16, 96, 201, 235, 243, 255},
	"green":  {24, 160, 88, 12, 122, 67, 232, 248, 239},
	"red":    {208, 48, 80, 171, 31, 63, 253, 235, 238},
	"orange": {240, 160, 32, 201, 124, 16, 255, 246, 229},
	"purple": {114, 46, 209, 83, 29, 171, 243, 237, 255},
	"teal":   {14, 176, 169, 7, 135, 127, 230, 250, 249},
	"pink":   {235, 47, 150, 196, 29, 127, 255, 232, 246},
}

func (h *ExportHandler) PDF(c *gin.Context) {
	from, to := parsePeriodParams(c)
	txType := c.Query("type")

	themeKey := c.Query("theme")
	theme, ok := pdfThemes[themeKey]
	if !ok {
		theme = pdfThemes["blue"]
	}

	transactions, err := h.txRepo.FindAll(c.Request.Context(), from, to, txType)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	summary, err := h.txRepo.GetSummary(c.Request.Context(), from, to)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	fontName := "Arial"
	regularPath, boldPath := resolveDejaVuPaths(h.fontPath)

	fontDir := ""
	if regularPath != "" {
		fontDir = filepath.Dir(regularPath)
	}
	pdf := fpdf.New("L", "mm", "A4", fontDir)

	if regularPath != "" {
		pdf.AddUTF8Font("CustomFont", "", filepath.Base(regularPath))
		pdf.AddUTF8Font("CustomFont", "B", filepath.Base(boldPath))
		fontName = "CustomFont"
	}

	pdf.AddPage()

	// Title
	pdf.SetFont(fontName, "B", 18)
	pdf.SetTextColor(theme.headerR, theme.headerG, theme.headerB)
	pdf.CellFormat(0, 12, "Финансовый отчёт", "", 1, "C", false, 0, "")

	// Period
	pdf.SetFont(fontName, "", 10)
	pdf.SetTextColor(100, 100, 100)
	if !from.IsZero() && !to.IsZero() {
		pdf.CellFormat(0, 6, fmt.Sprintf("Период: %s — %s", from.Format("02.01.2006"), to.Format("02.01.2006")), "", 1, "C", false, 0, "")
	}
	pdf.CellFormat(0, 6, fmt.Sprintf("Сформирован: %s", time.Now().Format("02.01.2006 15:04")), "", 1, "C", false, 0, "")
	pdf.Ln(6)

	// Summary box
	pdf.SetFont(fontName, "B", 12)
	pdf.SetTextColor(0, 0, 0)
	pdf.CellFormat(0, 8, "Сводка", "", 1, "L", false, 0, "")
	pdf.SetFont(fontName, "", 11)

	summaryRows := [][]string{
		{"Доходы:", fmt.Sprintf("%.2f ₽", summary.TotalIncome)},
		{"Расходы:", fmt.Sprintf("%.2f ₽", summary.TotalExpense)},
		{"Баланс:", fmt.Sprintf("%.2f ₽", summary.Balance)},
	}

	colors := [][3]int{
		{theme.incomeR, theme.incomeG, theme.incomeB},
		{125, 26, 26},
		{0, 0, 0},
	}
	for i, row := range summaryRows {
		pdf.SetTextColor(colors[i][0], colors[i][1], colors[i][2])
		pdf.CellFormat(50, 7, row[0], "", 0, "L", false, 0, "")
		pdf.CellFormat(60, 7, row[1], "", 1, "L", false, 0, "")
	}
	pdf.SetTextColor(0, 0, 0)
	pdf.Ln(6)

	// Transactions table
	pdf.SetFont(fontName, "B", 12)
	pdf.CellFormat(0, 8, "Транзакции", "", 1, "L", false, 0, "")

	// Table header
	pdf.SetFillColor(theme.headerR, theme.headerG, theme.headerB)
	pdf.SetTextColor(255, 255, 255)
	pdf.SetFont(fontName, "B", 9)

	cols := []float64{30, 22, 45, 35, 55, 80}
	headers := []string{"Дата", "Тип", "Категория", "Сумма", "Источник/Цель", "Описание"}
	for i, h := range headers {
		pdf.CellFormat(cols[i], 7, h, "1", 0, "C", true, 0, "")
	}
	pdf.Ln(-1)

	pdf.SetFont(fontName, "", 8)
	fill := false
	for _, t := range transactions {
		if pdf.GetY() > 180 {
			pdf.AddPage()
			pdf.SetFillColor(theme.headerR, theme.headerG, theme.headerB)
			pdf.SetTextColor(255, 255, 255)
			pdf.SetFont(fontName, "B", 9)
			for i, h := range headers {
				pdf.CellFormat(cols[i], 7, h, "1", 0, "C", true, 0, "")
			}
			pdf.Ln(-1)
			pdf.SetFont(fontName, "", 8)
		}

		if fill {
			pdf.SetFillColor(theme.rowR, theme.rowG, theme.rowB)
		} else {
			pdf.SetFillColor(255, 255, 255)
		}

		switch t.Type {
		case models.Income, models.InitialBalance:
			pdf.SetTextColor(theme.incomeR, theme.incomeG, theme.incomeB)
		default:
			pdf.SetTextColor(125, 26, 26)
		}

		srcPurpose := t.Source
		if t.Type == models.Expense {
			srcPurpose = t.Purpose
		}

		pdf.CellFormat(cols[0], 6, t.Date.Format("02.01.2006"), "1", 0, "C", fill, 0, "")
		pdf.CellFormat(cols[1], 6, txTypeLabel(t.Type), "1", 0, "C", fill, 0, "")
		pdf.CellFormat(cols[2], 6, truncate(t.Category, 22), "1", 0, "L", fill, 0, "")
		pdf.CellFormat(cols[3], 6, fmt.Sprintf("%.2f ₽", t.Amount), "1", 0, "R", fill, 0, "")
		pdf.CellFormat(cols[4], 6, truncate(srcPurpose, 25), "1", 0, "L", fill, 0, "")
		pdf.CellFormat(cols[5], 6, truncate(t.Description, 35), "1", 1, "L", fill, 0, "")

		fill = !fill
	}

	c.Header("Content-Type", "application/pdf")
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=budget_%s.pdf", time.Now().Format("2006-01-02")))

	if err := pdf.Output(c.Writer); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}

func writeCategorySheet(f *excelize.File, sheet string, transactions []models.Transaction, txType models.TransactionType, theme pdfTheme) {
	headers := []string{"Категория", "Сумма (₽)", "Количество", "Доля (%)"}
	for i, h := range headers {
		cell, _ := excelize.CoordinatesToCellName(i+1, 1)
		f.SetCellValue(sheet, cell, h)
	}

	headerStyle, _ := f.NewStyle(&excelize.Style{
		Font:      &excelize.Font{Bold: true, Size: 11},
		Fill:      excelize.Fill{Type: "pattern", Color: []string{fmt.Sprintf("#%02X%02X%02X", theme.headerR, theme.headerG, theme.headerB)}, Pattern: 1},
		Alignment: &excelize.Alignment{Horizontal: "center"},
	})
	f.SetRowStyle(sheet, 1, 1, headerStyle)

	altStyle, _ := f.NewStyle(&excelize.Style{
		Fill: excelize.Fill{Type: "pattern", Color: []string{fmt.Sprintf("#%02X%02X%02X", theme.rowR, theme.rowG, theme.rowB)}, Pattern: 1},
	})

	catMap := make(map[string]struct {
		amount float64
		count  int
	})
	var total float64
	for _, t := range transactions {
		if t.Type == txType {
			e := catMap[t.Category]
			e.amount += t.Amount
			e.count++
			catMap[t.Category] = e
			total += t.Amount
		}
	}

	row := 2
	fill := false
	for cat, data := range catMap {
		pct := 0.0
		if total > 0 {
			pct = data.amount / total * 100
		}
		f.SetCellValue(sheet, cellName(1, row), cat)
		f.SetCellValue(sheet, cellName(2, row), data.amount)
		f.SetCellValue(sheet, cellName(3, row), data.count)
		f.SetCellValue(sheet, cellName(4, row), fmt.Sprintf("%.1f%%", pct))
		if fill {
			for col := 1; col <= 4; col++ {
				f.SetCellStyle(sheet, cellName(col, row), cellName(col, row), altStyle)
			}
		}
		row++
		fill = !fill
	}

	for i := range headers {
		col, _ := excelize.ColumnNumberToName(i + 1)
		f.SetColWidth(sheet, col, col, 36)
	}
}

func writeMonthlySheet(f *excelize.File, sheet string, transactions []models.Transaction, theme pdfTheme) {
	headers := []string{"Месяц", "Доходы (₽)", "Расходы (₽)", "Баланс (₽)"}
	for i, h := range headers {
		cell, _ := excelize.CoordinatesToCellName(i+1, 1)
		f.SetCellValue(sheet, cell, h)
	}

	headerStyle, _ := f.NewStyle(&excelize.Style{
		Font:      &excelize.Font{Bold: true, Size: 11},
		Fill:      excelize.Fill{Type: "pattern", Color: []string{fmt.Sprintf("#%02X%02X%02X", theme.headerR, theme.headerG, theme.headerB)}, Pattern: 1},
		Alignment: &excelize.Alignment{Horizontal: "center"},
	})
	f.SetRowStyle(sheet, 1, 1, headerStyle)

	altStyle, _ := f.NewStyle(&excelize.Style{
		Fill: excelize.Fill{Type: "pattern", Color: []string{fmt.Sprintf("#%02X%02X%02X", theme.rowR, theme.rowG, theme.rowB)}, Pattern: 1},
	})

	type monthKey struct{ year, month int }
	monthly := make(map[monthKey]struct{ income, initialBalance, expense float64 })

	for _, t := range transactions {
		k := monthKey{t.Date.Year(), int(t.Date.Month())}
		e := monthly[k]
		switch t.Type {
		case models.Income:
			e.income += t.Amount
		case models.InitialBalance:
			e.initialBalance += t.Amount
		default:
			e.expense += t.Amount
		}
		monthly[k] = e
	}

	monthNames := []string{"", "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
		"Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"}

	row := 2
	fill := false
	for k, data := range monthly {
		label := fmt.Sprintf("%s %d", monthNames[k.month], k.year)
		f.SetCellValue(sheet, cellName(1, row), label)
		f.SetCellValue(sheet, cellName(2, row), data.income)
		f.SetCellValue(sheet, cellName(3, row), data.expense)
		f.SetCellValue(sheet, cellName(4, row), data.income+data.initialBalance-data.expense)
		if fill {
			for col := 1; col <= 4; col++ {
				f.SetCellStyle(sheet, cellName(col, row), cellName(col, row), altStyle)
			}
		}
		row++
		fill = !fill
	}
}

func cellName(col, row int) string {
	name, _ := excelize.CoordinatesToCellName(col, row)
	return name
}

func txTypeLabel(t models.TransactionType) string {
	switch t {
	case models.Income:
		return "Доход"
	case models.InitialBalance:
		return "Нач. баланс"
	default:
		return "Расход"
	}
}

func truncate(s string, max int) string {
	runes := []rune(s)
	if len(runes) <= max {
		return s
	}
	return string(runes[:max-1]) + "…"
}

// resolveDejaVuPaths returns paths to DejaVuSans regular and bold fonts.
// Checks the configured path first, then falls back to known system locations.
func resolveDejaVuPaths(configuredPath string) (regular, bold string) {
	candidates := []string{
		configuredPath,
		"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", // Debian/Ubuntu
		"/usr/share/fonts/dejavu/DejaVuSans.ttf",          // Alpine (Docker)
	}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			dir := p[:len(p)-len("DejaVuSans.ttf")]
			boldPath := dir + "DejaVuSans-Bold.ttf"
			if _, err := os.Stat(boldPath); err != nil {
				boldPath = p // fall back to regular for bold if bold file missing
			}
			return p, boldPath
		}
	}
	return "", ""
}
