package com.repl.bubbledrawer.ui.util

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏渐进模糊（Progressive Blur）统一参数，与 HyperMusicCover 保持一致。
 */
object TopBarBlurConfig {
    /** 模糊半径（dp），模糊最强处的强度 */
    const val BlurRadius: Float = 15f

    /** 渐变曲线指数：1 = 线性；>1 让模糊更快衰减到清晰端 */
    const val GradientCurve: Float = 10f

    /** 顶栏 surface 背景混合透明度（0~1），越大栏越实 */
    const val SurfaceAlpha: Float = 0.3f

    /**
     * 滚动渐显距离（dp）：内容下滑该距离内，模糊从透明渐显到完整。
     * 0 = 顶栏常驻完整模糊（推荐，各页面顶部即可见模糊）。
     */
    val ScrollFadeDistance: Dp = 0.dp
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/** true when [backdrop] is actually usable; call once at the top of a screen. */
fun blurActive(backdrop: LayerBackdrop?): Boolean = backdrop != null

/** Bar background color for the current mode: transparent lets the blur show through. */
@Composable
fun barColor(active: Boolean): Color =
    if (active) Color.Transparent else MiuixTheme.colorScheme.surface

/**
 * Wrap a TopAppBar / PanelBar so the bar itself blurs what the content layer recorded.
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurEnabled: Boolean = true,
    scrollBehavior: ScrollBehavior? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val blurActive = blurEnabled && backdrop != null
    val scrollFadePx = with(LocalDensity.current) { TopBarBlurConfig.ScrollFadeDistance.toPx() }
    Box(modifier = modifier) {
        if (blurActive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = if (scrollFadePx > 0f) {
                            scrollBehavior?.state
                                ?.let { (-it.contentOffset / scrollFadePx).coerceIn(0f, 1f) }
                                ?: 1f
                        } else {
                            1f
                        }
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Black,
                                0.6f to Color.Black,
                                1.0f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = TopBarBlurConfig.GradientCurve),
                        blurRadius = TopBarBlurConfig.BlurRadius,
                    )
                    .background(
                        MiuixTheme.colorScheme.surface.copy(TopBarBlurConfig.SurfaceAlpha),
                    ),
            )
        }
        content()
    }
}

/**
 * The content container of a page: registers its draw output as the backdrop the bar
 * samples.
 */
@Composable
fun Modifier.pageBackdrop(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this
