package com.repl.bubbledrawer.pin

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.pinyin.BubbleApp
import de.hdodenhof.circleimageview.CircleImageView

/**
 * Single 4-column grid for the pin manager (AppDragLayout GridLayoutManager(4)
 * idiom :185 + SpanSizeLookup for full-span rows, as LightWeightOpenSettings
 * does at C2837c :193-198). Cell rendering follows C2995f (:137-175):
 *  - badge: pinned → remove icon, else add icon (:143)
 *  - manage mode: badge VISIBLE, click toggles, long-press on Pin starts drag (:168-173)
 *  - read mode: badge INVISIBLE(4), no long-press
 */
class PinGridAdapter(
    val cells: MutableList<Cell>,
    private val manageMode: Boolean,
    private val iconOf: (BubbleApp) -> android.graphics.drawable.Drawable?,
    private val onClick: (BubbleApp) -> Unit,
    private val onPinDragStart: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_PIN_HEAD = 0
        const val TYPE_EMPTY = 1
        const val TYPE_LABEL = 2
        const val TYPE_PIN = 3
        const val TYPE_APP = 4
    }

    /** span 4 for head/label/empty rows, 1 for apps (set on GridLayoutManager). */
    fun spanAt(position: Int) = when (cells[position]) {
        is Cell.PinHead, is Cell.Label, is Cell.EmptyHint -> 4
        else -> 1
    }

    fun pinRange(): IntRange {
        var first = -1; var last = -1
        cells.forEachIndexed { i, c ->
            if (c is Cell.Pin) { if (first < 0) first = i; last = i }
        }
        return if (first < 0) 0 until 0 else first..last
    }

    /** ★ drag reorder: move the Pin at `from` to `to` (both Pin positions). */
    fun movePin(from: Int, to: Int) {
        if (from == to || from !in cells.indices || to !in cells.indices) return
        if (cells[from] !is Cell.Pin || cells[to] !is Cell.Pin) return
        cells.add(to, cells.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun pinOrder(): List<BubbleApp> = cells.filterIsInstance<Cell.Pin>().map { it.app }

    override fun getItemCount() = cells.size

    override fun getItemViewType(position: Int) = when (cells[position]) {
        is Cell.PinHead -> TYPE_PIN_HEAD
        is Cell.EmptyHint -> TYPE_EMPTY
        is Cell.Label -> TYPE_LABEL
        is Cell.Pin -> TYPE_PIN
        is Cell.App -> TYPE_APP
    }

    class PinHeadVH(v: View) : RecyclerView.ViewHolder(v)
    class EmptyVH(v: View) : RecyclerView.ViewHolder(v)
    class LabelVH(v: View) : RecyclerView.ViewHolder(v) {
        val category: TextView = v.findViewById(R.id.app_category)
    }

    class CellVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: CircleImageView = v.findViewById(R.id.app_icon)
        val title: TextView = v.findViewById(R.id.title)
        val badge: ImageView = v.findViewById(R.id.operate_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PIN_HEAD -> PinHeadVH(inflater.inflate(R.layout.app_settings_item_head, parent, false))
            TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.app_settings_item_selected, parent, false))
            TYPE_LABEL -> LabelVH(inflater.inflate(R.layout.app_settings_item_all_header, parent, false))
            else -> CellVH(inflater.inflate(R.layout.launcher_app_item, parent, false))
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val cell = cells[position]) {
            is Cell.PinHead -> Unit // static texts baked into the xml head row
            is Cell.EmptyHint -> Unit // 暂无已添加应用 baked into app_settings_item_selected.xml
            is Cell.Label -> (holder as LabelVH).category.text = cell.text
            is Cell.Pin -> bindApp(holder as CellVH, cell.app, pinned = true)
            is Cell.App -> bindApp(holder as CellVH, cell.app, pinned = false)
        }
    }

    private fun bindApp(holder: CellVH, app: BubbleApp, pinned: Boolean) {
        holder.icon.setImageDrawable(iconOf(app))
        holder.title.text = app.label
        holder.badge.setImageResource(
            if (pinned) R.drawable.app_launcher_item_remove_icon
            else R.drawable.app_launcher_item_add_icon,
        )
        holder.badge.visibility = if (manageMode) View.VISIBLE else View.INVISIBLE
        // C3010u.m9935g: manage mode → tap toggles pin; view mode → tap launches the app
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            if (manageMode && pinned) { onPinDragStart(holder); true } else false
        }
    }
}
