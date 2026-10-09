package com.repl.bubbledrawer

import android.app.Application

/**
 * App-process graph. The fan itself now lives INSIDE SystemUI (xposed/FanHost);
 * this process is pure settings/management and talks to the module through
 * RemoteBridge's mirrored SharedPreferences (xposed/RemotePrefs.GROUP).
 */
object AppGraph {
    @Volatile var pinStore: com.repl.bubbledrawer.data.PinStore? = null
    @Volatile var repo: com.repl.bubbledrawer.data.AppRepository? = null
}

class BubbleAppApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.repl.bubbledrawer.xposed.RemoteBridge.start(this)
    }
}
