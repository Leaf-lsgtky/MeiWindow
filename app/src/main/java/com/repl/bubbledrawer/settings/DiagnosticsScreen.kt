package com.repl.bubbledrawer.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.diagnostics.CheckGroup
import com.repl.bubbledrawer.diagnostics.CheckStatus
import com.repl.bubbledrawer.diagnostics.Diagnosis
import com.repl.bubbledrawer.diagnostics.DiagnosisCheck
import com.repl.bubbledrawer.diagnostics.ExpectedSettings
import com.repl.bubbledrawer.diagnostics.LogSnapshot
import com.repl.bubbledrawer.diagnostics.ModuleLogDiagnosis
import com.repl.bubbledrawer.diagnostics.ModuleLogSource
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.StatusBadge
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.StatusColors
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.barColor
import com.repl.bubbledrawer.ui.util.horizontalCutoutPadding
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageContentPadding
import com.repl.bubbledrawer.ui.util.pageScroll
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 诊断页：把模块自己的日志读成一张体检表 —— 哪些成功、哪些有问题、为什么。
 *
 * 定位：测试机常常没有电脑、拿不到 adb，而"功能没生效"的每一种可能都恰好有一行日志。所以这一页
 * 自己把日志读出来（需要 root，见 [ModuleLogSource]）交给 [ModuleLogDiagnosis] 判定，并把原始证据
 * 行一起摆出来；结论可以一键复制，直接发给开发者。
 *
 * 读日志这件事**只在打开页面时做一次**（以及用户点刷新时）：它要起一个 root 进程，不该在滚动、
 * 重组时反复触发。
 */
@Composable
fun DiagnosticsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    var attempt by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<LogSnapshot?>(null) }
    var diagnosis by remember { mutableStateOf<Diagnosis?>(null) }

    val snap = state.snapshot
    LaunchedEffect(attempt) {
        running = true
        val collected = ModuleLogSource.collect()
        val result = ModuleLogDiagnosis.analyze(
            lines = collected.lines,
            expected = ExpectedSettings(
                freeformEnabled = snap.flymeFreeformEnabled,
                center = snap.flymeFreeformCenter,
                scale = snap.flymeFreeformScale,
                noOffset = snap.flymeFreeformNoOffset,
            ),
        )
        snapshot = collected
        diagnosis = result
        running = false
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.diagnosis_title),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { attempt++ }) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = stringResource(R.string.diagnosis_rerun),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "diag-lead") { LeadSpacer() }

            item(key = "diag-summary") {
                val current = diagnosis
                val error = snapshot?.error
                SummaryCard(
                    running = running,
                    headline = when {
                        running -> stringResource(R.string.diagnosis_running)
                        error != null -> error
                        current != null -> current.headline
                        else -> stringResource(R.string.diagnosis_running)
                    },
                    detail = when {
                        running -> stringResource(R.string.diagnosis_running_hint)
                        current == null -> ""
                        else -> stringResource(
                            R.string.diagnosis_source,
                            snapshot?.source ?: "?",
                            current.lineCount,
                            current.moduleLineCount,
                        )
                    },
                    trouble = !running && (error != null || (diagnosis?.failed ?: 0) > 0),
                )
            }

            diagnosis?.let { current ->
                for (group in CheckGroup.entries) {
                    val inGroup = current.checks.filter { it.group == group }
                    if (inGroup.isEmpty()) continue
                    item(key = "diag-title-${group.name}") {
                        SmallTitle(text = group.title)
                    }
                    groupedCardItems(
                        keyPrefix = "diag-${group.name}",
                        items = inGroup.map { check -> CardItem(check.id) { CheckRow(check) } },
                    )
                }
            }

            item(key = "diag-actions-title") { SmallTitle(text = stringResource(R.string.diagnosis_actions)) }
            groupedCardItems(
                keyPrefix = "diag-actions",
                items = listOf(
                    CardItem("rerun") {
                        ArrowRow(
                            title = stringResource(R.string.diagnosis_rerun),
                            summary = stringResource(R.string.diagnosis_rerun_summary),
                            onClick = { attempt++ },
                        )
                    },
                    CardItem("copy") {
                        ArrowRow(
                            title = stringResource(R.string.diagnosis_copy),
                            summary = stringResource(R.string.diagnosis_copy_summary),
                            enabled = diagnosis != null,
                            onClick = {
                                val text = diagnosis?.toText().orEmpty()
                                copy(context, text)
                                toast(context, context.getString(R.string.diagnosis_copied))
                            },
                        )
                    },
                    CardItem("restart") {
                        ArrowRow(
                            title = stringResource(R.string.restart_systemui),
                            summary = stringResource(R.string.diagnosis_restart_summary),
                            onClick = actions::restartSystemUi,
                        )
                    },
                ),
            )

            val raw = diagnosis?.raw.orEmpty()
            if (raw.isNotEmpty()) {
                item(key = "diag-raw-title") { SmallTitle(text = stringResource(R.string.diagnosis_raw)) }
                item(key = "diag-raw") {
                    RawLogCard(raw)
                }
            }

            item(key = "diag-bottom") { PageBottomSpacer() }
        }
    }
}

@Composable
private fun SummaryCard(
    running: Boolean,
    headline: String,
    detail: String,
    trouble: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = when {
                        running -> "…"
                        trouble -> "有问题"
                        else -> "正常"
                    },
                    containerColor = when {
                        running -> StatusColors.neutral()
                        trouble -> StatusColors.danger()
                        else -> StatusColors.healthy()
                    },
                    contentColor = MiuixTheme.colorScheme.onPrimary,
                )
                Text(
                    text = headline,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MiuixTheme.textStyles.title4,
                )
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    modifier = Modifier.padding(top = 6.dp),
                    color = StatusColors.neutral(),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

/** 一条结论：状态徽标 + 标题 + 一句话原因 + 原始证据行。 */
@Composable
private fun CheckRow(check: DiagnosisCheck) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(
                text = check.status.label(),
                containerColor = check.status.container(),
                contentColor = MiuixTheme.colorScheme.onPrimary,
            )
            Text(
                text = check.title,
                modifier = Modifier.padding(start = 8.dp),
                style = MiuixTheme.textStyles.body1,
            )
        }
        Text(
            text = check.detail,
            modifier = Modifier.padding(top = 6.dp),
            color = StatusColors.neutral(),
            style = MiuixTheme.textStyles.body2,
        )
        check.evidence?.let { line ->
            Text(
                text = line,
                modifier = Modifier.padding(top = 6.dp),
                color = StatusColors.neutral(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun RawLogCard(lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = StatusColors.neutral(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun CheckStatus.label(): String = when (this) {
    CheckStatus.PASS -> stringResource(R.string.diagnosis_pass)
    CheckStatus.WARN -> stringResource(R.string.diagnosis_warn)
    CheckStatus.FAIL -> stringResource(R.string.diagnosis_fail)
    CheckStatus.INFO -> stringResource(R.string.diagnosis_info)
}

@Composable
private fun CheckStatus.container() = when (this) {
    CheckStatus.PASS -> StatusColors.healthy()
    CheckStatus.WARN -> StatusColors.warning()
    CheckStatus.FAIL -> StatusColors.danger()
    CheckStatus.INFO -> StatusColors.neutral()
}

private fun copy(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MeiWindow", text))
    }
}

private fun toast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
