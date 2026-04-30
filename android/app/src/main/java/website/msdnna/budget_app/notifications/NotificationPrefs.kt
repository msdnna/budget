package website.msdnna.budget_app.notifications

data class NotificationPrefs(
    val expensesEnabled: Boolean = false,
    val expensesHour: Int = 21,
    val expensesMinute: Int = 0,
    val incomeEnabled: Boolean = false,
    val incomeHour: Int = 12,
    val incomeMinute: Int = 0,
    val incomeDay: Int = 30
)
