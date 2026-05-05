package website.msdnna.budget_app.notifications

import java.util.Calendar

enum class NotificationFrequency {
    DAILY, WEEKLY, MONTHLY, QUARTERLY;

    companion object {
        fun fromName(name: String?, default: NotificationFrequency): NotificationFrequency =
            entries.firstOrNull { it.name == name } ?: default
    }
}

data class NotificationPrefs(
    val expensesEnabled: Boolean = false,
    val expensesFrequency: NotificationFrequency = NotificationFrequency.DAILY,
    val expensesHour: Int = 21,
    val expensesMinute: Int = 0,
    val expensesDayOfWeek: Int = Calendar.MONDAY,
    val expensesDayOfMonth: Int = 1,

    val incomeEnabled: Boolean = false,
    val incomeFrequency: NotificationFrequency = NotificationFrequency.MONTHLY,
    val incomeHour: Int = 12,
    val incomeMinute: Int = 0,
    val incomeDayOfWeek: Int = Calendar.MONDAY,
    val incomeDayOfMonth: Int = 30,
)
