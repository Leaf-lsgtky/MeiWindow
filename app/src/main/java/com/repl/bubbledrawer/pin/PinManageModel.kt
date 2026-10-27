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
    private val recommendConfig: () -> Pair<Boolean, Int> = { true to com.repl.bubbledrawer.xposed.RemotePrefs.DEFAULT_RECOMMEND_COUNT },
    private val loadRecommendations: (suspend (List<BubbleApp>, Set<String>, Int) -> List<BubbleApp>)? = null,
    /**
     * 分身角标开关（[com.repl.bubbledrawer.xposed.RemotePrefs.KEY_CLONE_BADGE]）—— 由宿主读设置后传进来：
     * 面板跑在 SystemUI 里（LSPosed 远程组），管理页跑在应用进程（本地镜像）。图标本身永远取主空间
     * 那张，这一项只管要不要叠角标。
     */
    val cloneBadge: Boolean = com.repl.bubbledrawer.xposed.RemotePrefs.DEFAULT_CLONE_BADGE,
    private val loadShortcuts: (suspend (onProgress: (Int, Int) -> Unit) -> Pair<List<com.repl.bubbledrawer.shortcut.ShortcutItem>, List<com.repl.bubbledrawer.shortcut.ShortcutItem>>)? = null,
    initialTab: Int = 0,
    private val functionPinsOf: () -> List<String> = { emptyList() },
    /**
     * 已选功能的**详情**（宿主提供）。面板（SystemUI）从共享组读 App 发布的那份：
     * 它自己的枚举结果里未必有这些键，而 `mapNotNull` 遇上认不出来的键会**静默丢掉** ——
     * 「设置页里选好了，扇形面板里什么都没有」就是这么来的。
     */
    private val pinnedFunctionItems: () -> List<com.repl.bubbledrawer.shortcut.ShortcutItem> = { emptyList() },
    private val onFunctionOrderChange: (List<String>) -> Unit = {},
    mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
) {
    /** 0 = 应用 (Apps), 1 = 功能 (Shortcuts / Features) */
    var selectedTab: Int by mutableStateOf(initialTab)
        private set

    fun selectTab(index: Int) {
        if (selectedTab != index) {
            selectedTab = index
        }
    }

    var functionPins: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * 宿主给的已选功能详情（面板从共享组读 App 发布的那份）。
     *
     * 缓存在状态里而不是每次读都问宿主：这是个 getter，组合里会被读很多次，而它背后是一次 prefs
     * 读取 + 解码。
     */
    var pinnedFunctionDetails: List<com.repl.bubbledrawer.shortcut.ShortcutItem> by mutableStateOf(emptyList())
        private set

    fun refreshFunctionPins() {
        functionPins = functionPinsOf()
        pinnedFunctionDetails = runCatching { pinnedFunctionItems() }.getOrDefault(emptyList())
    }

    fun isFunctionPinned(item: com.repl.bubbledrawer.shortcut.ShortcutItem): Boolean =
        functionPins.contains(item.pinKey)

    fun toggleFunctionPin(item: com.repl.bubbledrawer.shortcut.ShortcutItem) {
        val key = item.pinKey
        val next = if (functionPins.contains(key)) functionPins - key else functionPins + key
        // 同 [togglePin]：刚写下的这一份就是权威。写入会同步触发 prefs 监听（值 + 戳两个 key 在
        // 同一个文件里），监听者回头再读一次可能拿到撕裂的那一对，于是刚点的这一下被旧值顶掉 ——
        // 用户看到的是「第二次开始点了没反应」。写回之后这里再断言一次，页面与手一致。
        functionPins = next
        functionOrderDirty = false
        onFunctionOrderChange(next)
        functionPins = next
    }

    var appShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem> by mutableStateOf(emptyList())
        private set

    var desktopShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem> by mutableStateOf(emptyList())
        private set

    /**
     * 功能页加载进度：`ShortcutRepository` 每扫到一条就回调一次，面板顶部据此画进度条 +
     * 「第 x 个 / 共 y 个」。total 是扫描前的估算值（LauncherApps + 桌面捷径 + 兜底），
     * done 只增不减；total 小于 done 时自动抬高，不会出现 3/2。
     */
    var functionLoading: Boolean by mutableStateOf(false)
        private set
    var functionLoaded: Int by mutableStateOf(0)
        private set
    var functionTotal: Int by mutableStateOf(0)
        private set

    /**
     * 功能页的完整目录 = 自己枚举到的 + 宿主给的已选详情。
     *
     * 加上详情这一半是为了让面板**至少画得出用户已经选过的那些**：面板（SystemUI）自己的枚举结果
     * 里未必有它们（它读不到 App 侧的缓存），而认不出来的键在已选区就是"凭空消失"
     * （用户 2026-10-13：「功能不显示在扇形面板」）。自己枚举到的条目排在前、优先使用。
     */
    val allShortcuts: List<com.repl.bubbledrawer.shortcut.ShortcutItem>
        get() {
            val list = mutableListOf<com.repl.bubbledrawer.shortcut.ShortcutItem>()
            val seen = mutableSetOf<String>()
            for (item in appShortcuts + desktopShortcuts + pinnedFunctionDetails) {
                if (seen.add(item.pinKey)) {
                    list.add(item)
                }
            }
            return list
        }

    /** 需求 3：从扇形面板打开的更多应用，只显示用户手动添加的功能 */
    val pinnedFunctions: List<com.repl.bubbledrawer.shortcut.ShortcutItem>
        get() {
            // [allShortcuts] 里已经含「自己枚举到的」与「宿主给的详情」两半，自己那份优先。
            // 两边都没有才退回占位项 —— 绝不让一条已选功能凭空消失（用户 2026-10-13 的报障）。
            val allMap = allShortcuts.associateBy { it.pinKey }
            val list = functionPins.mapNotNull { key ->
                allMap[key] ?: com.repl.bubbledrawer.shortcut.FunctionPinDetails.placeholder(key)
            }
            if (!isSearching || searchQuery.isBlank()) return list
            val q = searchQuery.trim()
            return list.filter {
                it.label.contains(q, ignoreCase = true) || it.subLabel?.contains(q, ignoreCase = true) == true
            }
        }

    /**
     * 拼音排序键缓存。
     *
     * 这些 getter 在一次组合里会被读好几遍（列表、字母条、空态判断…），而 `AppSortKey.of` 是
     * 一次汉字→拼音转换 —— 不缓存就是同一个应用名反复转换；面板里那还是 SystemUI 的主线程。
     */
    private val functionSortKeys = HashMap<String, String>()

    private fun functionSortKey(appName: String): String =
        functionSortKeys.getOrPut(appName) { com.repl.bubbledrawer.pinyin.AppSortKey.of(appName) }

    /**
     * 需求 5：以应用名称为小标题作为分类，应用名称使用拼音排序。
     *
     * 同一应用的快捷方式按原有顺序排在一起。
     */
    val groupedFunctions: List<AppFunctionGroup>
        get() {
            val query = if (isSearching) searchQuery.trim() else ""
            val groups = mutableMapOf<String, MutableList<com.repl.bubbledrawer.shortcut.ShortcutItem>>()
            for (item in allShortcuts) {
                // 聚合支付是内建功能，**不进**字母分组目录（用户 2026-10-24：不与快捷方式混排，
                // 它在功能页有自己的独立格位）；但它可以被收藏，收藏区的解析（pinnedFunctions）照样用。
                if (com.repl.bubbledrawer.shortcut.AggregatePayment.isEntry(item)) continue
                val appName = item.subLabel?.ifBlank { null } ?: item.label
                if (query.isNotEmpty() && !item.label.contains(query, ignoreCase = true) && !appName.contains(query, ignoreCase = true)) {
                    continue
                }
                groups.getOrPut(appName) { mutableListOf() }.add(item)
            }
            return groups.map { (appName, items) ->
                AppFunctionGroup(
                    appName = appName,
                    sortKey = functionSortKey(appName),
                    items = items,
                )
            }.sortedBy { it.sortKey }
        }

    val functionLetters: List<String>
        get() {
            val list = mutableListOf<String>()
            list.add("★")
            val lettersFromGroups = groupedFunctions.map { group ->
                val ch = group.sortKey.firstOrNull()?.uppercaseChar() ?: '#'
                if (ch in 'A'..'Z') ch.toString() else "#"
            }.distinct()
            list.addAll(lettersFromGroups)
            return list.distinct()
        }

    /** false = view mode (tap launches); true = edit mode (tap pins, long-press drags). */
    var manageMode: Boolean by mutableStateOf(false)
        private set

    /**
     * 分区折叠状态（见 [Sections.visible]）：某一区超过 [Sections.VISIBLE_MAX] 个时默认只铺 20 个，
     * 点那一枚「展开更多」才全放出来。**不放进 [cells]**：折叠只是显示层的事，改它不该重跑推荐。
     *
     * 存在 model 上而不是 composable 的 `remember` 里：面板里切一次 Tab（应用 ↔ 功能）整棵内容树
     * 会重建，状态挂在内容上就会被重置回折叠态。
     */
    var pinsExpanded: Boolean by mutableStateOf(false)
        private set

    var recommendExpanded: Boolean by mutableStateOf(false)
        private set

    fun togglePinsExpanded() {
        pinsExpanded = !pinsExpanded
    }

    fun toggleRecommendExpanded() {
        recommendExpanded = !recommendExpanded
    }

    /** 按当前折叠状态铺到界面上的 cell 列表（搜索不经过这里，搜索结果不折叠）。 */
    val visibleCells: List<Cell>
        get() = Sections.visible(cells, pinsExpanded, recommendExpanded)

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

    /**
     * 这一页当前的收藏集合（`pkg#userId`）。
     *
     * 之前 [isPinned] 每格都调一次 `pinsOf()` —— 一次页面构建就是几百次**和解读**（每次都可能把
     * 落后的一方写回共享组），把刚写下的编辑用旧值顶掉的机会也被放大几百倍（2026-10-12
     * 「收藏自动反复添加/移除」）。收藏列表在一次构建里本来就是同一份，这里在 [rebuild] 时取一次。
     *
     * **它必须是 Compose 状态**（2026-10-18）：搜索结果里的格子一直是 `Cell.App`（只有 `+`/`−`
     * 徽标不同），点一下收藏时 `cells` 变了但那些格子不会重建；读普通字段的话界面上什么都不会变，
     * 看起来就是"点了没用"。用户 2026-10-18：「当点击管理再搜索应用时，搜索到的应用无法正常点击
     * 收藏或取消收藏」—— 位置在 ★ 区（搜索时看不见），徽标又不动，就是完全没反应。
     *
     * 还没构建过时（快照为 null）退回现读：那种状态下页面是空的，不会有人来问。
     */
    private var pinnedKeys by mutableStateOf<Set<String>?>(null)

    fun isPinned(app: BubbleApp): Boolean =
        (pinnedKeys ?: pinsOf().map { "${it.packageName}#${it.userId}" }.toSet())
            .contains("${app.packageName}#${app.userId}")

    val displayedCells: List<Cell>
        get() {
            if (!isSearching || searchQuery.isBlank()) {
                return cells
            }
            val matched = com.repl.bubbledrawer.pinyin.AppSearch.filter(apps, searchQuery)

            if (matched.isEmpty()) {
                return listOf(Cell.Label("无搜索结果"))
            }
            return listOf(Cell.Label("搜索结果")) + matched.map { Cell.App(it, "搜索结果") }
        }

    /**
     * 搜索排序优先级：见 [com.repl.bubbledrawer.pinyin.AppSearch] —— 匹配与排序都搬到那里了，
     * 「选择应用」页要和面板给出同样的结果，两边共用一份实现（这里只留转发，别再加分支）。
     */
    fun searchRank(app: BubbleApp, query: String): Int =
        com.repl.bubbledrawer.pinyin.AppSearch.rank(app, query)

    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())

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
        // 应用列表与功能快捷方式互不依赖：并行加载。功能页首屏不必等 AppRepository 枚举完
        // 几百个应用图标才开始解析 shortcuts.xml/缓存。
        scope.launch {
            apps = loadApps()
            rebuild()
            // 键与详情一起刷新（详情决定「面板能不能把已选功能画出来」，见 allShortcuts）。
            refreshFunctionPins()
        }
        scope.launch {
            loadShortcuts?.let { loader -> loadFunctionShortcuts(loader) }
        }
    }

    @MainThread
    fun refreshShortcuts() {
        scope.launch {
            loadShortcuts?.let { loader -> loadFunctionShortcuts(loader) }
        }
    }

    /**
     * 功能页统一加载入口：进度状态与结果赋值只在这里，reload / 面板刷新不会各写一半。
     * 回调来自 IO 线程，状态写入切回 Main；`finally` 保证异常时进度条也会消失。
     */
    private suspend fun loadFunctionShortcuts(
        loader: suspend (onProgress: (Int, Int) -> Unit) -> Pair<
            List<com.repl.bubbledrawer.shortcut.ShortcutItem>,
            List<com.repl.bubbledrawer.shortcut.ShortcutItem>
            >,
    ) {
        functionLoading = true
        functionLoaded = 0
        functionTotal = 0
        try {
            runCatching {
                val (appsList, desktopList) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    loader { loaded, total ->
                        scope.launch {
                            functionLoaded = loaded
                            functionTotal = total.coerceAtLeast(loaded)
                        }
                    }
                }
                appShortcuts = appsList
                desktopShortcuts = desktopList
                functionLoaded = appsList.size + desktopList.size
                functionTotal = functionTotal.coerceAtLeast(functionLoaded)
            }
        } finally {
            functionLoading = false
        }
    }

    /**
     * Re-read only the pins (no app-list pass). The SystemUI panel writes pins through the
     * handoff while this page may already be open — without this the app's 收藏 kept the
     * pre-edit list on screen next to a panel that showed the new one.
     */
    @MainThread
    fun refreshPins() {
        refreshFunctionPins()
        rebuild()
    }

    @MainThread
    fun toggleManageMode() {
        manageMode = !manageMode
        if (!manageMode) {
            if (orderDirty) persistOrder()
            if (functionOrderDirty) persistFunctionOrder()
        }
        rebuild()
    }

    fun managerLabel(complete: String, manage: String): String = if (manageMode) complete else manage

    /** 标记是否发生过拖拽换位：只有真拖过才需要在退出管理模式时补写顺序，避免把刚点的收藏覆盖掉。 */
    private var orderDirty = false
    private var functionOrderDirty = false

    /** ★ drag reorder: move the Pin at [from] to [to] (both Pin positions, cells-indexed). */
    fun movePin(from: Int, to: Int) {
        if (from == to) return
        val c = cells
        if (from !in c.indices || to !in c.indices) return
        if (c[from] !is Cell.Pin || c[to] !is Cell.Pin) return
        val mutable = c.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        cells = mutable
        orderDirty = true
        // Persisting per move would spam the bridge; the drag end calls [persistOrder].
    }

    fun persistOrder() {
        orderDirty = false
        val order = cells.filterIsInstance<Cell.Pin>().map { PinnedRef(it.app.packageName, it.app.userId) }
        if (order.isNotEmpty()) onOrderChange(order)
    }

    /**
     * ★ 已选功能的拖拽排序。
     *
     * 应用收藏和功能收藏的持久化是两条链（`PinnedRef` / `functionPins`），不能把它们混成一个
     * 下标移动：功能格只在功能段内换位，应用段保持原顺序。拖动结束由 [persistFunctionOrder]
     * 统一落盘，避免每一帧都写远程 prefs。
     */
    fun moveFunctionPin(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val from = functionPins.indexOf(fromKey)
        val to = functionPins.indexOf(toKey)
        if (from < 0 || to < 0) return
        val mutable = functionPins.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        functionPins = mutable
        functionOrderDirty = true
    }

    fun persistFunctionOrder() {
        functionOrderDirty = false
        onFunctionOrderChange(functionPins)
    }

    fun togglePin(app: BubbleApp) {
        val current = pinsOf().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        if (!current.remove(ref)) current.add(ref)
        orderDirty = false
        onOrderChange(current)
        // 刚写下的这一份就是权威，**不要立刻再和解读一次**：写入值与戳分两个 key，同一毫秒的平票
        // 可能把旧值读回来，表现就是「点了没动，再点别的才刷新」（用户 2026-10-12 报告）。
        rebuild(pins = current)
    }

    /**
     * One-line summary of what the page currently shows, for the module log — replaces the
     * View-tree walk of the old PinManageView.describe(): with Compose the texts are
     * state, so the summary is derivable without touching the UI.
     */
    fun describe(tabText: String, headText: String): String =
        "texts=[$tabText,$headText] items=${cells.size} letters=${letters.size} manage=$manageMode"

    /** 上一次异步算出的推荐列表缓存：让 [rebuild] 能先同步落盘最新收藏，再异步刷新推荐。 */
    private var lastRecommendations: List<BubbleApp> = emptyList()

    /**
     * Rebuild the cell list.
     *
     * @param pins 已知的收藏列表（[togglePin] 刚写下的那份）；null = 从存储现读。
     *
     * 两条纪律，都是用户 2026-10-12 那两条报告换来的：
     *  1. **`pinnedKeys` 与 `cells` 必须来自同一次快照**。图标读 [isPinned]（`pinnedKeys`），
     *     位置读 `cells` —— 一个同步落、一个在协程里晚一步落，就会"图标变了、位置没变，
     *     再点别的才刷新"。
     *  2. **旧的重建不许覆盖新的**（[rebuildGeneration]）：带推荐的那条路要跳一次 IO，
     *     连续两次操作时先发起的那个可能后完成，用旧快照把刚刷好的列表盖回去。
     */
    private fun rebuild(pins: List<PinnedRef>? = null) {
        val generation = ++rebuildGeneration
        val (enabled, count) = recommendConfig()
        val currentPins = pins ?: pinsOf()
        // 一次重建只读一次（缓存给 isPinned 用）：见上面的 `pinnedKeys`。
        val pinned = currentPins.map { "${it.packageName}#${it.userId}" }.toSet()
        // 先用缓存的推荐列表同步落地 `pinnedKeys` 与 `cells`：避免异步算推荐的窗口期内 `cells`
        // 仍停留在旧收藏（例如点完收藏立刻点「完成」触发二次 rebuild 时把刚点的收藏吞掉）。
        // 缓存必须按**当前** apps 过滤：应用卸载/列表刷新后，旧推荐不能短暂画出一个不存在的应用。
        val availableKeys = apps.mapTo(HashSet()) { "${it.packageName}#${it.userId}" }
        val cachedRecs = if (enabled) {
            lastRecommendations.filter {
                "${it.packageName}#${it.userId}" in availableKeys &&
                    "${it.packageName}#${it.userId}" !in pinned
            }
        } else {
            emptyList()
        }
        applyCells(currentPins, pinned, enabled, count, cachedRecs)
        if (enabled && loadRecommendations != null && apps.isNotEmpty()) {
            scope.launch {
                val recs = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { loadRecommendations.invoke(apps, pinned, count) }.getOrDefault(emptyList())
                }
                if (generation != rebuildGeneration) return@launch
                lastRecommendations = recs
                applyCells(currentPins, pinned, enabled, count, recs)
            }
        }
    }

    /** 唯一落地点：`pinnedKeys` 与 `cells` 一起更新，别处不要再单独改它们。 */
    private fun applyCells(
        pins: List<PinnedRef>,
        pinned: Set<String>,
        enabled: Boolean,
        count: Int,
        recommended: List<BubbleApp>,
    ) {
        pinnedKeys = pinned
        cells = Sections.build(
            all = apps,
            pins = pins,
            recommend = enabled,
            recommendCount = count,
            recommendedApps = recommended,
        )
        letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
        onContentChanged?.invoke()
    }

    /** 每次重建的序号：只有最新那次的异步结果可以落地（见 [rebuild]）。 */
    private var rebuildGeneration = 0

    /** Detach from the (foreign, long-lived) host process. */
    fun release() {
        scope.cancel()
    }
}

/**
 * 需求 5：以应用名称为小标题分类的功能分组
 */
data class AppFunctionGroup(
    val appName: String,
    val sortKey: String,
    val items: List<com.repl.bubbledrawer.shortcut.ShortcutItem>,
)
