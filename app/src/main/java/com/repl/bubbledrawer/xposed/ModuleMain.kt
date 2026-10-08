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

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
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
                "Landroid/view/InputMonitor;",
                "Landroid/view/InputChannel;",
                "Landroid/view/InputEventReceiver;",
                "Landroid/view/InputEvent;",
                "Landroid/hardware/input/InputManagerGlobal;",
                "Landroid/hardware/input/InputManager;",
                // getInputMethodWindowVisibleHeight() — read once per corner DOWN to decide
                // whether the keyboard owns that corner right now (see imeVisibleHeightPx).
                "Landroid/view/inputmethod/InputMethodManager;",
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
        val prefs = configuration ?: return
        if (processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI) {
            hooksInstalled = true
            systemUiClassLoader = param.classLoader
            SystemUiHookInstaller(this, prefs).also { systemUiInstaller = it }.install(param.classLoader)
        }
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
        val loader = systemUiClassLoader
        runCatching {
            param.setSavedInstanceState(arrayOf(processName, context, loader))
        }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_STATE_SAVE_FAILED", it) }
        runCatching { systemUiInstaller?.dispose() }
        systemUiInstaller = null
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
            }.onFailure { log(Log.WARN, TAG, "HOT_RELOAD_REINSTALL_FAILED", it) }
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
    }
}
