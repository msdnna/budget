package website.msdnna.budget_app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.ui.components.SummaryCard
import java.io.File

private val EXPORT_TYPES = listOf(
    "" to "Все операции",
    "income" to "Только доходы",
    "expense" to "Только расходы"
)

@Composable
fun ExportScreen(serverUrl: String, primaryColor: Color) {
    val service = remember(serverUrl) { RetrofitClient.getService(serverUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedType by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Экспорт данных", style = MaterialTheme.typography.titleLarge)

        // Type selector
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Тип операций", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                EXPORT_TYPES.forEach { (key, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedType == key,
                            onClick = { selectedType = key },
                            colors = RadioButtonDefaults.colors(selectedColor = primaryColor)
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Export buttons
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Формат файла", style = MaterialTheme.typography.titleMedium)

                Button(
                    onClick = {
                        scope.launch {
                            loading = true; message = null
                            try {
                                val body = service.exportExcel(
                                    type = selectedType.ifBlank { null }
                                )
                                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                                val filename = "budget_${System.currentTimeMillis()}.xlsx"
                                val uri = saveFile(context, bytes, filename, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                if (uri != null) {
                                    message = "Файл сохранён: $filename"
                                    openFile(context, uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                }
                            } catch (e: Exception) {
                                message = "Ошибка: ${e.localizedMessage}"
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать Excel (.xlsx)", fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loading = true; message = null
                            try {
                                val body = service.exportPdf(
                                    type = selectedType.ifBlank { null }
                                )
                                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                                val filename = "budget_${System.currentTimeMillis()}.pdf"
                                val uri = saveFile(context, bytes, filename, "application/pdf")
                                if (uri != null) {
                                    message = "Файл сохранён: $filename"
                                    openFile(context, uri, "application/pdf")
                                }
                            } catch (e: Exception) {
                                message = "Ошибка: ${e.localizedMessage}"
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(primaryColor)
                    ),
                    enabled = !loading
                ) {
                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Скачать PDF", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        message?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.startsWith("Ошибка"))
                        MaterialTheme.colorScheme.errorContainer
                    else primaryColor.copy(alpha = 0.1f)
                )
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msg.startsWith("Ошибка"))
                        MaterialTheme.colorScheme.onErrorContainer
                    else primaryColor
                )
            }
        }
    }
}

private fun saveFile(context: Context, bytes: ByteArray, filename: String, mimeType: String): Uri? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.also { resolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, filename)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    } catch (e: Exception) {
        // Fallback: save to app cache
        val file = File(context.cacheDir, filename)
        file.writeBytes(bytes)
        Uri.fromFile(file)
    }
}

private fun openFile(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}
