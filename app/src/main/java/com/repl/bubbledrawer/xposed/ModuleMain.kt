package com.repl.bubbledrawer.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 module entry — structure mirrors the FlymeFreeform reference
 * (ModuleMain.kt:15-110): remote preferences at load time, per-package hook
 * install at package ready, hot reload rejected.
 *
 * Scope (META-INF/xposed/scope.list): com.android.systemui + com.miui.home.
 * The launcher is in scope because the corner stroke's counterparty lives there
 * (see LauncherInputObserver); it gets a read-only probe, never a behaviour change.
 */
class ModuleMain : XposedModule() {

    private var processName: String? = null
    private var configuration: android.content.SharedPreferences? = null
    private var hooksInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        if (param.processName != PROCESS_SYSTEM_UI && param.processName != PROCESS_LAUNCHER) {
            log(Log.WARN, TAG, "MODULE_SKIPPED_UNEXPECTED_PROCESS")
            return
        }
        // Exempt the @hide input classes the corner transport reflects into / links
        // against directly (android.view.InputMonitor + InputChannel +
        // InputEventReceiver, android.hardware.input.InputManagerGlobal). Prefixes
        // follow AndroidHiddenApiBypass.setHiddenApiExemptions' "L<fqcn>;" signature
        // form (Helper.java). MUST run before any of those classes is resolved — the
        // module only touches them later, in onPackageReady. The launcher process needs
        // them too: its probe resolves the very same hidden methods to hook them.
        val exempted = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/InputMonitor;",
                "Landroid/view/InputChannel;",
                "Landroid/view/InputEventReceiver;",
                "Landroid/view/InputEvent;",
                "Landroid/hardware/input/InputManagerGlobal;",
                "Landroid/hardware/input/InputManager;",
            )
        }.getOrDefault(false)
        log(if (exempted) Log.INFO else Log.WARN, TAG, "HIDDEN_API_EXEMPT=$exempted")
        if (param.processName == PROCESS_LAUNCHER) {
            // The launcher probe needs no configuration: it is read-only.
            log(Log.INFO, TAG, "MODULE_LAUNCHER_PROCESS_READY")
            return
        }
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
        if (processName == PROCESS_LAUNCHER && param.packageName == PROCESS_LAUNCHER) {
            hooksInstalled = true
            // Read-only: must never be able to take the launcher down.
            runCatching {
                LauncherInputObserver(this) { priority, message, error ->
                    log(priority, TAG, message, error)
                }.install(param.classLoader)
            }.onFailure { log(Log.WARN, TAG, "LAUNCHER_OBSERVER_INSTALL_FAILED", it) }
            return
        }
        val prefs = configuration ?: return
        if (processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI) {
            hooksInstalled = true
            SystemUiHookInstaller(this, prefs).install(param.classLoader)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.WARN, TAG, "HOT_RELOAD_REJECTED_RESTART_REQUIRED")
        return false
    }

    private fun disableForConfigurationFailure(code: String, exception: RuntimeException) {
        configuration = null
        log(Log.ERROR, TAG, code, exception)
    }

    private companion object {
        const val TAG = "BubbleDrawer"
        const val PROCESS_SYSTEM_UI = "com.android.systemui"
        const val PROCESS_LAUNCHER = "com.miui.home"
    }
}
