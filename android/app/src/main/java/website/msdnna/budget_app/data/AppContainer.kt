package website.msdnna.budget_app.data

import android.content.Context
import website.msdnna.budget_app.data.db.AppDatabase
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.data.sync.NetworkObserver
import website.msdnna.budget_app.data.sync.SyncEngine

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
}
