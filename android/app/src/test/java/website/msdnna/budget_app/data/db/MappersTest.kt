package website.msdnna.budget_app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem

/**
 * Round-trip Entity<->Model mappers. Catches column drift between
 * Mappers.kt и data class'ами Models.kt — частая регрессия после миграций.
 */
class MappersTest {

    @Test
    fun `transaction roundtrip preserves all fields incl detail-request and wishlist linkage`() {
        val original = Transaction(
            id = "tx-1",
            type = "expense",
            amount = 1234.56,
            date = "2026-05-12",
            category = "food",
            source = null,
            purpose = "lunch",
            description = "burger",
            hidden = false,
            createdBy = UserInfo("u1", "Alice", "a.png"),
            createdAt = "2026-05-12T10:00:00Z",
            version = 3,
            updatedAt = "2026-05-12T11:00:00Z",
            deletedAt = null,
            lastModifiedBy = UserInfo("u2", "Bob", null),
            parentId = "parent-9",
            detailRequestId = "dr-1",
            detailRequestStatus = "open",
            excludedFromStats = true,
            wishlistId = "wl-7",
        )

        val roundTripped = original.toEntity(SyncStatus.PENDING_UPDATE).toModel()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `transaction with null createdBy collapses createdBy to null after roundtrip`() {
        val original = Transaction(
            id = "tx-2",
            createdBy = null,
            lastModifiedBy = null,
            createdAt = "2026-05-12T00:00:00Z",
            updatedAt = "2026-05-12T00:00:00Z",
        )

        val result = original.toEntity().toModel()

        assertThat(result.createdBy).isNull()
        assertThat(result.lastModifiedBy).isNull()
    }

    @Test
    fun `entity syncStatus and serverPayload are set from toEntity arguments`() {
        val tx = Transaction(id = "tx-3", createdAt = "x", updatedAt = "x")
        val entity = tx.toEntity(SyncStatus.CONFLICT, serverPayload = """{"foo":1}""")

        assertThat(entity.syncStatus).isEqualTo(SyncStatus.CONFLICT)
        assertThat(entity.serverPayload).isEqualTo("""{"foo":1}""")
    }

    @Test
    fun `wishlist roundtrip preserves notes priority and frequency`() {
        val original = WishlistItem(
            id = "wl-1",
            name = "Holidays",
            estimatedCost = 50_000.0,
            category = "vacation",
            priority = 1,
            purchased = false,
            frequency = "yearly",
            notes = "Сочи",
            createdBy = UserInfo("u1", "Alice", null),
            createdAt = "2026-01-01",
            version = 2,
            updatedAt = "2026-05-01",
            deletedAt = null,
            lastModifiedBy = null,
        )

        // WishlistItem includes `isFavorite` which isn't persisted by the entity;
        // copy it back to compare the persisted projection only.
        val result = original.toEntity().toModel().copy(isFavorite = original.isFavorite)
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun `category roundtrip preserves section and isDefault`() {
        val original = Category(
            id = "cat-1",
            section = "expense",
            name = "Продукты",
            isDefault = true,
            createdAt = "2026-01-01",
            version = 1,
            updatedAt = "2026-01-01",
            deletedAt = null,
        )

        val result = original.toEntity().toModel()
        // CategoryEntity.toModel() doesn't carry lastModifiedBy, but neither did
        // the original — direct equality is the right assertion.
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun `category roundtrip preserves monthly_limit incl null`() {
        val withLimit = Category(
            id = "cat-2",
            section = "expense",
            name = "Транспорт",
            isDefault = false,
            monthlyLimit = 5_000.0,
            createdAt = "2026-01-01",
            version = 1,
            updatedAt = "2026-01-01",
            deletedAt = null,
        )
        val withoutLimit = withLimit.copy(monthlyLimit = null)

        assertThat(withLimit.toEntity().toModel().monthlyLimit).isEqualTo(5_000.0)
        assertThat(withoutLimit.toEntity().toModel().monthlyLimit).isNull()
    }

    @Test
    fun `parseTransaction reads server JSON produced by toJson`() {
        val original = Transaction(
            id = "tx-9",
            type = "income",
            amount = 100.0,
            date = "2026-05-01",
            category = "salary",
            createdAt = "2026-05-01T00:00:00Z",
            version = 1,
            updatedAt = "2026-05-01T00:00:00Z",
            createdBy = UserInfo("u1", "Alice", null),
        )

        val parsed = parseTransaction(original.toJson())
        assertThat(parsed).isEqualTo(original)
    }
}
