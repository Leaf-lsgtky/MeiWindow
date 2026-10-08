package com.repl.bubbledrawer.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.pin.PinManageActivity
import com.repl.bubbledrawer.xposed.RemoteBridge
import com.repl.bubbledrawer.xposed.SettingsStore
import android.content.Intent

/**
 * Settings hub — now the front-end of the LSPosed MODULE: the fan and corner
 * capture run inside SystemUI (xposed/CornerInputMonitor + xposed/FanHost),
 * configured live through RemotePrefs.GROUP (LSPosed mirrors that file into the
 * hooked process; changes apply without restart).
 */
class MainActivity : AppCompatActivity() {

    private var suppressListener = false
    private var switchView: CheckBox? = null
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val snap = SettingsStore.snapshot(this)
        statusView?.text = getString(
            R.string.module_status,
            if (RemoteBridge.connected) {
                getString(R.string.module_connected, RemoteBridge.frameworkLabel ?: "?")
            } else {
                getString(R.string.module_disconnected)
            },
        )
        val cb = switchView
        suppressListener = true
        cb?.isChecked = snap.enabled
        suppressListener = false
    }

    private fun buildUi(): View {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        fun add(v: View) = root.addView(
            v,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val cb = CheckBox(this).apply {
            setText(R.string.enable_service)
            setOnCheckedChangeListener { _, checked ->
                if (!suppressListener) SettingsStore.setEnabled(this@MainActivity, checked)
                refreshState()
            }
        }
        switchView = cb
        add(cb)

        add(TextView(this).apply { statusView = this; textSize = 12f })

        add(TextView(this).apply { setText(R.string.module_howto) })

        add(Button(this).apply {
            text = getString(R.string.trigger_pos) + ": " + posLabel()
            setOnClickListener { pickTriggerPos() }
        })

        add(TextView(this).apply { setText(R.string.range_title) })
        add(SeekBar(this).apply {
            max = MAX_DP - MIN_DP
            progress = SettingsStore.snapshot(this@MainActivity).rangeDp - MIN_DP
            onSeek { set ->
                SettingsStore.setRangeDp(this@MainActivity, set + MIN_DP)
                refreshState()
            }
        })

        // Trigger REGION (not only its size): the corner is a quarter-ellipse and these two
        // sliders are its axes — how far it reaches along the bottom edge and up the side
        // edge. Both default to the radius above.
        val snap = SettingsStore.snapshot(this)
        add(TextView(this).apply {
            setText(getString(R.string.range_bottom) + "：" + snap.bottomDp + "dp")
        })
        add(SeekBar(this).apply {
            max = MAX_DP - MIN_DP
            progress = snap.bottomDp - MIN_DP
            onSeek { set ->
                SettingsStore.setBottomDp(this@MainActivity, set + MIN_DP)
                recreate()
            }
        })
        add(TextView(this).apply {
            setText(getString(R.string.range_edge) + "：" + snap.edgeDp + "dp")
        })
        add(SeekBar(this).apply {
            max = MAX_DP - MIN_DP
            progress = snap.edgeDp - MIN_DP
            onSeek { set ->
                SettingsStore.setEdgeDp(this@MainActivity, set + MIN_DP)
                recreate()
            }
        })
        add(TextView(this).apply { setText(R.string.range_hint); textSize = 12f })

        // freeform ("小窗启动") toggle — where the device supports desktop/freeform
        // windows the app floats over the current page; otherwise fullscreen
        add(CheckBox(this).apply {
            setText(R.string.freeform_toggle)
            isChecked = SettingsStore.snapshot(this@MainActivity).freeform
            setOnCheckedChangeListener { _, checked ->
                SettingsStore.setFreeform(this@MainActivity, checked)
                Toast.makeText(this@MainActivity, R.string.freeform_hint, Toast.LENGTH_LONG).show()
            }
        })

        // 更多面板（方案 B overlay）外观：长 / 宽 / 图标 / 文字。面板始终居中，
        // 长宽是"占屏幕的百分比"（0 = 默认 62 %），不是相对当前小窗的缩放。
        add(TextView(this).apply { setText(R.string.panel_section_title) })
        add(TextView(this).apply {
            setText(getString(R.string.panel_width) + "：" + pctLabel(snap.panelWidthPct))
        })
        add(SeekBar(this).apply {
            max = PANEL_PCT_MAX - PANEL_PCT_MIN + 1
            progress = if (snap.panelWidthPct == 0) 0 else snap.panelWidthPct - PANEL_PCT_MIN + 1
            onSeek { set ->
                SettingsStore.setPanelWidthPct(this@MainActivity, if (set == 0) 0 else set + PANEL_PCT_MIN - 1)
                recreate()
            }
        })
        add(TextView(this).apply {
            setText(getString(R.string.panel_height) + "：" + pctLabel(snap.panelHeightPct))
        })
        add(SeekBar(this).apply {
            max = PANEL_PCT_MAX - PANEL_PCT_MIN + 1
            progress = if (snap.panelHeightPct == 0) 0 else snap.panelHeightPct - PANEL_PCT_MIN + 1
            onSeek { set ->
                SettingsStore.setPanelHeightPct(this@MainActivity, if (set == 0) 0 else set + PANEL_PCT_MIN - 1)
                recreate()
            }
        })
        add(TextView(this).apply {
            setText(getString(R.string.panel_icon) + "：" + sizeLabel(snap.panelIconDp, "dp"))
        })
        add(SeekBar(this).apply {
            max = PANEL_ICON_MAX - PANEL_ICON_MIN + 1
            progress = if (snap.panelIconDp == 0) 0 else snap.panelIconDp - PANEL_ICON_MIN + 1
            onSeek { set ->
                SettingsStore.setPanelIconDp(this@MainActivity, if (set == 0) 0 else set + PANEL_ICON_MIN - 1)
                recreate()
            }
        })
        add(TextView(this).apply {
            setText(getString(R.string.panel_text) + "：" + sizeLabel(snap.panelTextSp, "sp"))
        })
        add(SeekBar(this).apply {
            max = PANEL_TEXT_MAX - PANEL_TEXT_MIN + 1
            progress = if (snap.panelTextSp == 0) 0 else snap.panelTextSp - PANEL_TEXT_MIN + 1
            onSeek { set ->
                SettingsStore.setPanelTextSp(this@MainActivity, if (set == 0) 0 else set + PANEL_TEXT_MIN - 1)
                recreate()
            }
        })
        add(Button(this).apply {
            text = getString(R.string.open_manage)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PinManageActivity::class.java))
            }
        })
        refreshState()
        return root
    }

    private fun posLabel() = arrayOf(
        getString(R.string.pos_both), getString(R.string.pos_left), getString(R.string.pos_right),
    )[SettingsStore.triggerPos(this).coerceIn(0, 2)]

    private fun pickTriggerPos() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                getString(R.string.pos_both), getString(R.string.pos_left), getString(R.string.pos_right),
            )) { _, which ->
                SettingsStore.setTriggerPos(this, which)
                recreate()
            }.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pctLabel(v: Int) = if (v == 0) getString(R.string.panel_pct_default) else "$v%"

    private fun sizeLabel(v: Int, unit: String) =
        if (v == 0) getString(R.string.panel_default) else "$v$unit"

    private companion object {
        /** Slider bounds, shared with RemotePrefs.read so the UI cannot drift from the reader. */
        const val MIN_DP = com.repl.bubbledrawer.xposed.RemotePrefs.MIN_DP
        const val MAX_DP = com.repl.bubbledrawer.xposed.RemotePrefs.MAX_DP
        const val PANEL_PCT_MIN = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_PCT_MIN
        const val PANEL_PCT_MAX = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_PCT_MAX
        const val PANEL_ICON_MIN = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_ICON_MIN
        const val PANEL_ICON_MAX = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_ICON_MAX
        const val PANEL_TEXT_MIN = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_TEXT_MIN
        const val PANEL_TEXT_MAX = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_TEXT_MAX
    }

    private fun SeekBar.onSeek(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = block(sb?.progress ?: 0)
        })
    }
}
