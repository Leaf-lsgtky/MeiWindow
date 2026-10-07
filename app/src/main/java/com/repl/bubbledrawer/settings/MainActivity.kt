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
            max = 160 - 24
            progress = SettingsStore.snapshot(this@MainActivity).rangeDp - 24
            onSeek { set ->
                SettingsStore.setRangeDp(this@MainActivity, set + 24)
                refreshState()
            }
        })

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

    private fun SeekBar.onSeek(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = block(sb?.progress ?: 0)
        })
    }
}
