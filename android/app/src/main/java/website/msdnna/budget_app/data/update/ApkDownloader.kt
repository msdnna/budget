package website.msdnna.budget_app.data.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadProgress {
    data class Running(val bytesRead: Long, val total: Long) : DownloadProgress {
        val fraction: Float get() = if (total > 0) bytesRead.toFloat() / total else 0f
    }
    data class Done(val file: File) : DownloadProgress
    data class Failed(val message: String) : DownloadProgress
}

object ApkDownloader {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    fun download(context: Context, url: String, version: String): Flow<DownloadProgress> = flow {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        // Clean stale APKs from previous attempts so getExternalFilesDir doesn't fill up.
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

        val target = File(dir, "msdnna-budget-app-v$version.apk")
        val request = Request.Builder().url(url).build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            emit(DownloadProgress.Failed(e.message ?: "network error"))
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(DownloadProgress.Failed(httpErrorMessage(resp.code)))
                return@flow
            }

            // Reject SPA fallbacks / error pages early — nginx may return 200 +
            // text/html for missing files when /apks/ isn't configured.
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.startsWith("text/html") || contentType.startsWith("text/plain")) {
                emit(DownloadProgress.Failed("сервер вернул не APK (нашли $contentType). Проверьте наличие файла в /apks/"))
                return@flow
            }

            val body = resp.body
            val total = body.contentLength()
            try {
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var sum = 0L
                        var lastEmitted = 0L
                        emit(DownloadProgress.Running(0L, total))
                        while (true) {
                            read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            sum += read
                            // Throttle progress emission to avoid recompose storms.
                            if (sum - lastEmitted > 64 * 1024 || sum == total) {
                                lastEmitted = sum
                                emit(DownloadProgress.Running(sum, total))
                            }
                        }
                        output.flush()
                    }
                }
            } catch (e: IOException) {
                target.delete()
                emit(DownloadProgress.Failed(e.message ?: "ошибка записи на диск"))
                return@flow
            }

            // APK is a zip — must start with PK\x03\x04. Anything else (HTML
            // error page that slipped past content-type, truncated download,
            // proxy nonsense) is unsafe to hand to PackageInstaller.
            if (!isApk(target)) {
                target.delete()
                emit(DownloadProgress.Failed("скачанный файл не является APK"))
                return@flow
            }

            emit(DownloadProgress.Done(target))
        }
    }.flowOn(Dispatchers.IO)

    private fun httpErrorMessage(code: Int): String = when (code) {
        404 -> "файл не найден на сервере (HTTP 404). Убедитесь, что APK загружен в /apks/"
        403 -> "доступ к файлу запрещён (HTTP 403)"
        in 500..599 -> "ошибка сервера (HTTP $code)"
        else -> "HTTP $code"
    }

    private fun isApk(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { stream ->
            val sig = ByteArray(4)
            if (stream.read(sig) != 4) return false
            // ZIP local file header — APKs always start with this.
            sig[0] == 0x50.toByte() &&
                sig[1] == 0x4B.toByte() &&
                sig[2] == 0x03.toByte() &&
                sig[3] == 0x04.toByte()
        }
    }
}
