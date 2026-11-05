package com.repl.bubbledrawer.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 module entry — structure mirrors the FlymeFreeform reference
 * (ModuleMain.kt:15-110): remote preferences at load time, per-package hook
 * install at package ready, hot reload accepted.
 *
 * Scope (META-INF/xposed/scope.list): com.android.systemui only.
 *
 * The launcher-side work was tried and removed (commit "chore: remove the launcher-side
 * hooks and the system_server region experiment"): Android 17 MiuiHome is forked by Xiaomi's
 * own /system_ext/bin/hyos_spawner instead of the ART zygote, so no LSPosed Java hook can
 * enter it (see docs/input-hook-architecture.md §1 and §3.4), and the system_server variant
 * of "cooperate with the launcher" (giving its swipe-up monitor a corner-cut touchable
 * region) broke MiuiHome's HOME gesture everywhere. What is left is the SystemUI half, which
 * is the part that actually works: a DO_NOT_PILFER gesture monitor, DOWN-time ownership
 * inside the bottom strip, and a sector-shaped corner trigger.
 */
class ModuleMain : XposedModule() {

    private var processName: String? = null
    private var configuration: android.content.SharedPreferences? = null
    private var hooksInstalled = false
    private var systemUiInstaller: SystemUiHookInstaller? = null
    private var systemUiClassLoader: ClassLoader? = null

    /**
     * 「轻量打开」两侧的 hook 安装器。**必须持有引用**：它们的 prefs 监听器是重建缓存的入口，
     * 而 SharedPreferences 对监听器只持弱引用 —— 被 GC 掉就等于开关不再生效（且毫无征兆）。
     */
    private var lightOpenSystemUi: LightOpenSystemUi? = null
    private var lightOpenGate: LightOpenGate? = null
    private var launcherMonitorRegion: LauncherMonitorRegion? = null

    /** system_server 的 classloader（热重载后重建闸门钩子要用）。 */
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        if (param.processName == PROCESS_SYSTEM) {
            // system_server：安装点在 onSystemServerStarting（那里才拿得到它的 classloader）。
            // 这里不做 hidden-API 豁免 —— 它本身就是平台进程。
            log(Log.INFO, TAG, "MODULE_LOADED_SYSTEM_SERVER")
            return
        }
        if (param.processName != PROCESS_SYSTEM_UI) {
            log(Log.WARN, TAG, "MODULE_SKIPPED_UNEXPECTED_PROCESS")
            return
        }
        // Exempt the @hide input classes the corner transport reflects into / links
        // against directly (android.view.InputMonitor + InputChannel +
        // InputEventReceiver, android.hardware.input.InputManagerGlobal). Prefixes follow
        // AndroidHiddenApiBypass.setHiddenApiExemptions' "L<fqcn>;" signature form
        // (Helper.java). MUST run before any of those classes is resolved.
        val exempted = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "",
                "Landroid/view/InputMonitor;",
                "Landroid/view/InputChannel;",
                "Landroid/view/InputEventReceiver;",
                "Landroid/view/InputEvent;",
                "Landroid/hardware/input/InputManagerGlobal;",
                "Landroid/hardware/input/InputManager;",
                "Landroid/view/inputmethod/InputMethodManager;",
                "Landroid/app/ActivityThread;",
                "Landroid/content/pm/IPackageManager;",
                "Landroid/util/MiuiFreeformLaunchConfig;",
                "Landroid/util/MiuiMultiWindowUtils;",
                "Landroid/app/ActivityOptions;",
                "Lcom/android/wm/shell/",
                "Lcom/android/systemui/animation/",
                "Lcom/android/systemui/qs/",
                "Lcom/android/systemui/statusbar/phone/",
            )
        }.getOrDefault(false)
        log(if (exempted) Log.INFO else Log.WARN, TAG, "HIDDEN_API_EXEMPT=$exempted")
        val properties = getFrameworkProperties()
        if (properties and XposedInterface.PROP_CAP_REMOTE == 0L) {
            log(Log.WARN, TAG, "MODULE_CONFIG_REMOTE_UNAVAILABLE")
            return
        }
        try {
            configuration = getRemotePreferences(RemotePrefs.GROUP)
        } catch (exception: UnsupportedOperationException) {
            disableForConfigurationFailure("MODULE_CONFIG_UNSUPPORTED", exception)
        } catch (exception: SecurityException) {
            disableForConfigurationFailure("MODULE_CONFIG_ACCESS_DENIED", exception)
        } catch (exception: IllegalStateException) {
            disableForConfigurationFailure("MODULE_CONFIG_NOT_READY", exception)
        } catch (exception: RuntimeException) {
            disableForConfigurationFailure("MODULE_CONFIG_FAILURE", exception)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (hooksInstalled) return
        val prefs = configuration ?: runCatching { getRemotePreferences(RemotePrefs.GROUP) }.getOrNull()?.also {
            configuration = it
        } ?: return
        if (processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI) {
            hooksInstalled = true
            systemUiClassLoader = param.classLoader
            SystemUiHookInstaller(this, prefs).also { systemUiInstaller = it }.install(param.classLoader)
            // 轻量打开的两项（点通知 / 控制中心长按）都在 SystemUI 进程里做 —— 系统闸门对
            // SystemUI 发起的启动直接早退，见 LightOpenSystemUi 的类注释。
            runCatching {
                LightOpenSystemUi(this, prefs) { systemUiInstaller?.capturedContext() }
                    .also { lightOpenSystemUi = it }.install(param.classLoader)
            }.onFailure { log(Log.WARN, TAG, "LIGHT_OPEN_SYSTEMUI_INSTALL_FAILED", it) }
        }
    }

    /**
     * API-102 里 system_server 的入口（`onPackageReady` 不会为它触发）。
     *
     * 这里只装一个钩子：`MiuiFreeformServiceImpl.startActivityByFreeForm` 的返回值覆盖
     * （分享至应用 / 文本操作 / 应用间跳转）。
     *
     * **注意**：真机上 LSPosed 会照常给 system_server 投递热重载（日志
     * `HOT_RELOAD_ACCEPTED process=system`），但本回调不会再次触发 —— 重建在
     * [onHotReloaded] 里做（见那里的注释：漏了这一步，闸门钩子会在每次 `adb install` 之后
     * 静默消失到下次重启）。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        processName = PROCESS_SYSTEM
        systemServerClassLoader = param.classLoader
        val prefs = configuration ?: runCatching { getRemotePreferences(RemotePrefs.GROUP) }
            .onFailure { log(Log.WARN, TAG, "LIGHT_OPEN_GATE_CONFIG_FAILED", it) }
            .getOrNull()
        if (prefs == null) {
            log(Log.WARN, TAG, "LIGHT_OPEN_GATE_NO_CONFIG")
            return
        }
        configuration = prefs
        runCatching {
            LightOpenGate(this, prefs).also { lightOpenGate = it }.install(param.classLoader)
        }.onFailure { log(Log.WARN, TAG, "LIGHT_OPEN_GATE_INSTALL_FAILED", it) }
        // 小窗尺寸/居中/避让：system_server 里解析出来的小窗启动（轻量打开闸门、跳板 Activity
        // 链式继承）也在这里调 MiuiMultiWindowUtils，只装在 SystemUI 里会漏掉它们。
        runCatching {
            com.repl.bubbledrawer.launch.FlymeFreeformController.installSystemServerHooks(this, prefs, param.classLoader)
        }.onFailure { log(Log.WARN, TAG, "FREEFORM_SYSTEM_SERVER_HOOKS_FAILED", it) }
        runCatching {
            LauncherMonitorRegion(this, prefs) { pr, msg, t ->
                if (t != null) log(pr, TAG, msg, t) else log(pr, TAG, msg)
            }.also { launcherMonitorRegion = it }.install(param.classLoader)
        }.onFailure { log(Log.WARN, TAG, "ARBITER_INSTALL_FAILED", it) }
    }

    /**
     * API-102 HOT RELOAD — the whole point of `autoHotReload=true` in module.prop: after
     * `adb install -r` LSPosed swaps the module classes without a reboot or a process
     * restart. Our module owns live objects (the gesture monitor and its input channel, the
     * SPY-view fallback windows, the fan's full-screen windows), so they are torn down here
     * and a fresh instance is built in [onHotReloaded]; the framework drops the old hooks for
     * us (we also unhook ours explicitly, since a stale handle would otherwise keep calling
     * into the old monitor).
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "HOT_RELOAD_ACCEPTED process=$processName")
        // Hand what the next generation cannot rebuild on its own to the framework: the
        // Application Context (onCreate will not run again) and this process' identity.
        val context = runCatching { systemUiInstaller?.capturedContext() }.getOrNull()
        val loader = systemUiClassLoader ?: systemServerClassLoader
        runCatching {
            param.setSavedInstanceState(arrayOf(processName, context, loader))
        }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_STATE_SAVE_FAILED", it) }
        runCatching { systemUiInstaller?.dispose() }
        runCatching { launcherMonitorRegion?.dispose() }
        systemUiInstaller = null
        lightOpenSystemUi = null
        lightOpenGate = null
        launcherMonitorRegion = null
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        var context: android.content.Context? = null
        var loader: ClassLoader? = null
        runCatching {
            var unhooked = 0
            for (handle in param.oldHookHandles) {
                if (handle.id?.startsWith(HOOK_ID_PREFIX) == true) {
                    runCatching { handle.unhook() }
                    unhooked++
                }
            }
            log(Log.INFO, TAG, "HOT_RELOADED process=${param.processName} unhooked=$unhooked")
        }
        runCatching {
            // The API types the saved state as Object; the module stores an Object[] in it.
            val saved = param.savedInstanceState as? Array<*>
            if (saved != null) {
                if (saved.isNotEmpty()) processName = saved[0] as? String
                if (saved.size > 1) context = saved[1] as? android.content.Context
                if (saved.size > 2) loader = saved[2] as? ClassLoader
            }
        }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_STATE_RESTORE_FAILED", it) }
        if (processName == null) processName = param.processName

        // Rebuild the runtime the teardown removed — without it the process would be left with
        // no monitor at all after a reload (the old one is disposed, onCreate never runs again).
        if (processName == PROCESS_SYSTEM_UI) {
            runCatching {
                val prefs = configuration ?: getRemotePreferences(RemotePrefs.GROUP).also {
                    configuration = it
                }
                val ctx = context ?: systemContext()
                if (ctx == null) {
                    log(Log.WARN, TAG, "HOT_RELOAD_NO_CONTEXT")
                    return@runCatching
                }
                if (loader != null) systemUiClassLoader = loader
                SystemUiHookInstaller(this, prefs).also { systemUiInstaller = it }.reinstall(ctx)
                runCatching {
                    LightOpenSystemUi(this, prefs) { systemUiInstaller?.capturedContext() }
                        .also { lightOpenSystemUi = it }
                        .install(loader ?: javaClass.classLoader)
                }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_LIGHT_OPEN_FAILED", it) }
            }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_REINSTALL_FAILED", it) }
        }

        // system_server：LSPosed 也会给它投递热重载，但 onSystemServerStarting 不会再次触发 ——
        // 少了这一步，闸门钩子会在**每次 `adb install -r`** 之后被卸掉并且再也装不回来
        // （真机踩到：HOT_RELOADED process=system unhooked=1，之后所有跳转都回到原生行为），
        // 只有重启才能恢复。classloader 用启动时存下的那个，取不到就退回系统 classloader
        // （system_server 的框架类都在 boot classpath 上，够用）。
        if (processName == PROCESS_SYSTEM) {
            val effectiveLoader = loader ?: systemServerClassLoader ?: ClassLoader.getSystemClassLoader()
            runCatching {
                val prefs = configuration ?: getRemotePreferences(RemotePrefs.GROUP).also {
                    configuration = it
                }
                LightOpenGate(this, prefs).also { lightOpenGate = it }
                    .install(effectiveLoader)
                runCatching {
                    com.repl.bubbledrawer.launch.FlymeFreeformController
                        .installSystemServerHooks(this, prefs, effectiveLoader)
                }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_FREEFORM_SYSTEM_SERVER_HOOKS_FAILED", it) }
                LauncherMonitorRegion(this, prefs) { pr, msg, t ->
                    if (t != null) log(pr, TAG, msg, t) else log(pr, TAG, msg)
                }.also { launcherMonitorRegion = it }.install(effectiveLoader)
            }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_LIGHT_OPEN_GATE_FAILED", it) }
        }
    }

    /** Last-resort Context for a reloaded module instance (system Context is enough for a monitor). */
    private fun systemContext(): android.content.Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? android.content.Context
    }.getOrNull()

    private fun disableForConfigurationFailure(code: String, exception: RuntimeException) {
        configuration = null
        log(Log.ERROR, TAG, code, exception)
    }

    private companion object {
        const val TAG = "BubbleDrawer"

        /** every hook this module registers is named with this prefix (unhook filter) */
        const val HOOK_ID_PREFIX = "bubbledrawer."

        const val PROCESS_SYSTEM_UI = "com.android.systemui"

        /** system_server 在这个 API 里叫 "system"（见 commit 423c937 的 system_server 实验）。 */
        const val PROCESS_SYSTEM = "system"
    }
}
