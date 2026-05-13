package website.msdnna.budget_app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.api.RetrofitClient

class BudgetApplication :
    Application(),
    ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }

    // Coil singleton: shares RetrofitClient.authToken via an interceptor so
    // requests to `<server>/api/icons/<id>` carry the Bearer header. Without
    // this the AsyncImage would 401 against our auth-required endpoint.
    // Memory + disk caches are explicit so multiple chart renders reuse the
    // same bytes — `Cache-Control: immutable` from the backend cooperates.
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = RetrofitClient.authToken
                val req = if (token.isNotBlank()) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(req)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            // SVG decoder for user-uploaded vector icons. PNG is handled by
            // the default BitmapFactory decoder.
            .components { add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("category_icons"))
                    .maxSizeBytes(8L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
