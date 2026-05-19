package website.msdnna.budget_app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import website.msdnna.budget_app.data.db.AppDatabase
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.data.sync.NetworkObserver
import website.msdnna.budget_app.data.sync.SyncEngine
import website.msdnna.budget_app.data.sync.SyncWorker

/**
 * Process-wide holder for shared data-layer singletons. Initialized in
 * [BudgetApplication.onCreate] so anywhere in the app can grab a Repository
 * without dependency injection plumbing.
 */
object AppContainer {
    lateinit var appContext: Context
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: AppPreferences
        private set
    lateinit var syncEngine: SyncEngine
        private set
    lateinit var networkObserver: NetworkObserver
        private set

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            db = AppDatabase.get(appContext)
            prefs = AppPreferences(appContext)
            syncEngine = SyncEngine(db, prefs)
            networkObserver = NetworkObserver(appContext)
            networkObserver.start()
            initialized = true
        }
    }

    /**
     * Стереть кэшированные «серверные» данные пользователя — Room (все
     * таблицы) + sync-cursor в DataStore + pending SyncWorker'ы. Вызывается
     * из явного logout'а: следующий вход (тот же или другой сервер)
     * стартует с чистого листа и тянет всё через `/sync/pull`.
     *
     * Что НЕ трогаем: device-локальные настройки (theme, dark mode,
     * server URL history, PIN/biometric — последние очищаются отдельным
     * `prefs.clearAuthAndSecurity()` в том же flow). Auth-token и
     * UserInfo (display_name/avatar_url/is_admin) — там же.
     *
     * Background sync отменяется ПЕРЕД `clearAllTables()`: уже-в-очереди
     * worker может стартовать после wipe и неудачно дёрнуть сервер на
     * промежуточном состоянии (или, что хуже, на новом сервере под
     * прежним auth-token'ом до того, как успеет прорастать clearAuth).
     *
     * Bug, который это лечит: переключение сервера без logout/wipe
     * оставляло старые UUID-категории в Room рядом с новыми с того же
     * сервера → дубликаты в UI («Жильё/ЖКХ» × 2 и т.д.).
     */
    suspend fun wipeUserData() {
        SyncWorker.cancel(appContext)
        withContext(Dispatchers.IO) { db.clearAllTables() }
        prefs.clearLastSyncToken()
    }
}
