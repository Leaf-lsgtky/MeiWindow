plugins {
    id("com.android.library")
}

/**
 * Compile-only stubs for @hide platform classes. Consumed by :app as
 * `compileOnly(project(":hiddenapi"))`, so nothing here is ever packaged: at runtime
 * the boot classpath provides the real android.view.InputMonitor / InputChannel /
 * InputEventReceiver / android.hardware.input.InputManagerGlobal.
 *
 * An Android library (not java-library) so android.os.IBinder / Looper / InputEvent
 * resolve from the platform SDK — the same trick as MiuiBackGestureHook's :hidden-api.
 */
android {
    namespace = "com.repl.bubbledrawer.hiddenapi"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
