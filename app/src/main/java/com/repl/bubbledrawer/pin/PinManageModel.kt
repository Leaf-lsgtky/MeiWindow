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
    private val recommendConfig: () -> Pair<Boolean, Int> = { true to 8 },
    private val loadRecommendations: (suspend (List<BubbleApp>, Set<String>, Int) -> List<BubbleApp>)? = null,
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

    /** Search state. */
    var isSearching: Boolean by mutableStateOf(false)
    var searchQuery: String by mutableStateOf("")

    fun startSearch() {
        isSearching = true
        searchQuery = ""
    }

    fun cancelSearch() {
        isSearching = false
        searchQuery = ""
    }

    fun isPinned(app: BubbleApp): Boolean =
        pinsOf().any { it.packageName == app.packageName && it.userId == app.userId }

    val displayedCells: List<Cell>
        get() {
            if (!isSearching || searchQuery.isBlank()) {
                return cells
            }
            val q = searchQuery.trim()
            val matched = apps.mapNotNull { app ->
                val rank = searchRank(app, q)
                if (rank > 0) app to rank else null
            }.sortedWith { (a, rankA), (b, rankB) ->
                if (rankA != rankB) rankA.compareTo(rankB)
                else {
                    val lenCmp = a.label.length.compareTo(b.label.length)
                    if (lenCmp != 0) lenCmp
                    else if (b.usageCount != a.usageCount) b.usageCount.compareTo(a.usageCount)
                    else a.sortKey.compareTo(b.sortKey)
                }
            }.map { it.first }

            if (matched.isEmpty()) {
                return listOf(Cell.Label("无搜索结果"))
            }
            return listOf(Cell.Label("搜索结果")) + matched.map { Cell.App(it, "搜索结果") }
        }

    /**
     * 搜索排序优先级：
     * 1: 首字匹配 (app.label.startsWith)
     * 2: 拼音首字匹配 (app.sortKey.startsWith 或 app.initials.startsWith)
     * 3: 字符包含 (app.label / sortKey / initials 包含)
     * 4: 包名包含 (app.packageName 包含)
     * 0: 不匹配
     */
    fun searchRank(app: BubbleApp, query: String): Int {
        if (app.label.startsWith(query, ignoreCase = true)) return 1
        if (app.sortKey.startsWith(query, ignoreCase = true) ||
            app.initials.startsWith(query, ignoreCase = true)) return 2
        if (app.label.contains(query, ignoreCase = true) ||
            app.sortKey.contains(query, ignoreCase = true) ||
            app.initials.contains(query, ignoreCase = true)) return 3
        if (app.packageName.contains(query, ignoreCase = true)) return 4
        return 0
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Fired once [cells]/[letters] have actually been (re)built.
     *
     * The host used to log `describe()` right after [reload] and one frame later — both
     * BEFORE the load coroutine finished, so the "ground truth" line always read
     * `items=0 letters=0` even on a page full of apps (device log 2026-10-09: panel visibly
     * complete while every MORE_PANEL_TREE line said 0). The rebuild is the only moment the
     * page content is known, so that is where the host is told.
     */
    var onContentChanged: (() -> Unit)? = null

    @MainThread
    fun reload() {
        scope.launch {
            apps = loadApps()
            rebuild()
        }
    }

    /**
     * Re-read only the pins (no app-list pass). The SystemUI panel writes pins through the
     * handoff while this page may already be open — without this the app's 收藏 kept the
     * pre-edit list on screen next to a panel that showed the new one.
     */
    @MainThread
    fun refreshPins() = rebuild()

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
        val (enabled, count) = recommendConfig()
        val currentPins = pinsOf()
        val pinnedKeys = currentPins.map { "${it.packageName}#${it.userId}" }.toSet()
        if (enabled && loadRecommendations != null && apps.isNotEmpty()) {
            scope.launch {
                val recs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { loadRecommendations.invoke(apps, pinnedKeys, count) }.getOrDefault(emptyList())
                }
                cells = Sections.build(
                    all = apps,
                    pins = currentPins,
                    recommend = enabled,
                    recommendCount = count,
                    recommendedApps = recs,
                )
                letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
                onContentChanged?.invoke()
            }
        } else {
            cells = Sections.build(
                all = apps,
                pins = currentPins,
                recommend = enabled,
                recommendCount = count,
            )
            letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
            onContentChanged?.invoke()
        }
    }

    /** Detach from the (foreign, long-lived) host process. */
    fun release() {
        scope.cancel()
    }
}
