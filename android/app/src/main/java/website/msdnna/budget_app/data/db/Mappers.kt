package website.msdnna.budget_app.data.db

import com.google.gson.Gson
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem

private val gson = Gson()

// ---- Transaction ----

fun TransactionEntity.toModel(): Transaction = Transaction(
    id = id,
    type = type,
    amount = amount,
    date = date,
    category = category,
    source = source,
    purpose = purpose,
    description = description,
    hidden = hidden,
    createdBy = createdById?.let { UserInfo(it, createdByName.orEmpty(), createdByAvatar) },
    createdAt = createdAt,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    lastModifiedBy = lastModifiedById?.let { UserInfo(it, lastModifiedByName.orEmpty(), lastModifiedByAvatar) },
    parentId = parentId,
    detailRequestId = detailRequestId,
    detailRequestStatus = detailRequestStatus,
    excludedFromStats = excludedFromStats,
    wishlistId = wishlistId,
    deposit = deposit.ifBlank { "bank" },
)

fun Transaction.toEntity(syncStatus: String = SyncStatus.SYNCED, serverPayload: String? = null): TransactionEntity =
    TransactionEntity(
        id = id,
        type = type,
        amount = amount,
        date = date,
        category = category,
        source = source,
        purpose = purpose,
        description = description,
        hidden = hidden,
        createdById = createdBy?.userId,
        createdByName = createdBy?.displayName,
        createdByAvatar = createdBy?.avatarUrl,
        lastModifiedById = lastModifiedBy?.userId,
        lastModifiedByName = lastModifiedBy?.displayName,
        lastModifiedByAvatar = lastModifiedBy?.avatarUrl,
        createdAt = createdAt,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        serverPayload = serverPayload,
        parentId = parentId,
        detailRequestId = detailRequestId,
        detailRequestStatus = detailRequestStatus,
        excludedFromStats = excludedFromStats,
        wishlistId = wishlistId,
        deposit = deposit.ifBlank { "bank" },
    )

// ---- Wishlist ----

fun WishlistEntity.toModel(): WishlistItem = WishlistItem(
    id = id,
    name = name,
    estimatedCost = estimatedCost,
    category = category,
    priority = priority,
    purchased = purchased,
    frequency = frequency,
    deposit = deposit.ifBlank { "bank" },
    notes = notes,
    createdBy = createdById?.let { UserInfo(it, createdByName.orEmpty(), createdByAvatar) },
    createdAt = createdAt,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    lastModifiedBy = lastModifiedById?.let { UserInfo(it, lastModifiedByName.orEmpty(), lastModifiedByAvatar) },
)

fun WishlistItem.toEntity(syncStatus: String = SyncStatus.SYNCED, serverPayload: String? = null): WishlistEntity =
    WishlistEntity(
        id = id,
        name = name,
        estimatedCost = estimatedCost,
        category = category,
        priority = priority,
        frequency = frequency,
        purchased = purchased,
        notes = notes,
        createdById = createdBy?.userId,
        createdByName = createdBy?.displayName,
        createdByAvatar = createdBy?.avatarUrl,
        lastModifiedById = lastModifiedBy?.userId,
        lastModifiedByName = lastModifiedBy?.displayName,
        lastModifiedByAvatar = lastModifiedBy?.avatarUrl,
        createdAt = createdAt,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        serverPayload = serverPayload,
        deposit = deposit.ifBlank { "bank" },
    )

// ---- Category ----

fun CategoryEntity.toModel(): Category = Category(
    id = id,
    section = section,
    name = name,
    color = color?.takeIf { it.isNotEmpty() },
    icon = icon?.takeIf { it.isNotEmpty() },
    iconScale = iconScale,
    monthlyLimit = monthlyLimit,
    isDefault = isDefault,
    createdAt = createdAt,
    version = version,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

fun Category.toEntity(syncStatus: String = SyncStatus.SYNCED, serverPayload: String? = null): CategoryEntity =
    CategoryEntity(
        id = id,
        section = section,
        name = name,
        color = color.orEmpty(),
        icon = icon.orEmpty(),
        iconScale = iconScale,
        monthlyLimit = monthlyLimit,
        isDefault = isDefault,
        createdAt = createdAt,
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
        serverPayload = serverPayload,
    )

// ---- Server payload <-> Model (used during conflict resolution) ----

fun Transaction.toJson(): String = gson.toJson(this)
fun WishlistItem.toJson(): String = gson.toJson(this)
fun Category.toJson(): String = gson.toJson(this)

fun parseTransaction(json: String): Transaction = gson.fromJson(json, Transaction::class.java)
fun parseWishlistItem(json: String): WishlistItem = gson.fromJson(json, WishlistItem::class.java)
fun parseCategory(json: String): Category = gson.fromJson(json, Category::class.java)
