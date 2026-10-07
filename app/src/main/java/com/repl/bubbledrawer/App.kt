package com.repl.bubbledrawer

import android.app.Application

/** Process-singleton graph (overlay service + activities share one process). */
object AppGraph {
    @Volatile var pinStore: com.repl.bubbledrawer.data.PinStore? = null
    @Volatile var repo: com.repl.bubbledrawer.data.AppRepository? = null
    @Volatile var previewRequested = false
    @Volatile var serviceRunning = false
}

class BubbleAppApplication : android.app.Application()
