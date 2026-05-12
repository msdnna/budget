package website.msdnna.budget_app.data.db

/**
 * Sync state of a Room row.
 *
 *  - SYNCED: identical to the server version we last saw.
 *  - PENDING_CREATE: created locally while offline; needs POST.
 *  - PENDING_UPDATE: existed on server but was modified locally; needs PUT.
 *  - PENDING_DELETE: marked for deletion locally; needs DELETE.
 *  - CONFLICT: server returned a different version on push; user must resolve.
 *    The local row holds the user's pending changes; `serverPayload` holds the
 *    authoritative server document we conflicted with.
 */
object SyncStatus {
    const val SYNCED = "synced"
    const val PENDING_CREATE = "pending_create"
    const val PENDING_UPDATE = "pending_update"
    const val PENDING_DELETE = "pending_delete"
    const val CONFLICT = "conflict"
}
