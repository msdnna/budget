package website.msdnna.budget_app.data.update

sealed interface UpdateState {
    data object None : UpdateState
    data class Optional(val latest: String, val apkUrl: String) : UpdateState
    data class Required(val latest: String, val apkUrl: String) : UpdateState
}

internal fun apkUrlFor(serverUrl: String, version: String): String {
    val root = serverUrl.trimEnd('/').removeSuffix("/api").trimEnd('/')
    return "$root/apks/msdnna-budget-app-v$version.apk"
}

internal fun resolveUpdate(
    current: String,
    latest: String,
    minRequired: String,
    serverUrl: String,
): UpdateState {
    if (latest.isBlank()) return UpdateState.None
    val url = apkUrlFor(serverUrl, latest)
    return when {
        minRequired.isNotBlank() && compareSemVer(current, minRequired) < 0 ->
            UpdateState.Required(latest, url)
        compareSemVer(current, latest) < 0 ->
            UpdateState.Optional(latest, url)
        else -> UpdateState.None
    }
}
