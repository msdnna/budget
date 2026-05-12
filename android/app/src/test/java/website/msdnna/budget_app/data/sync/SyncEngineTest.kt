package website.msdnna.budget_app.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import website.msdnna.budget_app.data.db.AppDatabase
import website.msdnna.budget_app.data.db.SyncStatus
import website.msdnna.budget_app.data.db.toJson
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.data.preferences.AppPreferences

/**
 * Покрывает offline-conflict-resolution-путь SyncEngine: resolveKeepServer.
 * Сетевые пути (`sync`, `resolveKeepLocal`) дороже в тестировании и требуют
 * RetrofitClient — здесь намеренно вне scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        engine = SyncEngine(db, mockk<AppPreferences>(relaxed = true))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `resolveKeepServer adopts server transaction overwriting local pending edits`() = runTest {
        val local = makeTx(id = "tx-1", amount = 100.0, version = 5, syncStatus = SyncStatus.CONFLICT)
        val server = Transaction(
            id = "tx-1",
            type = "expense",
            amount = 999.0,
            date = "2026-05-12",
            category = "food",
            createdAt = "2026-05-12T00:00:00Z",
            updatedAt = "2026-05-12T01:00:00Z",
            version = 6,
            createdBy = UserInfo("u1", "Alice", null),
        )
        db.transactions().upsert(local.copy(serverPayload = server.toJson()))

        val result = engine.resolveKeepServer("transaction", "tx-1")

        assertThat(result).isEqualTo(SyncEngine.Result.Success)
        val after = db.transactions().findById("tx-1")
        assertThat(after).isNotNull()
        assertThat(after!!.amount).isEqualTo(999.0)
        assertThat(after.version).isEqualTo(6)
        assertThat(after.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(after.serverPayload).isNull()
    }

    @Test
    fun `resolveKeepServer hard-deletes when server payload is a tombstone`() = runTest {
        val tombstone = Transaction(
            id = "tx-2",
            deletedAt = "2026-05-12T00:00:00Z",
        )
        val local = makeTx(
            id = "tx-2",
            amount = 50.0,
            version = 2,
            syncStatus = SyncStatus.CONFLICT,
        ).copy(serverPayload = tombstone.toJson())
        db.transactions().upsert(local)

        val result = engine.resolveKeepServer("transaction", "tx-2")

        assertThat(result).isEqualTo(SyncEngine.Result.Success)
        assertThat(db.transactions().findById("tx-2")).isNull()
    }

    @Test
    fun `resolveKeepServer hard-deletes when there is no serverPayload at all`() = runTest {
        val local = makeTx(id = "tx-3", amount = 10.0, version = 1, syncStatus = SyncStatus.CONFLICT)
        // serverPayload = null — deleteHard branch.
        db.transactions().upsert(local)

        val result = engine.resolveKeepServer("transaction", "tx-3")

        assertThat(result).isEqualTo(SyncEngine.Result.Success)
        assertThat(db.transactions().findById("tx-3")).isNull()
    }

    @Test
    fun `resolveKeepServer returns Skipped when row is missing`() = runTest {
        val result = engine.resolveKeepServer("transaction", "does-not-exist")
        assertThat(result).isEqualTo(SyncEngine.Result.Skipped)
    }

    @Test
    fun `resolveKeepServer works for wishlist entity too`() = runTest {
        val server = WishlistItem(
            id = "wl-1",
            name = "Server name",
            estimatedCost = 1000.0,
            category = "cat",
            priority = 3,
            purchased = false,
            frequency = "monthly",
            createdAt = "2026-05-01",
            updatedAt = "2026-05-02",
            version = 4,
        )
        db.wishlist().upsert(
            website.msdnna.budget_app.data.db.WishlistEntity(
                id = "wl-1",
                name = "Local name",
                estimatedCost = 500.0,
                category = "cat",
                priority = 5,
                frequency = "monthly",
                purchased = false,
                notes = null,
                createdById = null,
                createdByName = null,
                createdByAvatar = null,
                lastModifiedById = null,
                lastModifiedByName = null,
                lastModifiedByAvatar = null,
                createdAt = "2026-05-01",
                version = 3,
                updatedAt = "2026-05-01",
                deletedAt = null,
                syncStatus = SyncStatus.CONFLICT,
                serverPayload = server.toJson(),
            ),
        )

        val result = engine.resolveKeepServer("wishlist", "wl-1")

        assertThat(result).isEqualTo(SyncEngine.Result.Success)
        val after = db.wishlist().findById("wl-1")
        assertThat(after).isNotNull()
        assertThat(after!!.name).isEqualTo("Server name")
        assertThat(after.estimatedCost).isEqualTo(1000.0)
        assertThat(after.syncStatus).isEqualTo(SyncStatus.SYNCED)
    }

    @Test
    fun `resolveKeepServer works for category entity`() = runTest {
        val server = Category(
            id = "cat-1",
            section = "expense",
            name = "После",
            isDefault = false,
            createdAt = "2026-01-01",
            version = 2,
            updatedAt = "2026-05-12",
        )
        db.categories().upsert(
            website.msdnna.budget_app.data.db.CategoryEntity(
                id = "cat-1",
                section = "expense",
                name = "До",
                isDefault = false,
                createdAt = "2026-01-01",
                version = 1,
                updatedAt = "2026-01-01",
                deletedAt = null,
                syncStatus = SyncStatus.CONFLICT,
                serverPayload = server.toJson(),
            ),
        )

        val result = engine.resolveKeepServer("category", "cat-1")
        assertThat(result).isEqualTo(SyncEngine.Result.Success)
        assertThat(db.categories().findById("cat-1")!!.name).isEqualTo("После")
    }

    @Test
    fun `sync with blank serverUrl returns Skipped without touching DB`() = runTest {
        val local = makeTx(id = "tx-x", amount = 1.0, version = 0, syncStatus = SyncStatus.PENDING_CREATE)
        db.transactions().upsert(local)

        val result = engine.sync(serverUrl = "")

        assertThat(result).isEqualTo(SyncEngine.Result.Skipped)
        // Локальная строка не тронута.
        assertThat(db.transactions().findById("tx-x")!!.syncStatus).isEqualTo(SyncStatus.PENDING_CREATE)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeTx(
        id: String,
        amount: Double,
        version: Int,
        syncStatus: String,
    ): website.msdnna.budget_app.data.db.TransactionEntity =
        website.msdnna.budget_app.data.db.TransactionEntity(
            id = id,
            type = "expense",
            amount = amount,
            date = "2026-05-12",
            category = "food",
            source = null,
            purpose = null,
            description = null,
            hidden = false,
            createdById = "u1",
            createdByName = "Alice",
            createdByAvatar = null,
            lastModifiedById = null,
            lastModifiedByName = null,
            lastModifiedByAvatar = null,
            createdAt = "2026-05-12T00:00:00Z",
            version = version,
            updatedAt = "2026-05-12T00:00:00Z",
            deletedAt = null,
            syncStatus = syncStatus,
            serverPayload = null,
        )
}
