package com.repl.bubbledrawer.pin

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.repl.bubbledrawer.AppGraph
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Port of `SlideLaunchAppSettings.java` — the 应用 tab slice (功能 tab phase 2):
 *  - title "选择快捷启动的应用" (:74-77 string title)
 *  - tab strip 应用 (slide_launcher_tab_all_app 0x7f1303f2, minHeight 54dp :802-805)
 *  - single 4-col grid: ★(≤6, AppDragLayout drag-sort) → 推荐 → A..Z → # (Sections)
 *  - manage-mode toggle via menu 管理/完成 (action_app_manager/_complate) drives
 *    C2995f badge visibility (:168-173)
 *  - LetterIndexBar (★/A–Z/#) scrolls to labels; scroll syncs currentLetter (:326)
 */
class PinManageActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var bar: LetterIndexBar
    private lateinit var lm: GridLayoutManager
    private val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private val repo by lazy { AppGraph.repo ?: AppRepository(this).also { AppGraph.repo = it } }
    private val pinStore by lazy {
        AppGraph.pinStore ?: PinStore(
            PrefsPinBackend(getSharedPreferences("pins", MODE_PRIVATE)),
        ).also { AppGraph.pinStore = it }
    }

    private var apps: List<BubbleApp> = emptyList()
    private var manageMode = false // open in view mode; 管理 enables edits (menu semantics)
    private var adapter: PinGridAdapter? = null
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_manage)
        title = getString(R.string.slide_launch_app_settings_title)

        findViewById<com.google.android.material.tabs.TabLayout>(R.id.tab_container)
            .addTab(
                findViewById<com.google.android.material.tabs.TabLayout>(R.id.tab_container)
                    .newTab().setText(getString(R.string.slide_launcher_tab_all_app)),
            )

        list = findViewById(R.id.app_list)
        bar = findViewById(R.id.letter_bar)
        lm = GridLayoutManager(this, 4) // f10411g0 = 4 (SlideLaunchAppSettings:87)
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

        scope.launch {
            apps = repo.cachedAll().ifEmpty { repo.loadAll() }
            rebuild()
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
            onClick = { app -> togglePin(app) },
            onPinDragStart = { vh -> touchHelper.startDrag(vh) },
        )
        this.adapter = adapter
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = adapter.spanAt(position.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0)))
        }
        list.adapter = adapter
        // ★ is the top marker in the original bar (setCurrentLetter("★"), :326);
        // group labels follow in display order
        bar.letters = (listOf("★") + Sections.labelsOf(cells)).distinct()
    }

    private fun togglePin(app: BubbleApp) {
        val pins = pinStore.pins().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        if (!pins.remove(ref)) {
            if (pins.size >= PinCodec.MAX_PINS) {
                Toast.makeText(this, getString(R.string.max_pin_tips, PinCodec.MAX_PINS), Toast.LENGTH_SHORT).show()
                return
            }
            pins.add(ref)
        }
        pinStore.setPins(pins)
        rebuild()
    }

    private fun scrollToLetter(letter: String) {
        val a = adapter ?: return
        val pos = if (letter == "★") 0 // ★ area sits at the very top (head + pins)
        else a.cells.indexOfFirst { (it as? Cell.Label)?.text == letter }
        if (pos >= 0) list.scrollToPosition(pos)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_title_manager, menu)
        updateMenuTitle(menu)
        return true
    }

    private fun updateMenuTitle(menu: Menu) {
        menu.findItem(R.id.action_manager)?.setTitle(
            if (manageMode) R.string.action_app_manager_complate else R.string.action_app_manager,
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_manager -> {
            manageMode = !manageMode
            if (!manageMode) persistPinOrder()
            invalidateOptionsMenu()
            rebuild()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        scope.launch { apps = repo.loadAll(); rebuild() } // list may have changed
    }
}
