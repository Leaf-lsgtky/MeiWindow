package com.repl.bubbledrawer.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.repl.bubbledrawer.AppGraph
import com.repl.bubbledrawer.LauncherService
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.BubbleConfig
import com.repl.bubbledrawer.pin.PinManageActivity
import com.repl.bubbledrawer.root.EdgeModeHelper

/**
 * Hub activity (original counterpart: WindowModeSettings, a separate system app —
 * inlined here): enable switch, overlay-permission guidance, trigger-zone tuning,
 * trigger position, edge (root) mode, preview, manage entry.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: BubbleConfig
    private var suppressListener = false
    private var switchView: CheckBox? = null

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshState() }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = BubbleConfig(this)
        setContentView(buildUi())
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun isRunning() = AppGraph.serviceRunning

    private fun refreshState() {
        val cb = switchView
        suppressListener = true
        cb?.isChecked = isRunning()
        cb?.text = if (Settings.canDrawOverlays(this)) getString(R.string.enable_service)
        else getString(R.string.perm_overlay_missing)
        suppressListener = false
    }

    private fun setRunning(on: Boolean) {
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.perm_overlay_missing, Toast.LENGTH_LONG).show()
            refreshState()
            return
        }
        if (on) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            LauncherService.start(this)
        } else {
            LauncherService.stop(this)
            AppGraph.serviceRunning = false
        }
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
                if (!suppressListener) setRunning(checked)
            }
        }
        switchView = cb
        add(cb)

        add(Button(this).apply {
            text = getString(R.string.grant_overlay)
            setOnClickListener {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        })

        add(TextView(this).apply { setText(R.string.zone_title) })
        add(SeekBar(this).apply {
            max = 80; progress = config.insetDp.toInt()
            onSeek { set -> config.insetDp = set.toFloat(); restartIfRunning() }
        })
        add(SeekBar(this).apply {
            max = 240; progress = config.widthDp.toInt()
            onSeek { set -> config.widthDp = set.toFloat(); restartIfRunning() }
        })
        add(SeekBar(this).apply {
            max = 400; progress = config.heightDp.toInt()
            onSeek { set -> config.heightDp = set.toFloat(); restartIfRunning() }
        })

        add(Button(this).apply {
            text = getString(R.string.trigger_pos) + ": " + posLabel()
            setOnClickListener { pickTriggerPos() }
        })

        add(Button(this).apply {
            text = getString(R.string.edge_mode) + ": " + if (config.edgeMode)
                getString(R.string.edge_on) else getString(R.string.edge_off)
            setOnClickListener { toggleEdgeMode() }
        })

        add(Button(this).apply {
            text = getString(R.string.preview)
            setOnClickListener { preview() }
        })

        add(Button(this).apply {
            text = getString(R.string.open_manage)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PinManageActivity::class.java))
            }
        })
        return root
    }

    private fun posLabel() = arrayOf(
        getString(R.string.pos_both), getString(R.string.pos_left),
        getString(R.string.pos_right), getString(R.string.pos_side),
    )[config.triggerPos.coerceIn(0, 3)]

    private fun pickTriggerPos() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                getString(R.string.pos_both), getString(R.string.pos_left),
                getString(R.string.pos_right), getString(R.string.pos_side),
            )) { _, which ->
                config.triggerPos = which
                restartIfRunning()
                recreate()
            }.show()
    }

    private fun toggleEdgeMode() {
        val target = !config.edgeMode
        val result = EdgeModeHelper.apply(target) { cmds ->
            try {
                val p = ProcessBuilder(cmds).redirectErrorStream(true).start()
                p.waitFor()
            } catch (_: Exception) {
                null
            }
        }
        if (result == EdgeModeHelper.SuResult.AVAILABLE) {
            config.edgeMode = target
            restartIfRunning()
        } else {
            Toast.makeText(this, R.string.edge_needs_root, Toast.LENGTH_LONG).show()
        }
    }

    /** Expand the fan immediately (same path a gesture triggers), for tuning. */
    private fun preview() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.perm_overlay_missing, Toast.LENGTH_LONG).show()
            return
        }
        AppGraph.previewRequested = true
        LauncherService.start(this)
    }

    private fun restartIfRunning() {
        if (isRunning()) {
            LauncherService.stop(this)
            LauncherService.start(this)
        }
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
