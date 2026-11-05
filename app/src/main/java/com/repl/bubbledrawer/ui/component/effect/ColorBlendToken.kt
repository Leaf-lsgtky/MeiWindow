// Mirrored from compose-miuix-ui example, via KernelSU / HyperMusicCover.

package com.repl.bubbledrawer.ui.component.effect

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * The two `textureBlur` blend stacks the OS3 pages use. `textureBlur` blends the blurred
 * backdrop with these colours using the same band+curve weight as the blur radius, so the
 * tint fades out with the blur instead of ending in a hard colour edge.
 */
internal object ColorBlendToken {

    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )

    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )
}
