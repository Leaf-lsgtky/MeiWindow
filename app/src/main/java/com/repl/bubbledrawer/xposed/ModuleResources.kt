package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

/**
 * Resolves THIS module's resources inside the hooked process so the fan UI can
 * inflate our layouts/icons (R.* ids) — SystemUI's own Context cannot see them.
 *
 * createPackageContext(CONTEXT_INCLUDE_CODE) is the standard cross-app resource
 * path (the system hands out the APK assets; only /data DIRS are uid-private —
 * and FanContext below keeps storage on SystemUI anyway).
 *
 * Identity rule: ViewRootImpl passes the creating Context's package name WITH
 * our process uid to WindowManagerService, which verifies the package belongs
 * to the uid — so the fan Context MUST keep SystemUI's package/services/storage
 * and borrow ONLY resources + classloader from the module.
 */
object ModuleResources {

    const val MODULE_PACKAGE = "com.repl.bubbledrawer"

    @Volatile
    private var cached: Context? = null

    /** Module package context (resources + code), or null if PM cannot resolve it. */
    fun packageContext(host: Context): Context? = cached ?: runCatching {
        host.createPackageContext(
            MODULE_PACKAGE,
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
        )
    }.getOrNull()?.also { cached = it }

    /**
     * Fan-facing Context: resources/classloader from the module package context,
     * window identity (package name → services → WMS attribution) and file
     * storage (getSharedPreferences = the mirrored bubble_runtime) kept with
     * SystemUI.
     */
    class FanContext(base: Context, private val pkg: Context) : ContextWrapper(base) {
        override fun getResources(): Resources = pkg.resources
        override fun getClassLoader(): ClassLoader =
            runCatching { ModuleMain::class.java.classLoader }
                .getOrNull() ?: pkg.classLoader
    }
}
