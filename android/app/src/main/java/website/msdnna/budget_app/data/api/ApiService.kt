package website.msdnna.budget_app.data.api

import okhttp3.RequestBody
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
        @Query("to") to: String? = null,
        @Query("deposit") deposit: String? = null,
        @Query("unlinked") unlinked: Boolean? = null,
        @Query("include_split") includeSplit: Boolean? = null,
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

    @POST("transactions/{id}/split")
    suspend fun splitTransaction(
        @Path("id") id: String,
        @Body body: website.msdnna.budget_app.data.model.SplitRequest,
    ): website.msdnna.budget_app.data.model.SplitResponse

    @POST("transactions/{id}/unsplit")
    suspend fun unsplitTransaction(@Path("id") id: String): Transaction

    @GET("statistics/summary")
    suspend fun getStatsSummary(
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("deposit") deposit: String? = null
    ): StatsSummary

    @GET("statistics/by-category")
    suspend fun getByCategory(
        @Query("type") type: String,
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("deposit") deposit: String? = null
    ): List<CategoryStat>

    @GET("statistics/monthly")
    suspend fun getMonthlyStats(
        @Query("year") year: String? = null,
        @Query("deposit") deposit: String? = null
    ): List<MonthlyStat>

    @GET("statistics/forecast")
    suspend fun getForecast(
        @Query("deposit") deposit: String? = null
    ): ForecastData

    @GET("statistics/overview")
    suspend fun getStatisticsOverview(
        @Query("month") month: String? = null,
        @Query("year") year: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("deposit") deposit: String? = null,
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

    // PATCH /categories/{id} — admin-only. Caller passes a pre-built JSON
    // body so it can emit a literal `null` for monthly_limit (default Gson
    // drops null map values, which the backend would read as "leave
    // unchanged"; we need "clear"). See CategoryRepository.patch.
    @Headers("Content-Type: application/json; charset=utf-8")
    @PATCH("categories/{id}")
    suspend fun patchCategory(
        @Path("id") id: String,
        @Body body: RequestBody
    ): website.msdnna.budget_app.data.model.Category

    @GET("categories/limits-progress")
    suspend fun getLimitsProgress(
        @Query("month") month: String? = null
    ): website.msdnna.budget_app.data.model.LimitsProgressResponse

    @GET("notifications")
    suspend fun getNotifications(
        @Query("limit") limit: Int? = null
    ): website.msdnna.budget_app.data.model.NotificationsListResponse

    @POST("notifications/read-all")
    suspend fun markAllNotificationsRead(): Map<String, @JvmSuppressWildcards Any>

    @POST("notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Map<String, @JvmSuppressWildcards Any>

    @DELETE("wishlist/{id}")
    suspend fun deleteWishlistItem(@Path("id") id: String): Response<Unit>

    /** Bulk-clear wishlist_id on every linked expense in the recurring item's
     *  current period. Backs the «Отменить» action on Регулярные расходы. */
    @POST("wishlist/{id}/unlink-period")
    suspend fun unlinkWishlistPeriod(@Path("id") id: String): Map<String, @JvmSuppressWildcards Any>

    /** Attach an existing expense transaction to a wishlist/regular item.
     *  Server clones the wishlist category into expense if missing and (for
     *  `once`-items) flips `purchased=true`. See backend api ≥ 1.21.0. */
    @POST("wishlist/{id}/link/{tx_id}")
    suspend fun linkWishlistToExpense(
        @Path("id") id: String,
        @Path("tx_id") txId: String
    ): Transaction

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
