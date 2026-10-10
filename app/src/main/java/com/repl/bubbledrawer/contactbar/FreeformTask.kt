package com.repl.bubbledrawer.contactbar

import android.graphics.Rect

/**
 * Live view of the HyperOS freeform (小窗) window — the single source of truth for the
 * floating contact bar's geometry and visibility.
 *
 * Why reflection instead of a compile-time dependency: the module is one dex shared by every
 * hooked process (LSPosed mirrors it into SystemUI), and `MiuiFreeformModeTaskInfo` only exists
 * inside WMShell's classloader. Every accessor is resolved once and cached; a missing member
 * degrades to a sane default instead of throwing (see [ContactsBarController] for the caller).
 *
 * Verified against the device dumps (`out/WMShell_src`, WMShell inside SystemUI):
 *
 *  - `MiuiFreeformModeTaskInfo.getBounds()` → `MultiTaskingAnimTarget.getCurrentBounds()`, i.e.
 *    the *live* rect, mutated on every frame of a move/resize gesture
 *    (`MiuiFreeformModeResizeHandler.handleResize:225-228` → `setAnimParam` →
 *    `startGestureAnimation(3, …)`). There is NO per-frame listener anywhere in WMShell, so a
 *    consumer must poll — which is exactly what [ContactsBarController]'s frame callback does.
 *  - `getScaledBounds()` = `MultiTaskingCommonUtils.scaleBounds(getBounds(), getFreeformScale())`
 *    — the on-screen rect (the window is drawn scaled, `bounds` is unscaled content space).
 *  - `getActiveTaskScaleBounds()` prefers the destination rect while an animation is in flight,
 *    so the bar does not lag behind a resize/enter animation.
 *  - `mMode`: -1 not freeform · **0 normal 小窗** · **1 迷你小窗** · 2 normal pinned · 3 mini pinned,
 *    exposed as `isNormalState()/isMiniState()/isNormalPinedState()/isMiniPinedState()/isInPinMode()`.
 *    Flyme shows its contact bar only for a plain small window, and the user's ruling for this port
 *    is the same: hide it in the mini states.
 */
object FreeformTask {

    const val MODE_NONE = -1
    const val MODE_NORMAL = 0
    const val MODE_MINI = 1
    const val MODE_PINNED = 2
    const val MODE_MINI_PINNED = 3

    /** Resolved members per concrete class, so the frame loop does no lookup work. */
    private class Api(cls: Class<*>) {
        val getMode = find(cls, "getMode")
        val isInAnimating = find(cls, "isInAnimating")
        val activeScaleBounds = find(cls, "getActiveTaskScaleBounds")
        val scaledBounds = find(cls, "getScaledBounds")
        val scaledAnimatingBounds = find(cls, "getScaledAnimatingBounds")
        val scaledDestinationBounds = find(cls, "getScaledDestinationBounds")
        val bounds = find(cls, "getBounds")
        val freeformScale = find(cls, "getFreeformScale")
        val packageName = find(cls, "getPackageName")
        val taskId = find(cls, "getTaskId")
        val taskInfo = find(cls, "getTaskInfo")
        val isMiniState = find(cls, "isMiniState")
        val isMiniPinedState = find(cls, "isMiniPinedState")
        val isNormalState = find(cls, "isNormalState")
        val isInPinMode = find(cls, "isInPinMode")
        val enterState = find(cls, "getEnterState")
        val cornerRadius = find(cls, "getCornerRadius")

        private fun find(cls: Class<*>, name: String) =
            runCatching { cls.getMethod(name) }.getOrNull()
    }

    private val apiCache = HashMap<Class<*>, Api>()

    private fun api(info: Any?): Api? {
        val cls = info?.javaClass ?: return null
        synchronized(apiCache) {
            apiCache[cls]?.let { return it }
            val created = Api(cls)
            apiCache[cls] = created
            return created
        }
    }

    private fun read(info: Any?, member: (Api) -> java.lang.reflect.Method?): Any? {
        val a = api(info) ?: return null
        val m = member(a) ?: return null
        return runCatching { m.invoke(info) }.getOrNull()
    }

    /** `mMode`; [MODE_NONE] when the object is unknown. */
    fun mode(info: Any?): Int = (read(info) { it.getMode } as? Number)?.toInt() ?: MODE_NONE

    /** Only a plain small window carries the bar (Flyme parity: `windowingMode == 11 || 1035`). */
    fun isNormal(info: Any?): Boolean {
        val a = api(info) ?: return false
        a.isNormalState?.let { return runCatching { it.invoke(info) as? Boolean }.getOrNull() == true }
        return mode(info) == MODE_NORMAL
    }

    /** 迷你小窗 (either plain mini or mini-pinned) — the states that must NOT show the bar. */
    fun isMini(info: Any?): Boolean {
        val a = api(info) ?: return false
        var mini = false
        a.isMiniState?.let { mini = runCatching { it.invoke(info) as? Boolean }.getOrNull() == true }
        if (!mini) a.isMiniPinedState?.let { mini = runCatching { it.invoke(info) as? Boolean }.getOrNull() == true }
        if (mini) return true
        val m = mode(info)
        return m == MODE_MINI || m == MODE_MINI_PINNED
    }

    /** 贴边 (pinned to a screen edge, normal or mini) — half off-screen, no room for a bar. */
    fun isPinned(info: Any?): Boolean {
        val a = api(info) ?: return false
        a.isInPinMode?.let { return runCatching { it.invoke(info) as? Boolean }.getOrNull() == true }
        val m = mode(info)
        return m == MODE_PINNED || m == MODE_MINI_PINNED
    }

    fun isAnimating(info: Any?): Boolean =
        read(info) { it.isInAnimating } as? Boolean ?: false

    /**
     * The window's own corner radius, in screen pixels (the ROM divides it by the surface scale when
     * drawing into the scaled leash, so the stored value is already on-screen). The bar uses it so it
     * matches the window instead of looking like a pill. Null when unavailable.
     */
    fun cornerRadius(info: Any?): Float? {
        val value = read(info) { it.cornerRadius } as? Number ?: return null
        val radius = value.toFloat()
        return if (radius > 0f) radius else null
    }

    /**
     * EXITING: the ROM has committed to leaving freeform — the swipe-up close gesture, the ✕ button,
     * or a maximize. `MiuiFreeformModeTaskInfo.EXITING = 1` (set by
     * `MiuiFreeformModeMoveHandler.handleMotionEvents:207` and
     * `MiuiFreeformModeAnimation.startMaximizeShellTransition:5553`).
     */
    fun isExiting(info: Any?): Boolean {
        val state = read(info) { it.enterState } as? Number ?: return false
        return state.toInt() == 1
    }

    fun packageName(info: Any?): String? =
        (read(info) { it.packageName } as? String)
            ?: runCatching {
                val taskInfo = read(info) { it.taskInfo }
                taskInfo?.javaClass?.getMethod("getTopActivity")?.invoke(taskInfo)?.let { cn ->
                    cn.javaClass.getMethod("getPackageName").invoke(cn) as? String
                }
            }.getOrNull()

    fun taskId(info: Any?): Int = (read(info) { it.taskId } as? Number)?.toInt() ?: -1

    /** Whether the task is still visible to the user (`RunningTaskInfo.isVisible`). */
    fun isVisible(info: Any?): Boolean {
        val taskInfo = read(info) { it.taskInfo } ?: return true
        return runCatching {
            taskInfo.javaClass.getField("isVisible").get(taskInfo) as? Boolean
        }.getOrNull() ?: runCatching {
            taskInfo.javaClass.getMethod("isVisible").invoke(taskInfo) as? Boolean
        }.getOrNull() ?: true
    }

    /**
     * The rect the user actually sees, in screen coordinates — the bar's width and x come straight
     * from here, which is what keeps it flush with the window while the user drags to resize.
     *
     * Order matters: an in-flight gesture/animation writes the *destination* values first
     * (`getActiveTaskScaleBounds` already switches on `isInAnimating()`), so preferring it removes
     * the one-frame lag a plain `getScaledBounds()` would show mid-resize.
     */
    fun visualBounds(info: Any?): Rect? {
        (read(info) { it.activeScaleBounds } as? Rect)?.let { if (!it.isEmpty) return Rect(it) }
        (read(info) { it.scaledBounds } as? Rect)?.let { if (!it.isEmpty) return Rect(it) }
        if (isAnimating(info)) {
            (read(info) { it.scaledAnimatingBounds } as? Rect)?.let { if (!it.isEmpty) return Rect(it) }
            (read(info) { it.scaledDestinationBounds } as? Rect)?.let { if (!it.isEmpty) return Rect(it) }
        }
        val raw = (read(info) { it.bounds } as? Rect) ?: return null
        if (raw.isEmpty) return null
        val scale = (read(info) { it.freeformScale } as? Number)?.toFloat() ?: 1f
        return Rect(
            raw.left,
            raw.top,
            raw.left + (raw.width() * scale).toInt(),
            raw.top + (raw.height() * scale).toInt(),
        )
    }
}

/**
 * Freeform state push, fed by the hooks [com.repl.bubbledrawer.launch.FlymeFreeformController]
 * already installs on `MiuiFreeformModeTaskRepository` — independent of the Flyme-style freeform
 * toggle, because the contact bar must work with HyperOS's own 小窗 too.
 */
interface FreeformObserver {
    fun onFreeformTaskAppeared(taskInfo: Any)
    fun onFreeformTaskVanished(taskId: Int)
    fun onFreeformTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int)

    /**
     * The ROM has started closing this window (swipe-up dismiss, ✕, maximize). The bar must disappear
     * at once instead of riding the closing animation — `MiuiFreeformModeController.exitFreeformTask`
     * is the point where the decision is made.
     */
    fun onFreeformTaskClosing(taskId: Int) {}

    /**
     * The window the user is manipulating right now (resize / move gesture). With two 小窗 open the
     * bar must follow the one being touched; defaults to [onFreeformTaskAppeared] for consumers that
     * do not distinguish the two.
     */
    fun onFreeformTaskFocused(taskInfo: Any) = onFreeformTaskAppeared(taskInfo)

    /**
     * A freeform window became the focused one — `MultiTaskingTaskRepository.updateFreeformTaskToTop`
     * fires on every focus gain (`onTaskInfoChanged` for `runningTaskInfo.isFocused`) and on
     * `moveToFront`.
     *
     * This is what brings the bar back: with two 小窗 open, tapping the other one takes focus away (bar
     * hides, which is correct), and tapping this one again is announced *only* here — a plain tap is
     * not a move/resize gesture, so no other hook sees it.
     */
    fun onFreeformTaskToFront(taskId: Int) {}
}
