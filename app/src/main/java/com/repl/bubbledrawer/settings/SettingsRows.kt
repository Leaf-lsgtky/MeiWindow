package com.repl.bubbledrawer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.ui.theme.StatusColors
import com.repl.bubbledrawer.ui.component.StatusBadge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.repl.bubbledrawer.xposed.SettingsUiState

/** Slider bounds mirrors of RemotePrefs — keeps the UI from drifting off the reader. */
const val RemotePrefsMinDp = com.repl.bubbledrawer.xposed.RemotePrefs.MIN_DP
const val RemotePrefsMaxDp = com.repl.bubbledrawer.xposed.RemotePrefs.MAX_DP
const val RemotePrefsPctMin = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_PCT_MIN
const val RemotePrefsPctMax = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_PCT_MAX
const val RemotePrefsIconMin = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_ICON_MIN
const val RemotePrefsIconMax = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_ICON_MAX
const val RemotePrefsTextMin = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_TEXT_MIN
const val RemotePrefsTextMax = com.repl.bubbledrawer.xposed.RemotePrefs.PANEL_TEXT_MAX
const val RemotePrefsFanRadiusMin = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_RADIUS_MIN
const val RemotePrefsFanRadiusMax = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_RADIUS_MAX
const val RemotePrefsFanIconMin = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_ICON_MIN
const val RemotePrefsFanIconMax = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_ICON_MAX
const val RemotePrefsFanAutoRetractMin = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_AUTO_RETRACT_MIN
const val RemotePrefsFanAutoRetractMax = com.repl.bubbledrawer.xposed.RemotePrefs.FAN_AUTO_RETRACT_MAX

/** Bridge status line: module chip + one-line state, per the old page's status text. */
@Composable
fun ModuleStatusRow(state: SettingsUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                text = if (state.connected) "LSPosed" else "OFFLINE",
                containerColor = if (state.connected) {
                    StatusColors.healthy()
                } else {
                    StatusColors.removeAction()
                },
                contentColor = MiuixTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = if (state.connected) {
                stringResource(R.string.module_connected, state.frameworkLabel ?: "?")
            } else {
                stringResource(R.string.module_disconnected)
            },
            modifier = Modifier.padding(top = 8.dp),
            color = if (state.connected) {
                StatusColors.healthy()
            } else {
                StatusColors.neutral()
            },
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/**
 * Switch row inside a grouped card (miuix SwitchPreference carries its own padding).
 *
 * [summary] is the miuix description slot — the setting's one-line explanation belongs HERE,
 * not in a separate hint row below (docs/ui-guidelines.md: 每个设置项自带主标题 + 描述).
 * [enabled] = false greys the row out; used for "总开关没开时子项不可用".
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

/**
 * Single-select row. `OverlayDropdownPreference` is the miuix-native single-select list
 * (the guidelines' WindowSpinnerPreference ban does not apply — that ban is about
 * hand-rolled TextButton lists inside dialogs, which we do not build).
 */
@Composable
fun DropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        onSelectedIndexChange = onSelect,
    )
}

/**
 * Plain integer slider. Local float state while dragging (recompositions stay local),
 * the write happens on [onValueChangeFinished] — LSPosed's remote mirror round-trips,
 * so committing per-frame would spam the bridge.
 */
@Composable
fun IntSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    valueText: @Composable (Int) -> String,
    onCommit: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var dragging by remember(value) { mutableStateOf(value.toFloat()) }
    SliderPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        value = dragging,
        onValueChange = { dragging = it },
        valueText = valueText(Math.round(dragging).coerceIn(min, max)),
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min) - 1,
        onValueChangeFinished = { onCommit(Math.round(dragging).coerceIn(min, max)) },
    )
}

/**
 * Stepped integer slider snapping to increments of [step] (e.g. 4, 8, 12, 16, 20).
 */
@Composable
fun StepIntSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    valueText: @Composable (Int) -> String,
    onCommit: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var dragging by remember(value) { mutableStateOf(value.toFloat()) }
    val stepCount = ((max - min) / step).coerceAtLeast(1)
    val snappedCurrent = (Math.round((dragging - min) / step.toFloat()) * step + min).coerceIn(min, max)
    SliderPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        value = dragging,
        onValueChange = { dragging = it },
        valueText = valueText(snappedCurrent),
        valueRange = min.toFloat()..max.toFloat(),
        steps = stepCount - 1,
        onValueChangeFinished = {
            val snapped = (Math.round((dragging - min) / step.toFloat()) * step + min).coerceIn(min, max)
            onCommit(snapped)
        },
    )
}

/**
 * Slider that displays [defaultText] ("默认") when its value equals [defaultValue].
 * As the user slides (e.g. at 80), it shows: 79% -> 默认 -> 81%!
 */
@Composable
fun DefaultableIntSliderRow(
    title: String,
    value: Int,
    defaultValue: Int,
    min: Int,
    max: Int,
    defaultText: String = androidx.compose.ui.res.stringResource(com.repl.bubbledrawer.R.string.panel_default),
    valueText: @Composable (Int) -> String,
    onCommit: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var dragging by remember(value) { mutableStateOf(value.toFloat()) }
    val currentInt = Math.round(dragging).coerceIn(min, max)
    SliderPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        value = dragging,
        onValueChange = { dragging = it },
        valueText = if (currentInt == defaultValue) {
            defaultText
        } else {
            valueText(currentInt)
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min) - 1,
        onValueChangeFinished = {
            onCommit(Math.round(dragging).coerceIn(min, max))
        },
    )
}

/**
 * Section-level note (a paragraph that belongs to a whole card, not to one setting).
 * Per-row explanations use the component's own `summary` slot instead.
 */
@Composable
fun HintRow(text: String) {
    com.repl.bubbledrawer.ui.component.SupportText(text = text)
}

/** Preference entry row that navigates or triggers an action. */
@Composable
fun ArrowRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
    )
}
