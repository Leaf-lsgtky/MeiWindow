package com.repl.bubbledrawer.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.launch.ILaunchStrategy
import com.repl.bubbledrawer.pin.PinnedKey

/**
 * The role Flyme's `AppLauncherWindow` (WindowModeSlidGesture) plays around the
 * launcher view: bind data (pins → first 6 + "更多" tile, m9235C :447-463),
 * set radius/safeDegrees per navbar (m9237F :482-505), forward events
 * (SlideGestureForwarding), launch on selection, retract after.
 */
class BubbleDockController(
    private val context: Context,
    private val repo: AppRepository,
    private val pinStore: PinStore,
    private val launchStrategy: ILaunchStrategy,
    /** Optional external tile factory (FanHost builds tiles in code inside SystemUI);
     *  falls back to inflating slide_gesture_list_item in our own process. */
    private val viewFactory: GestureAppLauncher.ViewFactory? = null,
) {
    val launcher = GestureAppLauncher(context)
    var isBusy = false
        private set

    /** Fan shown/hidden signal → host dims/undims the scrim window. */
    var onShownChanged: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoRetract = Runnable { forceRetract() }

    /** Preview mode (no live finger): auto-collapse like a released gesture. */
    var previewMode = false

    /**
     * Fan "更多" tile → host hook. [com.repl.bubbledrawer.xposed.FanHost] sets this
     * inside SystemUI and raises the overlay manage-page panel (方案 B: the same
     * `PinManageView` the Activity hosts, pinned to the current freeform 小窗, so the
     * page IS the window content — Flyme likewise opens SlideLaunchAppSettings with
     * `start_windowmode` instead of a new full-screen page). Left null (e.g. a plain
     * app-process host) the full-screen manage Activity still runs.
     */
    var onMoreRequested: (() -> Unit)? = null

    /**
     * 「已选功能」（快捷方式）—— 它们和收藏的应用一起进扇形，排在应用之后。
     *
     * 由宿主提供（SystemUI 里的 FanHost 从共享组读 App 发布的详情）；返回的**顺序就是扇形里的顺序**。
     * 在 `buildPages` 里被调用，而那是手势中间的主线程 —— 实现必须是便宜的（不许在这里触发全量扫描）。
     */
    var functionItems: (() -> List<com.repl.bubbledrawer.shortcut.ShortcutItem>)? = null

    /**
     * 应用与功能的混排顺序（与收藏管理页同一份布局）。为空时保持旧布局：
     * 收藏应用在前、已选功能在后。
     */
    var pinnedOrder: (() -> List<PinnedKey>)? = null

    /**
     * 功能 tile 被选中：启动一条快捷方式。
     *
     * 与 [launchStrategy] 分开，因为快捷方式不是应用（要走 LauncherApps/Intent，还要带小窗参数）；
     * SystemUI 侧的宿主必须给出实现 —— `ShortcutRepository.launchShortcut` 的默认参数会去读 App 进程
     * 才有的设置（hook 进程读到的是系统界面自己的 cfg）。
     */
    var onFunctionSelected: ((com.repl.bubbledrawer.shortcut.ShortcutItem) -> Unit)? = null

    init {
        launcher.setCallback(object : GestureAppLauncher.Callback {
            override fun onItemSelected(item: GestureAppLauncher.AdapterItem, view: View, index: Int) {
                when (item) {
                    is GestureAppLauncher.AdapterItem.AppItem -> {
                        launchStrategy.launch(context, item.app)
                        LaunchCountStore.increment(context, item.app.packageName, item.app.userId)
                        com.repl.bubbledrawer.data.predict.AppPredictor.recordLaunch(context, item.app.packageName, item.app.userId)
                    }
                    is GestureAppLauncher.AdapterItem.FunctionItem -> {
                        // 快捷方式不是应用：不记进用量统计（那会让宿主应用看起来被"启动"了），
                        // 只把它交给宿主的启动实现（SystemUI 侧会带小窗参数）。
                        val launch = onFunctionSelected
                        if (launch != null) {
                            launch(item.shortcut)
                        } else {
                            runCatching {
                                com.repl.bubbledrawer.shortcut.ShortcutRepository
                                    .launchShortcut(context, item.shortcut)
                            }
                        }
                    }
                    GestureAppLauncher.AdapterItem.More -> {
                        // VERIFIED: original fan's trailing "更多" tile (C2821f.mo3456G
                        // :294-317) launches SlideLaunchAppSettings (选择/管理合一页,
                        // 带 A–Z 索引条) — NOT MoreAppWindow. Per user ruling the two
                        // pages merge: the manage page IS the "更多" destination.
                        // 方案 B: inside SystemUI the host answers with the overlay
                        // 更多应用 panel (Flyme puts MoreAppWindow INTO the window);
                        // only an Activity-less host falls back to the full-screen page.
                        val host = onMoreRequested
                        if (host != null) {
                            host()
                        } else {
                            // Explicit ComponentName by PACKAGE STRING: the caller may be
                            // the SystemUI-hosted fan whose Context reports a different
                            // package, and Intent(context, cls) would misattribute it.
                            context.startActivity(
                                Intent().setComponent(
                                    android.content.ComponentName(
                                        com.repl.bubbledrawer.xposed.ModuleResources.MODULE_PACKAGE,
                                        "com.repl.bubbledrawer.pin.PinManageActivity",
                                    ),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                retractAfterAction()
            }

            override fun onGestureCanceled(reason: Int) = retractAfterAction()

            override fun onExpandStarted() {
                isBusy = true
                onShownChanged?.invoke(true)
                schedulePreviewTimeout()
            }
        })
    }

    private fun schedulePreviewTimeout() {
        mainHandler.removeCallbacks(autoRetract)
        if (previewMode) {
            // a real finger ends with UP/CANCEL; a preview has none — end it after 4s
            mainHandler.postDelayed(autoRetract, 4000)
        }
    }

    var currentPage = 0
        private set
    var totalPages = 1
        private set
    private var pages: List<List<GestureAppLauncher.AdapterItem>> = emptyList()

    /** 这一轮 [show] 用的图标数量与层数 —— 翻页时要按同一套规则给每一页算圈。 */
    private var pageIconCount = 6
    private var pageLayers = FanLayout.LAYERS_SINGLE

    fun resetPage() {
        currentPage = 0
    }

    fun nextPage(): Boolean {
        if (pages.size <= 1) return false
        currentPage = (currentPage + 1) % pages.size
        launcher.switchToAdapter(adapterFor(pages[currentPage]), animated = true)
        return true
    }

    /**
     * 一页的 adapter：先按 [FanLayout.ringOrder] 把 item 排成「内圈（前几个应用 + 「更多」）在前、
     * 外圈（剩下的应用）在后」，再把「前几枚归内圈」告诉 launcher。
     */
    private fun adapterFor(items: List<GestureAppLauncher.AdapterItem>): GestureAppLauncher.SlideAdapter {
        val more = GestureAppLauncher.AdapterItem.More
        val ordered = FanLayout.ringOrder(items, pageIconCount, pageLayers) { it is GestureAppLauncher.AdapterItem.More }
        val appsOnPage = ordered.count { it !== more }
        return GestureAppLauncher.SlideAdapter(
            items = ordered,
            innerSplit = FanLayout.innerTileCount(appsOnPage, pageIconCount, pageLayers),
        ) { item, index -> viewFactory?.create(item, index) ?: itemView(item) }
    }

    /** @param screenH full-screen height in px (the launcher window is MATCH_PARENT). */
    fun show(
        corner: Corner,
        screenW: Int,
        screenH: Int,
        maxApps: Int = 6,
        radiusDp: Int = 0,
        autoFillRecommend: Boolean = false,
        layers: Int = FanLayout.LAYERS_SINGLE,
        recommendEnabled: Boolean = true,
        /**
         * 外圈半径（dp）；≤ 0 = 不单独设定，外圈跟着内圈走（见 [FanLayout.rings]）。
         *
         * 双层时 [radiusDp] 是**内圈**半径 —— 用户 2026-10-16 之前它是"两圈一起"的那个值，而旧几何
         * 又会把它顶到弦长下限上去，于是双层下半径滑条基本没反应。
         */
        outerRadiusDp: Int = 0,
    ) {
        currentPage = 0
        pageIconCount = maxApps
        pageLayers = layers
        pages = buildPages(maxApps, autoFillRecommend, layers, recommendEnabled)
        totalPages = pages.size
        val items = pages.firstOrNull() ?: listOf(GestureAppLauncher.AdapterItem.More)
        val side = if (corner == Corner.BOTTOM_RIGHT || corner == Corner.SIDE_RIGHT) 1 else 0
        launcher.setAdapter(adapterFor(items))
        launcher.setFanLayout(layers, maxApps)
        launcher.setLayoutSide(side)
        val navbar = hasNavBar()
        val defaultRadius = context.resources.getDimension(
            if (navbar) R.dimen.slide_gesture_launcher_item_radius
            else R.dimen.slide_gesture_launcher_item_radius_no_nav_bar,
        )
        val radiusPx = if (radiusDp > 0) {
            radiusDp * context.resources.displayMetrics.density
        } else {
            defaultRadius
        }
        launcher.setRadiusPx(radiusPx)
        launcher.setOuterRadiusPx(
            if (outerRadiusDp > 0) outerRadiusDp * context.resources.displayMetrics.density else 0f,
        )
        launcher.setSafeDegrees(if (navbar) 0 else 3)
        launcher.setCenterPosition(screenH.toFloat())
    }

    /** Finger events end (UP/CANCEL forwarded) → stop the preview timer. */
    fun onGestureFinished() {
        previewMode = false
        mainHandler.removeCallbacks(autoRetract)
    }

    private fun hasNavBar(): Boolean {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return id > 0 && context.resources.getDimensionPixelSize(id) > 0
    }

    /**
     * Builds pages of candidate apps.
     * Sequences:
     * 1. Pinned apps + pinned functions (快捷方式)，按同一份混排布局；无布局时应用在前
     * 2. Recommended apps
     *
     * 切页与容量本身在 [FanPagination]（纯函数、有单测）；这里只负责取数。
     *
     * 容量随层数变：单层 = [maxApps] 个应用 + 「更多」；双层 = 内层 `maxApps` + 外层
     * `maxApps + 2`（外层没有「更多」），见 [FanLayout.tilesPerPage]。
     *
     * @param recommendEnabled 「启用推荐应用」总开关。关掉时扇形里**一条推荐都不放**
     *   （用户 2026-10-15：「如果用户没开推荐就不动」）：页数=收藏切出来的那几页，
     *   最后一页短就短着。
     */
    fun buildPages(
        maxApps: Int,
        autoFillRecommend: Boolean,
        layers: Int = FanLayout.LAYERS_SINGLE,
        recommendEnabled: Boolean = true,
    ): List<List<GestureAppLauncher.AdapterItem>> {
        val all = repo.cachedAll()
        val pinnedApps = ArrayList<com.repl.bubbledrawer.pinyin.BubbleApp>()
        val pinnedKeys = HashSet<String>()
        for (ref in pinStore.pins()) {
            val app = all.firstOrNull { it.packageName == ref.packageName && it.userId == ref.userId }
                ?: repo.findApp(ref.packageName, ref.userId)
            if (app != null) {
                pinnedApps.add(app)
                pinnedKeys.add("${app.packageName}#${app.userId}")
            }
        }

        // 「已选功能」与收藏应用共享同一张布局；没有布局时才退回「应用在前、功能在后」。
        val pinnedFunctions = runCatching { functionItems?.invoke() }.getOrNull().orEmpty()
        val pinned = ArrayList<GestureAppLauncher.AdapterItem>(pinnedApps.size + pinnedFunctions.size)
        val appByKey = pinnedApps.associateBy { PinnedKey.App(it.packageName, it.userId) }
        val functionByKey = pinnedFunctions.associateBy { PinnedKey.Function(it.pinKey) }
        val placed = HashSet<PinnedKey>(appByKey.size + functionByKey.size)
        for (key in runCatching { pinnedOrder?.invoke() }.getOrNull().orEmpty()) {
            val item = when (key) {
                is PinnedKey.App -> appByKey[key]?.let { GestureAppLauncher.AdapterItem.AppItem(it) }
                is PinnedKey.Function -> functionByKey[key]?.let { GestureAppLauncher.AdapterItem.FunctionItem(it) }
            }
            if (item != null && placed.add(key)) pinned.add(item)
        }
        // 布局丢失/新收藏尚未写进布局时，剩下的项按原有顺序补齐，绝不丢 tile。
        pinnedApps.forEach { app ->
            val key = PinnedKey.App(app.packageName, app.userId)
            if (placed.add(key)) pinned.add(GestureAppLauncher.AdapterItem.AppItem(app))
        }
        pinnedFunctions.forEach { function ->
            val key = PinnedKey.Function(function.pinKey)
            if (placed.add(key)) pinned.add(GestureAppLauncher.AdapterItem.FunctionItem(function))
        }

        val capacity = FanLayout.tilesPerPage(maxApps, layers)
        // 取数上限 = 「够铺满前几页」的基数（容量 ×3）再按 [FanPagination.topUpRequest] 往上补整页 ——
        // 用户要的是"最后一页也铺满"，哪怕为此要多要几条推荐（"即使没开那么多推荐"）。
        val base = maxOf(15, capacity * 3)
        val request = FanPagination.topUpRequest(
            base = base,
            pinnedCount = pinned.size,
            perPage = capacity - 1,
            autoFillRecommend = autoFillRecommend,
        )
        val recommendations = if (recommendEnabled) {
            com.repl.bubbledrawer.data.predict.AppPredictor.getRecommendations(context, all, pinnedKeys, request)
        } else {
            emptyList()
        }

        return FanPagination.pages(
            pinned = pinned,
            recommended = recommendations.map { GestureAppLauncher.AdapterItem.AppItem(it) },
            capacity = capacity,
            autoFillRecommend = autoFillRecommend,
            more = { GestureAppLauncher.AdapterItem.More },
        )
    }

    /** Inflates the verbatim port of slide_gesture_list_item.xml. */
    private fun itemView(item: GestureAppLauncher.AdapterItem): View {
        val v = LayoutInflater.from(context)
            .inflate(R.layout.slide_gesture_list_item, launcher, false)
        v.layoutParams = GestureAppLauncher.LayoutParams()
        // 图标大小/形状：与 SystemUI 里的 FanHost.buildTile 读同一套设置（本地镜像），
        // 保证两条建 tile 的路径长得一样。
        val snap = com.repl.bubbledrawer.xposed.RemotePrefs.read(
            runCatching { com.repl.bubbledrawer.xposed.RemoteBridge.local(context) }.getOrNull(),
        )
        if (v is SlideGestureItemView) v.iconShape = snap.fanIconShape
        val icon = v.findViewById<SlideIconView>(R.id.slide_icon)
        if (icon != null) {
            val px = (snap.fanIconDp * context.resources.displayMetrics.density).toInt()
            icon.layoutParams = android.widget.FrameLayout.LayoutParams(px, px)
            icon.shape = snap.fanIconShape
            when (item) {
                is GestureAppLauncher.AdapterItem.AppItem -> icon.setImageDrawable(repo.icon(item.app, snap.cloneBadge))
                is GestureAppLauncher.AdapterItem.FunctionItem -> {
                    // 快捷方式的图标；内建条目（聚合支付）自带 drawable；没有就退回宿主应用图标
                    // （扇形的 tile 没有文字，全靠图）。
                    val bmp = item.shortcut.iconBitmap
                    val drawable = item.shortcut.iconDrawable
                    when {
                        bmp != null && !bmp.isRecycled ->
                            icon.setImageDrawable(android.graphics.drawable.BitmapDrawable(context.resources, bmp))
                        drawable != null -> icon.setImageDrawable(drawable)
                        else -> icon.setImageDrawable(
                            repo.icon(
                                com.repl.bubbledrawer.pinyin.BubbleApp(item.shortcut.packageName, item.shortcut.label),
                                snap.cloneBadge,
                            ),
                        )
                    }
                }
                GestureAppLauncher.AdapterItem.More -> icon.setImageResource(R.drawable.icon_gesture_more_app)
            }
        }
        // ORIGINAL: the adapter sets a click listener on every tile
        // (C2937F.m9712g :142 → C2937F$a.onClick :56-69 → mo9283j(item, view, reason=1)),
        // so with the panel open a plain TAP on an icon launches it without any drag.
        // (The corner-drag path launches via m9729p→m9736y with reason=0 instead.)
        v.setOnClickListener { launcher.clickSelect(item, v) }
        return v
    }

    /**
     * Fresh DOWN on void while the fan is open — verbatim C2822g.onTouchEvent
     * :381-393: DOWN → m9254Z() (close: m9245P → m9738l retract) + onGestureCanceled(1)
     * (stats-only in the original, :1096-1098; our onGestureCanceled maps to the same
     * retract, so one call is equivalent). ACTION_POINTER_DOWN :383-385 is ignored.
     */
    fun onVoidTouchDown() = retractAfterAction()

    fun forward(ev: MotionEvent, pointerId: Int): Boolean {
        val r = launcher.forwardEvent(ev, pointerId)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            onGestureFinished()
        }
        return r
    }

    private fun retractAfterAction() {
        launcher.retract(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { shown(false) }
            override fun onAnimationEnd(animation: Animator) { shown(false) }
        })
        onGestureFinished()
    }

    private fun shown(v: Boolean) {
        isBusy = launcher.state != 0
        if (!v) onShownChanged?.invoke(false)
    }

    fun forceRetract() = retractAfterAction()

    fun destroy() {
        mainHandler.removeCallbacks(autoRetract)
        launcher.removeAllViews()
    }
}
