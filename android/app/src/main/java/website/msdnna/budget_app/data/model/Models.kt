package website.msdnna.budget_app.data.model

import com.google.gson.annotations.SerializedName

data class UserInfo(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_url") val avatarUrl: String? = null
)

data class HealthResponse(val ok: Boolean, val app: String)

data class VersionResponse(
    val api: String = "",
    @SerializedName("android_latest") val androidLatest: String = "",
    @SerializedName("android_min_required") val androidMinRequired: String = "",
)

data class LoginRequest(
    val login: String,
    val password: String
)

data class LoginResponse(
    val token: String = "",
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("is_admin") val isAdmin: Boolean = false,
    @SerializedName("expires_at") val expiresAt: String = ""
)

data class RefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

data class RefreshResponse(
    val token: String = "",
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("expires_at") val expiresAt: String = ""
)

data class Transaction(
    val id: String = "",
    val type: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    val category: String = "",
    val source: String? = null,
    val purpose: String? = null,
    val description: String? = null,
    val hidden: Boolean = false,
    val deposit: String = "bank",
    @SerializedName("created_by") val createdBy: UserInfo? = null,
    @SerializedName("created_at") val createdAt: String = "",
    val version: Int = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("last_modified_by") val lastModifiedBy: UserInfo? = null,
    @SerializedName("parent_id") val parentId: String = "",
    @SerializedName("detail_request_id") val detailRequestId: String = "",
    @SerializedName("detail_request_status") val detailRequestStatus: String = "",
    @SerializedName("excluded_from_stats") val excludedFromStats: Boolean = false,
    @SerializedName("wishlist_id") val wishlistId: String = "",
)

// ===== Detail-request DTOs =====

data class DetailRequest(
    val id: String = "",
    @SerializedName("parent_transaction_id") val parentTransactionId: String = "",
    @SerializedName("target_amount") val targetAmount: Double = 0.0,
    val assignee: UserInfo? = null,
    val creator: UserInfo? = null,
    val status: String = "", // "open" | "closed"
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("closed_at") val closedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String = "",
)

data class DetailRequestView(
    val request: DetailRequest? = null,
    val parent: Transaction? = null,
    val children: List<Transaction> = emptyList(),
)

data class CreateDetailRequestPayload(
    @SerializedName("transaction_id") val transactionId: String,
    @SerializedName("assignee_id") val assigneeId: String,
)

data class TransactionListResponse(
    val data: List<Transaction>? = null, // Go sends null for empty slice; use .orEmpty()
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20
)

data class CreateTransactionRequest(
    val type: String,
    val amount: Double,
    val date: String,
    val category: String,
    val source: String? = null,
    val purpose: String? = null,
    val description: String? = null,
    val deposit: String = "bank",
    @SerializedName("wishlist_id") val wishlistId: String? = null,
)

data class StatsSummary(
    @SerializedName("total_income") val totalIncome: Double = 0.0,
    @SerializedName("total_expense") val totalExpense: Double = 0.0,
    val balance: Double = 0.0
)

data class CategoryStat(
    val category: String = "",
    val amount: Double = 0.0,
    val percentage: Double = 0.0,
    val count: Int = 0
)

data class MonthlyStat(
    val month: Int = 0,
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val balance: Double = 0.0
)

data class ForecastData(
    @SerializedName("total_monthly") val totalMonthly: Double = 0.0,
    @SerializedName("historical_avg") val historicalAvg: Double = 0.0,
    @SerializedName("wishlist_contrib") val wishlistContrib: Double = 0.0,
    // Subset of wishlist_contrib coming from recurring items only.
    // wishlist-only contribution = wishlistContrib - regularContrib.
    @SerializedName("regular_contrib") val regularContrib: Double = 0.0,
    val breakdown: List<CategoryStat> = emptyList(),
    @SerializedName("regular_items") val regularItems: List<RegularItem> = emptyList(),
    @SerializedName("unpurchased_wishlist") val unpurchasedWishlist: List<WishlistItem> = emptyList()
)

data class RegularItem(
    val id: String = "",
    val name: String = "",
    @SerializedName("estimated_cost") val estimatedCost: Double = 0.0,
    // Full per-period cost (api ≥ 1.11.0). Display with per-frequency suffix.
    @SerializedName("monthly_cost") val monthlyCost: Double = 0.0,
    val frequency: String = "monthly",
    val category: String = "",
    val deposit: String = "bank",
    @SerializedName("paid_this_period") val paidThisPeriod: Boolean = false,
    @SerializedName("paid_amount") val paidAmount: Double = 0.0,
    @SerializedName("paid_count") val paidCount: Int = 0,
    @SerializedName("next_due_date") val nextDueDate: String = "",
    // Mirrors the wishlist item's notes — used by the «Оплачено» flow to
    // prefill the expense form's «Описание».
    val notes: String = "",
)

data class WishlistItem(
    val id: String = "",
    val name: String = "",
    @SerializedName("estimated_cost") val estimatedCost: Double = 0.0,
    val category: String = "",
    val priority: Int = 5,
    @SerializedName("is_favorite") val isFavorite: Boolean = false,
    val purchased: Boolean = false,
    val frequency: String = "monthly",
    val deposit: String = "bank",
    val notes: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null,
    @SerializedName("created_at") val createdAt: String = "",
    val version: Int = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("last_modified_by") val lastModifiedBy: UserInfo? = null,
)

data class CreateWishlistRequest(
    val name: String,
    @SerializedName("estimated_cost") val estimatedCost: Double,
    val category: String,
    val frequency: String = "once",
    val priority: Int = 5,
    val deposit: String = "bank",
    val notes: String? = null
)

data class UpdateTransactionRequest(
    val amount: Double? = null,
    val date: String? = null,
    val category: String? = null,
    val source: String? = null,
    val purpose: String? = null,
    val description: String? = null,
    val deposit: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null
)

data class UpdateWishlistRequest(
    val name: String? = null,
    @SerializedName("estimated_cost") val estimatedCost: Double? = null,
    val category: String? = null,
    val frequency: String? = null,
    val purchased: Boolean? = null,
    val deposit: String? = null,
    val notes: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null
)

data class Category(
    val id: String = "",
    val section: String = "",
    val name: String = "",
    val color: String? = null,
    val icon: String? = null,
    @SerializedName("icon_scale") val iconScale: Double = 0.0,
    // monthly_limit — null = no limit, only meaningful on expense categories.
    @SerializedName("monthly_limit") val monthlyLimit: Double? = null,
    @SerializedName("is_default") val isDefault: Boolean = true,
    @SerializedName("created_at") val createdAt: String = "",
    val version: Int = 0,
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("last_modified_by") val lastModifiedBy: UserInfo? = null,
)

data class CreateCategoryRequest(
    val section: String,
    val name: String,
    val color: String? = null,
    val icon: String? = null,
)

data class StatisticsOverviewResponse(
    val summary: StatsSummary = StatsSummary(),
    val expenseByCategory: List<CategoryStat> = emptyList(),
    val incomeByCategory: List<CategoryStat> = emptyList(),
    val monthly: List<MonthlyStat> = emptyList()
)

data class CategoriesAllResponse(
    val expense: List<Category> = emptyList(),
    val income: List<Category> = emptyList(),
    val wishlist: List<Category> = emptyList()
)

// ===== Sync DTOs =====

data class SyncPullResponse(
    val transactions: List<Transaction> = emptyList(),
    val wishlist: List<WishlistItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    @SerializedName("server_time") val serverTime: String = "",
)

data class SyncOperation(
    @SerializedName("op_id") val opId: String,
    val type: String, // "create" | "update" | "delete"
    val entity: String, // "transaction" | "wishlist" | "category"
    val id: String,
    @SerializedName("base_version") val baseVersion: Int = 0,
    val force: Boolean = false,
    val payload: Any? = null,
)

data class SyncPushRequest(val operations: List<SyncOperation>)

data class SyncOperationResult(
    @SerializedName("op_id") val opId: String = "",
    val status: String = "", // "ok" | "conflict" | "error"
    val record: Map<String, @JvmSuppressWildcards Any?>? = null,
    val error: String? = null,
)

data class SyncPushResponse(
    val results: List<SyncOperationResult> = emptyList(),
    @SerializedName("server_time") val serverTime: String = "",
)

// ===== Category limits (Phase 5) =====

data class CategoryLimitProgress(
    @SerializedName("category_id") val categoryId: String = "",
    val name: String = "",
    val color: String? = null,
    val icon: String? = null,
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val percent: Double = 0.0,
)

data class LimitsProgressResponse(
    val period: String = "",
    val categories: List<CategoryLimitProgress> = emptyList(),
    @SerializedName("total_limit") val totalLimit: Double = 0.0,
    @SerializedName("total_spent") val totalSpent: Double = 0.0,
    @SerializedName("total_percent") val totalPercent: Double = 0.0,
)

// ===== Notifications (Phase 5) =====

data class ServerNotification(
    val id: String = "",
    val type: String = "", // "category_limit_exceeded" | "global_limit_exceeded"
    val period: String = "", // YYYY-MM
    @SerializedName("category_id") val categoryId: String = "",
    @SerializedName("category_name") val categoryName: String = "",
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    @SerializedName("read_by") val readBy: List<String>? = null,
    @SerializedName("created_at") val createdAt: String = "",
    val read: Boolean = false, // per-user view, computed by the server
)

data class NotificationsListResponse(
    val data: List<ServerNotification> = emptyList(),
    @SerializedName("unread_count") val unreadCount: Int = 0,
)
