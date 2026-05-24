package website.msdnna.budget_app.data.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformLatest

/**
 * High-level sync state shared with the UI (banner) and the system tray
 * notification. Emitted by [SyncEngine] during [SyncEngine.sync].
 *
 * The engine merges entities in a UX-friendly order — wishlist + categories
 * first (cheap, populates empty screens fast), then transactions in chunks
 * (the bulk of the work on first run with thousands of records). Each chunk
 * tick updates [Running.processed], so progress is honest end-to-end and not
 * just a spinner.
 */
sealed class SyncProgress {
    data object Idle : SyncProgress()

    data class Running(
        val phase: Phase,
        val processed: Int,
        val total: Int,
    ) : SyncProgress() {
        val fraction: Float
            get() = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data object Done : SyncProgress()

    data class Failed(val message: String?) : SyncProgress()

    enum class Phase(val label: String) {
        PUSH("Отправка изменений"),
        FETCH("Загрузка с сервера"),
        WISHLIST("Список желаний"),
        CATEGORIES("Категории"),
        TRANSACTIONS("Записи"),
        POST("Завершение"),
    }
}

/**
 * Process-wide single source of truth for sync progress. Reads via
 * [state]; the engine writes via [set]. Banner + notification both
 * collect from here so they stay in lockstep.
 */
object SyncProgressBus {
    private val _state = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val state: StateFlow<SyncProgress> = _state.asStateFlow()

    /**
     * Threshold above which a sync is considered "worth surfacing" — below
     * this, the work is fast enough that a banner / system notification
     * would just flash for a few hundred ms and add noise (think the
     * incremental pull after every CRUD: push of 1 op + fetch of 0-5 rows).
     */
    private const val VISIBLE_TOTAL_THRESHOLD = 500

    /**
     * Delay before surfacing a sync whose `total` we don't yet know (push
     * phase, fetch phase, post-pull refresh — anything that emits with
     * total=0). If the sync finishes within this window the next emit
     * cancels the delay before the banner ever flashes.
     */
    private const val VISIBLE_DEBOUNCE_MS = 600L

    /**
     * Filtered view of [state] for UI surfaces. Suppresses the brief
     * Running-flashes that would otherwise paint and unpaint the banner
     * within a few hundred milliseconds on every CRUD-triggered incremental
     * pull (push 0/0 → fetch 0/0 → wishlist 0/0 → ... → Done, all in <300ms).
     *
     * Rule: a Running emission is forwarded only if it represents real work
     * — either the total is already large enough to matter
     * (>[VISIBLE_TOTAL_THRESHOLD]), or the sync has been running for at
     * least [VISIBLE_DEBOUNCE_MS] without reaching a terminal state. The
     * latter catches genuine-but-unknown-total work (slow server, push of
     * many pending ops). `transformLatest` cancels the pending delay on
     * every new emit, so a fast Done within the window simply replaces the
     * Running with a Done without ever showing the banner.
     */
    val visibleState: Flow<SyncProgress> = state.transformLatest { value ->
        when {
            value !is SyncProgress.Running -> emit(value)
            value.total > VISIBLE_TOTAL_THRESHOLD -> emit(value)
            else -> {
                delay(VISIBLE_DEBOUNCE_MS)
                emit(value)
            }
        }
    }

    fun set(p: SyncProgress) {
        _state.value = p
    }
}
