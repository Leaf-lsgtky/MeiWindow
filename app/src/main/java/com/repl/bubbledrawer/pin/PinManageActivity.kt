package com.repl.bubbledrawer.pin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.AppGraph
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.launch.ConfigurableLaunchStrategy
import com.repl.bubbledrawer.ui.component.MiuixSearchBar
import com.repl.bubbledrawer.ui.component.SearchCloseButton
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.barColor
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.xposed.RemoteBridge
import com.repl.bubbledrawer.xposed.SettingsStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.repl.bubbledrawer.shortcut.ShortcutRepository

/**
 * Port of `SlideLaunchAppSettings` — the 应用 tab slice, now a Compose host
 * ([PinManageScreen] with [Chrome.ACTIVITY]). The 管理/完成 toggle moved from the
 * options menu into the TopAppBar actions (menu_title_manager.xml is gone); back is
 * MiuixIcons.Back per docs/ui-guidelines.md.
 */
class PinManageActivity : ComponentActivity() {

    private val repo by lazy {
        AppGraph.repo ?: AppRepository(
            this,
            cloneBadge = { SettingsStore.snapshot(this).cloneBadge },
        ).also { AppGraph.repo = it }
    }
    private val pinStore by lazy {
        AppGraph.pinStore ?: PinStore(
            SettingsStore.pinBackend(this),
        ).also { AppGraph.pinStore = it }
    }

    private var pinManageModel: PinManageModel? = null
    private var pinsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var shortcutCacheListener: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        // 从后台或扇形「更多」面板返回时主动刷新一次，确保与面板内的编辑即时同步。
        pinManageModel?.refreshPins()
    }

    override fun onDestroy() {
        pinsPrefsListener?.let { listener ->
            runCatching { RemoteBridge.local(this).unregisterOnSharedPreferenceChangeListener(listener) }
            runCatching { RemoteBridge.remote?.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        pinsPrefsListener = null
        shortcutCacheListener?.let { listener ->
            com.repl.bubbledrawer.shortcut.ShortcutCacheStore.removeListener(listener)
        }
        shortcutCacheListener = null
        pinManageModel?.release()
        pinManageModel = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 页签取自 Intent（面板的加号会指定「功能」页）：Activity 现在是导出的，别让外面塞进来的
        // 任意整数直达 PagerState 的 initialPage。
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 1)
        val model = PinManageModel(
            loadApps = { repo.all() },
            pinsOf = { pinStore.pins() },
            onOrderChange = { order -> pinStore.setPins(order) },
            recommendConfig = {
                val snap = SettingsStore.snapshot(this@PinManageActivity)
                snap.recommendEnabled to snap.recommendCount
            },
            loadRecommendations = { all, pinned, count ->
                com.repl.bubbledrawer.data.predict.AppPredictor.getRecommendations(
                    this@PinManageActivity, all, pinned, count,
                )
            },
            // 分身角标：图标本身取主空间那张，这一项只决定要不要叠角标（与面板同源设置）。
            cloneBadge = SettingsStore.snapshot(this@PinManageActivity).cloneBadge,
            loadShortcuts = { onProgress ->
                ShortcutRepository.loadShortcuts(this@PinManageActivity, onProgress)
            },
            initialTab = initialTab,
            functionPinsOf = { com.repl.bubbledrawer.shortcut.FunctionPinStore.getPins(this@PinManageActivity) },
            // 聚合支付不在扫描缓存里（内建功能）：已选区的解析要能凭 pinKey 认出它，详情从内建条目来。
            pinnedFunctionItems = {
                listOf(com.repl.bubbledrawer.shortcut.AggregatePayment.entryIn(this@PinManageActivity))
            },
            onFunctionOrderChange = { com.repl.bubbledrawer.shortcut.FunctionPinStore.setPins(this@PinManageActivity, it) },
            pinnedOrderOf = { com.repl.bubbledrawer.pin.PinnedOrderStore.get(this@PinManageActivity) },
            onPinnedOrderChange = { com.repl.bubbledrawer.pin.PinnedOrderStore.set(this@PinManageActivity, it) },
        )
        pinManageModel = model
        model.reload()
        // Pins can also be edited in the fan's 更多 panel (SystemUI), which hands them over via
        // ContentProvider / broadcast into this process. Watch both value and rev keys so even
        // same-content re-stamps or remote mirror updates refresh this page live.
        pinsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.repl.bubbledrawer.data.PrefsPinBackend.KEY ||
                key == com.repl.bubbledrawer.data.PrefsPinBackend.KEY_REV ||
                key == com.repl.bubbledrawer.shortcut.FunctionPinStore.KEY_PINNED_FUNCTIONS ||
                key == com.repl.bubbledrawer.shortcut.FunctionPinStore.PREFS_KEY_REV ||
                key == com.repl.bubbledrawer.pin.PinnedOrderStore.KEY_PINNED_ORDER ||
                key == com.repl.bubbledrawer.pin.PinnedOrderStore.PREFS_KEY_REV
            ) {
                runOnUiThread { model.refreshPins() }
            }
        }.also { listener ->
            runCatching { RemoteBridge.local(this).registerOnSharedPreferenceChangeListener(listener) }
            runCatching { RemoteBridge.remote?.registerOnSharedPreferenceChangeListener(listener) }
        }

        // 监听来自 SystemUI 上报的完整快捷方式更新
        val cacheListener = {
            runOnUiThread { model.refreshShortcuts() }
        }
        shortcutCacheListener = cacheListener
        com.repl.bubbledrawer.shortcut.ShortcutCacheStore.addListener(cacheListener)
        val appSettings = AppSettings.load(this)
        val themeMode = runCatching { top.yukonga.miuix.kmp.theme.ColorSchemeMode.valueOf(appSettings.themeMode) }
            .getOrDefault(top.yukonga.miuix.kmp.theme.ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                val scrollBehavior = MiuixScrollBehavior()
                val backdrop = rememberBlurBackdrop()
                val manageLabel = stringResourceFor(model)
                BackHandler(enabled = model.isSearching) {
                    model.cancelSearch()
                }
                Scaffold(
                    topBar = {
                        BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                            if (model.isSearching) {
                                MiuixSearchBar(
                                    modifier = Modifier
                                        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    insideMargin = DpSize(12.dp, 0.dp),
                                    inputField = {
                                        InputField(
                                            query = model.searchQuery,
                                            onQueryChange = { model.searchQuery = it },
                                            onSearch = { },
                                            expanded = true,
                                            onExpandedChange = { expanded ->
                                                if (!expanded) model.cancelSearch()
                                            },
                                            label = stringResource(R.string.search_hint),
                                        )
                                    },
                                    expanded = true,
                                    outsideEndAction = {
                                        SearchCloseButton(onClick = model::cancelSearch)
                                    },
                                )
                            } else {
                                SmallTopAppBar(
                                    title = getString(R.string.slide_launch_app_settings_title),
                                    color = barColor(backdrop != null),
                                    scrollBehavior = scrollBehavior,
                                    navigationIcon = {
                                        IconButton(onClick = { finish() }) {
                                            Icon(
                                                 imageVector = MiuixIcons.Back,
                                                 contentDescription = getString(R.string.back),
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = model::startSearch,
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.Search,
                                                contentDescription = stringResource(R.string.search),
                                                tint = MiuixTheme.colorScheme.onSurface,
                                            )
                                        }
                                        IconButton(
                                            onClick = model::toggleManageMode,
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.ListView,
                                                contentDescription = manageLabel,
                                                tint = if (model.manageMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    PinManageScreen(
                        model = model,
                        chrome = Chrome.ACTIVITY,
                        iconDp = 0,
                        textSp = 0,
                        onLaunch = { app ->
                            // ORIGINAL: view-mode tap launches the app (AbstractC2806s.m9105e
                            // carries start_windowmode=true).
                            //
                            // user 2026-10-17: 这里原先把 MIUI 的 freeform ActivityOptions 直接塞进
                            // startActivity，第三方进程过不了 checkAuthority，加上安全中心的
                            // 「xx 想要打开 xx」拦截框，那次小窗落到了**魅窗自己**身上（点应用 →
                            // 魅窗变成小窗，目标反而没小窗）。现在改走系统闸门：
                            // ConfigurableLaunchStrategy → GateLaunchStrategy（Intent 带标记，
                            // 由 system_server 用 ROM 自己的小窗参数落窗）。
                            ConfigurableLaunchStrategy(this@PinManageActivity).launch(this@PinManageActivity, app)
                            LaunchCountStore.increment(this@PinManageActivity, app.packageName, app.userId)
                            com.repl.bubbledrawer.data.predict.AppPredictor.recordLaunch(this@PinManageActivity, app.packageName, app.userId)
                        },
                        onClose = null,
                        onToggleManage = model::toggleManageMode,
                        manageLabel = manageLabel,
                        topPadding = innerPadding,
                        backdrop = backdrop,
                        scrollBehavior = scrollBehavior,
                        onLaunchShortcut = { item ->
                            when {
                                // 聚合支付：App 进程没有联系人条，发广播让 SystemUI 把条切到支付模式
                                // （FanHost 里有对应的导出广播，带发送方校验）。
                                com.repl.bubbledrawer.shortcut.AggregatePayment.isEntry(item) -> {
                                    com.repl.bubbledrawer.shortcut.AggregatePayment.requestShow(
                                        this@PinManageActivity,
                                    )
                                }
                                else -> {
                                    // 与小窗启动同一条规则：App 进程走「系统闸门」（见上面 onLaunch 的注释），
                                    // 不再自己拼 freeform ActivityOptions。
                                    ShortcutRepository.launchShortcut(this@PinManageActivity, item, viaGate = true)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun stringResourceFor(model: PinManageModel): String =
        androidx.compose.ui.res.stringResource(
            if (model.manageMode) R.string.action_app_manager_complate else R.string.action_app_manager,
        )

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
    }
}
