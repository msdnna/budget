package website.msdnna.budget_app.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import website.msdnna.budget_app.data.model.*

interface ApiService {

    @GET("health")
    suspend fun health(): website.msdnna.budget_app.data.model.HealthResponse

    @GET("version")
    suspend fun getVersion(): website.msdnna.budget_app.data.model.VersionResponse

    @POST("auth/login")
    suspend fun login(@Body body: website.msdnna.budget_app.data.model.LoginRequest): website.msdnna.budget_app.data.model.LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: website.msdnna.budget_app.data.model.RefreshRequest): website.msdnna.budget_app.data.model.RefreshResponse

    @GET("auth/me")
    suspend fun getMe(): Map<String, @JvmSuppressWildcards Any>

    @GET("transactions")
    suspend fun getTransactions(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 30,
        @Query("type") type: String? = null,
        @Query("category") category: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): TransactionListResponse

    @POST("transactions")
    suspend fun createTransaction(@Body body: CreateTransactionRequest): Transaction

    @PUT("transactions/{id}")
    suspend fun updateTransaction(
        @Path("id") id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Transaction

    @PUT("transactions/{id}")
    suspend fun updateTransactionTyped(
        @Path("id") id: String,
        @Body body: website.msdnna.budget_app.data.model.UpdateTransactionRequest
    ): Transaction

    @DELETE("transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: String): Response<Unit>

    @GET("statistics/summary")
    suspend fun getStatsSummary(
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): StatsSummary

    @GET("statistics/by-category")
    suspend fun getByCategory(
        @Query("type") type: String,
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): List<CategoryStat>

    @GET("statistics/monthly")
    suspend fun getMonthlyStats(
        @Query("year") year: String? = null
    ): List<MonthlyStat>

    @GET("statistics/forecast")
    suspend fun getForecast(): ForecastData

    @GET("statistics/overview")
    suspend fun getStatisticsOverview(
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): website.msdnna.budget_app.data.model.StatisticsOverviewResponse

    @GET("wishlist")
    suspend fun getWishlist(): List<WishlistItem>

    @POST("wishlist")
    suspend fun createWishlistItem(@Body body: CreateWishlistRequest): WishlistItem

    @PUT("wishlist/{id}")
    suspend fun updateWishlistItem(
        @Path("id") id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): WishlistItem

    @PUT("wishlist/{id}")
    suspend fun updateWishlistItemTyped(
        @Path("id") id: String,
        @Body body: website.msdnna.budget_app.data.model.UpdateWishlistRequest
    ): WishlistItem

    @GET("users")
    suspend fun getUsers(): List<website.msdnna.budget_app.data.model.UserInfo>

    @GET("categories")
    suspend fun getCategories(
        @Query("section") section: String
    ): List<website.msdnna.budget_app.data.model.Category>

    @GET("categories/all")
    suspend fun getAllCategories(): website.msdnna.budget_app.data.model.CategoriesAllResponse

    @POST("categories")
    suspend fun createCategory(
        @Body body: website.msdnna.budget_app.data.model.CreateCategoryRequest
    ): website.msdnna.budget_app.data.model.Category

    @DELETE("categories/{id}")
    suspend fun deleteCategory(@Path("id") id: String): Response<Unit>

    @DELETE("wishlist/{id}")
    suspend fun deleteWishlistItem(@Path("id") id: String): Response<Unit>

    /** Bulk-clear wishlist_id on every linked expense in the recurring item's
     *  current period. Backs the «Отменить» action on Регулярные расходы. */
    @POST("wishlist/{id}/unlink-period")
    suspend fun unlinkWishlistPeriod(@Path("id") id: String): Map<String, @JvmSuppressWildcards Any>

    @GET("sync/pull")
    suspend fun syncPull(
        @Query("since") since: String? = null
    ): website.msdnna.budget_app.data.model.SyncPullResponse

    @POST("sync/push")
    suspend fun syncPush(
        @Body body: website.msdnna.budget_app.data.model.SyncPushRequest
    ): website.msdnna.budget_app.data.model.SyncPushResponse

    // ───── Detail requests (online-only) ───────────────────────────────

    @GET("detail-requests")
    suspend fun listDetailRequests(
        @Query("assignee_id") assigneeId: String? = null,
        @Query("creator_id") creatorId: String? = null,
        @Query("status") status: String? = null,
    ): List<website.msdnna.budget_app.data.model.DetailRequest>

    @POST("detail-requests")
    suspend fun createDetailRequest(
        @Body body: website.msdnna.budget_app.data.model.CreateDetailRequestPayload
    ): website.msdnna.budget_app.data.model.DetailRequest

    @GET("detail-requests/{id}")
    suspend fun getDetailRequest(@Path("id") id: String): website.msdnna.budget_app.data.model.DetailRequestView

    @POST("detail-requests/{id}/transactions")
    suspend fun addDetailRequestChild(
        @Path("id") id: String,
        @Body body: CreateTransactionRequest
    ): Transaction

    @POST("detail-requests/{id}/close")
    suspend fun closeDetailRequest(@Path("id") id: String): website.msdnna.budget_app.data.model.DetailRequest

    @POST("detail-requests/{id}/cancel")
    suspend fun cancelDetailRequest(@Path("id") id: String): Map<String, String>

    @Streaming
    @GET("export/excel")
    suspend fun exportExcel(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("type") type: String? = null
    ): ResponseBody

    @Streaming
    @GET("export/pdf")
    suspend fun exportPdf(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("type") type: String? = null
    ): ResponseBody
}
