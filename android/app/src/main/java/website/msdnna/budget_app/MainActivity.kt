package website.msdnna.budget_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.notifications.NotificationReceiver
import website.msdnna.budget_app.notifications.NotificationScheduler
import website.msdnna.budget_app.ui.components.MbLogo
import website.msdnna.budget_app.ui.screens.ConnectScreen
import website.msdnna.budget_app.ui.screens.MainScreen
import website.msdnna.budget_app.ui.theme.BudgetTheme
import website.msdnna.budget_app.ui.theme.themeByKey

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        val prefs = AppPreferences(this)

        setContent {
            val themeKey    by prefs.themeKey.collectAsStateWithLifecycle(initialValue = "blue")
            val isDark      by prefs.darkMode.collectAsStateWithLifecycle(initialValue = false)
            val activeTheme  = themeByKey(themeKey)
            val primaryColor = activeTheme.primary
            val scope        = rememberCoroutineScope()

            val insetsController = remember {
                WindowCompat.getInsetsController(window, window.decorView)
            }
            LaunchedEffect(isDark) {
                insetsController.isAppearanceLightStatusBars     = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }

            // null = still loading from DataStore
            var serverUrl    by remember { mutableStateOf<String?>(null) }
            var authToken    by remember { mutableStateOf<String?>(null) }
            var displayName  by remember { mutableStateOf("") }
            var avatarUrl    by remember { mutableStateOf<String?>(null) }

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted state is implicit; notifications fire if granted */ }

            LaunchedEffect(Unit) {
                // Read initial values from DataStore in one shot to avoid race.
                combine(prefs.serverUrl, prefs.authToken, prefs.displayName, prefs.avatarUrl)
                    { url, token, name, avatar -> arrayOf(url, token, name, avatar) }
                    .first()
                    .let { values ->
                        serverUrl   = values[0] as? String ?: ""
                        authToken   = values[1] as? String ?: ""
                        displayName = values[2] as? String ?: ""
                        avatarUrl   = values[3] as? String
                    }
            }

            // Register 401 callback — called on OkHttp background thread.
            DisposableEffect(Unit) {
                RetrofitClient.onUnauthorized = {
                    mainHandler.post {
                        authToken = ""
                        scope.launch { prefs.clearAuth() }
                    }
                }
                onDispose { RetrofitClient.onUnauthorized = null }
            }

            // Keep RetrofitClient.authToken in sync with state.
            LaunchedEffect(authToken) {
                RetrofitClient.authToken = authToken ?: ""
            }

            BudgetTheme(primary = primaryColor, isDark = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        serverUrl == null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MbLogo(primaryColor = primaryColor, fontSize = 72.sp)
                            }
                        }

                        serverUrl.isNullOrBlank() || authToken.isNullOrBlank() -> {
                            val serverHistory by prefs.serverHistory.collectAsStateWithLifecycle(initialValue = emptyList())
                            // Not connected or not logged in → show connect/login wizard.
                            ConnectScreen(
                                primaryColor   = primaryColor,
                                savedServerUrl = serverUrl.takeIf { !it.isNullOrBlank() },
                                serverHistory  = serverHistory,
                                onAuthenticated = { url, token, name, avatar ->
                                    serverUrl   = url
                                    authToken   = token
                                    displayName = name
                                    avatarUrl   = avatar
                                    scope.launch {
                                        prefs.setServerUrl(url)
                                        prefs.setAuth(token, "", name, avatar)
                                        prefs.addToServerHistory(url)
                                    }
                                }
                            )
                        }

                        else -> MainScreen(
                            serverUrl        = serverUrl!!,
                            primaryColor     = primaryColor,
                            isDark           = isDark,
                            prefs            = prefs,
                            activeTheme      = activeTheme,
                            displayName      = displayName,
                            avatarUrl        = avatarUrl,
                            onThemeChange    = { theme ->
                                scope.launch { prefs.setThemeKey(theme.key) }
                            },
                            onDarkModeChange = { dark ->
                                scope.launch { prefs.setDarkMode(dark) }
                            },
                            onResetServer = {
                                serverUrl = ""
                                authToken = ""
                                scope.launch {
                                    prefs.clearServerUrl()
                                    prefs.clearAuth()
                                }
                            },
                            onLogout = {
                                authToken = ""
                                scope.launch { prefs.clearAuth() }
                            },
                            onRequestNotifPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationReceiver.CHANNEL_ID,
                "Напоминания о бюджете",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания о внесении доходов и расходов"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
