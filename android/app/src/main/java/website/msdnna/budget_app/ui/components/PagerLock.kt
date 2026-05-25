package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Lets nested horizontally-scrollable composables (chip filter rows etc.)
 * tell their HorizontalPager ancestor to pause its own swipe gesture while
 * an inner scroll is in progress.
 *
 * Why this is needed: `HorizontalPager`'s scrollable internally listens on
 * the same pointer stream, and when an inner LazyRow finishes its scroll
 * the remaining drag/fling drifts up to the pager — manifesting as a tab
 * change every time the user scrolls filter chips past their last position.
 * `pageNestedScrollConnection` only covers fling consumption, not the live
 * pointer events.
 *
 * MainScreen exposes a single `MutableState<Boolean>` here; LazyRow's
 * inside the pager flip it `true` while their state.isScrollInProgress and
 * the pager reads `!flag.value` for `userScrollEnabled`.
 */
val LocalInnerHorizontalScroll = compositionLocalOf<MutableState<Boolean>?> { null }

/**
 * Drop-in companion for `LazyRow(state = …)` inside a pager. Mirrors the
 * lazy list's `isScrollInProgress` into [LocalInnerHorizontalScroll] so the
 * pager swipe is suspended for the duration of the gesture.
 *
 * Outside a pager the CompositionLocal is null and this composable no-ops.
 */
@Composable
fun TrackInnerHorizontalScroll(state: LazyListState) {
    val flag = LocalInnerHorizontalScroll.current ?: return
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            flag.value = scrolling
        }
    }
}
