package website.msdnna.budget_app.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Calendar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import website.msdnna.budget_app.BuildConfig
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.data.repository.DetailRequestStore
import website.msdnna.budget_app.data.repository.TransactionRepository
import website.msdnna.budget_app.data.repository.WishlistRepository
import website.msdnna.budget_app.data.sync.ReachabilityGate
import website.msdnna.budget_app.data.update.ApkDownloader
import website.msdnna.budget_app.data.update.ApkInstaller
import website.msdnna.budget_app.data.update.DownloadProgress
import website.msdnna.budget_app.data.update.UpdateState
import website.msdnna.budget_app.data.update.resolveUpdate
import website.msdnna.budget_app.notifications.NotificationPrefs
import website.msdnna.budget_app.notifications.NotificationScheduler
import website.msdnna.budget_app.ui.components.MandatoryUpdateDialog
import website.msdnna.budget_app.ui.components.MbLogo
import website.msdnna.budget_app.ui.components.OptionalUpdateProgressDialog
import website.msdnna.budget_app.ui.components.UpdateBanner
import website.msdnna.budget_app.ui.theme.AppTheme
import website.msdnna.budget_app.ui.theme.AppThemes

private data class NavItem(val label: String, val icon: ImageVector, val route: String)

/** Fetch /api/version and resolve update state. Shared between initial load
 *  and lifecycle resume so cross-app upgrades surface without a restart. */
private suspend fun checkVersion(
    serverUrl: String,
    setApiVersion: (String?) -> Unit,
    setUpdateState: (UpdateState) -> Unit,
) {
    try {
        val v = RetrofitClient.getService(serverUrl).getVersion()
        setApiVersion(v.api)
        setUpdateState(
            resolveUpdate(
                current = BuildConfig.VERSION_NAME,
                latest = v.androidLatest,
                minRequired = v.androidMinRequired,
                serverUrl = serverUrl,
            )
        )
    } catch (_: Exception) {}
}

private val NAV_ITEMS = listOf(
    NavItem("Статистика", Icons.Default.BarChart, "statistics"),
    NavItem("Доходы", Icons.AutoMirrored.Filled.TrendingUp, "income"),
    NavItem("Расходы", Icons.AutoMirrored.Filled.TrendingDown, "expenses"),
    NavItem("Прогноз", Icons.Default.Lightbulb, "forecast"),
    NavItem("Экспорт", Icons.Default.FileDownload, "export"),
)

private val PAGE_TITLES = mapOf(
    "statistics" to "Статистика",
    "income" to "Доходы",
    "expenses" to "Расходы",
    "forecast" to "Прогноз",
    "export" to "Экспорт",
)

@Composable
fun MainScreen(
    serverUrl: String,
    primaryColor: Color,
    isDark: Boolean = false,
    prefs: AppPreferences,
    activeTheme: AppTheme,
    displayName: String = "",
    avatarUrl: String? = null,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onRequestNotifPermission: () -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = 0) { NAV_ITEMS.size }
    val currentRoute = NAV_ITEMS[pagerState.currentPage].route
    // For TopAppBar title and conditional actions: use targetPage so a
    // bottom-nav tap (animateScrollToPage) snaps the title to the destination
    // instead of cycling through every intermediate page on the way there.
    val targetRoute = NAV_ITEMS[pagerState.targetPage].route
    var showSettings by remember { mutableStateOf(false) }
    var showConflicts by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    // Detail-request navigation: list overlay vs single-request overlay vs
    // "all" mode reachable from settings (showAll=true -> tabs).
    var showDetailRequestsList by remember { mutableStateOf(false) }
    var detailRequestsListShowAll by remember { mutableStateOf(false) }
    var openDetailRequestId by remember { mutableStateOf<String?>(null) }

    // Refresh reachability whenever an overlay screen becomes visible. Each
    // overlay is a separate state flag, but only the becoming-visible edge
    // matters — `LaunchedEffect(key)` re-runs naturally on flag changes.
    // Pager swipes (Statistics / Income / Expenses / Forecast / Export) are
    // intentionally skipped: swipes are too frequent to probe on every tick.
    LaunchedEffect(showSettings) { if (showSettings) ReachabilityGate.refresh() }
    LaunchedEffect(showConflicts) { if (showConflicts) ReachabilityGate.refresh() }
    LaunchedEffect(showNotifications) { if (showNotifications) ReachabilityGate.refresh() }
    LaunchedEffect(showSecurity) { if (showSecurity) ReachabilityGate.refresh() }
    LaunchedEffect(showDetailRequestsList) { if (showDetailRequestsList) ReachabilityGate.refresh() }
    LaunchedEffect(openDetailRequestId) { if (openDetailRequestId != null) ReachabilityGate.refresh() }

    val currentUserId by prefs.userId.collectAsStateWithLifecycle(initialValue = "")
    val drItems by DetailRequestStore.items.collectAsStateWithLifecycle()
    val myOpenDrCount = drItems.count { it.status == "open" && it.assignee?.userId == currentUserId }
    LaunchedEffect(serverUrl, currentUserId) {
        if (currentUserId.isNotBlank()) DetailRequestStore.refresh()
    }
    var valuesHidden by remember { mutableStateOf(false) }
    // Income / Expenses share a "filters drawer" that's collapsed by default;
    // the header button below toggles it. Shared across both routes so a user
    // who expanded filters on Income sees them already expanded on Expenses.
    var filtersVisible by remember { mutableStateOf(false) }
    // Per-route selection count published by each transactional screen via callback;
    // we read the entry for the currently visible route to decide between the
    // section title and a "Выбрано: N" counter in the top app bar.
    val selectionCounts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val activeSelectionCount = selectionCounts[currentRoute] ?: 0
    var apiVersion by remember { mutableStateOf<String?>(null) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.None) }
    var bannerDismissed by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // canInstall depends on a system permission the user grants outside our app.
    // Re-check on ON_RESUME so the mandatory dialog flips to "Скачать и установить"
    // immediately after the user comes back from settings.
    var canInstallApk by remember { mutableStateOf(ApkInstaller.canInstall(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canInstallApk = ApkInstaller.canInstall(context)
                // Refresh online-only state (detail-requests, server version)
                // so cross-app actions (e.g. closing a request on web) reflect
                // here without an app restart.
                scope.launch {
                    DetailRequestStore.refresh()
                    checkVersion(serverUrl, { apiVersion = it }, { updateState = it })
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val conflictCount by remember {
        combine(
            TransactionRepository.observeConflictCount(),
            WishlistRepository.observeConflictCount(),
        ) { a, b -> a + b }
    }.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(serverUrl) { checkVersion(serverUrl, { apiVersion = it }, { updateState = it }) }

    fun startDownload(url: String, version: String, onDone: (java.io.File) -> Unit) {
        downloadProgress = DownloadProgress.Running(0, 0)
        scope.launch {
            ApkDownloader.download(context, url, version).collect { p ->
                downloadProgress = p
                if (p is DownloadProgress.Done) onDone(p.file)
            }
        }
    }

    val notifPrefs by prefs.notifPrefs.collectAsStateWithLifecycle(
        initialValue = NotificationPrefs()
    )
    val pieUnitRuble by prefs.pieUnitRuble.collectAsStateWithLifecycle(initialValue = true)

    val now = Calendar.getInstance()
    val today = remember {
        val months = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
        )
        "${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MbLogo(primaryColor = primaryColor, size = 28.dp)
                        androidx.compose.animation.Crossfade(
                            targetState = activeSelectionCount > 0,
                            animationSpec = androidx.compose.animation.core.tween(180),
                            label = "title"
                        ) { showCounter ->
                            if (showCounter) {
                                Text(
                                    "Выбрано: $activeSelectionCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryColor
                                )
                            } else {
                                Text(
                                    PAGE_TITLES[targetRoute] ?: "",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                },
                actions = {
                    Text(
                        today,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    if (conflictCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(conflictCount.toString())
                                }
                            }
                        ) {
                            IconButton(onClick = { showConflicts = true }) {
                                Icon(Icons.Default.SyncProblem, "Конфликты синхронизации")
                            }
                        }
                    }
                    // DR badge — only on Expenses (per spec) and only when
                    // there's at least one open request assigned to me.
                    // clip = false on expand/shrink: BadgedBox renders the
                    // count badge OUTSIDE the IconButton's measured bounds,
                    // so the default clip rect cuts it off mid-animation.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = targetRoute == "expenses" && myOpenDrCount > 0,
                        enter = androidx.compose.animation.fadeIn(animationSpec = tween(200)) +
                            androidx.compose.animation.expandHorizontally(animationSpec = tween(220), clip = false),
                        exit = androidx.compose.animation.fadeOut(animationSpec = tween(160)) +
                            androidx.compose.animation.shrinkHorizontally(animationSpec = tween(180), clip = false),
                    ) {
                        // Custom Box layout (instead of M3 BadgedBox): the
                        // default BadgedBox positions the badge OUTSIDE the
                        // icon at top-end with a negative offset, which gets
                        // clipped by the TopAppBar's top edge. Box+align gives
                        // us pixel-perfect control: the badge sits inside the
                        // action slot's bounds.
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = {
                                detailRequestsListShowAll = false
                                showDetailRequestsList = true
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Assignment, "Запросы на детализацию")
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 6.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(0xFFF0A020))
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    myOpenDrCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = targetRoute == "income" || targetRoute == "expenses",
                        enter = androidx.compose.animation.fadeIn(animationSpec = tween(200)) +
                            androidx.compose.animation.expandHorizontally(animationSpec = tween(220), clip = false),
                        exit = androidx.compose.animation.fadeOut(animationSpec = tween(160)) +
                            androidx.compose.animation.shrinkHorizontally(animationSpec = tween(180), clip = false),
                    ) {
                        IconButton(onClick = { filtersVisible = !filtersVisible }) {
                            // Outlined variants match the visual weight of the
                            // eye/settings glyphs alongside (the filled
                            // FilterAlt sat heavier than its neighbours in
                            // light theme and lighter in dark — both broke
                            // visual balance).
                            Icon(
                                if (filtersVisible) Icons.Outlined.FilterAltOff else Icons.Outlined.FilterAlt,
                                if (filtersVisible) "Скрыть фильтры" else "Показать фильтры",
                                tint = if (filtersVisible) primaryColor
                                else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = { valuesHidden = !valuesHidden }) {
                        Icon(
                            if (valuesHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            if (valuesHidden) "Показать суммы" else "Скрыть суммы"
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp // no tonal tinting → bar stays pure white
            ) {
                NAV_ITEMS.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = { Icon(item.icon, item.label) },
                        label = { Text(item.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = primaryColor,
                            selectedTextColor = primaryColor,
                            // Color.Transparent renders black in this Compose version;
                            // Color(0x00FFFFFF) is truly transparent (alpha=0, white base)
                            indicatorColor = Color(0x00FFFFFF),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val s = updateState
            if (s is UpdateState.Optional && !bannerDismissed) {
                UpdateBanner(
                    latestVersion = s.latest,
                    primaryColor = primaryColor,
                    onClick = {
                        if (!canInstallApk) {
                            ApkInstaller.openUnknownSourcesSettings(context)
                            return@UpdateBanner
                        }
                        startDownload(s.apkUrl, s.latest) { file ->
                            ApkInstaller.install(context, file)
                        }
                    },
                    onDismiss = { bannerDismissed = true },
                )
            }
            HorizontalPager(
                state = pagerState,
                // Pre-compose all off-screen pages so multi-page swipes never have
                // to inflate during the gesture. Cold-start cost is one-time;
                // subsequent swipes are pure layout-translate of cached compositions.
                beyondViewportPageCount = NAV_ITEMS.size - 1,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                key(NAV_ITEMS[page].route) {
                    when (NAV_ITEMS[page].route) {
                        "statistics" -> StatisticsScreen(serverUrl, primaryColor, valuesHidden, pieUnitRuble)
                        "income" -> IncomeScreen(
                            serverUrl, primaryColor, valuesHidden,
                            filtersVisible = filtersVisible,
                            onSelectionCountChange = { selectionCounts["income"] = it }
                        )
                        "expenses" -> ExpensesScreen(
                            serverUrl, primaryColor, valuesHidden,
                            filtersVisible = filtersVisible,
                            currentUserId = currentUserId,
                            onSelectionCountChange = { selectionCounts["expenses"] = it },
                            onOpenDetailRequest = { id -> openDetailRequestId = id },
                        )
                        "forecast" -> ForecastScreen(
                            serverUrl, primaryColor,
                            onSelectionCountChange = { selectionCounts["forecast"] = it }
                        )
                        "export" -> ExportScreen(serverUrl, primaryColor)
                    }
                }
            }
        }
    }

    // Optional update — show a small progress dialog while the APK is downloading.
    val optState = updateState
    val dp = downloadProgress
    if (optState is UpdateState.Optional && dp != null) {
        OptionalUpdateProgressDialog(
            latestVersion = optState.latest,
            primaryColor = primaryColor,
            progress = dp,
            onDismiss = { downloadProgress = null },
        )
    }

    // Mandatory update — fullscreen blocking dialog.
    if (optState is UpdateState.Required) {
        MandatoryUpdateDialog(
            latestVersion = optState.latest,
            primaryColor = primaryColor,
            progress = downloadProgress,
            canInstall = canInstallApk,
            onDownload = {
                startDownload(optState.apkUrl, optState.latest) { file ->
                    ApkInstaller.install(context, file)
                }
            },
            onGrantInstallPermission = {
                ApkInstaller.openUnknownSourcesSettings(context)
            },
        )
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showConflicts,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
    ) {
        ConflictsScreen(serverUrl = serverUrl, onClose = { showConflicts = false })
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showNotifications,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
    ) {
        NotificationsScreen(
            primaryColor = primaryColor,
            notifPrefs = notifPrefs,
            onNotifPrefsChange = { np ->
                scope.launch {
                    prefs.setNotifPrefs(np)
                    NotificationScheduler.applyPrefs(context, np)
                }
                if (np.expensesEnabled || np.incomeEnabled) {
                    onRequestNotifPermission()
                }
            },
            onClose = { showNotifications = false },
        )
    }

    if (showSettings) {
        val availableUpdate = when (val s = updateState) {
            is UpdateState.Optional -> s.latest
            is UpdateState.Required -> s.latest
            UpdateState.None -> null
        }
        SettingsDialog(
            primaryColor = primaryColor,
            isDark = isDark,
            activeTheme = activeTheme,
            displayName = displayName,
            apiVersion = apiVersion,
            availableUpdate = availableUpdate,
            pieUnitRuble = pieUnitRuble,
            onThemeChange = { theme ->
                onThemeChange(theme)
                scope.launch { prefs.setThemeKey(theme.key) }
            },
            onDarkModeChange = onDarkModeChange,
            onOpenNotifications = {
                showSettings = false
                showNotifications = true
            },
            onOpenSecurity = {
                showSettings = false
                showSecurity = true
            },
            onOpenDetailRequests = {
                showSettings = false
                detailRequestsListShowAll = true
                showDetailRequestsList = true
            },
            onPieUnitChange = { ruble ->
                scope.launch { prefs.setPieUnitRuble(ruble) }
            },
            onLogout = {
                showSettings = false
                onLogout()
            },
            onDismiss = { showSettings = false }
        )
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showSecurity,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
    ) {
        SecurityScreen(
            primaryColor = primaryColor,
            prefs = prefs,
            onClose = { showSecurity = false },
        )
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showDetailRequestsList,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
    ) {
        DetailRequestsScreen(
            primaryColor = primaryColor,
            currentUserId = currentUserId,
            showAll = detailRequestsListShowAll,
            onOpen = { id ->
                openDetailRequestId = id
            },
            onClose = { showDetailRequestsList = false },
        )
    }

    val openDrId = openDetailRequestId
    androidx.compose.animation.AnimatedVisibility(
        visible = openDrId != null,
        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
    ) {
        if (openDrId != null) {
            DetailRequestScreen(
                requestId = openDrId,
                primaryColor = primaryColor,
                valuesHidden = valuesHidden,
                currentUserId = currentUserId,
                serverUrl = serverUrl,
                onClose = { openDetailRequestId = null },
            )
        }
    }
}

@Composable
fun SettingsDialog(
    primaryColor: Color,
    isDark: Boolean = false,
    activeTheme: AppTheme,
    displayName: String = "",
    apiVersion: String? = null,
    availableUpdate: String? = null,
    pieUnitRuble: Boolean = true,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (Boolean) -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenSecurity: () -> Unit = {},
    onOpenDetailRequests: () -> Unit = {},
    onPieUnitChange: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Logged-in user info — logout icon at the trailing edge.
                if (displayName.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(primaryColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                    .take(2).joinToString(""),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Вы авторизованы", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onOpenDetailRequests) {
                            Icon(
                                Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = "Запросы на детализацию",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onLogout) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Выйти из аккаунта",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider()
                }

                // Theme picker
                Text(
                    "Цвет темы", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppThemes.forEach { theme ->
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                .background(theme.primary).clickable { onThemeChange(theme) }
                        ) {
                            if (theme.key == activeTheme.key) {
                                Icon(
                                    Icons.Default.Check, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp).align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // Dark mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Тёмная тема", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = onDarkModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryColor.copy(alpha = 0.4f)
                        )
                    )
                }

                HorizontalDivider()

                // Pie chart unit
                Text(
                    "Диаграммы", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Единица Pie Chart", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = !pieUnitRuble,
                            onClick = { onPieUnitChange(false) },
                            label = { Text("%") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primaryColor,
                                selectedLabelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = pieUnitRuble,
                            onClick = { onPieUnitChange(true) },
                            label = { Text("₽") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primaryColor,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                HorizontalDivider()

                // Notifications — opens dedicated screen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNotifications)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Уведомления", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // Защита приложения — opens a dedicated screen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSecurity)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Защита приложения", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // Version info
                Text(
                    "Версия",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Приложение", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val current = "v${BuildConfig.VERSION_NAME}"
                    val text = if (!availableUpdate.isNullOrBlank())
                        "$current (доступна v$availableUpdate)"
                    else current
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!availableUpdate.isNullOrBlank()) primaryColor
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Сервер (API)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        apiVersion?.let { "v$it" } ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}
