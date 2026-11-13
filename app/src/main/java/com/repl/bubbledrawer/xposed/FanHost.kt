package com.repl.bubbledrawer.xposed

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.PowerManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.bubble.FanPressureDetector
import com.repl.bubbledrawer.bubble.GestureAppLauncher
import com.repl.bubbledrawer.bubble.PressureCalibration
import com.repl.bubbledrawer.bubble.PressureCalibrationState
import com.repl.bubbledrawer.bubble.SlideGestureItemView
import com.repl.bubbledrawer.bubble.SlideIconView
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PinsSync
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.SpySide
import com.repl.bubbledrawer.launch.FreeformLaunchStrategy
import com.repl.bubbledrawer.launch.FreeformTrigger
import com.repl.bubbledrawer.launch.FullscreenLaunchStrategy
import com.repl.bubbledrawer.launch.ILaunchStrategy
import com.repl.bubbledrawer.pin.PanelContentFactory
import com.repl.bubbledrawer.pin.PinManageModel
import com.repl.bubbledrawer.pin.PinnedKey
import com.repl.bubbledrawer.pin.PinnedOrderStore
import com.repl.bubbledrawer.shortcut.AggregatePayment
import com.repl.bubbledrawer.shortcut.FunctionPinDetails
import com.repl.bubbledrawer.shortcut.FunctionPinStore
import com.repl.bubbledrawer.contactbar.quickPayTrigger
import com.repl.bubbledrawer.shortcut.ShortcutItem
import com.repl.bubbledrawer.shortcut.ShortcutRepository
import kotlinx.coroutines.launch

/**
 * The fan UI (scrim + touchable canvas windows — the same window pair the old
 * OverlayHost used) living INSIDE the SystemUI process, driven by the single
 * pilfered stroke from the SPY view. This replaces the old zone-window routing.
 *
 * Retract paths, per the FlymeFreeform study (CornerRadialOverlayView.kt):
 *  • engine Cancel — pull-back beyond reverseTolerance (CornerGestureEngine :141-145)
 *    → cancelExternal
 *  • release decisions — forwarded UP runs GestureAppLauncher m9729p (launch over
 *    an icon / cancelFlag collapse after pull-back / stay open over plain void)
 *  • canvas 上的新触摸 — 竖直滑翻页、点一下收起（见 [FanCanvas]；只有一页时仍是原版
 *    C2822g.onTouchEvent :381-393 的"落下即收起"）
 *  • 不操作自动收起 — 用户在设置里给的秒数（默认 10s，0 = 不收起）。参考实现在
 *    beginGesture/updateGesture 上以固定 5s 重新计时（:186-187 / :194-195），这里同规则、
 *    时长可调：每个事件（含 UP）重新计时，所以滑出后不点选才会收回。
 *  • SCREEN_OFF → retract (original mo864f :1049-1056)
 */
class FanHost(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val wm = host.getSystemService(WindowManager::class.java)!!
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Fan context: SystemUI identity (package/services/storage — the window attribution rule in ModuleResources), module resources + classloader
     *  (our layouts, dimens, drawables). */
    private val moduleContext: Context? = ModuleResources.packageContext(host)
    private val context: Context = moduleContext?.let { ModuleResources.FanContext(host, it) } ?: host

    private val repo = AppRepository(context, cloneBadge = { RemotePrefs.read(prefs).cloneBadge })

    /**
     * Pin persistence inside SystemUI (方案 B panel writes).
     *
     * `prefs` (the module's `XposedInterface.getRemotePreferences` view) is a READ-ONLY
     * mirror — its `edit()` throws `UnsupportedOperationException: Read only
     * implementation` (verified on device; libxposed 102 semantics: hook-process writes
     * do not exist on that object, and `XposedServiceHelper` does not deliver to
     * hook processes either — only to the module's own app process).
     *
     * So the write path is a round-trip to the module APP process: [PinSyncReceiver]
     * (exported manifest receiver, guarded by a caller-identity check) applies the encoded
     * order through the ordinary app-side backend (local mirror + LSPosed remote group),
     * which is exactly what the manage Activity writes. Explicit broadcasts to a manifest
     * receiver cold-start the module app even when it is not running. Until the round-trip
     * lands, the in-memory overlay below keeps the FAN consistent within this SystemUI
     * lifetime (the original ItemTouchHelper also only re-read on next show).
     */
    @Volatile
    private var pinsOverlay: String? = null

    // SystemUI-local persistent SharedPreferences: guaranteed to survive SystemUI reboots
    private val storageContext: Context =
        if (host.isDeviceProtectedStorage) host
        else runCatching { host.createDeviceProtectedStorageContext() }.getOrDefault(host)

    private val sysUiPrefs: SharedPreferences =
        storageContext.getSharedPreferences("bubbledrawer_pins", Context.MODE_PRIVATE)

    private val pinStore = PinStore(object : PinBackend {
        private val local get() = PrefsPinBackend(sysUiPrefs)
        private val remote get() = PrefsPinBackend(prefs)

        override fun read(): String {
            // This session's own edit is authoritative until the app confirms it.
            pinsOverlay?.let { return it }

            val localBackend = local
            // `prefs` is the LSPosed remote group: readable here, never writable (see the
            // class doc above). A read failure must not hide the local copy.
            val remoteValue = runCatching { remote.read() }.getOrDefault("")
            val remoteRev = runCatching { remote.readRev() }.getOrDefault(0L)

            val decision = PinsSync.decide(
                localValue = localBackend.read(),
                localRev = localBackend.readRev(),
                remoteValue = remoteValue,
                remoteRev = remoteRev,
            )
            when (decision.source) {
                PinsSync.Source.REMOTE -> {
                    // 值与戳一次 commit（见 PinBackend.writeWithRev）：同一文件上的监听者若在两次
                    // commit 之间读一次，看到的是「新值 + 旧戳」，平票会把旧值判给远端。
                    localBackend.writeWithRev(decision.value, decision.rev)
                }
                PinsSync.Source.LOCAL -> if (decision.pushBack) {
                    // Our copy is newer than the shared group — the app never applied the
                    // handoff (force-stopped by HyperOS, broadcast lost…). Hand it over again,
                    // otherwise the very next SystemUI start would read the stale value.
                    pushPinsToApp(decision.value, decision.rev)
                }
                PinsSync.Source.NONE -> Unit
            }
            logPinRead(decision.source, localBackend.readRev(), remoteRev, decision.value)
            return decision.value
        }

        override fun write(value: String) {
            val rev = System.currentTimeMillis()
            pinsOverlay = value
            val localBackend = local
            localBackend.writeWithRev(value, rev)
            pushPinsToApp(value, rev)
        }

        override fun readRev(): Long = local.readRev()

        override fun writeRev(rev: Long) {
            local.writeRev(rev)
        }
    })

    /**
     * 「已选功能」（快捷方式收藏）在 SystemUI 侧的存储 —— 与 [pinStore] 同一套规则，只换了 key。
     *
     * 为什么不能直接用 [com.repl.bubbledrawer.shortcut.FunctionPinStore.getPins]：那个入口读的是
     * `RemoteBridge.remote`，而它只在 App 进程被绑定过（hook 进程拿不到），于是会退到
     * `RemoteBridge.local(context)` —— 那是 **SystemUI 自己的 cfg 文件**，App 永远读不到，
     * 写进去也只有 SystemUI 自己看得见。功能页第一版正是如此：面板里选的功能设置页不显示，
     * 设置页选的面板不显示。
     *
     * 现在与收藏应用逐条对齐：读 = 本会话覆盖 >（SystemUI 本地副本 与 远程镜像按戳和解，必要时把
     * 赢家推回 App）；写 = 本会话覆盖 + SystemUI 本地副本（重启不丢）+ 交给 App 进程落盘
     * （广播 + 服务，同一条交接通道）。
     */
    @Volatile
    private var functionsOverlay: String? = null

    private val functionsLocal get() = FunctionPinStore.backend(sysUiPrefs)
    private val functionsRemote get() = FunctionPinStore.backend(prefs)

    /**
     * 「应用 + 功能」混排顺序在 SystemUI 侧的镜像。规则与 [functionsOverlay] 完全相同：
     * 本会话覆盖 >（SystemUI 本地副本 与 远程镜像按戳和解，必要时推回 App）；写 = 覆盖 +
     * 本地副本 + 交给 App 进程。
     */
    @Volatile
    private var orderOverlay: String? = null

    private val orderLocal get() = PinnedOrderStore.backend(sysUiPrefs)
    private val orderRemote get() = PinnedOrderStore.backend(prefs)

    private fun readPinnedOrder(): List<PinnedKey> {
        orderOverlay?.let { return com.repl.bubbledrawer.pin.PinnedOrderCodec.decode(it) }
        val local = orderLocal
        val remoteValue = runCatching { orderRemote.read() }.getOrDefault("")
        val remoteRev = runCatching { orderRemote.readRev() }.getOrDefault(0L)
        val decision = PinsSync.decide(
            localValue = local.read(),
            localRev = local.readRev(),
            remoteValue = remoteValue,
            remoteRev = remoteRev,
        )
        when (decision.source) {
            PinsSync.Source.REMOTE -> {
                local.writeWithRev(decision.value, decision.rev)
                orderOverlay = null
            }
            PinsSync.Source.LOCAL -> if (decision.pushBack) {
                pushPinnedOrderToApp(decision.value, decision.rev)
            }
            PinsSync.Source.NONE -> Unit
        }
        return com.repl.bubbledrawer.pin.PinnedOrderCodec.decode(decision.value)
    }

    private fun writePinnedOrder(order: List<PinnedKey>) {
        val value = com.repl.bubbledrawer.pin.PinnedOrderCodec.encode(order)
        val rev = System.currentTimeMillis()
        orderOverlay = value
        val local = orderLocal
        local.writeWithRev(value, rev)
        pushPinnedOrderToApp(value, rev)
    }

    private fun pushPinnedOrderToApp(value: String, rev: Long) {
        scope.launch {
            val deliveredProvider = runCatching {
                val extras = android.os.Bundle().apply {
                    putString(PinSyncReceiver.EXTRA_ORDER, value)
                    putLong(PinSyncReceiver.EXTRA_ORDER_REV, rev)
                }
                host.contentResolver.call(
                    com.repl.bubbledrawer.shortcut.ShortcutBridge.CONTENT_URI,
                    PinSyncReceiver.METHOD_SYNC,
                    null,
                    extras,
                )?.getBoolean(com.repl.bubbledrawer.shortcut.ShortcutBridge.EXTRA_APPLIED) == true
            }.getOrDefault(false)

            val deliveredBroadcast = if (deliveredProvider) {
                false
            } else {
                runCatching {
                    val intent = Intent(PinSyncReceiver.ACTION)
                        .setClassName(ModuleResources.MODULE_PACKAGE, "com.repl.bubbledrawer.xposed.PinSyncReceiver")
                        .putExtra(PinSyncReceiver.EXTRA_ORDER, value)
                        .putExtra(PinSyncReceiver.EXTRA_ORDER_REV, rev)
                    PinSyncReceiver.sendBroadcastWithIdentity(host, intent)
                    true
                }.getOrDefault(false)
            }

            logger(
                if (deliveredProvider || deliveredBroadcast) Log.INFO else Log.WARN,
                "PINNED_ORDER_WRITE_HANDOFF provider=$deliveredProvider broadcast=$deliveredBroadcast " +
                    "rev=$rev valueLen=${value.length}",
                null,
            )
        }
    }

    /**
     * 面板读「已选功能」的**详情**（App 进程发布到共享组的那份）。
     *
     * 面板要画一条已选功能，本来得在自己的枚举结果里认出这个键 —— 而两边的枚举结果并不相同
     * （面板有 LauncherApps 但读不到 App 的缓存，App 反过来）。少一条就会出现「设置页里选好了，
     * 扇形面板里什么都没有」，还不给任何提示。详情里带着名称、宿主与启动 Intent，面板直接画就行，
     * 不再依赖自己的枚举（图标缺失时 `ShortcutIcon` 会退回宿主应用图标）。
     */
    private fun readFunctionDetails(): List<ShortcutItem> =
        runCatching { FunctionPinDetails.decode(prefs.getString(FunctionPinDetails.KEY, null)) }
            .getOrDefault(emptyList())

    /**
     * 已选功能解析用的详情 = App 发布的那份 + 内建的「聚合支付」条目。
     *
     * 聚合支付不在任何扫描结果里（内建功能），发布链对它的 placeholder 也带不上图标 ——
     * 这里把发布版里那条替换成 [AggregatePayment.entryIn]（带模块的 aggregate_payment 图标），
     * 收藏区/扇形凭 pinKey 就能解析出带真图标的条目。
     */
    private fun functionDetailsForRender(): List<ShortcutItem> =
        readFunctionDetails().filterNot { AggregatePayment.isEntry(it) } +
            AggregatePayment.entryIn(host)

    /** 面板读：[com.repl.bubbledrawer.shortcut.FunctionPinStore] 的键列表。 */
    private fun readFunctionPins(): List<String> {
        functionsOverlay?.let { return FunctionPinStore.decode(it) }
        val local = functionsLocal
        val remoteValue = runCatching { functionsRemote.read() }.getOrDefault("")
        val remoteRev = runCatching { functionsRemote.readRev() }.getOrDefault(0L)
        val decision = PinsSync.decide(
            localValue = local.read(),
            localRev = local.readRev(),
            remoteValue = remoteValue,
            remoteRev = remoteRev,
        )
        when (decision.source) {
            PinsSync.Source.REMOTE -> {
                local.writeWithRev(decision.value, decision.rev)
                functionsOverlay = null
            }
            // 本地更新 = 上一次交接没落地（App 被 HyperOS 冻结、广播丢了…）：再交一次，
            // 否则下一次 SystemUI 启动读的还是旧值（与 pinStore 的 pushBack 同因）。
            PinsSync.Source.LOCAL -> if (decision.pushBack) {
                pushFunctionsToApp(decision.value, decision.rev)
            }
            PinsSync.Source.NONE -> Unit
        }
        return FunctionPinStore.decode(decision.value)
    }

    /** 面板写：本地立刻一致（覆盖），落 SystemUI 本地副本，再交给 App 进程。 */
    private fun writeFunctionPins(pins: List<String>) {
        val value = FunctionPinStore.encode(pins)
        val rev = System.currentTimeMillis()
        functionsOverlay = value
        val local = functionsLocal
        local.writeWithRev(value, rev)
        pushFunctionsToApp(value, rev)
    }

    private fun pushFunctionsToApp(value: String, rev: Long) {
        // 这里原本是同步 ContentResolver.call：面板编辑回调在 SystemUI 主线程，App 冷启动/
        // provider 初始化会把主线程卡住。交接只需要最终送达，不阻塞本地面板，所以放到 IO scope；
        // rev 会保证后发起的编辑不被旧请求覆盖。
        scope.launch {
            val deliveredProvider = runCatching {
                val extras = android.os.Bundle().apply {
                    putString(PinSyncReceiver.EXTRA_FUNCTIONS, value)
                    putLong(PinSyncReceiver.EXTRA_FUNCTIONS_REV, rev)
                }
                host.contentResolver.call(
                    com.repl.bubbledrawer.shortcut.ShortcutBridge.CONTENT_URI,
                    PinSyncReceiver.METHOD_SYNC,
                    null,
                    extras,
                )?.getBoolean(com.repl.bubbledrawer.shortcut.ShortcutBridge.EXTRA_APPLIED) == true
            }.getOrDefault(false)

            val deliveredBroadcast = if (deliveredProvider) {
                false
            } else {
                runCatching {
                    val intent = Intent(PinSyncReceiver.ACTION)
                        .setClassName(ModuleResources.MODULE_PACKAGE, "com.repl.bubbledrawer.xposed.PinSyncReceiver")
                        .putExtra(PinSyncReceiver.EXTRA_FUNCTIONS, value)
                        .putExtra(PinSyncReceiver.EXTRA_FUNCTIONS_REV, rev)
                    PinSyncReceiver.sendBroadcastWithIdentity(host, intent)
                    true
                }.getOrDefault(false)
            }

            // 与 pushPinsToApp 同一条通道、同样的理由（provider 同步回执优先，带身份广播兜底）。
            logger(
                if (deliveredProvider || deliveredBroadcast) Log.INFO else Log.WARN,
                "FUNCTION_WRITE_HANDOFF provider=$deliveredProvider broadcast=$deliveredBroadcast " +
                    "rev=$rev valueLen=${value.length}",
                null,
            )
        }
    }

    /**
     * The pin store's read trace, THROTTLED.
     *
     * `pinsOf()` is asked once per app while a panel page is built (`PinManageModel` asks "is
     * this one pinned?" for every entry), so a single 更多/管理页 build produced hundreds of
     * `PIN_READ` lines — each one a synchronous write into LSPosed's log file, on the main
     * thread, mid-gesture. Besides the cost, it buried the events the log exists for: the
     * diagnostics page shows the last 25 lines, and they were all this. So a read is logged
     * when its OUTCOME changes, and otherwise at most once per [PIN_READ_LOG_INTERVAL_MS] with
     * the number of reads that were folded into it.
     */
    private fun logPinRead(source: PinsSync.Source, localRev: Long, remoteRev: Long, value: String) {
        val line = "PIN_READ src=$source localRev=$localRev remoteRev=$remoteRev " +
            "pins=${PinCodec.decode(value).size}"
        val now = android.os.SystemClock.uptimeMillis()
        val changed = line != lastPinReadLine
        if (!changed && now - lastPinReadLogUptime < PIN_READ_LOG_INTERVAL_MS) {
            pinReadsSuppressed++
            return
        }
        val suppressed = pinReadsSuppressed
        pinReadsSuppressed = 0
        lastPinReadLogUptime = now
        lastPinReadLine = line
        logger(
            Log.INFO,
            if (suppressed == 0) line else "$line (+$suppressed identical reads)",
            null,
        )
    }

    private var lastPinReadLine: String? = null
    private var lastPinReadLogUptime = 0L
    private var pinReadsSuppressed = 0

    /**
     * Hand the pin order to the module's app process: explicit broadcast first (works even
     * when the app is idle), service as the fallback (it is accepted now — the old uid check
     * only allowed uid 1000, while this ROM's SystemUI is an ordinary app uid).
     */
    private fun pushPinsToApp(value: String, rev: Long) {
        // 同 pushFunctionsToApp：不要在 SystemUI 主线程等 provider。面板本地副本已经先更新，
        // 这里只负责异步把同一份值和 rev 交给 App。
        scope.launch {
            val deliveredProvider = runCatching {
                val extras = android.os.Bundle().apply {
                    putString(PinSyncReceiver.EXTRA_PINS, value)
                    putLong(PinSyncReceiver.EXTRA_REV, rev)
                }
                host.contentResolver.call(
                    com.repl.bubbledrawer.shortcut.ShortcutBridge.CONTENT_URI,
                    PinSyncReceiver.METHOD_SYNC,
                    null,
                    extras,
                )?.getBoolean(com.repl.bubbledrawer.shortcut.ShortcutBridge.EXTRA_APPLIED) == true
            }.getOrDefault(false)

            val deliveredBroadcast = if (deliveredProvider) {
                false
            } else {
                runCatching {
                    val intent = Intent(PinSyncReceiver.ACTION)
                        .setClassName(ModuleResources.MODULE_PACKAGE, "com.repl.bubbledrawer.xposed.PinSyncReceiver")
                        .putExtra(PinSyncReceiver.EXTRA_PINS, value)
                        .putExtra(PinSyncReceiver.EXTRA_REV, rev)
                    PinSyncReceiver.sendBroadcastWithIdentity(host, intent)
                    true
                }.getOrDefault(false)
            }

            // ContentResolver.call 主通道（Binder.getCallingUid 校验 + 同步回执）优先，显式带身份广播兜底。
            logger(
                if (deliveredProvider || deliveredBroadcast) Log.INFO else Log.WARN,
                "PIN_WRITE_HANDOFF provider=$deliveredProvider broadcast=$deliveredBroadcast rev=$rev valueLen=${value.length}",
                null,
            )
        }
    }

    private val strategy = ILaunchStrategy { ctx, app ->
        // freeform toggle from the same prefs the monitor watches (remote group)
        val snap = RemotePrefs.read(prefs)
        (if (snap.freeform) FreeformLaunchStrategy(freeformTrigger()) else FullscreenLaunchStrategy()).launch(ctx, app)
    }

    /**
     * 小窗要落在用户划出扇形的**那一边** (用户 2026-10-11：横屏从哪个角落划出来，小窗都在左边)。
     *
     * 这里只给一个点：左下角 = 屏幕左端，右下角 = 右端 —— [FreeformLaunchStrategy] 再把它换算成
     * 小窗的落点 (`MiuiFreeform.triggerSideRect`)。屏幕一并交出去，因为要用的必须是**扇形当时**
     * 那一块屏：它既决定了横竖屏（只有横屏才需要跟边），也是触控坐标的来源。
     */
    private fun freeformTrigger(): FreeformTrigger {
        val display = Rect(wm.currentWindowMetrics.bounds)
        val anchorX = if (currentCorner() == Corner.BOTTOM_RIGHT) display.width() else 0
        return FreeformTrigger(anchorX, display)
    }

    /** Tile factory — the verbatim slide_gesture_list_item structure built in
     *  code so the fan works even if XML inflation fails in the foreign process
     *  (same dimens/paddings as the layout file, res/values/dimens.xml:10-13). */
    private val itemFactory = GestureAppLauncher.ViewFactory { item, _ -> buildTile(item) }

    private val dock = BubbleDockController(context, repo, pinStore, strategy, itemFactory).also { controller ->
        // 扇形里也按收藏管理页的混排顺序铺 tile；没有混排布局时 controller 内部会自动退回旧顺序。
        controller.pinnedOrder = { readPinnedOrder() }
    }

    /** 扇形里要显示的功能条目（见 [com.repl.bubbledrawer.shortcut.FunctionFanItems]）。 */
    private fun fanFunctionEntries(): List<ShortcutItem> {
        val details = functionDetailsForRender()
        val keys = readFunctionPins()
        if (keys.isEmpty()) return emptyList()
        val entries = com.repl.bubbledrawer.shortcut.FunctionFanItems.resolve(
            keys = keys,
            details = details,
            cached = { key -> ShortcutRepository.peekCached(key) },
        )
        // 有认不出来的（枚举里没有，只能拿宿主应用图标顶）：后台补一次扫描，下一次划出扇形就有
        // 真图标了。就地扫描是绝对不行的 —— 这里在**手势中间的主线程**上（见 peekCached 的注释）。
        // 扫描自带 3s 最小间隔，所以连着划几次不会叠起来。聚合支付是内建条目，扫描也扫不出来，
        // 别为它触发。
        if (entries.any { it.iconBitmap == null && it.intentUri == null && !AggregatePayment.isEntry(it) }) {
            scanShortcuts("FAN_FUNCTIONS")
        }
        return entries
    }

    private val pressureDetector = FanPressureDetector(host) {
        onHeavyPressTriggered()
    }

    // ---------------- 气压重按校准会话（参考实现 FanRuntime 的 pressureCalibration* 一族） ----------------
    //
    // 采集在这里（气压计和扇形都在 SystemUI），落盘在 App（hook 进程的远程组是只读镜像，见
    // pushPinsToApp 的注释）：会话由 prefs 里 KEY_FAN_PRESSURE_CALIBRATION_ACTIVE 的一个 false→true
    // 开启，每次按压、最终结果、超时经 [PressureCalibrationBridge] 广播回 App。

    /** 会话进行中。 */
    private var calibrating = false

    /** 本次会话收集到的有效样本（按压峰值，hPa）。 */
    private val calibrationDeltas = ArrayList<Float>(PressureCalibration.REQUIRED_ATTEMPTS)

    /** 本次会话已完成的按压次数（含无效样本）。 */
    private var calibrationAttempts = 0

    /** 扇形里的进度提示「气压校准 n/5」；只在会话进行中且扇形开着时存在。 */
    private var calibrationHint: TextView? = null

    /** 刚送出去的终局事件（结果/超时），交给 [calibrationAck] 确认 App 是否真的写下了。 */
    private var pendingTerminalEvent: PressureCalibrationBridge.Event? = null

    /**
     * 超时收尾：参考实现 `schedulePressureCalibrationTimeout` 每次记到按压就重置 120s，超时后
     * 仍然 `finishPressureCalibration()`（拿已经收集到的样本算一次）—— 用户按了 4 次就走开，
     * 有 3 个有效样本就不该白按。
     */
    private val calibrationTimeout = Runnable { finishCalibration(PressureCalibrationState.Stage.TIMEOUT) }

    private var scrim: View? = null
    private var canvas: FrameLayout? = null
    private var fanWindowsUp = false

    private val timeout = Runnable {
        // 自动收起（用户 2026-10-10 新增的设置项）：只在扇形还开着、且设置仍要求自动收起时生效
        // —— 用户可能在计时期间把滑块拖到「不自动收起」。
        if (dock.isBusy && RemotePrefs.read(prefs).fanAutoRetractSec > 0) retract("AUTO_TIMEOUT")
    }
    private var screenOffReceiver: android.content.BroadcastReceiver? = null
    private var pinsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var syncFromAppReceiver: android.content.BroadcastReceiver? = null
    private var quickPayReceiver: android.content.BroadcastReceiver? = null

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    )

    init {
        if (moduleContext == null) logger(Log.ERROR, "FAN_MODULE_CONTEXT_FAILED", null)
        val initialSnap = RemotePrefs.read(prefs)
        pressureDetector.isEnabled = initialSnap.fanPressurePageTurn
        // 阈值只有一个来源：校准值优先、否则灵敏度预设（RemotePrefs.pressureThresholdFor）——
        // 以前这里直接取 sensitivityToThreshold，加了校准以后再那样写就会把校准值盖掉。
        pressureDetector.thresholdHpa = RemotePrefs.pressureThresholdFor(initialSnap)
        dock.onShownChanged = { shown ->
            if (shown) {
                // 扇形「滑出来」的瞬间（expand 动画开始）振一下 —— 用户 2026-10-10 新增开关。
                maybeVibrateFanShown()
                // 也在这里开始计时：手速极快时 UP 可能早于这一帧的 layout 到达，
                // 那样 armTimeout() 在事件里会因 isBusy 还是 false 而不计时（扇形就永不自动收回了）。
                armTimeout()
            } else {
                fadeOutFanWindows()
            }
        }
        // 更多 tile → overlay panel instead of the full-screen Activity (方案 B)
        dock.onMoreRequested = { showMorePanel() }
        // 「已选功能」也进扇形：排在收藏应用之后，顺序 = 用户点选顺序。数据源见 fanFunctionEntries()。
        dock.functionItems = { fanFunctionEntries() }
        dock.onFunctionSelected = { item ->
            when {
                // 聚合支付：以小窗打开默认入口并把支付条贴到小窗下方（不启动"自己"）。
                AggregatePayment.isEntry(item) -> quickPayTrigger?.invoke()
                else -> {
                    // 快捷方式不是应用：走 ShortcutRepository（LauncherApps/Intent 两条路），小窗开关从远程组读
                    // —— 默认参数会去读 App 进程才有的设置，hook 进程读到的是系统界面自己的 cfg。
                    ShortcutRepository.launchShortcut(host, item, RemotePrefs.read(prefs).freeform)
                }
            }
        }
        scope.launch {
            runCatching { repo.loadAll() }
            mainHandler.post {
                if (dock.isBusy) {
                    val snap = RemotePrefs.read(prefs)
                    dock.show(
                        currentCorner(), screenW(), screenH(),
                        snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend, snap.fanLayers,
                        snap.recommendEnabled, outerRadiusDpOf(snap),
                    )
                }
            }
        }
        registerUnlockReceiver()
        // pins edits while the fan is open → live rebind (reference :85-96 watch pattern)
        pinsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PrefsPinBackend.KEY || key == PrefsPinBackend.KEY_REV) {
                // The shared group moved. Adopt it only when its stamp is not older than ours —
                // and NEVER answer a difference by pushing our own copy back: the value and its
                // rev are two keys that LSPosed mirrors one at a time, so this callback can
                // legitimately see the NEW value with the OLD stamp. Pushing back then deletes
                // the edit that just happened, the app re-applies it, and the two stores oscillate
                // — which is exactly the "收藏自动反复添加/移除" report of 2026-10-12.
                // Healing is the read path's job (`PinsSync.decide`'s pushBack), where both keys
                // have landed. See [PinsSync.shouldAdoptOnKeyEvent].
                val remoteBackend = PrefsPinBackend(prefs)
                val remoteRev = runCatching { remoteBackend.readRev() }.getOrDefault(0L)
                val remoteValue = runCatching { remoteBackend.read() }.getOrDefault("")
                val localBackend = PrefsPinBackend(sysUiPrefs)
                val localRev = localBackend.readRev()
                val adopted = PinsSync.shouldAdoptOnKeyEvent(remoteRev, localRev)
                if (adopted) {
                    localBackend.writeWithRev(remoteValue, remoteRev)
                    pinsOverlay = null
                }
                logger(
                    Log.INFO,
                    "PIN_REMOTE_CHANGED remoteRev=$remoteRev localRev=$localRev " +
                        "adopted=$adopted pins=${PinCodec.decode(localBackend.read()).size}",
                    null,
                )
                rebindFanIfBusy()
            } else if (key == FunctionPinDetails.KEY) {
                // 已选功能的详情到了：面板开着就重画已选区 —— 这一刻它可能刚从「认不出来」变成
                // 「认得出来」（用户 2026-10-13：设置页选好了、面板里没有）。
                logger(
                    Log.INFO,
                    "FUNCTION_DETAILS_CHANGED len=" +
                        prefs.getString(FunctionPinDetails.KEY, "").orEmpty().length,
                    null,
                )
                mainHandler.post { if (moreUp) moreContent?.refreshFunctionPins() }
            } else if (key == FunctionPinStore.KEY_PINNED_FUNCTIONS || key == FunctionPinStore.PREFS_KEY_REV) {
                // 功能收藏的同款逐 key 回调（为什么不回推见上面 PIN_REMOTE_CHANGED 那段）：
                // 远端戳不小于本地就采纳，其余什么都不做，和解交给读路径。
                val remoteRev = runCatching { functionsRemote.readRev() }.getOrDefault(0L)
                val remoteValue = runCatching { functionsRemote.read() }.getOrDefault("")
                val localBackend = functionsLocal
                val localRev = localBackend.readRev()
                val adopted = PinsSync.shouldAdoptOnKeyEvent(remoteRev, localRev)
                if (adopted) {
                    localBackend.writeWithRev(remoteValue, remoteRev)
                    functionsOverlay = null
                }
                logger(
                    Log.INFO,
                    "FUNCTION_REMOTE_CHANGED remoteRev=$remoteRev localRev=$localRev adopted=$adopted " +
                        "functions=${FunctionPinStore.decode(localBackend.read()).size}",
                    null,
                )
                // 面板开着就顺手刷新（回调线程不保证是主线程，而这里是 Compose 状态）。
                mainHandler.post { if (moreUp) moreContent?.refreshFunctionPins() }
            } else if (key == PinnedOrderStore.KEY_PINNED_ORDER || key == PinnedOrderStore.PREFS_KEY_REV) {
                // 混排顺序同款逐 key 回调：远端不旧就采纳，不因为值不同而回推。
                val remoteRev = runCatching { orderRemote.readRev() }.getOrDefault(0L)
                val remoteValue = runCatching { orderRemote.read() }.getOrDefault("")
                val localBackend = orderLocal
                val localRev = localBackend.readRev()
                val adopted = PinsSync.shouldAdoptOnKeyEvent(remoteRev, localRev)
                if (adopted) {
                    localBackend.writeWithRev(remoteValue, remoteRev)
                    orderOverlay = null
                }
                logger(
                    Log.INFO,
                    "PINNED_ORDER_REMOTE_CHANGED remoteRev=$remoteRev localRev=$localRev adopted=$adopted " +
                        "entries=${com.repl.bubbledrawer.pin.PinnedOrderCodec.decode(localBackend.read()).size}",
                    null,
                )
                mainHandler.post { if (moreUp) moreContent?.refreshPins() }
            } else if (key == RemotePrefs.KEY_FAN_ICON_COUNT || key == RemotePrefs.KEY_FAN_RADIUS_DP ||
                key == RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND || key == RemotePrefs.KEY_FAN_ICON_DP ||
                key == RemotePrefs.KEY_FAN_ICON_SHAPE || key == RemotePrefs.KEY_FAN_LAYERS ||
                key == RemotePrefs.KEY_FAN_OUTER_RADIUS_ENABLED || key == RemotePrefs.KEY_FAN_OUTER_RADIUS_DP
            ) {
                rebindFanIfBusy()
            } else if (key == RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_ACTIVE) {
                // App 点「开始/取消校准」→ 远程组这一个键变了：开一个会话 / 关掉会话。
                //
                // 必须回主线程（同款处理见 CornerInputMonitor 的 prefsListener → scheduleApplySettings
                // 的注释）：远程组的回调不保证在主线程，而会话开关会**碰 View**（扇形里那条「n/5」
                // 提示要 addView / 改 text，非主线程会撞 ViewRootImpl.checkThread），还会读写一批
                // 非同步的会话状态（deltas/attempts）—— 让它在别的线程上跑，轻则崩系统界面，
                // 重则和主线程的 handleCalibrationPress 交错，静默把样本算坏。
                mainHandler.post {
                    if (RemotePrefs.read(prefs).fanPressureCalibrationActive) {
                        startCalibration("REQUEST")
                    } else {
                        cancelCalibration("REQUEST")
                    }
                }
            } else if (key == RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN ||
                key == RemotePrefs.KEY_FAN_PRESSURE_SENSITIVITY ||
                key == RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED ||
                key == RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED_HPA
            ) {
                val snap = RemotePrefs.read(prefs)
                pressureDetector.isEnabled = snap.fanPressurePageTurn
                pressureDetector.thresholdHpa = RemotePrefs.pressureThresholdFor(snap)
            }
        }
        runCatching { prefs.registerOnSharedPreferenceChangeListener(pinsListener) }

        // 会话可能是「FanHost 还不存在」时请求的（它是首次手势才懒创建的），也可能跨了一次 SystemUI
        // 重启：初始化时按 prefs 补一次。用户点了「开始校准」，不该等到下一次划出才有反应。
        if (initialSnap.fanPressureCalibrationActive) startCalibration("INIT_RESUME")

        // Direct broadcast receiver from App (e.g. PinManageActivity in settings app)
        val syncFromApp = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // 这个 action 是导出的：不验发送方，任何应用都能用 pins="" / functions="" 把用户的
                // 收藏洗掉（先写进 SystemUI 本地副本，再被读路径当成"本地更新"推回 App，永久生效）。
                // 合法的发送方只有模块自己的 App 进程（SettingsStore.broadcastPins / broadcastFunctions）。
                if (!isFromModuleApp(this)) {
                    logger(
                        Log.WARN,
                        "PIN_SYNC_REJECTED_SENDER uid=${runCatching { sentFromUid }.getOrNull()}",
                        null,
                    )
                    return
                }
                val raw = intent?.getStringExtra(PinSyncReceiver.EXTRA_PINS)
                if (raw != null && PinSyncReceiver.isPlausiblePinsValue(raw)) {
                    val rev = intent.getLongExtra(PinSyncReceiver.EXTRA_REV, 0L)
                    val localBackend = PrefsPinBackend(sysUiPrefs)
                    val localRev = localBackend.readRev()
                    if (PinsSync.shouldApplyPush(rev, localRev)) {
                        // The app's copy is at least as new: adopt it and drop our session overlay,
                        // so reads stop pinning this session's earlier edit.
                        localBackend.writeWithRev(raw, if (rev > 0L) rev else System.currentTimeMillis())
                        pinsOverlay = null
                        rebindFanIfBusy()
                        logger(Log.INFO, "PIN_SYNC_FROM_APP_APPLIED len=${raw.length} rev=$rev", null)
                    } else {
                        // A stale delivery (a duplicate, or one produced by the handoff ping-pong):
                        // drop it and DO NOT push ours back. The app finds out its copy is behind on
                        // its next read — `PinsSync.decide` pushes the winner back there, where both
                        // keys have already landed. Replying here is what made the two stores
                        // oscillate. See [PinsSync.shouldApplyPush].
                        logger(Log.WARN, "PIN_SYNC_FROM_APP_STALE rev=$rev localRev=$localRev", null)
                    }
                }

                // 「已选功能」走同一条广播，规则一模一样。
                val functions = intent?.getStringExtra(PinSyncReceiver.EXTRA_FUNCTIONS)
                if (functions != null && PinSyncReceiver.isPlausibleFunctionsValue(functions)) {
                    val rev = intent.getLongExtra(PinSyncReceiver.EXTRA_FUNCTIONS_REV, 0L)
                    val localBackend = functionsLocal
                    val localRev = localBackend.readRev()
                    if (PinsSync.shouldApplyPush(rev, localRev)) {
                        localBackend.writeWithRev(functions, if (rev > 0L) rev else System.currentTimeMillis())
                        functionsOverlay = null
                        logger(
                            Log.INFO,
                            "FUNCTION_SYNC_FROM_APP_APPLIED len=${functions.length} rev=$rev",
                            null,
                        )
                        mainHandler.post { if (moreUp) moreContent?.refreshFunctionPins() }
                    } else {
                        logger(Log.WARN, "FUNCTION_SYNC_FROM_APP_STALE rev=$rev localRev=$localRev", null)
                    }
                }

                // 应用 + 功能混排顺序：同样的交接规则。
                val order = intent?.getStringExtra(PinSyncReceiver.EXTRA_ORDER)
                if (order != null && PinSyncReceiver.isPlausibleOrderValue(order)) {
                    val rev = intent.getLongExtra(PinSyncReceiver.EXTRA_ORDER_REV, 0L)
                    val localBackend = orderLocal
                    val localRev = localBackend.readRev()
                    if (PinsSync.shouldApplyPush(rev, localRev)) {
                        localBackend.writeWithRev(order, if (rev > 0L) rev else System.currentTimeMillis())
                        orderOverlay = null
                        logger(
                            Log.INFO,
                            "PINNED_ORDER_SYNC_FROM_APP_APPLIED len=${order.length} rev=$rev",
                            null,
                        )
                        mainHandler.post { if (moreUp) moreContent?.refreshPins() }
                    } else {
                        logger(Log.WARN, "PINNED_ORDER_SYNC_FROM_APP_STALE rev=$rev localRev=$localRev", null)
                    }
                }
            }
        }
        runCatching {
            host.registerReceiver(
                syncFromApp,
                IntentFilter(PinSyncReceiver.ACTION_TO_SYSTEMUI),
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess { syncFromAppReceiver = syncFromApp }

        // 管理页（App 进程）点「聚合支付」时联系人条在 SystemUI 这边，发广播让控制器去弹
        // （AggregatePayment.requestShow）。同样导出 + 只认模块 App。
        val quickPay = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != AggregatePayment.ACTION_SHOW) return
                if (!isFromModuleApp(this)) {
                    logger(Log.WARN, "QUICK_PAY_SHOW_REJECTED sender=${runCatching { sentFromUid }.getOrNull()}", null)
                    return
                }
                mainHandler.post { quickPayTrigger?.invoke() }
            }
        }
        runCatching {
            host.registerReceiver(
                quickPay,
                IntentFilter(AggregatePayment.ACTION_SHOW),
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess { quickPayReceiver = quickPay }
            .onFailure { logger(Log.WARN, "QUICK_PAY_REGISTER_FAILED", it) }

        // SCREEN_OFF → retract (original mo864f :1049-1056 → m9254Z)
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                mainHandler.post {
                    hideMorePanel(animated = false)
                    retract("SCREEN_OFF")
                }
            }
        }
        runCatching {
            host.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess { screenOffReceiver = r }

        // 扇形第一次打开时补一次扫描；force=false 让 30s 扫描缓存挡掉重复的全量扫描。
        scanShortcuts("INIT", force = false, delayMs = 2_000L)
    }

    /**
     * 枚举 → 上报快捷方式，只给 hook 侧用（App 进程没有 ACCESS_SHORTCUTS，读的是缓存）。
     *
     * 每次留一行 `SHORTCUT_SCAN`：功能页空着的时候，诊断页要能区分「SystemUI 没扫到」和
     * 「扫到了但没送到 App」——没有这行标记就只能靠猜。
     */
    private fun scanShortcuts(reason: String, force: Boolean = false, delayMs: Long = 0L) {
        scope.launch {
            if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
            val started = SystemClock.uptimeMillis()
            val report = runCatching {
                com.repl.bubbledrawer.shortcut.ShortcutRepository.scanAndReportFromSystemUi(host, force)
            }.getOrNull()
            logger(
                Log.INFO,
                "SHORTCUT_SCAN reason=$reason items=${report?.items ?: -1} " +
                    "result=${report?.result?.name ?: "FAILED"} ms=${SystemClock.uptimeMillis() - started}",
                null,
            )
        }
    }

    /** 广播是否来自模块自己的 App 进程（hook 进程问 PackageManager 要发送方 uid 的包名）。 */
    private fun isFromModuleApp(receiver: android.content.BroadcastReceiver): Boolean = runCatching {
        val uid = receiver.sentFromUid
        if (uid == android.os.Process.myUid()) return@runCatching true
        host.packageManager.getPackagesForUid(uid)?.contains(ModuleResources.MODULE_PACKAGE) == true
    }.getOrDefault(false)

    // ---------------- app list freshness (the boot/locked-enumeration trap) ----------------

    private var unlockReceiver: android.content.BroadcastReceiver? = null

    /**
     * The module is injected into SystemUI at BOOT, i.e. while the user is still locked — and
     * in that state the platform answers package queries with a tiny direct-boot-shaped
     * subset. Measured on device (2026-10-09, uid 10231 com.android.systemui): the fan's own
     * `AppRepository` enumerated **2** apps (设置 + 联系人) at boot versus **216** after
     * unlock, and because that answer sits in `AppRepository.cache` for the life of this
     * process, the 更多 panel showed those two apps until SystemUI was restarted by hand.
     *
     * Nothing else re-reads the list, so the unlock is the moment to do it: reload, then
     * rebind whatever is on screen. [Intent.ACTION_BOOT_COMPLETED] is the belt-and-braces
     * second chance (a device without a secure lock never gets USER_UNLOCKED, and there the
     * boot-time answer was already correct).
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = reloadApps(i?.action ?: "UNLOCK")
        }
        runCatching {
            host.registerReceiver(
                r,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            unlockReceiver = r
            logger(Log.INFO, "APP_LIST_RELOAD_ARMED", null)
        }.onFailure { logger(Log.WARN, "APP_LIST_RELOAD_ARM_FAILED", it) }
    }

    /** Re-enumerate when the cached list cannot be trusted; rebind the open fan/panel. */
    private fun reloadApps(reason: String) {
        if (!repo.cacheNeedsRefresh()) {
            logger(Log.INFO, "APP_LIST_RELOAD_SKIPPED reason=$reason total=${repo.cachedAll().size}", null)
            return
        }
        scope.launch {
            val total = runCatching { repo.loadAll() }.getOrNull()?.size ?: -1
            logger(Log.INFO, "APP_LIST_RELOADED reason=$reason total=$total", null)
            mainHandler.post {
                val snap = RemotePrefs.read(prefs)
                if (moreUp) {
                    // The panel is reading the old list: rebuild it in place.
                    moreContent?.reload()
                } else if (dock.isBusy) {
                    dock.show(
                        currentCorner(), screenW(), screenH(),
                        snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend, snap.fanLayers,
                        snap.recommendEnabled, outerRadiusDpOf(snap),
                    )
                }
            }
            scanShortcuts("APP_LIST_RELOAD")
        }
    }

    /** adb verification hook — see `CornerInputMonitor.registerDebugReceiver`. */
    fun toggleMorePanelForDebug() {
        if (moreUp) hideMorePanel() else showMorePanel()
    }

    private var activeCorner = Corner.BOTTOM_LEFT
    private fun currentCorner(): Corner = activeCorner

    private fun screenW(): Int = wm.currentWindowMetrics.bounds.width()
    private fun screenH(): Int = wm.currentWindowMetrics.bounds.height()

    /**
     * 外圈半径（dp）传给扇形：**开关关着就是 0 = 自动**（外圈 = 内圈 + 一圈图标 + 径向间隙）。
     *
     * 收在这里是因为 `dock.show` 有四条入口（角落手势 ×2、磁贴/广播、重绑），四处各写一遍三元只会
     * 让"开关到底生没生效"变成四个可以走偏的地方。
     */
    private fun outerRadiusDpOf(snap: RemotePrefs.Snapshot): Int =
        if (snap.fanOuterRadiusEnabled) snap.fanOuterRadiusDp else 0

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /**
     * 这一笔被拦掉了（黑名单 / 横屏两次滑动）：`forward` 必须把后续事件整体丢掉，
     * 否则扇形没画出来、dock 却还在收事件（会点中"看不见的格子"）。
     */
    private var fanSuppressed = false

    /** 横屏两次滑动：第一次上滑的时刻与哪一侧（0 = 没有待确认的第一次）。 */
    private var pendingSwipeAt = 0L
    private var pendingSwipeSide: SpySide? = null
    /** Corner claim: raise windows, bind fresh pins, seed the launcher with the
     *  synthetic DOWN at the press point (the real DOWN stayed with the app under
     *  the corner — the spy was only observing until pilfer), then arm the timeout. */
    fun show(side: SpySide, downX: Float, downY: Float, screenW: Int, screenH: Int) {
        if (moduleContext == null) return // no module resources → no fan (fail closed)
        val km = host.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return
        val pm = host.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == false) return
        hideMorePanel(animated = false) // a fresh stroke always starts from the fan
        // The fan renders tiles from the cached list; if that list is the boot's locked-user
        // subset, refresh in the background so the NEXT stroke (and the 更多 panel) is whole.
        if (repo.cacheNeedsRefresh()) reloadApps("FAN_SHOW")
        val snap = RemotePrefs.read(prefs)
        // 用户 2026-10-18 的两个开关：黑名单里的应用、横屏的第一次上滑 —— 都不弹扇形。
        // 拦掉之后必须让 forward() 把这一笔的后续事件也丢掉（见 fanSuppressed）。
        val blocked = suppressionReason(side, screenW, screenH, snap)
        if (blocked != null) {
            fanSuppressed = true
            logger(Log.INFO, "FAN_SUPPRESSED reason=$blocked side=$side pkg=${foregroundPackage()}", null)
            return
        }
        fanSuppressed = false
        activeCorner = if (side == SpySide.LEFT) Corner.BOTTOM_LEFT else Corner.BOTTOM_RIGHT
        raiseFanWindows()
        dock.previewMode = false
        dock.show(
            activeCorner, screenW, screenH,
            snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend, snap.fanLayers,
            snap.recommendEnabled, outerRadiusDpOf(snap),
        )
        lastTouchX = downX
        lastTouchY = downY
        pressureDetector.startListening(settleMs = 280L)
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, downX, downY, 0)
        dock.forward(down, 0)
        down.recycle()
        armTimeout() // reference :186-187 (beginGesture → postDelayed(timeout, …))
    }

    /**
     * 这一笔该不该被拦掉，返回原因（null = 正常弹扇形）。顺序：黑名单优先于"横屏两次"。
     *
     * 用户 2026-10-18：「在启用角落手势下面加一个手势黑名单和横屏时需滑动两次才打开的开关」。
     * 黑名单看的是**前台应用的包名**（角落里滑出来的时候，用户眼睛盯着的是那个应用）；
     * 横屏两次则记下第一次的侧别与时刻，[RemotePrefs.FAN_SECOND_SWIPE_MS] 内同一侧再来一次才算数。
     */
    private fun suppressionReason(
        side: SpySide,
        screenW: Int,
        screenH: Int,
        snap: RemotePrefs.Snapshot,
    ): String? {
        val pkg = foregroundPackage()
        if (pkg != null && snap.fanBlacklist.contains(pkg)) return "BLACKLIST"
        if (!snap.fanLandscapeTwoSwipes) return null
        if (!com.repl.bubbledrawer.launch.isWideScreen(screenW, screenH)) return null
        val now = SystemClock.uptimeMillis()
        val pending = pendingSwipeAt
        if (pending != 0L && pendingSwipeSide == side && now - pending <= RemotePrefs.FAN_SECOND_SWIPE_MS) {
            pendingSwipeAt = 0L
            pendingSwipeSide = null
            logger(Log.INFO, "FAN_TWO_SWIPE_CONFIRMED side=$side gapMs=${now - pending}", null)
            return null
        }
        pendingSwipeAt = now
        pendingSwipeSide = side
        logger(Log.INFO, "FAN_TWO_SWIPE_FIRST side=$side", null)
        runCatching {
            android.widget.Toast.makeText(context, R.string.fan_second_swipe_hint, android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { logger(Log.WARN, "FAN_TOAST_FAILED", it) }
        return "TWO_SWIPES"
    }

    /** 前台应用包名（黑名单用）。取不到就返回 null = 不拦（宁可多弹一次，也不要永久失效）。 */
    private fun foregroundPackage(): String? = runCatching {
        val am = host.getSystemService(android.app.ActivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    /** A pilfered MOVE/UP in SCREEN coords — one continuous stream, so the
     *  release always arrives (this is what the old two-window handoff lost). */
    fun forward(ev: MotionEvent) {
        // 这一笔在 show() 里被拦掉了（黑名单 / 横屏第一次上滑）：扇形没有画出来，dock 也不能收事件，
        // 否则会点中"看不见的格子"（甚至启动应用）。
        if (fanSuppressed) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                fanSuppressed = false
            }
            return
        }
        dock.forward(ev, 0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastTouchX
                val dy = ev.y - lastTouchY
                if (kotlin.math.hypot(dx, dy) > 15f) {
                    pressureDetector.onMotionOccurred(180L)
                }
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressureDetector.stopListening()
                // 0 秒 = **松手直接收起**（用户 2026-10-18：「在…不操作自动收起时间里滑块添加一个 0 秒，
                // 即松手直接收起」）。以前 0 表示"永不收起"，所以这里要单独接一下：
                // 放到队列尾部，让这一笔 UP 先把"点没点中某个格子"处理完（点中时它自己会收）。
                if (RemotePrefs.read(prefs).fanAutoRetractSec <= 0) {
                    mainHandler.post {
                        // retract() 自己会打 `FAN_RETRACT_ON_RELEASE`，这里不再重复一行。
                        if (dock.isBusy) retract("ON_RELEASE")
                    }
                }
            }
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
        }
        armTimeout() // reference :194-195 (updateGesture re-arms the timeout every event)
    }

    /** Spy stream died mid-gesture (system stole the gesture, multi-finger,
     *  ACTION_CANCEL) → collapse, mirroring the engine's Cancel path. */
    fun cancelExternal() = retract("STREAM_END")

    /** Re-bind the fan's tiles from the current pins/apps without waiting for a new stroke. */
    private fun rebindFanIfBusy() {
        val snap = RemotePrefs.read(prefs)
        mainHandler.post {
            if (dock.isBusy) {
                dock.show(
                    currentCorner(), screenW(), screenH(),
                    snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend, snap.fanLayers,
                    snap.recommendEnabled, outerRadiusDpOf(snap),
                )
            }
        }
    }

    private fun retract(source: String) {
        pressureDetector.stopListening()
        mainHandler.removeCallbacks(timeout)
        logger(Log.INFO, "FAN_RETRACT_$source", null)
        dock.forceRetract()
    }

    // ---------------- 气压重按校准（会话本体） ----------------

    /**
     * 校准专用的 Handler。
     *
     * 与 [mainHandler] **分成两个实例**，是为了让「取消校准超时」与扇形自己的 timeout 队列
     * （`armTimeout`/`tearFanWindowsNow` 的 `removeCallbacks`）互不干扰：两个 Handler 共享同一个
     * Looper 队列，所以事件顺序仍是主线程的顺序，只是各自的清理只清自己那一条。
     *
     * 注意别搞错对象：`FanPressureDetector` 里 `stopListening()` 清的是**它自己**的 mainHandler
     * （投递翻页用），从来不会清这个 —— 真正要防的是 FanHost 自己的 timeout 清理顺手把会话的
     * 结算回调删掉。
     */
    private val calibrationHandler = Handler(Looper.getMainLooper())

    /**
     * 开一个校准会话：采集按压峰值、广播进度，按满 [PressureCalibration.REQUIRED_ATTEMPTS] 次
     * 或超时后算阈值。
     */
    private fun startCalibration(reason: String) {
        if (calibrating) {
            logger(Log.DEBUG, "PRESSURE_CALIBRATION_ALREADY_ACTIVE reason=$reason", null)
            return
        }
        if (!pressureDetector.isSupported) {
            logger(Log.WARN, "PRESSURE_CALIBRATION_UNSUPPORTED reason=$reason", null)
            sendCalibrationEvent(PressureCalibrationBridge.Event(PressureCalibrationState.Stage.UNSUPPORTED))
            return
        }
        calibrating = true
        calibrationDeltas.clear()
        calibrationAttempts = 0
        // 校准期间 detector 只报按压、不翻页（见 FanPressureDetector.calibrationSink）。
        pressureDetector.calibrationSink = { peak -> calibrationHandler.post { handleCalibrationPress(peak) } }
        calibrationHandler.removeCallbacks(calibrationTimeout)
        calibrationHandler.postDelayed(calibrationTimeout, PressureCalibration.SESSION_TIMEOUT_MS)
        ensureCalibrationHint()
        sendCalibrationEvent(PressureCalibrationBridge.Event(PressureCalibrationState.Stage.STARTED))
        logger(
            Log.INFO,
            "PRESSURE_CALIBRATION_STARTED reason=$reason timeoutMs=${PressureCalibration.SESSION_TIMEOUT_MS} " +
                "sensor=${pressureDetector.pressureSensor?.name} periodUs=${pressureDetector.sensorPeriodUs} " +
                "effective=${RemotePrefs.pressureThresholdFor(RemotePrefs.read(prefs))}",
            null,
        )
    }

    /** 一次按压结束（传感器线程经 [calibrationHandler] 转投递过来）。 */
    private fun handleCalibrationPress(peak: Float) {
        if (!calibrating) return
        val delta = if (peak.isFinite() && peak > 0f) peak else 0f
        // 光是越过分段门限（0.08 hPa）**不算**一个有效样本：那样 5 次轻点也会「校准成功」，
        // 而 (min+median)/2 会把阈值压到 0.2 hPa 以下 —— 于是打字/走路的噪声就能触发翻页，
        // 比「还没校准」更糟。所以样本另有下限（见 PressureCalibration.MIN_SAMPLE_HPA），
        // 达不到就照参考实现的办法剔除并告诉用户「这次太轻」，次数照算。
        val valid = PressureCalibration.isUsableSample(delta)
        if (valid) calibrationDeltas.add(delta)
        calibrationAttempts = minOf(PressureCalibration.REQUIRED_ATTEMPTS, calibrationAttempts + 1)
        sendCalibrationEvent(
            PressureCalibrationBridge.Event(
                PressureCalibrationState.Stage.SAMPLE,
                attempts = calibrationAttempts,
                delta = delta,
                valid = valid,
                validCount = calibrationDeltas.size,
            ),
        )
        updateCalibrationHint()
        logger(
            Log.INFO,
            "PRESSURE_CALIBRATION_SAMPLE attempt=$calibrationAttempts " +
                "valid=$valid delta=$delta validCount=${calibrationDeltas.size}",
            null,
        )
        if (PressureCalibration.shouldFinish(calibrationAttempts)) {
            finishCalibration(PressureCalibrationState.Stage.RESULT)
        } else {
            // 参考实现每记到一次按压就把 120s 的超时窗口重新计时（schedulePressureCalibrationTimeout）；
            // 超时是「中途走开」的兜底，不是从点「开始」起的总时限。
            calibrationHandler.removeCallbacks(calibrationTimeout)
            calibrationHandler.postDelayed(calibrationTimeout, PressureCalibration.SESSION_TIMEOUT_MS)
        }
    }

    /**
     * 收尾并广播结果。[reason] 只在「样本不够」时决定报哪个阶段：按满 5 次但有效样本不足
     * [PressureCalibration.MIN_VALID_SAMPLES] → `RESULT(valid=false)`；超时 → `TIMEOUT`。
     * 超时同样会把已经收集到的样本算一次（参考实现 `onPressureCalibrationTimeout` →
     * `finishPressureCalibration()`）：按了 4 次走开，有 3 个有效样本就不该白按。
     */
    private fun finishCalibration(reason: String) {
        if (!calibrating) return
        calibrating = false
        pressureDetector.calibrationSink = null
        calibrationHandler.removeCallbacks(calibrationTimeout)
        removeCalibrationHint()
        val result = PressureCalibration.calibrate(calibrationDeltas)
        if (result.valid) {
            // 立即生效：不等 App 落盘再经 prefs 回推（事件会把同一个值写进 prefs，两处必然一致）。
            pressureDetector.thresholdHpa = result.thresholdHpa
            sendCalibrationEvent(
                PressureCalibrationBridge.Event(
                    PressureCalibrationState.Stage.RESULT,
                    attempts = calibrationAttempts,
                    delta = calibrationDeltas.lastOrNull() ?: 0f,
                    valid = true,
                    threshold = result.thresholdHpa,
                    validCount = result.validCount,
                ),
            )
            logger(
                Log.INFO,
                "PRESSURE_CALIBRATION_FINISHED reason=$reason valid=true validCount=${result.validCount} " +
                    "threshold=${result.thresholdHpa} clamped=${result.clamped} attempts=$calibrationAttempts",
                null,
            )
        } else {
            val stage = if (reason == PressureCalibrationState.Stage.TIMEOUT) {
                PressureCalibrationState.Stage.TIMEOUT
            } else {
                PressureCalibrationState.Stage.RESULT
            }
            sendCalibrationEvent(
                PressureCalibrationBridge.Event(
                    stage,
                    attempts = calibrationAttempts,
                    valid = false,
                    validCount = result.validCount,
                ),
            )
            logger(
                Log.INFO,
                "PRESSURE_CALIBRATION_FINISHED reason=$reason valid=false " +
                    "validCount=${result.validCount} attempts=$calibrationAttempts",
                null,
            )
        }
        calibrationDeltas.clear()
        calibrationAttempts = 0
    }

    /** 用户取消 / 热重载：不算、不广播（取消本来就是 App 自己写下的请求）。 */
    private fun cancelCalibration(reason: String) {
        if (!calibrating) return
        calibrating = false
        pressureDetector.calibrationSink = null
        calibrationHandler.removeCallbacks(calibrationTimeout)
        calibrationHandler.removeCallbacks(calibrationAck)
        pendingTerminalEvent = null
        removeCalibrationHint()
        calibrationDeltas.clear()
        calibrationAttempts = 0
        logger(Log.INFO, "PRESSURE_CALIBRATION_CANCELLED reason=$reason", null)
    }

    /**
     * 把一次校准事件交给 App：**provider 优先（同步、有回执），失败再走广播**。
     *
     * 两条通道并存是 2026-10-13 那次修复的核心：广播没有回执，而它的发送方校验依赖
     * `sentFromUid` / `sentFromPackage` —— 这两个在**清单注册**的接收者上不保证有值，拿不到就
     * 一律拒绝，SystemUI 这边却以为送到了。用户看到的就是「按满 5 次，页面还停在取消校准」。
     * provider 用 `Binder.getCallingUid()` 判定身份，并把「受理了」作为返回值带回来。
     *
     * @return `"provider"` / `"broadcast"` / `"none"`（只表示送出去了，不代表 App 一定落盘 ——
     *         落盘由终局事件的回执检查 [calibrationAck] 负责）
     */
    private fun deliverCalibrationEvent(event: PressureCalibrationBridge.Event): String {
        val applied = runCatching {
            host.contentResolver.call(
                PressureCalibrationBridge.uri(),
                PressureCalibrationBridge.METHOD_EVENT,
                null,
                event.toBundle(),
            )?.getBoolean(PressureCalibrationBridge.EXTRA_APPLIED) == true
        }.getOrDefault(false)
        if (applied) return "provider"

        val sent = runCatching {
            PinSyncReceiver.sendBroadcastWithIdentity(
                host,
                event.toIntent().setClassName(
                    ModuleResources.MODULE_PACKAGE,
                    "com.repl.bubbledrawer.xposed.PressureCalibrationReceiver",
                ),
            )
            true
        }.getOrDefault(false)
        return if (sent) "broadcast" else "none"
    }

    private fun sendCalibrationEvent(event: PressureCalibrationBridge.Event) {
        val via = deliverCalibrationEvent(event)
        logger(
            if (via == "none") Log.WARN else Log.INFO,
            "PRESSURE_CALIBRATION_SENT via=$via stage=${event.stage} attempts=${event.attempts}",
            null,
        )
        if (via == "none") {
            // 两条通道都没送出去（App 被强停/未装）：会话照常按本地结果结束，用户重试即可 ——
            // 但不能假装成功，日志要留证据。
            logger(Log.WARN, "PRESSURE_CALIBRATION_NOT_DELIVERED stage=${event.stage}", null)
        }
        if (event.stage == PressureCalibrationState.Stage.RESULT ||
            event.stage == PressureCalibrationState.Stage.TIMEOUT
        ) {
            // 终局事件是「页面能不能从校准中出来」的唯一依据：送出去之后再确认一次 App 真的写下了
            // （active 是 App 自己的键，SystemUI 读得到）。没落地就重投一次 —— App 侧对重复是幂等的
            //（状态机只在真的变了才写 prefs），所以重投不会把已完成的校准弄乱。
            pendingTerminalEvent = event
            calibrationHandler.removeCallbacks(calibrationAck)
            calibrationHandler.postDelayed(calibrationAck, CALIBRATION_ACK_DELAY_MS)
        }
    }

    /** 终局事件的回执检查（跑在主线程，[calibrationHandler]）。 */
    private val calibrationAck = Runnable {
        val event = pendingTerminalEvent ?: return@Runnable
        pendingTerminalEvent = null
        val stillPending = runCatching { RemotePrefs.read(prefs).fanPressureCalibrationActive }.getOrDefault(false)
        if (!stillPending) return@Runnable
        val via = deliverCalibrationEvent(event)
        logger(
            Log.WARN,
            "PRESSURE_CALIBRATION_RESULT_RETRY via=$via stage=${event.stage}",
            null,
        )
    }

    /** 会话进行中且扇形开着时，在屏幕上方显示「气压校准 n/5」。 */
    private fun ensureCalibrationHint() {
        if (!calibrating) return
        val canvasView = canvas ?: return
        if (calibrationHint == null) {
            val density = context.resources.displayMetrics.density
            val hint = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                val padH = (14 * density).toInt()
                val padV = (8 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor(Color.parseColor("#CC101418"))
                }
                // 不消费触摸：非 clickable 的 View 在 dispatchTouchEvent 里一律返回 false，事件
                // 照常落到 canvas —— 一行提示绝不能挡住扇形手势。
                isClickable = false
                isFocusable = false
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
            lp.topMargin = (96 * density).toInt()
            // addView/改 text 都是 View 操作，只有主线程能做；包一层是为了「窗口正在被拆」
            // 这类边界不把 SystemUI 带崩（会话本身不依赖这条提示）。
            runCatching { canvasView.addView(hint, lp) }
                .onSuccess { calibrationHint = hint }
                .onFailure { logger(Log.WARN, "PRESSURE_CALIBRATION_HINT_FAILED", it) }
        }
        updateCalibrationHint()
    }

    private fun updateCalibrationHint() {
        val hint = calibrationHint ?: return
        runCatching {
            hint.text = context.getString(
                R.string.fan_pressure_calibration_hint,
                calibrationAttempts,
                PressureCalibration.REQUIRED_ATTEMPTS,
            )
        }
    }

    private fun removeCalibrationHint() {
        val hint = calibrationHint ?: return
        calibrationHint = null
        // 只有主线程能动 View。热重载的 dispose 路径可能不在主线程（见 destroy → cancelCalibration），
        // 那时 canvas 紧接着就会被 removeViewImmediate 整个拆掉，提示跟着消失，不必单独移除。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { canvas?.removeView(hint) }
        }
    }

    /** adb 验证钩子（见 `CornerInputMonitor.registerDebugReceiver`）：不碰真机触摸也能走完整个会话。 */
    fun debugPressureCalibration(command: String, delta: Float) {
        when (command) {
            "start" -> startCalibration("DEBUG")
            "sample" -> calibrationHandler.post { handleCalibrationPress(delta) }
            "finish" -> calibrationHandler.post { finishCalibration(PressureCalibrationState.Stage.RESULT) }
            "cancel" -> cancelCalibration("DEBUG")
            else -> logger(Log.WARN, "DEBUG_PRESSURE_UNKNOWN command=$command", null)
        }
    }

    private fun onHeavyPressTriggered() {
        if (!fanWindowsUp || !dock.isBusy) return
        if (!pressureDetector.isTouchActive) return
        // 需注意手指停在app图标或更多上不能触发翻页
        if (dock.launcher.isHoveringItem) {
            logger(Log.DEBUG, "FAN_PRESSURE_IGNORED_HOVER index=${dock.launcher.currentHoveredIndex}", null)
            return
        }
        val turned = dock.nextPage()
        if (turned) {
            logger(Log.INFO, "FAN_PRESSURE_PAGE_TURN page=${dock.currentPage} total=${dock.totalPages}", null)
            performHeavyPressHaptic()
        }
    }

    private fun performHeavyPressHaptic() {
        val vibrator = defaultVibrator()
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vibrator.vibrate(effect)
            } catch (_: Throwable) {
                try {
                    val effect = VibrationEffect.createOneShot(35L, 180)
                    vibrator.vibrate(effect)
                } catch (_: Throwable) {
                    dock.launcher.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
        } else {
            dock.launcher.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * 扇形滑出时的振动（设置项「滑出时振动」，默认开启）。
     *
     * Deliberately lighter than [performHeavyPressHaptic]: this fires on every fan opening, so it uses
     * the platform's TICK — the same class of feedback as the per-icon hover tick
     * (`GestureAppLauncher` :553-554), just once for the whole panel.
     */
    private fun maybeVibrateFanShown() {
        if (!RemotePrefs.read(prefs).fanShowVibrate) return
        val vibrator = defaultVibrator()
        if (vibrator != null && vibrator.hasVibrator()) {
            val done = runCatching {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                true
            }.getOrDefault(false)
            if (done) return
            val shot = runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(20L, 120))
                true
            }.getOrDefault(false)
            if (shot) return
        }
        dock.launcher.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun defaultVibrator(): Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31) {
        host.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        host.getSystemService(Vibrator::class.java)
    }

    private fun armTimeout() {
        // 扇形滑出后不点选就自动收回：每次事件（含 UP/CANCEL）重新计时，时长来自设置。
        // **0 = 松手即收**（不再表示"永不收起"，用户 2026-10-18）：那一下由 [forward] 的
        // ACTION_UP/CANCEL 分支处理，这里只负责"滑出后一直不动"的那种计时。
        mainHandler.removeCallbacks(timeout)
        if (!dock.isBusy) return
        val sec = RemotePrefs.read(prefs).fanAutoRetractSec
        if (sec <= 0) return
        mainHandler.postDelayed(timeout, sec * 1000L)
    }

    // ---------------- tile building ----------------

    /** slide_gesture_list_item.xml (verbatim port) built in code —
     *  SlideGestureItemView padding=slide_gesture_item_padding (:5-7 of the layout),
     *  inner FrameLayout wrap (:9-17), icon sized by the 图标大小 setting (default =
     *  launcher_app_item_icon_width 44dp) and shaped by 图标形状 ([SlideIconView]).
     *  Direct construction: LSPosed loads our whole dex once, so this IS the same
     *  class identity GestureAppLauncher uses (ring drawing included). */
    private fun buildTile(item: GestureAppLauncher.AdapterItem): View {
        val res = context.resources
        // 图标大小/形状是用户设置（每次 bind 读一次；条目只有 5–7 个）。
        val snap = RemotePrefs.read(prefs)
        val iconPx = (snap.fanIconDp * res.displayMetrics.density).toInt()
        val tile = SlideGestureItemView(context)
        tile.iconShape = snap.fanIconShape
        val pad = res.getDimensionPixelSize(R.dimen.slide_gesture_item_padding)
        tile.setPadding(pad, pad, pad, pad)
        val inner = FrameLayout(context).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val icon = SlideIconView(context).apply {
            id = R.id.slide_icon // SlideGestureItemView:73 findViewById(R.id.slide_icon)
            shape = snap.fanIconShape
            layoutParams = FrameLayout.LayoutParams(iconPx, iconPx)
        }
        when (item) {
            is GestureAppLauncher.AdapterItem.AppItem -> icon.setImageDrawable(repo.icon(item.app, snap.cloneBadge))
            is GestureAppLauncher.AdapterItem.FunctionItem -> {
                // 扇形的 tile 没有文字，所以图标就是全部信息：优先用快捷方式自己的图标
                // （最近一次枚举结果带回来的那张），其次内建条目自带的 drawable（聚合支付），
                // 最后才退回宿主应用图标。
                val bmp = item.shortcut.iconBitmap
                val drawable = item.shortcut.iconDrawable
                when {
                    bmp != null && !bmp.isRecycled ->
                        icon.setImageDrawable(android.graphics.drawable.BitmapDrawable(res, bmp))
                    drawable != null -> icon.setImageDrawable(drawable)
                    else -> icon.setImageDrawable(
                        repo.icon(
                            com.repl.bubbledrawer.pinyin.BubbleApp(item.shortcut.packageName, item.shortcut.label),
                            snap.cloneBadge,
                        ),
                    )
                }
            }
            GestureAppLauncher.AdapterItem.More ->
                runCatching { icon.setImageResource(R.drawable.icon_gesture_more_app) }
        }
        inner.addView(icon)
        tile.addView(inner)
        // ORIGINAL: the adapter sets a click listener on every tile (C2937F.m9712g :142 →
        // C2937F$a.onClick :56-69 → mo9283j(item, view, reason=1)) — same timing as the
        // factory itself, so taps work even while the fan is still expanding.
        tile.setOnClickListener { dock.launcher.clickSelect(item, tile) }
        return tile
    }

    // ---------------- windows (same pair as the legacy OverlayHost) ----------------

    private fun raiseFanWindows() {
        if (fanWindowsUp) return
        (dock.launcher.parent as? android.view.ViewGroup)?.removeView(dock.launcher)

        // scrim BELOW — pure dim, untouchable (framework small-window dim analogue)
        val s = View(context).apply {
            background = ColorDrawable(SCRIM_COLOR)
            alpha = 0f
        }
        wm.addView(s, fullScreenParams("bubble slide scrim", touchable = false))
        s.animate().alpha(1f).setDuration(FADE).start()
        scrim = s

        // canvas ABOVE, TOUCHABLE — AppLauncherWindow.m9248S :640-650 flags 16777984
        // has NO 0x10 NOT_TOUCHABLE; icon children carry the click listener
        // (C2937F$a.onClick :56-69, wired in buildTile). The drag stroke itself
        // comes from the SPY, not from this window. 收起 / 翻页的判据见 [FanCanvas]。
        val c = FanCanvas(context).apply {
            addView(
                dock.launcher,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        wm.addView(c, fullScreenParams("bubble slide gesture", touchable = true))
        canvas = c
        fanWindowsUp = true
        // 校准会话进行中划出扇形 → 顶部的「n/5」提示跟着窗口一起上来。
        ensureCalibrationHint()
    }

    private fun fadeOutFanWindows() {
        val s = scrim
        if (!fanWindowsUp) return
        if (s != null) {
            s.animate().alpha(0f).setDuration(FADE).withEndAction { tearFanWindowsNow() }.start()
        } else {
            tearFanWindowsNow()
        }
    }

    private fun tearFanWindowsNow() {
        mainHandler.removeCallbacks(timeout)
        canvas?.let { runCatching { wm.removeViewImmediate(it) } }
        scrim?.let { runCatching { wm.removeViewImmediate(it) } }
        canvas = null
        scrim = null
        // 提示随 canvas 一起被移除了，只留引用没有意义（下次划出时 ensureCalibrationHint 会重建）。
        calibrationHint = null
        fanWindowsUp = false
    }

    /**
     * 扇形打开之后落在 canvas 上的**新触摸**（划出扇形的那一笔不走这里，它由 SPY 直接喂给 [dock]）。
     *
     * 原版 `C2822g.onTouchEvent` :381-393 是「落下即收起」；这里只多一个设置项：「双击外部关闭」
     * 打开时第一下先攒着、双击时限内的第二下才收起（用户 2026-10-17 要的开关，默认关）。
     */
    private inner class FanCanvas(context: Context) : FrameLayout(context) {

        private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** 双击模式下上一次「点在外面」的时间（0 = 没有待配对的第一下）。 */
        private var lastTapAt = 0L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // 手指刚落下：顺手重新计时「不操作自动收起」（双击模式下这一下并不收）。
                armTimeout()
                if (!doubleTapMode()) {
                    logger(Log.INFO, "FAN_VOID_TAP_CLOSE mode=single", null)
                    dock.onVoidTouchDown()
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (lastTapAt != 0L && now - lastTapAt <= doubleTapMs) {
                        lastTapAt = 0L
                        logger(Log.INFO, "FAN_VOID_TAP_CLOSE mode=double", null)
                        dock.onVoidTouchDown()
                    } else {
                        lastTapAt = now
                        logger(Log.INFO, "FAN_VOID_TAP_FIRST mode=double waitMs=$doubleTapMs", null)
                    }
                }
            }
            return true
        }

        private fun doubleTapMode(): Boolean = RemotePrefs.read(prefs).fanDoubleTapDismiss
    }

    // ---------------- 更多应用 panel (方案 B: pinned on the current 小窗) ----------------

    private var moreCatcher: View? = null
    private var morePanel: FrameLayout? = null
    private var moreContent: PinManageModel? = null
    private var moreUp = false
    private var moreRect: Rect? = null

    /**
     * Raise the 固定管理 page (`PinManageView`) as an overlay ON TOP of whatever is on
     * screen — inside apps too — instead of opening the full-screen manage Activity.
     *
     * Rect: ALWAYS the screen-centred [defaultPanelRect] (62 % of the display, the same
     * footprint the launch default uses), never the bounds of the current小窗. Following
     * the window was tried first and dropped: this ROM's `MiuiFreeFormManager` stack list
     * comes back empty from SystemUI's uid (`stacks=0`) and its rect fallback returns
     * off-screen bounds, so the panel jumped around and the 长/宽 sliders looked
     * inconsistent. Centred + percentage sliders keeps those two controls predictable.
     *
     * Windows: the fan's existing pair pattern — a touchable full-screen catcher
     * (tap outside closes, light dim) plus the panel itself at [rect]. Both keep the
     * fan's type-2024 + trusted-overlay identity, so they stay visible inside apps.
     */
    private fun showMorePanel() {
        if (moreUp || moduleContext == null) return
        val km = host.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return
        val pm = host.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == false) return
        // Placement is screen-centred and independent of whatever小窗 is open; the only
        // inputs are the two size sliders (see applyPanelSize).
        val panelSnap = RemotePrefs.read(prefs)
        val rect = applyPanelSize(defaultPanelRect(), panelSnap)
        moreRect = Rect(rect)

        val dismissMode = panelSnap.panelDismissOutside
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        var lastCatcherTapTime = 0L
        val catcher = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (dismissMode == RemotePrefs.DISMISS_OUTSIDE_SINGLE) {
                        hideMorePanel()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastCatcherTapTime <= doubleTapTimeout) {
                            hideMorePanel()
                            lastCatcherTapTime = 0L
                        } else {
                            lastCatcherTapTime = now
                        }
                    }
                }
                return true
            }
        }.apply {
            background = ColorDrawable(MORE_SCRIM_COLOR)
            isClickable = true
        }
        val catcherAdded = runCatching {
            wm.addView(catcher, fullScreenParams("bubble more catcher", touchable = true))
        }
        if (catcherAdded.isFailure) {
            logger(Log.WARN, "MORE_CATCHER_FAILED", catcherAdded.exceptionOrNull())
            return
        }
        moreCatcher = catcher

        // The panel hosts the SAME page the fan's 更多 tile always opened: the manage
        // page (方案 B). With the miuix Compose refactor the page IS the Activity's page —
        // PinManageScreen(chrome = PANEL) draws the 56dp look-alike bar itself. The theme
        // wrapper is still needed: it pins the window's DayNight resource resolution for
        // the View islands (LetterIndexBar) inside the Compose tree.
        val themed = ContextThemeWrapper(context, R.style.Theme_BubbleDrawer)
        val content = PinManageModel(
            loadApps = { repo.all() },
            pinsOf = { pinStore.pins() },
            onOrderChange = { order -> pinStore.setPins(order) },
            recommendConfig = {
                val snap = RemotePrefs.read(prefs)
                snap.recommendEnabled to snap.recommendCount
            },
            loadRecommendations = { all, pinned, count ->
                com.repl.bubbledrawer.data.predict.AppPredictor.getRecommendations(
                    context, all, pinned, count,
                )
            },
            // 分身角标：面板里的图标与扇子同源（都按这次快照决定要不要叠角标）。
            cloneBadge = RemotePrefs.read(prefs).cloneBadge,
            loadShortcuts = { onProgress ->
                com.repl.bubbledrawer.shortcut.ShortcutRepository.loadShortcuts(host, onProgress)
            },
            functionPinsOf = { readFunctionPins() },
            // 已选功能的详情（名称/宿主/启动 Intent）：面板据此渲染，不依赖自己那份枚举结果。
            pinnedFunctionItems = { functionDetailsForRender() },
            onFunctionOrderChange = { writeFunctionPins(it) },
            pinnedOrderOf = { readPinnedOrder() },
            onPinnedOrderChange = { writePinnedOrder(it) },
        )
        // Panel window root FIRST, then the Compose content inside it: the view-tree
        // owners must be tagged on the window root too (see PanelContentFactory.create
        // doc) because compose resolves the lifecycle owner at the compose-view ROOT,
        // not on the ComposeView itself.
        val panel = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    if (content.isSearching) {
                        content.cancelSearch()
                    } else {
                        hideMorePanel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                // MiuiMultiWindowUtils.FREEFORM_ROUND_CORNER = 25.8dp — match the ROM window
                cornerRadius = 26f * resources.displayMetrics.density
                setColor(themed.resources.getColor(R.color.fd_sys_color_surface_bright_default, null))
            }
            clipToOutline = true
            alpha = 0f
        }
        val composeView = PanelContentFactory.create(
            windowRoot = panel,
            context = themed,
            model = content,
            iconDp = panelSnap.panelIconDp,
            textSp = panelSnap.panelTextSp,
            onLaunch = { app -> launchFromPanel(app) },
            onClose = { hideMorePanel() },
            onFocusChange = { focusable ->
                val lp = panel.layoutParams as? WindowManager.LayoutParams ?: return@create
                if (focusable) {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                runCatching { wm.updateViewLayout(panel, lp) }
            },
            onLaunchShortcut = { item ->
                // 聚合支付不启动任何应用：它把联系人条切到聚合支付模式（Flyme QuickPayWindowHelper
                // 驱动同一个 C2831I 的行为），面板先收起，条在面板走后出现。
                if (com.repl.bubbledrawer.shortcut.AggregatePayment.isEntry(item)) {
                    quickPayTrigger?.invoke()
                } else {
                    // 面板在 SystemUI 里：小窗开关必须从远程组读（见 ShortcutRepository.launchShortcut
                    // 的注释），启动后收起面板（与 PanelContentFactory 的默认行为一致）。
                    ShortcutRepository.launchShortcut(host, item, RemotePrefs.read(prefs).freeform)
                }
                hideMorePanel()
            },
        )
        panel.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val panelAdded = runCatching { wm.addView(panel, panelParams("bubble more panel", rect)) }
        if (panelAdded.isFailure) {
            logger(Log.WARN, "MORE_PANEL_FAILED", panelAdded.exceptionOrNull())
            runCatching { wm.removeViewImmediate(catcher) }
            moreCatcher = null
            return
        }
        // Ground truth for "which page is in the panel?" — one line per rebuild. It has to come
        // from the model's own rebuild callback: the load is asynchronous (app list + the
        // recommendation pass), so logging right here and one frame later only ever printed
        // the empty initial state (items=0 letters=0) for a panel that was visibly full.
        content.onContentChanged = {
            val page = if (moreUp && moreContent === content) {
                content.describe(
                    themed.resources.getString(R.string.slide_launcher_tab_all_app),
                    themed.resources.getString(R.string.slide_launch_app_has_selected),
                )
            } else {
                "closed-before-rebuild"
            }
            // 已选功能的两半分开记：keys = 用户选了几条，items = 面板真能画出来几条。两个数不一样
            // 就说明详情没送到（或还没发布），页面会缺项 —— 这是「设置页选好了、面板里没有」的判据。
            panelLog(
                "MORE_PANEL_TREE $page functions=${content.functionPins.size}" +
                    " resolved=${content.pinnedFunctions.size}",
            )
        }
        content.reload()
        panel.animate().alpha(1f).setDuration(FADE).start()
        morePanel = panel
        moreContent = content
        moreUp = true
        panelLog(
            "MORE_PANEL_SHOW " + rect.toShortString() + " screen=" + screenW() + "x" + screenH() +
                " pct=" + panelSnap.panelWidthPct + "x" + panelSnap.panelHeightPct +
                " icon=" + panelSnap.panelIconDp + " text=" + panelSnap.panelTextSp +
                " page=" + content.describe(
                    themed.resources.getString(R.string.slide_launcher_tab_all_app),
                    themed.resources.getString(R.string.slide_launch_app_has_selected),
                ),
        )
    }

    /** Panel diagnostics: LSPosed's module log (lagged) plus logcat for live reading. */
    private fun panelLog(message: String) {
        logger(Log.INFO, message, null)
        runCatching { Log.println(Log.INFO, "BubbleDrawer", message) }
    }

    private fun hideMorePanel(animated: Boolean = true) {
        if (!moreUp) return
        moreUp = false
        moreContent?.cancelSearch()
        moreContent?.release()
        moreContent = null
        // 会话覆盖只为一件事存在：同一次面板会话里读到的必须是用户刚点的那个值（交接还没落地时
        // 也不能被旧值顶回去）。面板一关就没有「刚点的」了 —— 留着它反而会把读路径的自愈
        // （PinsSync.decide 的 pushBack）挡在门外：那次交接如果没送到 App，就再也没人补。
        pinsOverlay = null
        functionsOverlay = null
        moreCatcher?.let { runCatching { wm.removeViewImmediate(it) } }
        moreCatcher = null
        val panel = morePanel
        morePanel = null
        if (panel == null) return
        if (!animated) {
            runCatching { wm.removeViewImmediate(panel) }
            return
        }
        panel.animate().alpha(0f).setDuration(FADE)
            .withEndAction { runCatching { wm.removeViewImmediate(panel) } }
            .start()
        panelLog("MORE_PANEL_HIDE")
    }

    /**
     * Panel app click → launch exactly like a fan tile does.
     *
     * The panel's rect used to be handed over as the window's launch bounds, which gave the
     * 更多 panel's own shape (976x1859 here) to an app opened from it, while the fan gave the
     * ROM's small-window geometry (1220x1952) — two different shapes for the same action.
     * User ruling 2026-10-09: "面板矩形不应该存在，扇形打开的小窗和更多里打开的小窗应该一致";
     * the 更多 panel's own size remains a user setting and no longer leaks into the window.
     */
    private fun launchFromPanel(app: com.repl.bubbledrawer.pinyin.BubbleApp) {
        val rect = moreRect
        hideMorePanel(animated = false)
        val snap = RemotePrefs.read(prefs)
        val strategy: ILaunchStrategy =
            if (snap.freeform) FreeformLaunchStrategy(freeformTrigger()) else FullscreenLaunchStrategy()
        val ok = runCatching { strategy.launch(context, app) }.getOrDefault(false)
        LaunchCountStore.increment(context, app.packageName, app.userId)
        com.repl.bubbledrawer.data.predict.AppPredictor.recordLaunch(context, app.packageName, app.userId)
        panelLog(
            "MORE_LAUNCH " + app.packageName + "#" + app.userId + " freeform=" + snap.freeform +
                " rect=" + (rect?.toShortString() ?: "-") + " ok=" + ok,
        )
    }

    /**
     * 面板长宽：屏幕百分比（0 = 默认 [DEFAULT_PANEL_PCT]），始终以屏幕正中为基准。
     * 两个轴互不影响：只拖宽度时高度保持基准值，反之亦然。
     */
    private fun applyPanelSize(base: Rect, snap: RemotePrefs.Snapshot): Rect {
        val sw = screenW()
        val sh = screenH()
        val w = if (snap.panelWidthPct == 0) base.width() else sw * snap.panelWidthPct / 100
        val h = if (snap.panelHeightPct == 0) base.height() else sh * snap.panelHeightPct / 100
        val l = ((sw - w) / 2).coerceAtLeast(0)
        val top = ((sh - h) / 2).coerceAtLeast(0)
        return Rect(l, top, l + w, top + h)
    }

    /**
     * 基准矩形：屏幕正中默认占地 —— 与启动小窗的默认尺寸一致，
     * 所以从面板里点应用时，应用正好落在面板原来的位置。
     */
    private fun defaultPanelRect(): Rect {
        val w = screenW()
        val h = screenH()
        val pw = (w * RemotePrefs.DEFAULT_PANEL_W_PCT / 100f).toInt()
        val ph = (h * RemotePrefs.DEFAULT_PANEL_H_PCT / 100f).toInt()
        return Rect((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
    }

    /** Same window class as the SPY: type 2024 (NAVIGATION_BAR_PANEL), trusted
     *  overlay, fitInsets 0. The previous TYPE_APPLICATION_OVERLAY variant got
     *  mPolicyVisibility=false on HyperOS inside apps (isVisibleByPolicy=false —
     *  MIUI hides foreign APPLICATION_OVERLAY layers) even though WMS accepted
     *  the add; the 2024 spy windows proved visible in the same dump
     *  (frame=[0,2357][299,2656]). MATCH_PARENT + LAYOUT_IN_SCREEN + NO_LIMITS +
     *  cutout ALWAYS keeps origin == physical (0,0), so forwarded spy coordinates
     *  line up 1:1 (OverlayHost comment: getRealSize parity). */
    private fun fullScreenParams(title: String, touchable: Boolean) =
        baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            title,
            touchable,
        )

    /**
     * 更多面板窗口：按 [rect] 定位（定位窗口，不是全屏）。
     * Same type-2024 trusted-overlay identity as the fan; `LAYOUT_NO_LIMITS` +
     * `setFitInsetsTypes(0)` keep `x/y` in physical screen coordinates, so the panel
     * lands exactly on the centred rect [applyPanelSize] computed.
     */
    private fun panelParams(title: String, rect: Rect) =
        baseParams(rect.width(), rect.height(), title, touchable = true).apply {
            x = rect.left
            y = rect.top
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    private fun baseParams(width: Int, height: Int, title: String, touchable: Boolean) =
        WindowManager.LayoutParams(
            width,
            height,
            CornerInputMonitor.CORNER_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.title = title
            windowAnimations = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            runCatching { setFitInsetsTypes(0) }
            runCatching {
                WindowManager.LayoutParams::class.java
                    .getMethod("setTrustedOverlay")
                    .invoke(this)
            }
        }

    fun destroy() {
        screenOffReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        screenOffReceiver = null
        unlockReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        unlockReceiver = null
        syncFromAppReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        syncFromAppReceiver = null
        quickPayReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        quickPayReceiver = null
        pinsListener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        pinsListener = null
        mainHandler.removeCallbacks(timeout)
        // 校准会话属于这个实例：热重载时把采集停掉（App 那边的 active 由用户/超时收尾，这里不广播
        // —— hook 进程也写不了远程组）。这条路径可能不在主线程（ModuleMain → SystemUiHookInstaller
        // → CornerInputMonitor.dispose），所以 cancelCalibration 只碰标量与 Handler，
        // View 那边的移除由 removeCalibrationHint 自己判断线程。
        cancelCalibration("DESTROY")
        pressureDetector.destroy()
        hideMorePanel(animated = false)
        tearFanWindowsNow()
        dock.destroy()
    }

    private companion object {
        val SCRIM_COLOR = Color.parseColor("#73000000") // fd_sys_color_scrim_default night

        /** 更多面板外圈轻遮罩：比扇子的 scrim 浅，面板仍是"小窗内容"而不是模态弹窗。 */
        val MORE_SCRIM_COLOR = Color.parseColor("#33000000")

        /** 默认面板占地（屏幕百分比），两个轴都用它 —— 与启动小窗的默认尺寸对齐。 */
        const val DEFAULT_PANEL_PCT = 62
        const val FADE = 130L

        /** 见 [logPinRead]：同一个读结果最多这么久记一行。 */
        const val PIN_READ_LOG_INTERVAL_MS = 1000L

        /**
         * 终局事件发出后多久检查回执（App 把 active 写成 false 就是对「收到了」的确认）。
         * 2s 足够一次跨进程调用 + 一次 prefs 写入往返，又不至于让用户觉得卡。
         */
        const val CALIBRATION_ACK_DELAY_MS = 2000L
    }
}
