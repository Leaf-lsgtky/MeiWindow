// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
@file:androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)

package com.repl.bubbledrawer.ui.component.effect

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun Modifier.bgEffectDraw(
    painter: BgEffectPainter,
    preset: BgEffectConfig.Config,
    deviceType: DeviceType,
    isDarkTheme: Boolean,
    surface: Color,
    effectBackground: Boolean,
    isFullSize: Boolean,
    playing: Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then BgEffectElement(
    painter = painter,
    preset = preset,
    deviceType = deviceType,
    isDarkTheme = isDarkTheme,
    surface = surface,
    effectBackground = effectBackground,
    isFullSize = isFullSize,
    playing = playing,
    colorStage = colorStage,
    alpha = alpha,
)

private data class BgEffectElement(
    val painter: BgEffectPainter,
    val preset: BgEffectConfig.Config,
    val deviceType: DeviceType,
    val isDarkTheme: Boolean,
    val surface: Color,
    val effectBackground: Boolean,
    val isFullSize: Boolean,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<BgEffectNode>() {

    override fun create(): BgEffectNode = BgEffectNode(
        painter = painter,
        preset = preset,
        deviceType = deviceType,
        isDarkTheme = isDarkTheme,
        surface = surface,
        effectBackground = effectBackground,
        isFullSize = isFullSize,
        playing = playing,
        colorStage = colorStage,
        alpha = alpha,
    )

    override fun update(node: BgEffectNode) {
        node.update(
            painter = painter,
            preset = preset,
            deviceType = deviceType,
            isDarkTheme = isDarkTheme,
            surface = surface,
            effectBackground = effectBackground,
            isFullSize = isFullSize,
            playing = playing,
            colorStage = colorStage,
            alpha = alpha,
        )
    }
}

/**
 * Draws the shader and drives its clock.
 *
 * Differences from the reference implementations, both deliberate:
 *  - the frame loop is **stopped while the effect is invisible** (alpha reaches 0 at the end
 *    of the About page's scroll). The reference keeps it running and only skips the draw,
 *    which costs a shader evaluation per enclosing layer every frame for the rest of the
 *    session; docs/ui-guidelines.md forbids exactly that. The pause is decided in `draw()`
 *    because that is where alpha is legitimately readable (it is a deferred lambda), and the
 *    next element update — the scroll that made it visible again — restarts it;
 *  - `withFrameNanos` is the only clock, no per-frame state writes, so a running effect
 *    invalidates draw and nothing else.
 */
private class BgEffectNode(
    private var painter: BgEffectPainter,
    private var preset: BgEffectConfig.Config,
    private var deviceType: DeviceType,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var effectBackground: Boolean,
    private var isFullSize: Boolean,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {

    private var animationJob: Job? = null
    private var animTime: Float = 0f
    private var startOffset: Float = 0f

    /** Set from `draw()` once the effect has faded out; cleared by the next [update]. */
    private var fadedOut: Boolean = false

    override fun onAttach() {
        if (playing) startAnimation()
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    fun update(
        painter: BgEffectPainter,
        preset: BgEffectConfig.Config,
        deviceType: DeviceType,
        isDarkTheme: Boolean,
        surface: Color,
        effectBackground: Boolean,
        isFullSize: Boolean,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.deviceType = deviceType
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.effectBackground = effectBackground
        this.isFullSize = isFullSize
        this.colorStage = colorStage
        this.alpha = alpha

        if (this.playing != playing) {
            this.playing = playing
            fadedOut = false
            if (playing) startAnimation() else stopAnimation()
        } else if (playing && fadedOut) {
            // Visible again after the fade-out: the scroll that brought it back is exactly this
            // update, so this is the only place the restart can be decided from.
            fadedOut = false
            startAnimation()
        }
        invalidateDraw()
    }

    private fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    private fun startAnimation() {
        if (animationJob?.isActive == true) return
        startOffset = animTime
        animationJob = coroutineScope.launch {
            val minDeltaNanos = 1_000_000_000L / 60L
            val origin = withFrameNanos { it }
            var lastEmit = origin
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - lastEmit < minDeltaNanos) continue
                lastEmit = now
                animTime = startOffset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawRect(surface)
        if (!effectBackground) {
            drawContent()
            return
        }
        val alphaValue = alpha()
        if (alphaValue <= 0f) {
            // Off screen: stop paying for a shader nobody can see. `draw` will run again the
            // moment the component is updated (the scroll that reveals it) or re-attached.
            if (playing) {
                fadedOut = true
                stopAnimation()
            }
            drawContent()
            return
        }
        val drawHeight = if (isFullSize) size.height * 0.8f else size.height * 0.5f

        painter.updateResolution(size.width, size.height)
        painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
        painter.updatePresetIfNeeded(deviceType, isDarkTheme)
        painter.updateColors(preset, colorStage())
        painter.updateAnimTime(animTime)
        painter.updatePointsAnim(animTime, preset)

        drawRect(painter.brush, alpha = alphaValue)
        drawContent()
    }
}
