package com.repl.bubbledrawer.ui.screen.features

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.bubble.FanLayout
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.settings.DefaultableIntSliderRow
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.RemotePrefsFanAutoRetractMax
import com.repl.bubbledrawer.settings.RemotePrefsFanAutoRetractMin
import com.repl.bubbledrawer.settings.RemotePrefsFanIconMax
import com.repl.bubbledrawer.settings.RemotePrefsFanIconMin
import com.repl.bubbledrawer.settings.RemotePrefsFanRadiusMax
import com.repl.bubbledrawer.settings.RemotePrefsFanRadiusMin
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import com.repl.bubbledrawer.xposed.RemotePrefs
import com.repl.bubbledrawer.xposed.SettingsStore
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class FanDockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = StoreSettingsActions(applicationContext)
        val stateFlow = SettingsStore.observe(applicationContext)

        val appSettings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(appSettings.themeMode) }.getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = SettingsStore.currentState(applicationContext),
                )
                FanDockPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun FanDockPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val snap = state.snapshot
    val isDoubleLayer = snap.fanLayers == RemotePrefs.FAN_LAYERS_DOUBLE

    PageScaffold(
        title = stringResource(R.string.features_fan_title),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        // --- 布局与层级 ---
        groupedCardItems(
            keyPrefix = "fan_layout",
            items = buildList {
                add(CardItem("layers") {
                    DropdownRow(
                        title = stringResource(R.string.fan_layers),
                        summary = stringResource(R.string.fan_layers_summary),
                        items = listOf(
                            stringResource(R.string.fan_layers_single),
                            stringResource(R.string.fan_layers_double),
                        ),
                        selectedIndex = if (snap.fanLayers == RemotePrefs.FAN_LAYERS_DOUBLE) 1 else 0,
                        onSelect = {
                            actions.setFanLayers(
                                if (it == 1) RemotePrefs.FAN_LAYERS_DOUBLE else RemotePrefs.FAN_LAYERS_SINGLE,
                            )
                        },
                    )
                })
                add(CardItem("icon_count") {
                    DropdownRow(
                        title = stringResource(R.string.fan_icon_count),
                        summary = if (isDoubleLayer) {
                            stringResource(
                                R.string.fan_icon_count_summary_double,
                                snap.fanIconCount,
                                FanLayout.outerApps(snap.fanIconCount, snap.fanLayers),
                            )
                        } else {
                            null
                        },
                        items = listOf(
                            stringResource(R.string.fan_icon_count_5),
                            stringResource(R.string.fan_icon_count_6),
                        ),
                        selectedIndex = if (snap.fanIconCount == 5) 0 else 1,
                        onSelect = { actions.setFanIconCount(if (it == 0) 5 else 6) },
                    )
                })
            },
        )

        // --- 展开半径 ---
        groupedCardItems(
            keyPrefix = "fan_radius_group",
            items = buildList {
                add(CardItem("radius") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.fan_radius),
                        summary = if (isDoubleLayer) {
                            stringResource(R.string.fan_radius_summary_double)
                        } else {
                            null
                        },
                        value = snap.fanRadiusDp,
                        defaultValue = RemotePrefs.DEFAULT_FAN_RADIUS_DP,
                        min = RemotePrefsFanRadiusMin,
                        max = RemotePrefsFanRadiusMax,
                        valueText = { stringResource(R.string.dp_value, it) },
                        onCommit = actions::setFanRadiusDp,
                    )
                })
                add(CardItem("outer_radius_enabled") {
                    SwitchRow(
                        title = stringResource(R.string.fan_outer_radius_enabled),
                        summary = stringResource(R.string.fan_outer_radius_enabled_summary),
                        checked = snap.fanOuterRadiusEnabled,
                        enabled = isDoubleLayer,
                        onCheckedChange = actions::setFanOuterRadiusEnabled,
                    )
                })
                add(CardItem("outer_radius") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.fan_outer_radius),
                        summary = stringResource(R.string.fan_outer_radius_summary),
                        value = snap.fanOuterRadiusDp,
                        defaultValue = RemotePrefs.DEFAULT_FAN_OUTER_RADIUS_DP,
                        min = maxOf(RemotePrefsFanRadiusMin, snap.fanRadiusDp),
                        max = RemotePrefsFanRadiusMax,
                        enabled = isDoubleLayer && snap.fanOuterRadiusEnabled,
                        valueText = { stringResource(R.string.dp_value, it) },
                        onCommit = actions::setFanOuterRadiusDp,
                    )
                })
            },
        )

        // --- 图标 ---
        item(key = "fan_icon_title") {
            SmallTitle(text = stringResource(R.string.fan_icon_section_title))
        }
        groupedCardItems(
            keyPrefix = "fan_icons",
            items = buildList {
                add(CardItem("icon_size") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.fan_icon_size),
                        summary = stringResource(R.string.fan_icon_size_summary),
                        value = snap.fanIconDp,
                        defaultValue = RemotePrefs.DEFAULT_FAN_ICON_DP,
                        min = RemotePrefsFanIconMin,
                        max = RemotePrefsFanIconMax,
                        valueText = { stringResource(R.string.dp_value, it) },
                        onCommit = actions::setFanIconDp,
                    )
                })
                add(CardItem("icon_shape") {
                    DropdownRow(
                        title = stringResource(R.string.fan_icon_shape),
                        summary = stringResource(R.string.fan_icon_shape_summary),
                        items = listOf(
                            stringResource(R.string.fan_icon_shape_circle),
                            stringResource(R.string.fan_icon_shape_rounded),
                        ),
                        selectedIndex = snap.fanIconShape.coerceIn(0, 1),
                        onSelect = actions::setFanIconShape,
                    )
                })
                add(CardItem("clone_badge") {
                    SwitchRow(
                        title = stringResource(R.string.fan_clone_badge),
                        summary = stringResource(R.string.fan_clone_badge_summary),
                        checked = snap.cloneBadge,
                        onCheckedChange = actions::setCloneBadge,
                    )
                })
                add(CardItem("auto_fill") {
                    SwitchRow(
                        title = stringResource(R.string.fan_auto_fill_recommend),
                        summary = stringResource(R.string.fan_auto_fill_recommend_summary),
                        checked = snap.fanAutoFillRecommend,
                        onCheckedChange = actions::setFanAutoFillRecommend,
                    )
                })
                add(CardItem("show_vibrate") {
                    SwitchRow(
                        title = stringResource(R.string.fan_show_vibrate),
                        summary = stringResource(R.string.fan_show_vibrate_summary),
                        checked = snap.fanShowVibrate,
                        onCheckedChange = actions::setFanShowVibrate,
                    )
                })
                add(CardItem("auto_retract") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.fan_auto_retract),
                        summary = stringResource(R.string.fan_auto_retract_summary),
                        value = snap.fanAutoRetractSec,
                        defaultValue = RemotePrefs.DEFAULT_FAN_AUTO_RETRACT_SEC,
                        min = RemotePrefsFanAutoRetractMin,
                        max = RemotePrefsFanAutoRetractMax,
                        valueText = {
                            if (it <= 0) stringResource(R.string.fan_auto_retract_release)
                            else stringResource(R.string.sec_value, it)
                        },
                        onCommit = actions::setFanAutoRetractSec,
                    )
                })
                add(CardItem("double_tap_dismiss") {
                    SwitchRow(
                        title = stringResource(R.string.fan_double_tap_dismiss),
                        summary = stringResource(R.string.fan_double_tap_dismiss_summary),
                        checked = snap.fanDoubleTapDismiss,
                        onCheckedChange = actions::setFanDoubleTapDismiss,
                    )
                })
            },
        )

        // --- 气压重按翻页 ---
        item(key = "fan_pressure_title") {
            SmallTitle(text = stringResource(R.string.fan_pressure_page_turn))
        }
        groupedCardItems(
            keyPrefix = "fan_pressure",
            items = buildList {
                add(CardItem("pressure_page_turn") {
                    SwitchRow(
                        title = stringResource(R.string.fan_pressure_page_turn),
                        summary = stringResource(R.string.fan_pressure_page_turn_summary),
                        checked = snap.fanPressurePageTurn,
                        onCheckedChange = actions::setFanPressurePageTurn,
                    )
                })
                add(CardItem("pressure_sensitivity") {
                    DropdownRow(
                        title = stringResource(R.string.fan_pressure_sensitivity),
                        summary = if (snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
                            stringResource(R.string.fan_pressure_sensitivity_summary_calibrated, snap.fanPressureCalibratedHpa)
                        } else {
                            null
                        },
                        items = listOf(
                            stringResource(R.string.fan_pressure_sensitivity_standard),
                            stringResource(R.string.fan_pressure_sensitivity_sensitive),
                            stringResource(R.string.fan_pressure_sensitivity_heavy),
                        ),
                        selectedIndex = snap.fanPressureSensitivity.coerceIn(0, 2),
                        enabled = snap.fanPressurePageTurn && !snap.fanPressureCalibrated,
                        onSelect = actions::setFanPressureSensitivity,
                    )
                })
                add(CardItem("pressure_calibrate") {
                    ArrowRow(
                        title = stringResource(
                            when {
                                snap.fanPressureCalibrationActive -> R.string.fan_pressure_calibration_cancel
                                snap.fanPressureCalibrated -> R.string.fan_pressure_calibration_restart
                                else -> R.string.fan_pressure_calibration_start
                            },
                        ),
                        summary = pressureCalibrationSummary(snap),
                        enabled = snap.fanPressureCalibrationActive || snap.fanPressurePageTurn,
                        onClick = {
                            if (snap.fanPressureCalibrationActive) {
                                actions.cancelPressureCalibration()
                            } else {
                                actions.startPressureCalibration()
                            }
                        },
                    )
                })
                add(CardItem("pressure_calibrate_clear") {
                    ArrowRow(
                        title = stringResource(R.string.fan_pressure_calibration_clear),
                        summary = stringResource(R.string.fan_pressure_calibration_clear_summary),
                        enabled = snap.fanPressurePageTurn && snap.fanPressureCalibrated,
                        onClick = actions::clearPressureCalibration,
                    )
                })
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}

@Composable
private fun pressureCalibrationSummary(snap: RemotePrefs.Snapshot): String {
    val total = com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS
    if (snap.fanPressureCalibrationActive) {
        return when {
            snap.fanPressureCalibrationAttempts > 0 && !snap.fanPressureCalibrationLastValid -> stringResource(
                R.string.fan_pressure_calibration_status_running_light,
                snap.fanPressureCalibrationAttempts,
                total,
                snap.fanPressureCalibrationLastDelta,
                com.repl.bubbledrawer.bubble.PressureCalibration.MIN_SAMPLE_HPA,
            )

            snap.fanPressureCalibrationAttempts > 0 -> stringResource(
                R.string.fan_pressure_calibration_status_running_sample,
                snap.fanPressureCalibrationAttempts,
                total,
                snap.fanPressureCalibrationLastDelta,
            )

            else -> stringResource(
                R.string.fan_pressure_calibration_status_running,
                snap.fanPressureCalibrationAttempts,
                total,
            )
        }
    }

    val outcome = snap.fanPressureCalibrationOutcome
    val base = when (outcome) {
        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.UNSUPPORTED ->
            stringResource(R.string.fan_pressure_calibration_status_unsupported)

        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.TIMEOUT ->
            stringResource(R.string.fan_pressure_calibration_status_timeout)

        com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.FEW_SAMPLES ->
            stringResource(R.string.fan_pressure_calibration_status_few)

        else -> if (snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
            if (snap.fanPressureCalibrationValidCount > 0) {
                stringResource(
                    R.string.fan_pressure_calibration_status_ok,
                    snap.fanPressureCalibrationValidCount,
                    snap.fanPressureCalibratedHpa,
                )
            } else {
                stringResource(R.string.fan_pressure_calibration_status_ok_no_count, snap.fanPressureCalibratedHpa)
            }
        } else {
            stringResource(R.string.fan_pressure_calibration_status_none)
        }
    }

    val failed = outcome == com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.TIMEOUT ||
        outcome == com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.FEW_SAMPLES
    return if (failed && snap.fanPressureCalibrated && snap.fanPressureCalibratedHpa > 0f) {
        base + stringResource(R.string.fan_pressure_calibration_status_kept, snap.fanPressureCalibratedHpa)
    } else {
        base
    }
}
