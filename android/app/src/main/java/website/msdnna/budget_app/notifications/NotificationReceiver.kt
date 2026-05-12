package website.msdnna.budget_app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar
import website.msdnna.budget_app.MainActivity
import website.msdnna.budget_app.R

class NotificationReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "budget_reminders"
        private const val ID_EXPENSES = 2001
        private const val ID_INCOME = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val frequency = NotificationFrequency.fromName(
            intent.getStringExtra(NotificationScheduler.EXTRA_FREQUENCY),
            NotificationFrequency.DAILY,
        )
        val hour = intent.getIntExtra(NotificationScheduler.EXTRA_HOUR, 0)
        val minute = intent.getIntExtra(NotificationScheduler.EXTRA_MINUTE, 0)
        val dayOfWeek = intent.getIntExtra(NotificationScheduler.EXTRA_DAY_OF_WEEK, Calendar.MONDAY)
        val dayOfMonth = intent.getIntExtra(NotificationScheduler.EXTRA_DAY_OF_MONTH, 1)

        when (action) {
            NotificationScheduler.ACTION_EXPENSES -> {
                show(context, ID_EXPENSES, "Расходы", "Не забудьте внести расходы")
                NotificationScheduler.scheduleExpenses(
                    context, frequency, hour, minute, dayOfWeek, dayOfMonth,
                )
            }
            NotificationScheduler.ACTION_INCOME -> {
                show(context, ID_INCOME, "Доходы", "Не забудьте внести доходы")
                NotificationScheduler.scheduleIncome(
                    context, frequency, hour, minute, dayOfWeek, dayOfMonth,
                )
            }
        }
    }

    private fun show(context: Context, id: Int, title: String, text: String) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val tapPi = PendingIntent.getActivity(
            context, id, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }
}
