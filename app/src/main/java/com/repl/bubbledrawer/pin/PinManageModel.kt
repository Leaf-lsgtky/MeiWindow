package com.repl.bubbledrawer.pin

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * State holder behind the pin-manager page. ONE instance is created per host
 * (PinManageActivity / FanHost's overlay panel) and survives recomposition; the composable
 * reads it through `collectAsStateWithLifecycle`-compatible observable fields
 * (docs/ui-guidelines.md "Flow 收集" + "强跳过友好的状态形状").
 *
 * The cell list is REMEMBERED OUTSIDE the scroll content lambda and keyed on
 * (apps, pins): the original recomputed `Sections.build` on every adapter pass; with the
 * grid this caching is what keeps scroll cheap (same rationale as the guidelines'
 * "排序 + 分块结果必须在 LazyColumn 之外缓存").
 */
class PinManageModel(
    private val loadApps: suspend () -> ImmutableList<BubbleApp>,
    private val pinsOf: () -> List<PinnedRef>,
    /** Persisted on drag end / unpin. */
    private val onOrderChange: (List<PinnedRef>) -> Unit,
) {
    /** false = view mode (tap launches); true = edit mode (tap pins, long-press drags). */
    var manageMode: Boolean by mutableStateOf(false)
        private set

    var cells: List<Cell> by mutableStateOf(emptyList())
        private set

    var apps: ImmutableList<BubbleApp> by mutableStateOf(persistentListOf())
        private set

    /** Index-bar letters, display order (★ first — original marks it ★, SlideLaunchAppSettings :326). */
    var letters: List<String> by mutableStateOf(emptyList())
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @MainThread
    fun reload() {
        scope.launch {
            apps = loadApps()
            rebuild()
        }
    }

    @MainThread
    fun toggleManageMode() {
        manageMode = !manageMode
        if (!manageMode) persistOrder()
        rebuild()
    }

    fun managerLabel(complete: String, manage: String): String = if (manageMode) complete else manage

    /** ★ drag reorder: move the Pin at [from] to [to] (both Pin positions, cells-indexed). */
    fun movePin(from: Int, to: Int) {
        if (from == to) return
        val c = cells
        if (from !in c.indices || to !in c.indices) return
        if (c[from] !is Cell.Pin || c[to] !is Cell.Pin) return
        val mutable = c.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        cells = mutable
        // Persisting per move would spam the bridge; the drag end calls [persistOrder].
    }

    fun persistOrder() {
        val order = cells.filterIsInstance<Cell.Pin>().map { PinnedRef(it.app.packageName, it.app.userId) }
        if (order.isNotEmpty()) onOrderChange(order)
    }

    fun togglePin(app: BubbleApp) {
        val current = pinsOf().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        if (!current.remove(ref)) current.add(ref)
        onOrderChange(current)
        rebuild()
    }

    /**
     * One-line summary of what the page currently shows, for the module log — replaces the
     * View-tree walk of the old PinManageView.describe(): with Compose the texts are
     * state, so the summary is derivable without touching the UI.
     */
    fun describe(tabText: String, headText: String): String =
        "texts=[$tabText,$headText] items=${cells.size} letters=${letters.size} manage=$manageMode"

    private fun rebuild() {
        cells = Sections.build(apps, pinsOf(), recommend = true)
        letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
    }

    /** Detach from the (foreign, long-lived) host process. */
    fun release() {
        scope.cancel()
    }
}
