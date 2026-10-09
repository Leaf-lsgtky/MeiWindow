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
import com.repl.bubbledrawer.launch.ConfigurableLaunchStrategy
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Port of `SlideLaunchAppSettings` — the 应用 tab slice, now a Compose host
 * ([PinManageScreen] with [Chrome.ACTIVITY]). The 管理/完成 toggle moved from the
 * options menu into the TopAppBar actions (menu_title_manager.xml is gone); back is
 * MiuixIcons.Back per docs/ui-guidelines.md.
 */
class PinManageActivity : ComponentActivity() {

    private val repo by lazy { AppGraph.repo ?: AppRepository(this).also { AppGraph.repo = it } }
    private val pinStore by lazy {
        AppGraph.pinStore ?: PinStore(
            SettingsStore.pinBackend(this),
        ).also { AppGraph.pinStore = it }
    }

    private var pinsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onDestroy() {
        pinsPrefsListener?.let { listener ->
            runCatching { RemoteBridge.local(this).unregisterOnSharedPreferenceChangeListener(listener) }
        }
        pinsPrefsListener = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        )
        model.reload()
        // Pins can also be edited in the fan's 更多 panel (SystemUI), which hands them over as a
        // broadcast into this process. Watch the mirror file so this page follows live instead
        // of showing the list as it was when the Activity opened.
        pinsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.repl.bubbledrawer.data.PrefsPinBackend.KEY) {
                runOnUiThread { model.refreshPins() }
            }
        }.also { listener ->
            runCatching { RemoteBridge.local(this).registerOnSharedPreferenceChangeListener(listener) }
        }
        setContent {
            BubbleDrawerTheme {
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
                            // carries start_windowmode=true)
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
}
