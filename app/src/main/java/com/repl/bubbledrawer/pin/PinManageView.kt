package com.repl.bubbledrawer.pin

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The 管理页 content (`SlideLaunchAppSettings` 应用 tab slice) as a reusable View, so
 * ONE page serves both hosts:
 *
 *  - [PinManageActivity] — full screen (system back / ✕ closes);
 *  - `FanHost`'s overlay 更多 panel — pinned on the current freeform 小窗 (方案 B).
 *
 * That mirrors Flyme: the fan's 更多 tile (`C2821f.mo3456G` :294-317) opens
 * `SlideLaunchAppSettings` — the app manager page — and because it is started with
 * `start_windowmode` it lands INSIDE the window rather than as a new full-screen
 * page. There is no separate "更多应用" page in the replica (the decompiled
 * `MoreAppWindow` is not what the tile opens).
 *
 * Everything below the header is the previous Activity body, unchanged: 4-column
 * grid, ★ drag-reorder, 推荐 group, A–Z sections, letter index bar, view-mode tap
 * launches the app (original: `AbstractC2806s.m9105e` with `start_windowmode=true`).
 *
 * Resource reads always go through `context.resources` / the inflated views because
 * inside SystemUI the Context is `ModuleResources.FanContext` (module resources,
 * foreign storage) — `context.getString(R.string…)` would resolve against SystemUI.
 */
class PinManageView(
    private val context: Context,
    private val repo: AppRepository,
 private val pinStore: PinStore,
    /** View-mode tap: launch the app (Activity: ConfigurableLaunchStrategy; panel: freeform on the window rect). */
    private val onLaunch: (BubbleApp) -> Unit,
    /**
     * Where this page is hosted.
     *
     * [Chrome.ACTIVITY] — the Activity's own ActionBar draws the title and the 管理/完成
     * menu item (the page adds NO header of its own — identical to the pre-panel page).
     *
     * [Chrome.PANEL] — an overlay window has no ActionBar, so the page draws a look-alike
     * bar at the top (title + 管理/完成 + ✕) so the 更多 overlay is visually the same page.
     */
    private val chrome: Chrome = Chrome.ACTIVITY,
    /** Panel only: ✕ / finish. */
    private val onClose: (() -> Unit)? = null,
    /** Host refreshes its own affordance when 管理/完成 flips. */
    private val onManageModeChanged: ((Boolean) -> Unit)? = null,
    /** 面板外观：图标 dp / 文字 sp（0 = 布局默认；仅 overlay 传入）。 */
    private val iconDp: Int = 0,
    private val textSp: Int = 0,
) {

    enum class Chrome { ACTIVITY, PANEL }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val list: RecyclerView
    private val bar: LetterIndexBar
    private val lm: GridLayoutManager
    private val touchHelper: ItemTouchHelper
    private val manageButton: TextView
    private var adapter: PinGridAdapter? = null
    private var apps: List<BubbleApp> = emptyList()

    /** false = view mode (tap launches); true = edit mode (tap pins, long-press drags). */
    var manageMode = false
        private set

    val view: View

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.activity_pin_manage, null, false)

        val tabs = content.findViewById<TabLayout>(R.id.tab_container)
        tabs.addTab(tabs.newTab().setText(context.resources.getString(R.string.slide_launcher_tab_all_app)))

        list = content.findViewById(R.id.app_list)
        bar = content.findViewById(R.id.letter_bar)
        lm = GridLayoutManager(context, 4) // f10411g0 = 4 (SlideLaunchAppSettings:87)
        list.layoutManager = lm

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val a = adapter ?: return 0
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                return if (a.cells.getOrNull(pos) is Cell.Pin)
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
            }

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = adapter ?: return false
                val f = from.bindingAdapterPosition
                val t = to.bindingAdapterPosition
                if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
                if (a.cells.getOrNull(f) !is Cell.Pin || a.cells.getOrNull(t) !is Cell.Pin) return false
                a.movePin(f, t)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                persistPinOrder()
            }
        })
        touchHelper.attachToRecyclerView(list)

        bar.onLetterSelected = { letter -> scrollToLetter(letter) }

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = lm.findFirstVisibleItemPosition()
                val a = adapter ?: return
                var cur: String? = null
                for (i in first downTo 0) {
                    val c = a.cells.getOrNull(i)
                    if (c is Cell.Label) { cur = c.text; break }
                }
                // above the first section = the ★ pinned area (original marks it ★, :326)
                if (cur == null) cur = "★"
                if (cur != bar.currentLetter) bar.currentLetter = cur
            }
        })

        manageButton = actionLabel()
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (chrome == Chrome.PANEL) {
                // overlay has no ActionBar → same-looking bar, built here
                addView(
                    header(),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(56f),
                    ),
                )
            }
            addView(
                content,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    /** Re-read apps/pins and repaint (called on show / resume). */
    fun reload() {
        scope.launch {
            apps = repo.cachedAll().ifEmpty { repo.loadAll() }
            rebuild()
        }
    }

    /** 管理 / 完成 — same semantics as the original ActionBar menu item. */
    fun toggleManageMode() {
        manageMode = !manageMode
        if (!manageMode) persistPinOrder()
        manageButton.text = managerLabel()
        onManageModeChanged?.invoke(manageMode)
        rebuild()
    }

    /** ActionBar item title (Activity host) and panel bar button share one wording. */
    fun managerLabel(): String = context.resources.getString(
        if (manageMode) R.string.action_app_manager_complate else R.string.action_app_manager,
    )

    /**
     * One-line summary of what this page currently shows, for the module log —
     * makes it possible to verify the panel content from `adb` without a screenshot
     * (e.g. `MORE_PANEL_TREE texts=[选择快捷启动的应用, 管理, ✕, 应用, 已添加…] items=214`).
     */
    fun describe(): String {
        val texts = ArrayList<String>()
        fun walk(v: View) {
            if (texts.size >= 8) return
            if (v is TextView) {
                val t = v.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) texts.add(t.take(14))
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return "texts=" + texts + " items=" + (adapter?.itemCount ?: 0) +
            " letters=" + bar.letters.size + " manage=" + manageMode
    }

    /** Detach from the (foreign, long-lived) host process. */
    fun release() {
        scope.cancel()
        list.adapter = null
        list.clearOnScrollListeners()
    }

    // ---------------- internals (moved verbatim from the Activity) ----------------

    private fun header(): View {
        val onSurface = context.resources.getColor(R.color.fd_sys_color_on_surface_default, null)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ActionBar geometry (56dp bar / 16dp inset) so the overlay page matches the
            // full-screen page: title on the left, 管理/完成 as the menu item, ✕ to close.
            setPadding(dp(16f), 0, dp(8f), 0)
            addView(
                TextView(context).apply {
                    text = context.resources.getString(R.string.slide_launch_app_settings_title)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(onSurface)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(manageButton)
            onClose?.let { close ->
                addView(
                    TextView(context).apply {
                        text = "✕"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(onSurface)
                        gravity = Gravity.CENTER
                        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                        isClickable = true
                        setOnClickListener { close() }
                    },
                )
            }
        }
    }

    private fun actionLabel(): TextView {
        val onSurface = context.resources.getColor(R.color.fd_sys_color_on_surface_default, null)
        return TextView(context).apply {
            text = context.resources.getString(R.string.action_app_manager)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(onSurface)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            isClickable = true
            setOnClickListener { toggleManageMode() }
        }
    }

    /** Persist ★ order back into the config string (AppDragLayout onMove → settings save). */
    private fun persistPinOrder() {
        val a = adapter ?: return
        val order = a.pinOrder().map { PinnedRef(it.packageName, it.userId) }
        pinStore.setPins(order)
    }

    private fun rebuild() {
        val pins = pinStore.pins()
        val cells = Sections.build(apps, pins, recommend = true).toMutableList()
        val adapter = PinGridAdapter(
            cells = cells,
            manageMode = manageMode,
            iconOf = repo::icon,
            onClick = { app -> if (manageMode) togglePin(app) else onLaunch(app) },
            onPinDragStart = { vh -> touchHelper.startDrag(vh) },
            iconDp = iconDp,
            textSp = textSp,
        )
        this.adapter = adapter
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                adapter.spanAt(position.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0)))
        }
        list.adapter = adapter
        // ★ is the top marker in the original bar (setCurrentLetter("★"), :326);
        // group labels follow in display order
        bar.letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
    }

    private fun togglePin(app: BubbleApp) {
        // ORIGINAL: no cap at the manage page — any number can be added; the fan
        // shows the first 6 + 更多 (m9235C :453-457). SlideLaunchAppSettings source
        // contains no size>=6 check (grepped).
        pinStore.toggle(app)
        rebuild()
    }

    private fun scrollToLetter(letter: String) {
        val a = adapter ?: return
        val pos = if (letter == "★") 0 // ★ area sits at the very top (head + pins)
        else a.cells.indexOfFirst { (it as? Cell.Label)?.text == letter }
        if (pos >= 0) list.scrollToPosition(pos)
    }
}
