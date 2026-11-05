package com.repl.bubbledrawer.settings

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppNightMode
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.ui.component.PagerNavigationSpringSpec
import com.repl.bubbledrawer.ui.screen.features.FeaturesPageView
import com.repl.bubbledrawer.ui.screen.home.HomePageView
import com.repl.bubbledrawer.ui.screen.settings.SettingsPageView
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.xposed.SettingsStore
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Main entrance activity structured with 3 tabs following HyperMusicCover:
 * 1. 首页 (Home) - 状态监控与核心全局开关
 * 2. 功能 (Features) - 各核心独立功能专页分类中心
 * 3. 设置 (Settings) - 主题、视觉效果、备份管理与关于页入口
 *
 * 关于 is NOT a tab: it is a secondary Activity opened from 设置, exactly like 外观与主题 and
 * 模块运行诊断. Its hero + light-effect layout does not belong in a horizontally-swiped pager,
 * and the bottom bar stays reserved for the three pages used every day.
 */
class MainActivity : ComponentActivity() {

    private var settingsEpoch by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        settingsEpoch++
    }

    override fun onStop() {
        super.onStop()
        AppNightMode.apply(this, AppSettings.load(this).themeMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val actions = StoreSettingsActions(applicationContext)
        val stateFlow = SettingsStore.observe(applicationContext)

        setContent {
            val appSettings = remember(settingsEpoch) { AppSettings.load(this@MainActivity) }
            val themeMode = remember(appSettings.themeMode) {
                try {
                    ColorSchemeMode.valueOf(appSettings.themeMode)
                } catch (_: Exception) {
                    ColorSchemeMode.System
                }
            }

            BubbleDrawerTheme(themeMode = themeMode) {
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = SettingsStore.currentState(applicationContext),
                )

                MainContent(
                    state = state,
                    actions = actions,
                    isFloatingNavbar = appSettings.isFloatingNavbar,
                    isBlurEnabled = appSettings.isBlurEnabled,
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    state: com.repl.bubbledrawer.xposed.SettingsUiState,
    actions: SettingsActions,
    isFloatingNavbar: Boolean,
    isBlurEnabled: Boolean,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    val items = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.tab_settings),
    )
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings)

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    fun goToPage(index: Int) {
        if (index == selectedIndex) return
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize
                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = PagerNavigationSpringSpec,
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }
                if (pagerState.currentPage != index) {
                    pagerState.scrollToPage(index)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    // Back goes to first tab before leaving app
    BackHandler(enabled = selectedIndex != 0) {
        goToPage(0)
    }

    Scaffold(
        bottomBar = {
            BottomBar(
                isFloating = isFloatingNavbar,
                items = items,
                icons = icons,
                selectedIndex = selectedIndex,
                backdrop = backdrop,
                blurActive = isBlurEnabled,
                onItemSelected = { goToPage(it) },
            )
        },
    ) { globalPadding ->
        val navBarHeight = globalPadding.calculateBottomPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isBlurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(surfaceColor),
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> HomePageView(
                        state = state,
                        actions = actions,
                        extraBottomPadding = navBarHeight,
                    )
                    1 -> FeaturesPageView(
                        extraBottomPadding = navBarHeight,
                    )
                    2 -> SettingsPageView(
                        extraBottomPadding = navBarHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    isFloating: Boolean,
    items: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
) {
    if (isFloating) {
        val floatingBarColor =
            if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
        val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)

        FloatingNavigationBar(
            modifier = if (blurActive && backdrop != null) {
                Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = floatingBarShape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.4f)),
                        ),
                    ),
                )
            } else {
                Modifier
            },
            color = floatingBarColor,
        ) {
            items.forEachIndexed { index, label ->
                FloatingNavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onItemSelected(index) },
                    icon = icons[index],
                    label = label,
                    enabled = true,
                )
            }
        }
    } else {
        val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (blurActive && backdrop != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = 25f,
                            colors = BlurDefaults.blurColors(
                                blendColors = listOf(
                                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.5f)),
                                ),
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .background(barColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            NavigationBar(color = barColor) {
                items.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = icons[index],
                        label = label,
                        enabled = true,
                    )
                }
            }
        }
    }
}
