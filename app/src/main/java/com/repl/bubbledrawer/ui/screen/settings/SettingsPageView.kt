package com.repl.bubbledrawer.ui.screen.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.screen.about.AboutActivity
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
fun SettingsPageView(
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current

    PageScaffold(
        title = stringResource(R.string.tab_settings),
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "lead") { LeadSpacer() }

        // --- 界面与个性化 ---
        item(key = "interface_title") {
            SmallTitle(text = stringResource(R.string.settings_interface))
        }
        groupedCardItems(
            keyPrefix = "settings_interface_group",
            items = listOf(
                CardItem("theme") {
                    ArrowRow(
                        title = stringResource(R.string.settings_theme),
                        summary = stringResource(R.string.settings_theme_summary),
                        onClick = {
                            context.startActivity(Intent(context, ThemeActivity::class.java))
                        },
                    )
                },
            ),
        )

        // --- 调试与诊断 ---
        item(key = "debug_title") {
            SmallTitle(text = stringResource(R.string.settings_debug))
        }
        groupedCardItems(
            keyPrefix = "settings_debug_group",
            items = listOf(
                CardItem("diagnostics") {
                    ArrowRow(
                        title = stringResource(R.string.settings_diagnostics),
                        summary = stringResource(R.string.settings_diagnostics_summary),
                        onClick = {
                            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
                        },
                    )
                },
            ),
        )

        // --- 关于 ---
        // 独立二级页（与 外观与主题 / 模块运行诊断 同一种入口），不是第四个底栏 Tab：
        // 底栏留给每天都要用的三页，而关于页那套 hero 视差 + 光效背景也不适合塞进 Pager。
        item(key = "about_title") {
            SmallTitle(text = stringResource(R.string.tab_about))
        }
        groupedCardItems(
            keyPrefix = "settings_about_group",
            items = listOf(
                CardItem("about") {
                    ArrowRow(
                        title = stringResource(R.string.about_title),
                        summary = stringResource(R.string.about_summary),
                        onClick = {
                            context.startActivity(Intent(context, AboutActivity::class.java))
                        },
                    )
                },
            ),
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
