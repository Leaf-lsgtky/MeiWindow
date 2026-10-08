package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.xposed.RemotePrefs
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Full-screen overlay that visualizes the corner trigger zones (quarter-ellipses)
 * based on current [snap] settings (bottomDp × edgeDp).
 */
@Composable
fun TriggerZonePreview(
    snap: RemotePrefs.Snapshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Auto-dismiss after 6 seconds of inactivity
    LaunchedEffect(Unit) {
        delay(6000L)
        onDismiss()
    }

    val primaryColor = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current.density
    val bottomPx = snap.bottomDp * density
    val edgePx = snap.edgeDp * density

    // Determine which corners are enabled (if neither is set, preview both)
    val leftActive = snap.left || (!snap.left && !snap.right)
    val rightActive = snap.right || (!snap.left && !snap.right)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        // Dim background slightly to highlight the trigger shapes
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f)),
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val strokeWidth = 3f * density
            val dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(10f * density, 6f * density),
                0f,
            )

            // 1. Bottom-left corner quarter-ellipse
            if (leftActive) {
                val leftPath = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, h - edgePx)
                    arcTo(
                        rect = Rect(-bottomPx, h - edgePx, bottomPx, h + edgePx),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = leftPath,
                    color = primaryColor.copy(alpha = 0.35f),
                )
                drawPath(
                    path = leftPath,
                    color = primaryColor,
                    style = Stroke(width = strokeWidth, pathEffect = dashEffect),
                )
            }

            // 2. Bottom-right corner quarter-ellipse
            if (rightActive) {
                val rightPath = Path().apply {
                    moveTo(w, h)
                    lineTo(w, h - edgePx)
                    arcTo(
                        rect = Rect(w - bottomPx, h - edgePx, w + bottomPx, h + edgePx),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false,
                    )
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = rightPath,
                    color = primaryColor.copy(alpha = 0.35f),
                )
                drawPath(
                    path = rightPath,
                    color = primaryColor,
                    style = Stroke(width = strokeWidth, pathEffect = dashEffect),
                )
            }
        }

        // Center bottom prompt card
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
                .padding(horizontal = 24.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.preview_zone_title),
                    style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Bold),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.preview_zone_dims,
                        snap.bottomDp,
                        snap.edgeDp,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.preview_zone_tap_dismiss),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                    color = primaryColor,
                )
            }
        }
    }
}
