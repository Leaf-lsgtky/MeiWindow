package com.repl.bubbledrawer.ui.screen.about

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repl.bubbledrawer.BuildConfig
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.ui.component.effect.BgEffectBackground
import com.repl.bubbledrawer.ui.component.effect.ColorBlendToken
import com.repl.bubbledrawer.ui.component.effect.DeviceType
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.theme.isInDarkTheme
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.PageBackButton
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.pageScroll
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于 — a **secondary page**, reached from 设置 exactly like 外观与主题 / 模块运行诊断
 * (a separate Activity, not a fourth bottom-bar tab).
 *
 * The visual is the KernelSU / HyperMusicCover About screen:
 *  - the OS3 fluid-light shader background ([BgEffectBackground]) sits behind everything and
 *    fades out with the scroll;
 *  - a parallax hero (logo → app name → version) shrinks and fades in three stages as the hero
 *    spacer scrolls away. Each stage is driven by `graphicsLayer` reading the scroll progress
 *    lazily, so nothing recomposes per frame;
 *  - cards blur the light behind them (`textureBlur` + the OS3 blend tokens) when the 毛玻璃
 *    switch is on, and fall back to plain `surfaceContainer` when it is off;
 *  - a transparent top bar that only materialises (surface + title) once the hero is gone.
 *
 * Layout shape is deliberately the same as [com.repl.bubbledrawer.ui.util.PageScaffold]:
 * `Scaffold { Box(fillMaxSize) { background; hero overlay; LazyColumn } }`. The list is the
 * last child of a full-size Box — i.e. the topmost hit target and a plain viewport-sized
 * scroll container. The hero is an overlay BEHIND it, never a wrapper around it: nesting the
 * list inside the effect's layout is what made an earlier revision of this page refuse to
 * scroll.
 */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                AboutPage(
                    isBlurEnabled = settings.isBlurEnabled,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun AboutPage(
    onBack: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var heroSpacerPx by remember { mutableIntStateOf(0) }


    // The spacer is exactly one hero tall, so this is the fraction of the hero that has been
    // scrolled away — 0 at rest, 1 once the cards reach the bar.
    val scrollProgress by remember {
        derivedStateOf {
            if (heroSpacerPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                if (index > 0) 1f else (lazyListState.firstVisibleItemScrollOffset.toFloat() / heroSpacerPx).coerceIn(0f, 1f)
            }
        }
    }

    val barBackdrop = rememberBlurBackdrop()?.takeIf { isBlurEnabled }
    val barBlurActive = barBackdrop != null && scrollProgress == 1f
    val barColor = when {
        barBlurActive -> Color.Transparent
        scrollProgress == 1f -> MiuixTheme.colorScheme.surface
        else -> Color.Transparent
    }

    Scaffold(
        topBar = {
            // Only blur once the hero has scrolled away. With the veil always on, the bar
            // paints a 30%-surface band over the top of the light effect and the page starts
            // with a white strip instead of the shader — the reference gates it the same way.
            BlurredBar(backdrop = barBackdrop, blurEnabled = barBlurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.tab_about),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(
                        alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    ),
                    navigationIcon = { PageBackButton(onBack = onBack) },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // The bar's backdrop has to be recorded on the WHOLE page, not just the list: at the
        // top of the page the list is transparent (it starts with the hero spacer), so a
        // list-only backdrop blurs nothing and the bar paints a white veil over the light
        // effect. The reference does exactly this — layerBackdrop on the content container.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier),
        ) {
            AboutContent(
                innerPadding = innerPadding,
                lazyListState = lazyListState,
                scrollBehavior = scrollBehavior,
                scrollProgress = { scrollProgress },
                onHeroSpacerSized = { heroSpacerPx = it },
                isBlurEnabled = isBlurEnabled,
            )
        }
    }
}

@Composable
private fun AboutContent(
    innerPadding: PaddingValues,
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    scrollProgress: () -> Float,
    onHeroSpacerSized: (Int) -> Unit,
    isBlurEnabled: Boolean,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val isDark = isInDarkTheme()
    val blendColors = remember(isDark) {
        if (isDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    }
    // The hero's own blend stack: this is what turns the logo and the app name into a light
    // sweep instead of flat text — the same three entries KernelSU and HyperMusicCover use.
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val contentBackdrop = rememberBlurBackdrop()?.takeIf { isBlurEnabled }
    val deviceType = if (rememberIsWideScreen()) DeviceType.PAD else DeviceType.PHONE

    // One measurement drives both the spacer and the shader's `uBound`: the light band is
    // anchored to the bottom of the hero, so it has to know how tall that hero is.
    var heroHeightDp by remember { mutableStateOf(300.dp) }

    val scrollPadding = PaddingValues(
        top = innerPadding.calculateTopPadding(),
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )
    val heroPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 1) Light effect, bottom of the z-stack.
        BgEffectBackground(
            deviceType = deviceType,
            dynamicBackground = true,
            modifier = Modifier.matchParentSize(),
            bgModifier = if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier,
            isFullSize = true,
            alpha = { 1f - scrollProgress() },
        )

        // 2) Hero overlay — not interactive, so drags pass through to the list above it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = heroPadding.calculateTopPadding(),
                    start = heroPadding.calculateStartPadding(layoutDirection),
                    end = heroPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size ->
                    with(density) { heroHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgress() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    },
            ) {
                Image(
                    modifier = Modifier
                        .requiredSize(150.dp)
                        .then(
                            if (contentBackdrop != null) {
                                Modifier.textureBlur(
                                    backdrop = contentBackdrop,
                                    shape = RoundedCornerShape(0.dp),
                                    blurRadius = 150f,
                                    colors = BlurColors(blendColors = logoBlend),
                                    contentBlendMode = BlendMode.DstIn,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                    contentDescription = null,
                )
            }

            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress = ((scrollProgress() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (contentBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(0.dp),
                                blurRadius = 150f,
                                colors = BlurColors(blendColors = logoBlend),
                                contentBlendMode = BlendMode.DstIn,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = stringResource(R.string.app_name),
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionProgress = ((scrollProgress() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionProgress
                        scaleX = 1 - (versionProgress * 0.05f)
                        scaleY = 1 - (versionProgress * 0.05f)
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        // 3) The scroll container, on top of both.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .pageScroll(scrollBehavior),
            contentPadding = scrollPadding,
            overscrollEffect = null,
        ) {
            // Transparent spacer exactly one hero tall; the hero itself is the pinned Column
            // below this item, so the list scrolls under it and the two stay in sync by
            // construction.
            item(key = "heroSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            heroHeightDp + 40.dp + 52.dp + 126.dp,
                        )
                        .onSizeChanged { onHeroSpacerSized(it.height) },
                )
            }

            item(key = "links_title") {
                SmallTitle(text = stringResource(R.string.about_links))
            }
            item(key = "links_card") {
                AboutCard(backdrop = contentBackdrop, blendColors = blendColors) {
                    ArrowPreference(
                        title = stringResource(R.string.about_github),
                        summary = BuildConfig.REPOSITORY_URL,
                        onClick = { uriHandler.openUri(BuildConfig.REPOSITORY_URL) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_issues),
                        summary = stringResource(R.string.about_issues_summary),
                        onClick = { uriHandler.openUri("${BuildConfig.REPOSITORY_URL}/issues") },
                    )
                }
            }

            item(key = "open_source_title") {
                SmallTitle(text = stringResource(R.string.about_open_source))
            }
            item(key = "open_source_card") {
                AboutCard(backdrop = contentBackdrop, blendColors = blendColors) {
                    ArrowPreference(
                        title = stringResource(R.string.about_licenses),
                        summary = stringResource(R.string.about_licenses_summary),
                        onClick = { context.startActivity(Intent(context, LicenseActivity::class.java)) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_credits),
                        summary = stringResource(R.string.about_credits_summary),
                        onClick = { context.startActivity(Intent(context, CreditsActivity::class.java)) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.about_license_gpl),
                        summary = stringResource(R.string.about_license_gpl_summary),
                        onClick = { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.txt") },
                    )
                }
            }

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * One About card. Kept as a plain `Card` (not `groupedCardItems`) on purpose: this is a
 * static page whose cards carry `textureBlur`, and a blur has to wrap the WHOLE card — a
 * per-row blur would leave seams between the segments (docs/ui-guidelines.md lists exactly
 * this as the "纯静态文本卡" exception).
 */
@Composable
private fun AboutCard(
    backdrop: LayerBackdrop?,
    blendColors: List<BlendColorEntry>,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 60f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(blendColors = blendColors),
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        content()
    }
}
