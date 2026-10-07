package com.repl.bubbledrawer.more

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.repl.bubbledrawer.AppGraph
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.launch.FullscreenLaunchStrategy
import com.repl.bubbledrawer.pin.PinManageActivity
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.coroutines.launch
import de.hdodenhof.circleimageview.CircleImageView

/**
 * Port of `windowmode/views/MoreAppWindow.java` (259 lines).
 *  - title: @string/gesture_more_app_window_title "更多应用" (:onCreate 193 → layout slide_gesture_more_app_window)
 *  - grid: GridLayoutManager(this, 4), portrait (:175-177)
 *  - data: m8870J().subList(6, end) + trailing add-tile while size<999 (:135-149)
 *  - item: launcher_more_app_item (48dp icon + 12sp title, no border)
 *  - click → launch app (:121-125, mode 11 → we fullscreen) + RxBus refresh
 *  - menu action_manager_app "管理" → SlideLaunchAppSettings (:153-158, 216-227)
 *  - add-tile → same settings page (:142-145 + m9495s)
 */
class MoreAppsActivity : AppCompatActivity() {

    private val repo by lazy { AppGraph.repo ?: AppRepository(this).also { AppGraph.repo = it } }
    private val pinStore by lazy {
        AppGraph.pinStore ?: com.repl.bubbledrawer.data.PinStore(
            com.repl.bubbledrawer.data.PrefsPinBackend(getSharedPreferences("pins", MODE_PRIVATE)),
        ).also { AppGraph.pinStore = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.slide_gesture_more_app_window)
        val list = findViewById<RecyclerView>(R.id.slide_more_list)
        val lm = GridLayoutManager(this, 4) // :175
        lm.orientation = RecyclerView.VERTICAL // :176
        list.layoutManager = lm
    }

    override fun onResume() {
        super.onResume()
        val list = findViewById<RecyclerView>(R.id.slide_more_list)
        val cells = buildCells()
        list.adapter = MoreAppAdapter(cells, repo::icon, ::onCellClick, ::onAddClick) // :235-249
        if (cells.isEmpty() && repo.cachedAll().isEmpty()) {
            // cold start without the service having warmed the list yet
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                .launch {
                    repo.loadAll()
                    list.adapter = MoreAppAdapter(buildCells(), repo::icon, ::onCellClick, ::onAddClick)
                }
        }
    }

    /** m9494r (:135-149) */
    private fun buildCells(): List<BubbleApp> {
        val all = repo.cachedAll()
        return if (all.size > 6) all.subList(6, all.size) else emptyList()
    }

    private fun onCellClick(app: BubbleApp) {
        FullscreenLaunchStrategy().launch(this, app)   // :125 (start_windowmode 11 → 全屏占位)
        LaunchCountStore.increment(this, app.packageName)
    }

    private fun onAddClick() {
        // :142-145 add-tile + m9495s (:153-158)
        startActivity(Intent(this, PinManageActivity::class.java))
    }

    /** :201-207, 216-227 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_more_app_window, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressed(); true }        // :218-220
            R.id.action_manager_app -> {                            // :222-226 m9495s
                startActivity(Intent(this, PinManageActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

private class MoreAppAdapter(
    private val apps: List<BubbleApp>,
    private val iconOf: (BubbleApp) -> android.graphics.drawable.Drawable?,
    private val onClick: (BubbleApp) -> Unit,
    private val onAdd: () -> Unit,
) : RecyclerView.Adapter<MoreAppAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val icon: CircleImageView = view.findViewById(R.id.app_icon)
        val title: TextView = view.findViewById(R.id.title)
    }

    override fun getItemCount() = apps.size + 1 // trailing add tile

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.launcher_more_app_item, parent, false)
        v.layoutParams = GridLayoutManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ) // :81 (-2,-2)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position < apps.size) {
            val app = apps[position]
            holder.icon.setImageDrawable(iconOf(app))
            holder.title.text = app.label
            holder.itemView.setOnClickListener { onClick(app) }
        } else {
            holder.icon.setImageResource(R.drawable.icon_more_window_add_app)
            holder.title.setText(R.string.more_app_window_add_app)
            holder.itemView.setOnClickListener { onAdd() }
        }
    }

    companion object
}
