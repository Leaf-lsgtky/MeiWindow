package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repl.bubbledrawer.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Top status card redesigned following KernelSU / HyperMusicCover / ghostlock-app:
 * - Green / Red background depending on module connection state
 * - Oversized Material icon bleeding off the bottom-right corner (.offset(27.dp, 31.dp).size(110.dp))
 * - "已连接" / "未连接" title with informative status description
 * - Tilt press feedback and bottom-left app name tag
 */
@Composable
fun StatusCard(
    connected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val darkTheme = isSystemInDarkTheme()
    val dynamicColor = MiuixTheme.isDynamicColor

    val statusColor = if (connected) {
        when {
            dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            darkTheme -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
    } else {
        when {
            dynamicColor -> MiuixTheme.colorScheme.errorContainer
            darkTheme -> Color(0xFF3D1C1C)
            else -> Color(0xFFFDE8E8)
        }
    }

    val iconTint = if (connected) {
        if (dynamicColor) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        else Color(0xFF36D167)
    } else {
        if (dynamicColor) MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
        else Color(0xFFDC3545)
    }

    val titleText = if (connected) {
        stringResource(R.string.status_connected)
    } else {
        stringResource(R.string.status_disconnected)
    }

    val descText = if (connected) {
        stringResource(R.string.status_connected_desc)
    } else {
        stringResource(R.string.status_disconnected_desc)
    }

    val appTag = if (connected) {
        stringResource(R.string.app_name)
    } else {
        null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = statusColor),
            onClick = { onClick?.invoke() },
            showIndication = onClick != null,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                // Material offset icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = 27.dp, y = 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = if (connected) Icons.Rounded.CheckCircleOutline
                        else Icons.Rounded.ErrorOutline,
                        tint = iconTint,
                        contentDescription = null,
                    )
                }

                // Bottom tag (app name when connected)
                if (appTag != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, bottom = 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = appTag,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (dynamicColor) MiuixTheme.colorScheme.onSecondaryContainer
                            else if (darkTheme) Color(0xFF8CE7A2)
                            else Color(0xFF18793A),
                        )
                    }
                }

                // Text section: Title and Description
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 16.dp,
                            top = 14.dp,
                            end = 100.dp,
                            bottom = if (connected) 38.dp else 14.dp,
                        ),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = titleText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (dynamicColor) {
                                if (connected) MiuixTheme.colorScheme.onSecondaryContainer else MiuixTheme.colorScheme.onErrorContainer
                            } else {
                                if (connected) {
                                    if (darkTheme) Color(0xFFE2FBE8) else Color(0xFF0F5B2B)
                                } else {
                                    if (darkTheme) Color(0xFFFDE8E8) else Color(0xFF8B1A1A)
                                }
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = descText,
                            fontSize = 14.sp,
                            color = if (dynamicColor) {
                                if (connected) MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            } else {
                                if (connected) {
                                    if (darkTheme) Color(0xFFB4E6C1) else Color(0xFF2E7D46)
                                } else {
                                    if (darkTheme) Color(0xFFEAAFAF) else Color(0xFFA93232)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
