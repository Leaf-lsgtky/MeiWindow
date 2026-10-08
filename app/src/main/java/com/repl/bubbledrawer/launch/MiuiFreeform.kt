package com.repl.bubbledrawer.launch

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect

/**
 * Reflective bridge to HyperOS/MIUI's freeform (小窗) stack state — the piece the
 * "更多页" needs to sit exactly where the current 小窗 is (方案 B).
 *
 * Verified against the jars pulled from the target device
 * (`work/miui_fw/{framework,miui-framework}.jar`, decompiled to `out/single/`):
 *
 *  - `miui.app.MiuiFreeFormManager.getAllFreeFormStackInfosOnDisplay(int)` →
 *    `List<MiuiFreeFormManager.MiuiFreeFormStackInfo>` with public fields
 *    `packageName / stackId / userId / displayId / bounds / smallWindowBounds /
 *    visible / windowState / inPinMode / isForegroundPin …` and
 *    `isInFreeFormMode() / isInMiniFreeFormMode()`.
 *    `MiuiMultiWindowUtils.hasSmallFreeform()` (same jar) uses exactly the
 *    predicate `windowState == 1 && !inPinMode` — mirrored in [StackInfo.isNormalWindow].
 *  - `android.util.MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean noCheck,
 *    int launchBoundsLeft, int launchBoundsTop)` (line 711) builds freeform options with
 *    `setLaunchWindowingMode(5)` + `setLaunchBounds(getCustomFreeformRect(…))`; the
 *    sentinel `-1073741824` (= [POS_AUTO]) means "let the ROM pick the position", any
 *    other left/top pins the window there (clamped inside the freeform accessible area).
 *    `miui.app.MiuiFreeFormManager.getActivityOptions(Context, String, boolean, int, int)`
 *    exists as the same-shaped fallback.
 *  - `ActivityOptions.setFreeformAnimation(boolean)` is a MIUI addition; the ROM's own
 *    mini-window launch (`AppMiniWindowManagerImpl$launchMiniWindowActivity$1:133-135`)
 *    calls `getActivityOptions(ctx, pkg, true, false)` and then disables that animation.
 *
 * Everything is reflective + best-effort: a missing class/method returns null/empty and
 * the caller falls back (fullscreen launch, centred panel), so a non-MIUI ROM still works.
 */
object MiuiFreeform {

    /** `MiuiMultiWindowUtils` "no custom position" sentinel. */
    const val POS_AUTO = -1073741824

    data class StackInfo(
        val packageName: String,
        val stackId: Int,
        val displayId: Int,
        val bounds: Rect,
        val smallBounds: Rect?,
        val visible: Boolean,
        val windowState: Int,
        val inPinMode: Boolean,
        val foregroundPin: Boolean,
    ) {
        /** `MiuiMultiWindowUtils.hasSmallFreeform()` predicate. */
        val isNormalWindow: Boolean get() = windowState == 1 && !inPinMode
    }

    /** All freeform stacks of one display; empty list when the ROM has no such API. */
    fun stacks(displayId: Int = 0): List<StackInfo> {
        val raw = runCatching {
            Class.forName("miui.app.MiuiFreeFormManager")
                .getMethod("getAllFreeFormStackInfosOnDisplay", Int::class.javaPrimitiveType)
                .invoke(null, displayId) as? List<*>
        }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { it?.let(::toStack) }
    }

    /**
     * The stack the user is looking at — visible normal window first, then any visible
     * stack, then the first one with real bounds. Null when nothing is open.
     */
    fun currentStack(displayId: Int = 0): StackInfo? = pick(stacks(displayId))

    /** Same selection rule as [currentStack], for a list already read once (logging). */
    fun pick(list: List<StackInfo>): StackInfo? {
        val withBounds = list.filter { !it.bounds.isEmpty }
        return withBounds.firstOrNull { it.visible && it.isNormalWindow }
            ?: withBounds.firstOrNull { it.visible }
            ?: withBounds.firstOrNull()
    }

    /**
     * Bounds of the freeform window the user is looking at, whatever created it.
     *
     * `getAllFreeFormStackInfosOnDisplay` only lists MIUI's own tracked stacks; a window
     * produced through `MiuiMultiWindowUtils.getActivityOptions` (what the fan launches)
     * may not appear there — which is exactly what `stacks=0` in the panel log means. So
     * fall back to the activity manager: the first running task whose windowing mode is
     * freeform carries its own configuration bounds.
     *
     * @return bounds to package name, or null when no freeform window exists.
     */
    fun currentWindow(context: Context, displayId: Int = 0): Pair<Rect, String>? {
        val tracked = pick(stacks(displayId))
        if (tracked != null && !tracked.bounds.isEmpty) return tracked.bounds to tracked.packageName
        return freeformTask(context)
    }

    /** Activity-manager fallback of [currentWindow]: the first running freeform task. */
    fun freeformTask(context: Context): Pair<Rect, String>? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val tasks = runCatching { am.getRunningTasks(20) }.getOrNull() ?: return null
        for (task in tasks) {
            val freeform = runCatching {
                task.javaClass.getMethod("isFreeform").invoke(task) as? Boolean
            }.getOrNull() ?: false
            if (freeform != true) continue
            val config = runCatching {
                task.javaClass.getField("configuration").get(task) as? Configuration
            }.getOrNull() ?: continue
            val windowConfig = runCatching {
                Configuration::class.java.getMethod("getWindowConfiguration").invoke(config)
            }.getOrNull() ?: continue
            val bounds = runCatching {
                windowConfig.javaClass.getMethod("getBounds").invoke(windowConfig) as? Rect
            }.getOrNull() ?: continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val pkg = task.topActivity?.packageName
                ?: task.baseActivity?.packageName
                ?: "?"
            return bounds to pkg
        }
        return null
    }

    /**
     * Where MIUI itself would put a freeform window for [packageName] — the same
     * `MiuiMultiWindowUtils.getCustomFreeformRect` the launch options use. Last-resort
     * placement source: this ROM's SystemUI runs under a plain app uid, so neither the
     * MIUI stack list nor `getRunningTasks` yields the current window (both came back
     * empty on device), leaving the ROM's own footprint as the best available answer.
     */
    fun defaultFreeformRect(context: Context, packageName: String? = null): Rect? {
        if (packageName != null) {
            sixArgRect(context, packageName)?.let { return it }
        }
        return runCatching {
            Class.forName("android.util.MiuiMultiWindowUtils")
                .getMethod("getFreeformRect", Context::class.java)
                .invoke(null, context) as? Rect
        }.getOrNull()
    }

    private fun sixArgRect(context: Context, packageName: String): Rect? = runCatching {
        val cls = Class.forName("android.util.MiuiMultiWindowUtils")
        val normal = cls.getMethod("isNormalFreeForm", Context::class.java, String::class.java, Class.forName("android.content.ComponentName"))
            .invoke(null, context, packageName, null) as? Boolean ?: true
        cls.getMethod(
            "getCustomFreeformRect",
            Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(null, context, POS_AUTO, POS_AUTO, false, packageName, normal) as? Rect
    }.getOrNull()

    /**
     * MIUI freeform [ActivityOptions], pinned to `position.left/top` when given.
     * Returns null when neither vendor entry point exists (caller falls back).
     */
    fun activityOptions(
        context: Context,
        pkg: String,
        noCheck: Boolean = true,
        position: Rect? = null,
    ): ActivityOptions? {
        if (position != null) {
            options5("android.util.MiuiMultiWindowUtils", context, pkg, noCheck, position.left, position.top)
                ?.let { return it }
            options5("miui.app.MiuiFreeFormManager", context, pkg, noCheck, position.left, position.top)
                ?.let { return it }
        }
        options4("android.util.MiuiMultiWindowUtils", context, pkg, noCheck)?.let { return it }
        return options4("miui.app.MiuiFreeFormManager", context, pkg, noCheck)
    }

    /** `ActivityOptions.setFreeformAnimation(false)` — MIUI addition, best effort. */
    fun withoutFreeformAnimation(options: ActivityOptions): ActivityOptions {
        runCatching {
            ActivityOptions::class.java
                .getMethod("setFreeformAnimation", Boolean::class.javaPrimitiveType)
                .invoke(options, false)
        }
        return options
    }

    // ---------------- reflection plumbing ----------------

    private fun options4(
        clazz: String,
        context: Context,
        pkg: String,
        noCheck: Boolean,
    ): ActivityOptions? = runCatching {
        Class.forName(clazz).getMethod(
            "getActivityOptions",
            Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(null, context, pkg, noCheck) as? ActivityOptions
    }.getOrNull()

    private fun options5(
        clazz: String,
        context: Context,
        pkg: String,
        noCheck: Boolean,
        left: Int,
        top: Int,
    ): ActivityOptions? = runCatching {
        Class.forName(clazz).getMethod(
            "getActivityOptions",
            Context::class.java, String::class.java, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(null, context, pkg, noCheck, left, top) as? ActivityOptions
    }.getOrNull()

    private fun toStack(o: Any): StackInfo? = runCatching {
        val c = o.javaClass
        fun f(name: String): Any? = runCatching { c.getField(name).get(o) }.getOrNull()
        val pkg = f("packageName") as? String ?: return null
        StackInfo(
            packageName = pkg,
            stackId = f("stackId") as? Int ?: -1,
            displayId = f("displayId") as? Int ?: 0,
            bounds = f("bounds") as? Rect ?: Rect(),
            smallBounds = f("smallWindowBounds") as? Rect,
            visible = f("visible") as? Boolean ?: true,
            windowState = f("windowState") as? Int ?: -1,
            inPinMode = f("inPinMode") as? Boolean ?: false,
            foregroundPin = f("isForegroundPin") as? Boolean ?: false,
        )
    }.getOrNull()
}
