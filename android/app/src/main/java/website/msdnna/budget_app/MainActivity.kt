package website.msdnna.budget_app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.security.AppLock
import website.msdnna.budget_app.data.security.PinSecurity
import website.msdnna.budget_app.data.sync.ReachabilityGate
import website.msdnna.budget_app.notifications.NotificationReceiver
import website.msdnna.budget_app.notifications.NotificationScheduler
import website.msdnna.budget_app.ui.components.MbLogo
import website.msdnna.budget_app.ui.screens.ConnectScreen
import website.msdnna.budget_app.ui.screens.LockScreen
import website.msdnna.budget_app.ui.screens.MainScreen
import website.msdnna.budget_app.ui.screens.PinSetupScreen
import website.msdnna.budget_app.ui.theme.BudgetTheme
import website.msdnna.budget_app.ui.theme.themeByKey

private data class AuthSnapshot(val url: String?, val token: String?, val name: String, val avatar: String?)

class MainActivity : FragmentActivity() {
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
            var prefsReady   by remember { mutableStateOf(false) }
            // PIN state managed inside the loader effect (not via a separate
            // collectAsStateWithLifecycle) so the lock-gate decision can't
            // observe the initialValue=null race against `prefsReady=true`.
            var pinHash      by remember { mutableStateOf<String?>(null) }
            var pinSalt      by remember { mutableStateOf<String?>(null) }

            val notifPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* granted state is implicit; notifications fire if granted */ }

            LaunchedEffect(Unit) {
                // Read initial values from DataStore in one shot to avoid race.
                val snap = combine(prefs.serverUrl, prefs.authToken, prefs.displayName, prefs.avatarUrl)
                    { url, token, name, avatar -> AuthSnapshot(url, token, name, avatar) }
                    .first()
                val token = snap.token ?: ""
                // Materialise PIN values synchronously *before* flipping prefsReady so
                // the lock-gate sees a consistent snapshot. A separate reactive
                // collection used to race here and briefly report hasPin=false.
                val storedPin  = prefs.pinHash.first()
                val storedSalt = prefs.pinSalt.first()
                // Set RetrofitClient BEFORE updating state: recomposition creates ViewModels
                // whose init coroutines (Dispatchers.Main.immediate) enqueue OkHttp requests
                // synchronously. The token must be visible to OkHttp threads before that.
                RetrofitClient.authToken = token
                serverUrl   = snap.url ?: ""
                authToken   = token
                displayName = snap.name
                avatarUrl   = snap.avatar
                pinHash     = storedPin
                pinSalt     = storedSalt
                if (storedPin.isNullOrBlank()) AppLock.unlock()
                // Kick off the reachability probe BEFORE any HTTP fan-out so
                // the OkHttp interceptor has a chance to fail-fast cold-start
                // requests when the server is refusing/resetting connections.
                val initialUrl = snap.url
                if (!initialUrl.isNullOrBlank()) {
                    ReachabilityGate.setServerUrl(initialUrl)
                    ReachabilityGate.refresh()
                }
                prefsReady  = true

                // Keep PIN state live for runtime changes (settings toggles, recovery).
                launch { prefs.pinHash.collect { pinHash = it } }
                launch { prefs.pinSalt.collect { pinSalt = it } }

                // Backfill user_id for sessions created before the offline-mode rollout
                // (or any time it ends up blank) so locally-created records get an
                // author attribution.
                val url = snap.url
                if (token.isNotBlank() && !url.isNullOrBlank() && prefs.userId.first().isBlank()) {
                    runCatching {
                        val me = RetrofitClient.getService(url).getMe()
                        val uid = me["user_id"].orEmpty()
                        if (uid.isNotBlank()) {
                            prefs.setAuth(token, uid, snap.name, snap.avatar)
                        }
                    }
                }
            }

            // Register 401 callback — called on OkHttp background thread.
            DisposableEffect(Unit) {
                RetrofitClient.onUnauthorized = {
                    mainHandler.post {
                        RetrofitClient.authToken = ""
                        authToken = ""
                        scope.launch { prefs.clearAuth() }
                    }
                }
                onDispose { RetrofitClient.onUnauthorized = null }
            }

            // Safety-net: keep RetrofitClient in sync if authToken changes for any other reason.
            LaunchedEffect(authToken) {
                RetrofitClient.authToken = authToken ?: ""
            }

            // Warm the shared category cache once we have a server + token.
            // Done here (not per-VM) so VMs don't fan out 3 parallel requests
            // when their screens compose during cold start.
            LaunchedEffect(serverUrl, authToken) {
                val url = serverUrl
                if (!url.isNullOrBlank() && !authToken.isNullOrBlank()) {
                    CategoryRepository.loadAll(url)
                }
            }

            // Keep the reachability gate's server URL in sync with the saved
            // one. Re-binding triggers a fresh probe so we don't show stale
            // offline state after switching servers / re-login.
            LaunchedEffect(serverUrl) {
                val url = serverUrl
                if (!url.isNullOrBlank()) {
                    ReachabilityGate.setServerUrl(url)
                    ReachabilityGate.refresh()
                }
            }

            // App-lock state. pinHash/pinSalt are managed in the loader effect above
            // (not here) to avoid a race where collectAsStateWithLifecycle's
            // initialValue=null caused the lock gate to slip past.
            val biometricEnabled by prefs.biometricEnabled.collectAsStateWithLifecycle(initialValue = false)
            val lockTimeoutSec   by prefs.lockTimeoutSec.collectAsStateWithLifecycle(initialValue = 60)
            val pinSetupPrompted by prefs.pinSetupPrompted.collectAsStateWithLifecycle(initialValue = false)
            val isUnlocked       by AppLock.isUnlocked.collectAsStateWithLifecycle()

            val hasPin = !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()

            // Track foreground/background to apply the configurable lock timeout.
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, hasPin, lockTimeoutSec) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE  -> AppLock.onPause()
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                            AppLock.onResume(lockTimeoutSec, hasPin)
                            // Foreground resume: refresh reachability so the
                            // user sees the live state immediately (server may
                            // have come back up while we were backgrounded, or
                            // gone down).
                            ReachabilityGate.refresh()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Unlock transition (PIN / biometric / no-PIN auto-unlock): the
            // user just engaged with the app, re-probe so the home screen
            // reflects the live online state rather than a stale one from
            // before the lock.
            LaunchedEffect(isUnlocked) {
                if (isUnlocked) ReachabilityGate.refresh()
            }

            // Identifier for AnimatedContent so transitions between phases
            // (splash → connect → pin setup / lock → main) cross-fade rather
            // than snap. Each branch reads the same state vars in its closure.
            val phase = when {
                serverUrl == null || !prefsReady                            -> "splash"
                serverUrl.isNullOrBlank() || authToken.isNullOrBlank()      -> "connect"
                hasPin && !isUnlocked                                       -> "lock"
                !hasPin && !pinSetupPrompted                                -> "pin_setup"
                else                                                        -> "main"
            }

            BudgetTheme(primary = primaryColor, isDark = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = phase,
                        transitionSpec = {
                            val enterDur = if (initialState == "lock") 520 else 320
                            val exitDur  = if (initialState == "lock") 340 else 220
                            (androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = enterDur,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                )
                            ) + androidx.compose.animation.scaleIn(
                                initialScale = if (initialState == "lock") 0.90f else 0.96f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = enterDur,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                )
                            )) togetherWith androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = exitDur)
                            )
                        },
                        label = "appPhase",
                    ) { p -> when (p) {
                        "splash" -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MbLogo(primaryColor = primaryColor, size = 88.dp)
                            }
                        }

                        "connect" -> {
                            val serverHistory by prefs.serverHistory.collectAsStateWithLifecycle(initialValue = emptyList())
                            // Not connected or not logged in → show connect/login wizard.
                            ConnectScreen(
                                primaryColor   = primaryColor,
                                savedServerUrl = serverUrl.takeIf { !it.isNullOrBlank() },
                                serverHistory  = serverHistory,
                                onAuthenticated = { url, token, userId, name, avatar ->
                                    // Set token BEFORE state update so it's visible to ViewModel
                                    // init coroutines that start synchronously on recomposition.
                                    RetrofitClient.authToken = token
                                    serverUrl   = url
                                    authToken   = token
                                    displayName = name
                                    avatarUrl   = avatar
                                    AppLock.unlock()
                                    scope.launch {
                                        prefs.setServerUrl(url)
                                        prefs.setAuth(token, userId, name, avatar)
                                        prefs.addToServerHistory(url)
                                    }
                                }
                            )
                        }

                        "lock" -> {
                            LockScreen(
                                primaryColor     = primaryColor,
                                pinSalt          = pinSalt!!,
                                pinHash          = pinHash!!,
                                biometricEnabled = biometricEnabled,
                                serverUrl        = serverUrl!!,
                                onUnlocked       = { AppLock.unlock() },
                                onPinReset       = {
                                    scope.launch {
                                        prefs.clearPinAndBiometric()
                                        prefs.setPinSetupPrompted(false)
                                        AppLock.unlock()
                                    }
                                },
                            )
                        }

                        "pin_setup" -> {
                            // First post-login launch — offer to protect the app.
                            PinSetupScreen(
                                primaryColor = primaryColor,
                                onSkip = {
                                    scope.launch { prefs.setPinSetupPrompted(true) }
                                },
                                onPinSet = { pin, enableBio ->
                                    val stored = PinSecurity.hash(pin)
                                    scope.launch {
                                        prefs.setPin(stored.hashBase64, stored.saltBase64)
                                        prefs.setBiometricEnabled(enableBio)
                                        prefs.setPinSetupPrompted(true)
                                    }
                                },
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
                            onLogout = {
                                RetrofitClient.authToken = ""
                                authToken = ""
                                AppLock.lock()
                                scope.launch { prefs.clearAuthAndSecurity() }
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
                    } }
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
