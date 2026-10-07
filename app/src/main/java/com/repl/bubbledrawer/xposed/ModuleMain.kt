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
 * Scope (META-INF/xposed/scope.list): com.android.systemui only.
 */
class ModuleMain : XposedModule() {

    private var processName: String? = null
    private var configuration: android.content.SharedPreferences? = null
    private var hooksInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        if (param.processName != PROCESS_SYSTEM_UI) {
            log(Log.WARN, TAG, "MODULE_SKIPPED_UNEXPECTED_PROCESS")
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
        val prefs = configuration ?: return
        if (hooksInstalled) return
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
    }
}
