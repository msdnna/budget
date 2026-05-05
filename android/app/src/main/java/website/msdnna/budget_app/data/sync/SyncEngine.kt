package website.msdnna.budget_app.data.sync

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.db.AppDatabase
import website.msdnna.budget_app.data.db.SyncStatus
import website.msdnna.budget_app.data.db.toEntity
import website.msdnna.budget_app.data.db.toModel
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.SyncOperation
import website.msdnna.budget_app.data.model.SyncOperationResult
import website.msdnna.budget_app.data.model.SyncPushRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.data.preferences.AppPreferences
import java.util.UUID

private const val TAG = "SyncEngine"

/**
 * Two-way sync between Room (local) and the API (server).
 *
 * Order of operations on each [sync]:
 *   1. PUSH local pending rows → handle ok / conflict per row.
 *   2. PULL remote changes since the last successful pull token.
 *
 * Safe to invoke concurrently because each step touches Room atomically per
 * row; in practice [SyncWorker] serializes calls via WorkManager.
 *
 * Conflicts: rows are marked [SyncStatus.CONFLICT]. The local row keeps the
 * user's pending edits; [serverPayload] holds the authoritative server doc.
 * The user resolves them via [ConflictsScreen].
 */
class SyncEngine(
    private val db: AppDatabase,
    private val prefs: AppPreferences,
) {
    private val gson = Gson()

    enum class Result { Success, Skipped, Failed }

    suspend fun sync(serverUrl: String): Result {
        if (serverUrl.isBlank() || RetrofitClient.authToken.isBlank()) return Result.Skipped
        return try {
            val api = RetrofitClient.getService(serverUrl)
            push(api)
            pull(api)
            Result.Success
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            Result.Failed
        }
    }

    // -------- PUSH --------

    private suspend fun push(api: website.msdnna.budget_app.data.api.ApiService) {
        val ops = mutableListOf<SyncOperation>()

        for (e in db.transactions().findPending()) {
            ops += buildOp(
                entity = "transaction",
                id = e.id,
                status = e.syncStatus,
                version = e.version,
                payload = if (e.syncStatus == SyncStatus.PENDING_DELETE) null else e.toModel(),
            )
        }
        for (e in db.wishlist().findPending()) {
            ops += buildOp(
                entity = "wishlist",
                id = e.id,
                status = e.syncStatus,
                version = e.version,
                payload = if (e.syncStatus == SyncStatus.PENDING_DELETE) null else e.toModel(),
            )
        }
        for (e in db.categories().findPending()) {
            ops += buildOp(
                entity = "category",
                id = e.id,
                status = e.syncStatus,
                version = e.version,
                payload = if (e.syncStatus == SyncStatus.PENDING_DELETE) null else e.toModel(),
            )
        }

        if (ops.isEmpty()) return

        Log.i(TAG, "push: ${ops.size} ops")
        val resp = api.syncPush(SyncPushRequest(ops))

        // Pair operations with results by op_id (positional pairing also works
        // since the server preserves order, but op_id is the contract).
        val byOpId = resp.results.associateBy { it.opId }
        for (op in ops) {
            val result = byOpId[op.opId] ?: continue
            applyResult(op, result)
        }
    }

    private fun buildOp(entity: String, id: String, status: String, version: Int, payload: Any?): SyncOperation {
        val type = when (status) {
            SyncStatus.PENDING_CREATE -> "create"
            SyncStatus.PENDING_UPDATE -> "update"
            SyncStatus.PENDING_DELETE -> "delete"
            else -> "update"
        }
        val baseVersion = if (status == SyncStatus.PENDING_CREATE) 0 else version
        return SyncOperation(
            opId = UUID.randomUUID().toString(),
            type = type,
            entity = entity,
            id = id,
            baseVersion = baseVersion,
            payload = payload,
        )
    }

    private suspend fun applyResult(op: SyncOperation, r: SyncOperationResult) {
        when (r.status) {
            "ok" -> {
                applyOk(op, r)
                // Belt-and-suspenders: even if r.record was missing or de/serialization
                // hiccupped, force the local row out of pending/conflict state so the
                // user isn't stuck.
                clearPending(op.entity, op.id)
            }
            "conflict" -> applyConflict(op, r)
            else -> {
                Log.w(TAG, "push op ${op.entity}/${op.id} status=${r.status} error=${r.error}")
            }
        }
    }

    private suspend fun applyOk(op: SyncOperation, r: SyncOperationResult) {
        when (op.entity) {
            "transaction" -> {
                if (op.type == "delete") {
                    db.transactions().deleteHard(op.id)
                } else {
                    val model = recordTo<Transaction>(r.record) ?: return
                    db.transactions().upsert(model.toEntity(SyncStatus.SYNCED))
                }
            }
            "wishlist" -> {
                if (op.type == "delete") {
                    db.wishlist().deleteHard(op.id)
                } else {
                    val model = recordTo<WishlistItem>(r.record) ?: return
                    db.wishlist().upsert(model.toEntity(SyncStatus.SYNCED))
                }
            }
            "category" -> {
                if (op.type == "delete") {
                    db.categories().deleteHard(op.id)
                } else {
                    val model = recordTo<Category>(r.record) ?: return
                    db.categories().upsert(model.toEntity(SyncStatus.SYNCED))
                }
            }
        }
    }

    /**
     * Last-resort transition: if applyOk above couldn't decode the server record,
     * we still flip the local row out of pending/conflict so the UI doesn't get
     * stuck. The local data is what the user wanted (force-keep-mine semantics)
     * or what just got pushed successfully (regular create/update).
     */
    private suspend fun clearPending(entity: String, id: String) {
        when (entity) {
            "transaction" -> {
                val cur = db.transactions().findById(id) ?: return
                if (cur.syncStatus == SyncStatus.SYNCED && cur.serverPayload == null) return
                db.transactions().update(cur.copy(syncStatus = SyncStatus.SYNCED, serverPayload = null))
            }
            "wishlist" -> {
                val cur = db.wishlist().findById(id) ?: return
                if (cur.syncStatus == SyncStatus.SYNCED && cur.serverPayload == null) return
                db.wishlist().update(cur.copy(syncStatus = SyncStatus.SYNCED, serverPayload = null))
            }
            "category" -> {
                val cur = db.categories().findById(id) ?: return
                if (cur.syncStatus == SyncStatus.SYNCED && cur.serverPayload == null) return
                db.categories().update(cur.copy(syncStatus = SyncStatus.SYNCED, serverPayload = null))
            }
        }
    }

    private suspend fun applyConflict(op: SyncOperation, r: SyncOperationResult) {
        val serverJson = r.record?.let { gson.toJson(it) } ?: return
        when (op.entity) {
            "transaction" -> {
                val cur = db.transactions().findById(op.id) ?: return
                db.transactions().update(cur.copy(syncStatus = SyncStatus.CONFLICT, serverPayload = serverJson))
            }
            "wishlist" -> {
                val cur = db.wishlist().findById(op.id) ?: return
                db.wishlist().update(cur.copy(syncStatus = SyncStatus.CONFLICT, serverPayload = serverJson))
            }
            "category" -> {
                val cur = db.categories().findById(op.id) ?: return
                db.categories().update(cur.copy(syncStatus = SyncStatus.CONFLICT, serverPayload = serverJson))
            }
        }
    }

    private inline fun <reified T> recordTo(record: Map<String, Any?>?): T? {
        if (record == null) return null
        return runCatching { gson.fromJson(gson.toJson(record), T::class.java) }
            .onFailure { Log.w(TAG, "recordTo<${T::class.java.simpleName}> failed", it) }
            .getOrNull()
    }

    // -------- PULL --------

    private suspend fun pull(api: website.msdnna.budget_app.data.api.ApiService) {
        val since = prefs.lastSyncToken.first()
        val resp = api.syncPull(since)

        for (t in resp.transactions) mergeTransaction(t)
        for (w in resp.wishlist) mergeWishlist(w)
        for (c in resp.categories) mergeCategory(c)

        if (resp.serverTime.isNotBlank()) {
            prefs.setLastSyncToken(resp.serverTime)
        }
    }

    private suspend fun mergeTransaction(remote: Transaction) {
        val local = db.transactions().findById(remote.id)
        if (local == null) {
            // We never had this row. Server tombstones (deleted_at != null) for
            // unknown ids are simply ignored.
            if (remote.deletedAt != null) return
            db.transactions().upsert(remote.toEntity(SyncStatus.SYNCED))
            return
        }
        when (local.syncStatus) {
            SyncStatus.SYNCED -> {
                if (remote.deletedAt != null) {
                    db.transactions().deleteHard(remote.id)
                } else if (remote.version != local.version || remote.updatedAt != local.updatedAt) {
                    db.transactions().upsert(remote.toEntity(SyncStatus.SYNCED))
                }
            }
            SyncStatus.PENDING_CREATE,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.PENDING_DELETE -> {
                // Server changed under us while we have a pending local change.
                // Mark conflict; the next push attempt would discover this anyway,
                // but flagging it now lets the UI surface a badge sooner.
                if (remote.version != local.version) {
                    db.transactions().update(local.copy(
                        syncStatus = SyncStatus.CONFLICT,
                        serverPayload = gson.toJson(remote),
                    ))
                }
            }
            SyncStatus.CONFLICT -> {
                db.transactions().update(local.copy(serverPayload = gson.toJson(remote)))
            }
        }
    }

    private suspend fun mergeWishlist(remote: WishlistItem) {
        val local = db.wishlist().findById(remote.id)
        if (local == null) {
            if (remote.deletedAt != null) return
            db.wishlist().upsert(remote.toEntity(SyncStatus.SYNCED))
            return
        }
        when (local.syncStatus) {
            SyncStatus.SYNCED -> {
                if (remote.deletedAt != null) {
                    db.wishlist().deleteHard(remote.id)
                } else if (remote.version != local.version || remote.updatedAt != local.updatedAt) {
                    db.wishlist().upsert(remote.toEntity(SyncStatus.SYNCED))
                }
            }
            SyncStatus.PENDING_CREATE,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.PENDING_DELETE -> {
                if (remote.version != local.version) {
                    db.wishlist().update(local.copy(
                        syncStatus = SyncStatus.CONFLICT,
                        serverPayload = gson.toJson(remote),
                    ))
                }
            }
            SyncStatus.CONFLICT -> {
                db.wishlist().update(local.copy(serverPayload = gson.toJson(remote)))
            }
        }
    }

    private suspend fun mergeCategory(remote: Category) {
        val local = db.categories().findById(remote.id)
        if (local == null) {
            if (remote.deletedAt != null) return
            db.categories().upsert(remote.toEntity(SyncStatus.SYNCED))
            return
        }
        when (local.syncStatus) {
            SyncStatus.SYNCED -> {
                if (remote.deletedAt != null) {
                    db.categories().deleteHard(remote.id)
                } else if (remote.version != local.version || remote.updatedAt != local.updatedAt) {
                    db.categories().upsert(remote.toEntity(SyncStatus.SYNCED))
                }
            }
            SyncStatus.PENDING_CREATE,
            SyncStatus.PENDING_UPDATE,
            SyncStatus.PENDING_DELETE -> {
                if (remote.version != local.version) {
                    db.categories().update(local.copy(
                        syncStatus = SyncStatus.CONFLICT,
                        serverPayload = gson.toJson(remote),
                    ))
                }
            }
            SyncStatus.CONFLICT -> {
                db.categories().update(local.copy(serverPayload = gson.toJson(remote)))
            }
        }
    }

    // -------- Conflict resolution (called from UI) --------

    /** Re-push the local version of a conflict, forcing the server to accept. */
    suspend fun resolveKeepLocal(serverUrl: String, entity: String, id: String): Result {
        if (serverUrl.isBlank()) return Result.Skipped
        return try {
            val api = RetrofitClient.getService(serverUrl)
            val op = buildResolveOp(entity, id, force = true) ?: return Result.Skipped
            val resp = api.syncPush(SyncPushRequest(listOf(op)))
            val r = resp.results.firstOrNull() ?: return Result.Failed
            when (r.status) {
                "ok" -> {
                    applyOk(op, r)
                    clearPending(op.entity, op.id)
                    Result.Success
                }
                "conflict" -> {
                    // Server moved ahead again while we were resolving — refresh the
                    // shown server version so the user can retry against the new one.
                    applyConflict(op, r)
                    Log.w(TAG, "resolveKeepLocal: still conflicted ${op.entity}/${op.id}")
                    Result.Failed
                }
                else -> {
                    Log.w(TAG, "resolveKeepLocal ${op.entity}/${op.id} ${r.status} ${r.error}")
                    Result.Failed
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "resolveKeepLocal threw", e)
            Result.Failed
        }
    }

    /** Discard the local pending changes for a conflict and adopt the server version. */
    suspend fun resolveKeepServer(entity: String, id: String): Result {
        return try {
            when (entity) {
                "transaction" -> {
                    val cur = db.transactions().findById(id) ?: return Result.Skipped
                    val server = cur.serverPayload?.let { gson.fromJson(it, Transaction::class.java) }
                    if (server == null || server.deletedAt != null) {
                        db.transactions().deleteHard(id)
                    } else {
                        db.transactions().upsert(server.toEntity(SyncStatus.SYNCED))
                    }
                }
                "wishlist" -> {
                    val cur = db.wishlist().findById(id) ?: return Result.Skipped
                    val server = cur.serverPayload?.let { gson.fromJson(it, WishlistItem::class.java) }
                    if (server == null || server.deletedAt != null) {
                        db.wishlist().deleteHard(id)
                    } else {
                        db.wishlist().upsert(server.toEntity(SyncStatus.SYNCED))
                    }
                }
                "category" -> {
                    val cur = db.categories().findById(id) ?: return Result.Skipped
                    val server = cur.serverPayload?.let { gson.fromJson(it, Category::class.java) }
                    if (server == null || server.deletedAt != null) {
                        db.categories().deleteHard(id)
                    } else {
                        db.categories().upsert(server.toEntity(SyncStatus.SYNCED))
                    }
                }
            }
            Result.Success
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "resolveKeepServer threw", e)
            Result.Failed
        }
    }

    private suspend fun buildResolveOp(entity: String, id: String, force: Boolean): SyncOperation? {
        return when (entity) {
            "transaction" -> {
                val e = db.transactions().findById(id) ?: return null
                val effectiveStatus = if (e.version == 0) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE
                buildOp("transaction", e.id, effectiveStatus, e.version, e.toModel()).copy(force = force)
            }
            "wishlist" -> {
                val e = db.wishlist().findById(id) ?: return null
                val effectiveStatus = if (e.version == 0) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE
                buildOp("wishlist", e.id, effectiveStatus, e.version, e.toModel()).copy(force = force)
            }
            "category" -> {
                val e = db.categories().findById(id) ?: return null
                val effectiveStatus = if (e.version == 0) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE
                buildOp("category", e.id, effectiveStatus, e.version, e.toModel()).copy(force = force)
            }
            else -> null
        }
    }
}
