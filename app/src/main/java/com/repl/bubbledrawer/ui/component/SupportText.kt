package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Long-form explanation block (docs/ui-guidelines.md "长说明文本"): a paragraph that
 * belongs to a setting but is too long for a summary slot. Not clickable, so a plain
 * `background` container is enough — no offscreen squircle layer needed.
 *
 * Lives at the TOP of a section (like SmallTitle) with its own margins, so pages opening
 * with it skip the leading 12.dp spacer.
 */
@Composable
fun SupportText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
    )
}

/**
 * Restart-style notice (docs/ui-guidelines.md names a RestartRequiredHint component):
 * squircle container + icon, reserved for settings that genuinely do not apply live.
 * This module mirrors config through LSPosed remote preferences, so nearly everything
 * applies without a restart — the hint is only shown for states the fan cannot pick up.
 */
@Composable
fun RestartRequiredHint(
    text: String = androidx.compose.ui.res.stringResource(R.string.module_disconnected),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .squircleBackground(
                color = MiuixTheme.colorScheme.errorContainer,
                cornerRadius = 12.dp,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Info,
            contentDescription = null,
            tint = StatusColors.danger(),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onErrorContainer,
            style = MiuixTheme.textStyles.body2,
        )
    }
}
