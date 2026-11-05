package com.repl.bubbledrawer.ui.component

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.BuildConfig
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home page's status card.
 *
 * Laid out like KernelSU's `HomeMiuix` card (which HyperMusicCover reuses, and which the old
 * version of this file already imitated): three boxes stacked on each other rather than three
 * lines in a column — title + second line top-left, the app name pinned bottom-left, the
 * oversized icon bleeding off the bottom-right corner. The name and the icon balance each
 * other diagonally, and the empty middle is what stops it looking cramped.
 *
 * What is new here is the 光效: an animated fluid-light wash under the content, ported from
 * InstallerX's `AnimatedFluidBackground` (same three moving radial layers plus the static
 * centre glow, same colour drift), tinted with the status colour so the card reads
 * green-when-live / red-when-dead even without the icon.
 *
 * The card height comes from `Row(IntrinsicSize.Min)`: the inner boxes all `fillMaxSize` and
 * so contribute no size of their own, and without it the card collapses to the text height.
 */
@Composable
fun StatusCard(
    connected: Boolean,
    modifier: Modifier = Modifier,
    frameworkLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val darkTheme = isInDarkTheme()
    val dynamicColor = MiuixTheme.isDynamicColor
    val version = "v${BuildConfig.VERSION_NAME}"

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

    // When it works there is nothing to instruct, so the second line says what is running
    // instead; when it does not, the hint is the one thing worth reading.
    val lineTwo = if (connected) {
        listOfNotNull(frameworkLabel, version).joinToString(" · ")
    } else {
        stringResource(R.string.status_disconnected_desc)
    }

    // The bottom-left corner names the app rather than repeating the state: only one module
    // can answer here, and the name is what someone looking at a screenshot would want.
    val appTag = if (connected) stringResource(R.string.app_name) else null

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
                StatusCardGlow(
                    baseColor = statusColor,
                    modifier = Modifier.matchParentSize(),
                )

                // Oversized icon, deliberately bleeding off the corner and clipped by the card.
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

                if (appTag != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = appTag,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = 14.dp, end = 100.dp, bottom = if (appTag != null) 38.dp else 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = titleText,
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = lineTwo,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * InstallerX's `AnimatedFluidBackground`, re-shaped for a card:
 *
 *  - every animated value is a `State` that is **read inside the draw lambda only**, so a
 *    running glow invalidates draw and never recomposes the card (the reference reads them
 *    with `by` in composition, which recomposes four canvases per frame);
 *  - only three moving layers + one centre glow are kept — the reference's fourth canvas is
 *    a texture pass tuned for a full-page card and is invisible at this size;
 *  - colours drift through the same magenta / cyan / yellow accents at the same low alphas,
 *    mixed over the status colour so the card's own hue stays dominant.
 */
@Composable
private fun StatusCardGlow(
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "status_card_glow")

    val primaryFlow = transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.9f),
        targetValue = baseColor.copy(alpha = 0.6f).compositeOver(Color.Magenta.copy(alpha = 0.2f)),
        animationSpec = infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Reverse),
        label = "primary_flow",
    )
    val secondaryFlow = transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.7f).compositeOver(Color.Cyan.copy(alpha = 0.25f)),
        targetValue = baseColor.copy(alpha = 0.85f).compositeOver(Color.Blue.copy(alpha = 0.15f)),
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Reverse),
        label = "secondary_flow",
    )
    val accentFlow = transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.6f).compositeOver(Color.Yellow.copy(alpha = 0.1f)),
        targetValue = baseColor.copy(alpha = 0.8f).compositeOver(Color.Green.copy(alpha = 0.18f)),
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "accent_flow",
    )

    val time1 = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "time_1",
    )
    val time2 = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(13000, easing = LinearEasing), RepeatMode.Reverse),
        label = "time_2",
    )
    val time3 = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(17000, easing = LinearEasing), RepeatMode.Reverse),
        label = "time_3",
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2
        val maxRadius = maxOf(width, height)

        val t1 = time1.value
        val t2 = time2.value
        val t3 = time3.value

        val flowCenters = listOf(
            Offset(
                x = centerX + width * 0.45f * sin(t1 * 0.8f + 0.5f) + width * 0.15f * cos(t2 * 0.7f),
                y = centerY + height * 0.4f * cos(t1 * 0.9f) + height * 0.12f * sin(t2 * 1.1f + 1.2f),
            ),
            Offset(
                x = centerX + width * 0.5f * cos(t1 * 0.6f + 2.1f) + width * 0.18f * sin(t2 * 0.9f + 0.8f),
                y = centerY + height * 0.42f * sin(t1 * 0.7f + 1.5f) + height * 0.15f * cos(t2 * 0.8f + 2f),
            ),
            Offset(
                x = centerX + width * 0.38f * sin(t1 * 0.75f + 3.8f) + width * 0.2f * cos(t3 * 1.2f),
                y = centerY + height * 0.35f * cos(t1 * 0.85f + 2.7f) + height * 0.17f * sin(t3 * 1f + 1.1f),
            ),
        )
        val radii = listOf(
            maxRadius * 0.8f + maxRadius * 0.12f * sin(t3 * 0.8f),
            maxRadius * 0.75f + maxRadius * 0.15f * cos(t3 * 0.9f + 1f),
            maxRadius * 0.85f + maxRadius * 0.1f * sin(t3 * 1.1f + 2.3f),
        )
        val colors = listOf(primaryFlow.value, secondaryFlow.value, accentFlow.value)

        flowCenters.forEachIndexed { index, center ->
            val fluidColor = colors[index]
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        fluidColor,
                        fluidColor.copy(alpha = fluidColor.alpha * 0.6f),
                        fluidColor.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radii[index],
                ),
                center = center,
                radius = radii[index],
            )
        }

        // Static centre glow, the same one the reference draws on its topmost canvas.
        val glowIntensity = 0.08f + 0.04f * cos(t1 * 0.8f)
        val glowRadius = maxRadius * 0.9f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    baseColor.copy(alpha = glowIntensity),
                    baseColor.copy(alpha = glowIntensity * 0.5f),
                    Color.Transparent,
                ),
                center = Offset(centerX, centerY),
                radius = glowRadius,
            ),
            center = Offset(centerX, centerY),
            radius = glowRadius,
        )
    }
}
