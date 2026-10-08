package com.repl.bubbledrawer.pin

import android.content.Context
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop

/**
 * View-tree owners for a [ComposeView] that lives in a window with NO Activity — the
 * SystemUI overlay panel. Compose needs a LifecycleOwner (recomposer cancellation), a
 * ViewModelStoreOwner and a SavedStateRegistryOwner (`rememberSaveable` inside miuix's
 * TopAppBar state); without them the first composition throws. All three are the plain
 * java setters on the view, driven by [resume]/[destroy] from the host window cycle.
 */
class OverlayComposeOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
    }

    /** Window is up: composition runs, rememberSaveable can restore. */
    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Window is down: the recomposer is cancelled and the store is cleared. */
    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    fun attachTo(view: View) {
        setViewTreeOwner(
            view,
            className = "androidx.lifecycle.ViewTreeLifecycleOwner",
            owner = this as LifecycleOwner,
        )
        setViewTreeOwner(
            view,
            className = "androidx.lifecycle.ViewTreeViewModelStoreOwner",
            owner = this as ViewModelStoreOwner,
        )
        setViewTreeOwner(
            view,
            className = "androidx.savedstate.ViewTreeSavedStateRegistryOwner",
            owner = this as SavedStateRegistryOwner,
        )
    }


    /**
     * Reflective `ViewTreeXxx.set(view, owner)`.
     *
     * Why reflection: the three ViewTree setter classes are Java classes in
     * lifecycle-runtime / lifecycle-viewmodel / savedstate, but the Kotlin compile
     * classpath resolves them from an artifact variant where they are not visible
     * (AGP 9 built-in Kotlin + KMP metadata) — verified by a minimal import probe that
     * failed against the same classpath where javap finds the class in the aar. At
     * RUNTIME the real classes are present (they ship inside this APK), so reflection
     * is not a behavior change, only a link-time workaround. Same trick the module
     * already uses for @hide platform input classes (compileOnly(:hiddenapi)).
     */
    private fun setViewTreeOwner(view: View, className: String, owner: Any) {
        runCatching {
            val holder = Class.forName(className)
            val ownerType = holder.methods.firstOrNull { it.name == "set" }?.parameterTypes?.get(1)
                ?: throw NoSuchMethodException("$className.set")
            val setter = holder.getMethod("set", View::class.java, ownerType)
            setter.invoke(null, view, owner)
            // VERIFY the write landed by reading back through the same class's `get`. If
            // set/get disagree (two copies of this class in one process → different tag
            // ids), fail loudly HERE instead of inside ComposeView.attach later.
            val getter = holder.methods.firstOrNull { it.name == "get" }
            val readBack = getter?.invoke(null, view)
            android.util.Log.i(
                "BubbleDrawer",
                "VTREE $className ok readback=" + (readBack != null) +
                    " sameOwner=" + (readBack === owner),
            )
            if (readBack !== owner) {
                android.util.Log.w(
                    "BubbleDrawer",
                    "VTREE MISMATCH $className: set/get disagree — duplicate class copies " +
                        "with different tag ids in this process",
                )
            }
        }.onFailure {
            android.util.Log.w("BubbleDrawer", "ViewTree owner setup failed: $className", it)
        }
    }
}

/**
 * The 更多 panel content: the SAME [PinManageScreen] the Activity hosts, inside the
 * SystemUI overlay window (方案 B). Solid background by design — the panel is a floating
 * window, so there is nothing behind it to blur (docs/ui-guidelines.md: blur only where a
 * backdrop can actually be sampled).
 *
 * Font/display scaling note: the caller wraps the Context so the panel ignores the
 * system font scale; [BubbleDrawerTheme] still resolves the same palette.
 */
object PanelContentFactory {
    /**
     * Builds the panel content and its view-tree owners.
     *
     * @param windowRoot the view that will be added to WindowManager as the window root
     *   (the panel FrameLayout). Compose's `updateAutoCreatedComposeViewContext` looks up
     *   the owners on **`findViewTreeComposeViewRoot(this)`** — the window-root view, NOT
     *   the ComposeView itself — so the owners MUST be tagged on the root too. Setting
     *   them only on the ComposeView reads back fine (same tag id) and still crashes with
     *   "ViewTreeLifecycleOwner not found from FrameLayout" on first attach.
     */
    fun create(
        windowRoot: android.view.ViewGroup,
        context: Context,
        model: PinManageModel,
        iconDp: Int,
        textSp: Int,
        onLaunch: (com.repl.bubbledrawer.pinyin.BubbleApp) -> Unit,
        onClose: () -> Unit,
    ): ComposeView {
        val owners = OverlayComposeOwners()
        // Resume BEFORE the first composition: the recomposer reads the lifecycle state on
        // start, and a CREATED-only owner would postpone every effect to attach time.
        owners.resume()
        val view = ComposeView(context)
        // Tag BOTH ends of the lookup: the root (what compose queries at attach) and the
        // ComposeView (its own ensureCompositionCreated path).
        owners.attachTo(windowRoot)
        owners.attachTo(view)
        view.setContent {
            BubbleDrawerTheme {
                // LocalContext: the themed module-resources context, so R.string/R.color
                // inside the panel resolve to the MODULE's values.
                // LocalView: deliberately NOT overridden — compose provides its own
                // internal root view here, and AndroidView casts it to the node Owner;
                // supplying the outer ComposeView crashes inside viewinterop.
                CompositionLocalProvider(
                    LocalContext provides context,
                ) {
                    DisposableEffect(Unit) {
                        onDispose { owners.destroy() }
                    }
                    val backdrop = rememberBlurBackdrop()
                    PinManageScreen(
                        model = model,
                        chrome = Chrome.PANEL,
                        iconDp = iconDp,
                        textSp = textSp,
                        onLaunch = onLaunch,
                        onClose = onClose,
                        onToggleManage = model::toggleManageMode,
                        manageLabel = manageLabelOf(model),
                        backdrop = backdrop,
                    )
                }
            }
        }
        return view
    }

    @androidx.compose.runtime.Composable
    private fun manageLabelOf(model: PinManageModel): String =
        androidx.compose.ui.res.stringResource(
            if (model.manageMode) {
                com.repl.bubbledrawer.R.string.action_app_manager_complate
            } else {
                com.repl.bubbledrawer.R.string.action_app_manager
            },
        )
}
