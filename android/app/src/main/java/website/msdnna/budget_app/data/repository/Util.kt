package website.msdnna.budget_app.data.repository

import kotlinx.coroutines.flow.first
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.model.UserInfo

/** Shared helper for repositories: snapshot of the currently logged-in user. */
internal suspend fun currentUser(): UserInfo? {
    val prefs = AppContainer.prefs
    val uid = prefs.userId.first()
    if (uid.isBlank()) return null
    val name = prefs.displayName.first()
    val avatar = prefs.avatarUrl.first()
    return UserInfo(uid, name, avatar)
}
