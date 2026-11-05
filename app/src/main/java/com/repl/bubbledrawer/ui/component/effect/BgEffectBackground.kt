// Mirrored from compose-miuix-ui example, via KernelSU / HyperMusicCover.

package com.repl.bubbledrawer.ui.component.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.repl.bubbledrawer.ui.theme.isInDarkTheme

/**
 * The HyperOS 3 "fluid light" page background: four drifting colour spots behind a Perlin
 * noise field, cross-fading between three colour sets on a slow spring.
 *
 * This is the About page's 光效. Same structure and same presets as KernelSU's
 * `BgEffectBackground`, with two project-specific decisions:
 *  - [deviceType] is passed in instead of calling a split-pane helper — this app already owns
 *    the wide-screen authority ([com.repl.bubbledrawer.ui.util.rememberIsWideScreen]);
 *  - the colour cross-fade is only started when [dynamicBackground] is true, so a page that is
 *    not on screen does not wake the frame clock for its own spring.
 *
 * @param dynamicBackground whether the effect animates at all (false for a page that is not
 *   the one in front, or when the user turned the effect off)
 * @param isFullSize true for a full page (the light band covers 80 % of the height, as on the
 *   OS3 Settings home), false for a half-height sheet
 * @param alpha deferred opacity — read in the draw phase, so the caller can fade the effect
 *   with the scroll position without recomposing per frame
 */
@Composable
internal fun BgEffectBackground(
    deviceType: DeviceType,
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit = {},
) {
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(modifier = modifier) {
        val surface = MiuixTheme.colorScheme.surface
        val isDarkTheme = isInDarkTheme()
        val painter = remember { BgEffectPainter() }

        val preset = remember(deviceType, isDarkTheme) {
            BgEffectConfig.get(deviceType, isDarkTheme)
        }

        val colorStage = remember { Animatable(0f) }

        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong().milliseconds)
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}
