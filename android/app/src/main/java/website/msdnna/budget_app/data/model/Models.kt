package website.msdnna.budget_app.data.model

import com.google.gson.annotations.SerializedName

data class UserInfo(
    @SerializedName("user_id")      val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_url")   val avatarUrl: String? = null
)

data class HealthResponse(val ok: Boolean, val app: String)

data class LoginRequest(
    val login: String,
    val password: String
)

data class LoginResponse(
    val token: String = "",
    @SerializedName("user_id")      val userId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("avatar_url")   val avatarUrl: String? = null,
    @SerializedName("expires_at")   val expiresAt: String = ""
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
    @SerializedName("created_by") val createdBy: UserInfo? = null
)

data class TransactionListResponse(
    val data: List<Transaction>? = null,   // Go sends null for empty slice; use .orEmpty()
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
    val description: String? = null
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
    val breakdown: List<CategoryStat> = emptyList(),
    @SerializedName("regular_items") val regularItems: List<RegularItem> = emptyList(),
    @SerializedName("unpurchased_wishlist") val unpurchasedWishlist: List<WishlistItem> = emptyList()
)

data class RegularItem(
    val id: String = "",
    val name: String = "",
    @SerializedName("monthly_cost") val monthlyCost: Double = 0.0,
    val frequency: String = "monthly",
    val category: String = ""
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
    val notes: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null
)

data class CreateWishlistRequest(
    val name: String,
    @SerializedName("estimated_cost") val estimatedCost: Double,
    val category: String,
    val frequency: String = "once",
    val priority: Int = 5,
    val notes: String? = null
)

data class UpdateTransactionRequest(
    val amount: Double? = null,
    val date: String? = null,
    val category: String? = null,
    val source: String? = null,
    val purpose: String? = null,
    val description: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null
)

data class UpdateWishlistRequest(
    val name: String? = null,
    @SerializedName("estimated_cost") val estimatedCost: Double? = null,
    val category: String? = null,
    val frequency: String? = null,
    val purchased: Boolean? = null,
    val notes: String? = null,
    @SerializedName("created_by") val createdBy: UserInfo? = null
)
