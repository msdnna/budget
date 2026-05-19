package website.msdnna.budget_app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        WishlistEntity::class,
        CategoryEntity::class,
        NotificationHistoryEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun wishlist(): WishlistDao
    abstract fun categories(): CategoryDao
    abstract fun notifications(): NotificationHistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // v1 → v2: add detail-request columns to `transactions`.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN parent_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN detail_request_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN detail_request_status TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN excluded_from_stats INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 → v3: link expenses to recurring wishlist items.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN wishlist_id TEXT NOT NULL DEFAULT ''")
            }
        }

        // v3 → v4: category color/icon (filled from server on next sync pull).
        // Columns are nullable to match the Room schema generated from the
        // `String?` fields on CategoryEntity — using `NOT NULL` here causes
        // Room.validateMigration() to refuse the open with a hash mismatch.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN color TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT DEFAULT ''")
            }
        }

        // v4 → v5: per-category icon scale (0 = client uses default 1.0).
        // NOT NULL is fine here — the entity field is a non-nullable Double.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon_scale REAL NOT NULL DEFAULT 0")
            }
        }

        // v5 → v6: category monthly_limit (nullable — null = no limit
        // tracked) + new notification_history table backing the in-app
        // bell. The column is nullable so we can distinguish "no limit set"
        // from "limit of 0₽" (sentinel-free, mirrors backend semantics).
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN monthly_limit REAL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        server_id TEXT,
                        type TEXT NOT NULL,
                        period TEXT NOT NULL DEFAULT '',
                        category_id TEXT,
                        category_name TEXT,
                        `limit` REAL NOT NULL DEFAULT 0,
                        spent REAL NOT NULL DEFAULT 0,
                        title TEXT NOT NULL DEFAULT '',
                        body TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        read_local INTEGER NOT NULL DEFAULT 0,
                        pushed_at INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "msdnna_budget.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            ).build().also { instance = it }
        }
    }
}
