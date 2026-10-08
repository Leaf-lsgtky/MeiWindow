package com.repl.bubbledrawer.pin

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.repl.bubbledrawer.AppGraph
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.launch.ConfigurableLaunchStrategy

/**
 * Port of `SlideLaunchAppSettings.java` — the 应用 tab slice (功能 tab phase 2):
 *  - title "选择快捷启动的应用" (:74-77 string title, the ActionBar title)
 *  - tab strip 应用 (slide_launcher_tab_all_app, minHeight 54dp :802-805)
 *  - single 4-col grid: ★(≤6, drag-sort) → 推荐 → A..Z → # (Sections)
 *  - manage-mode toggle via the ActionBar item 管理/完成 (action_app_manager/_complate)
 *  - LetterIndexBar (★/A–Z/#) scrolls to labels; scroll syncs currentLetter (:326)
 *
 * Thin host: the page body lives in [PinManageView] so the SAME page also serves the
 * fan's 更多 overlay inside SystemUI (方案 B). Here the page is hosted with
 * [PinManageView.Chrome.ACTIVITY] — no header of its own; the ActionBar keeps the
 * original title + 管理/完成 menu (the overlay draws a look-alike bar itself).
 */
class PinManageActivity : AppCompatActivity() {

    private lateinit var content: PinManageView

    private val repo by lazy { AppGraph.repo ?: AppRepository(this).also { AppGraph.repo = it } }
    private val pinStore by lazy {
        AppGraph.pinStore ?: PinStore(
            com.repl.bubbledrawer.xposed.SettingsStore.pinBackend(this),
        ).also { AppGraph.pinStore = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = PinManageView(
            context = this,
            repo = repo,
            pinStore = pinStore,
            onLaunch = { app ->
                // ORIGINAL: view-mode tap launches the app (AbstractC2806s.m9105e carries
                // start_windowmode=true, so flyme floats it over the still-visible page)
                ConfigurableLaunchStrategy(this).launch(this, app)
                com.repl.bubbledrawer.data.LaunchCountStore.increment(this, app.packageName)
            },
            chrome = PinManageView.Chrome.ACTIVITY,
            onManageModeChanged = { invalidateOptionsMenu() },
        )
        setContentView(content.view)
        content.reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_title_manager, menu)
        updateMenuTitle(menu)
        return true
    }

    private fun updateMenuTitle(menu: Menu) {
        menu.findItem(R.id.action_manager)?.setTitle(content.managerLabel())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish(); true
        }
        R.id.action_manager -> {
            content.toggleManageMode()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        content.reload() // list may have changed
    }

    override fun onDestroy() {
        content.release()
        super.onDestroy()
    }
}
