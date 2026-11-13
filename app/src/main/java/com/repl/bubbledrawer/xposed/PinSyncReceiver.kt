package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.shortcut.FunctionPinStore

/**
 * Broadcast-based persistence handoff for the two user-curated lists edited inside SystemUI
 * (收藏应用 = pins, 已选功能 = shortcut keys).
 *
 * This is the ONLY leg of that handoff. It used to have a `startService` twin, which was
 * removed because it was not a security boundary at all: a service's `onStartCommand` runs in
 * a plain Handler message, so `Binder.getCallingUid()` there reports the process's OWN uid and
 * the caller check admitted everybody (any app could post `pins=""` and wipe both lists). It
 * was also pointless as a fallback — cross-app background `startService` is forbidden since
 * Android 8 (`BackgroundServiceStartNotAllowedException`). An explicit broadcast to a manifest
 * receiver cold-starts the module app even when it is idle or stopped.
 */
class PinSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!isFromSystemUi(this, context)) {
            // Was not verified at all before 2026-10-09 (any app could rewrite the pins);
            // the payload is harmless but the list is the user's, so check the sender.
            // uid + packages are logged so a device log can tell "identity unavailable"
            // (uid=-1) apart from "somebody else" — only the latter is an attack.
            Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED sender=${sentFromUidOrNull()} pkg=${sentFromPackageOrNull()}")
            return
        }
        val appContext = context.applicationContext
        applyPayload(
            appContext = appContext,
            pins = intent.getStringExtra(EXTRA_PINS),
            pinsRev = intent.getLongExtra(EXTRA_REV, 0L),
            functions = intent.getStringExtra(EXTRA_FUNCTIONS),
            functionsRev = intent.getLongExtra(EXTRA_FUNCTIONS_REV, 0L),
            order = intent.getStringExtra(EXTRA_ORDER),
            orderRev = intent.getLongExtra(EXTRA_ORDER_REV, 0L),
            via = "broadcast",
        )
    }

    private fun sentFromUidOrNull(): Int? = runCatching { sentFromUid }.getOrNull()

    private fun sentFromPackageOrNull(): String? = runCatching { sentFromPackage }.getOrNull()

    /**
     * True only for the SystemUI-side sender.
     *
     * Three ways in, because the two sender fields are not equally reliable on a manifest
     * receiver: the package NAME may be missing while the uid is present, so the uid is also
     * resolved through PackageManager (the module's own uid is accepted for self-refresh).
     * Fails closed: with no identity at all the edit is dropped and logged.
     */
    private fun isFromSystemUi(receiver: BroadcastReceiver, context: Context): Boolean = runCatching {
        val uid = receiver.sentFromUid
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return@runCatching true
        if (receiver.sentFromPackage == SYSTEMUI_PACKAGE) return@runCatching true
        context.packageManager.getPackagesForUid(uid)?.contains(SYSTEMUI_PACKAGE) == true
    }.getOrDefault(false)

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val ACTION_TO_SYSTEMUI = "com.repl.bubbledrawer.action.SYNC_PINS_TO_SYSTEMUI"
        /** ContentProvider 同步通道的方法名（借用 [com.repl.bubbledrawer.shortcut.ShortcutProvider]）。 */
        const val METHOD_SYNC = "sync_pins"
        const val EXTRA_PINS = "pins"

        /** 「已选功能」（快捷方式收藏）走同一条交接通道，只是换了一组 extra。 */
        const val EXTRA_FUNCTIONS = "functions"

        /** 功能收藏的戳，语义同 [EXTRA_REV]。 */
        const val EXTRA_FUNCTIONS_REV = "functions_rev"

        /** 应用 + 功能混排顺序（只存顺序，不存成员）。 */
        const val EXTRA_ORDER = "pinned_order"

        /** 混排顺序的戳，语义同 [EXTRA_REV]。 */
        const val EXTRA_ORDER_REV = "pinned_order_rev"

        /**
         * Freshness stamp travelling with [EXTRA_PINS] — see [com.repl.bubbledrawer.data.PinsSync].
         * Absent/0 means an older module build sent it.
         */
        const val EXTRA_REV = "pins_rev"

        /** On this ROM SystemUI is NOT uid 1000 (verified: u0_a231, `packages.list`). */
        const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /** "pkg#user;…" for ~100 pins is < 5 KB; 10 KB is a generous cap. */
        private const val MAX_VALUE_LEN = 10_000

        /**
         * 发送带调用方身份的广播。
         *
         * Android 14+（minSdk = 36）默认不在跨进程广播里暴露发送方身份：接收端的
         * `BroadcastReceiver.sentFromUid` 恒为 `-1`、`sentFromPackage` 恒为 `null`，导致
         * [isFromSystemUi] / `FanHost.isFromModuleApp` 的发送方校验把合法交接全部拒掉
         * （`PIN_SYNC_BROADCAST_REJECTED sender=-1 pkg=null`）。显式开启 `shareIdentity`
         * 后系统才会把真实 uid/package 传给接收端。
         */
        fun sendBroadcastWithIdentity(context: Context, intent: Intent) {
            val options = android.app.BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            context.sendBroadcast(intent, null, options)
        }

        /**
         * 统一落盘入口（ContentProvider 同步调用与 Broadcast 兜底共用）。
         * 返回是否至少有一项合法载荷被受理。
         */
        fun applyPayload(
            appContext: Context,
            pins: String?,
            pinsRev: Long,
            functions: String?,
            functionsRev: Long,
            order: String? = null,
            orderRev: Long = 0L,
            via: String = "broadcast",
        ): Boolean {
            var applied = false
            if (pins != null) {
                if (!isPlausiblePinsValue(pins)) {
                    Log.w(TAG, "PIN_SYNC_REJECTED via=$via bad payload len=${pins.length}")
                } else {
                    val pinApplied = if (pinsRev > 0L) {
                        // Keep the SENDER's stamp: this is the panel's edit, not a new one (see
                        // SettingsStore.applyPushedPins). An older rev is a no-op: the app-side
                        // value already won, so the provider must not acknowledge it as applied.
                        SettingsStore.applyPushedPins(appContext, pins, pinsRev)
                    } else {
                        SettingsStore.pinBackend(appContext).write(pins)
                        true
                    }
                    if (pinApplied) {
                        applied = true
                        Log.i(
                            TAG,
                            "PIN_SYNC_APPLIED via=$via pins=${PinCodec.decode(pins).size} " +
                                "len=${pins.length} rev=$pinsRev",
                        )
                    } else {
                        Log.i(TAG, "PIN_SYNC_STALE_DROPPED via=$via rev=$pinsRev len=${pins.length}")
                    }
                }
            }

            // 同一个交接通道也送「已选功能」（快捷方式收藏）：两个列表都由面板编辑，没必要开第二条路。
            if (functions != null) {
                if (!isPlausibleFunctionsValue(functions)) {
                    Log.w(TAG, "FUNCTION_SYNC_REJECTED via=$via bad payload len=${functions.length}")
                } else {
                    val functionApplied = if (functionsRev > 0L) {
                        SettingsStore.applyPushedFunctions(appContext, functions, functionsRev)
                    } else {
                        SettingsStore.functionBackend(appContext).write(functions)
                        true
                    }
                    if (functionApplied) {
                        applied = true
                        Log.i(
                            TAG,
                            "FUNCTION_SYNC_APPLIED via=$via functions=${FunctionPinStore.decode(functions).size} " +
                                "len=${functions.length} rev=$functionsRev",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "FUNCTION_SYNC_STALE_DROPPED via=$via rev=$functionsRev len=${functions.length}",
                        )
                    }
                }
            }

            // 混排顺序（应用 + 功能）也搭同一条交接通道。它只是顺序，丢了最多回到旧排列，
            // 所以校验比成员列表更严格：认不出的一律整条拒绝，避免把布局写成半截。
            if (order != null) {
                if (!isPlausibleOrderValue(order)) {
                    Log.w(TAG, "PINNED_ORDER_SYNC_REJECTED via=$via bad payload len=${order.length}")
                } else {
                    val orderApplied = if (orderRev > 0L) {
                        SettingsStore.applyPushedPinnedOrder(appContext, order, orderRev)
                    } else {
                        SettingsStore.pinnedOrderBackend(appContext).write(order)
                        true
                    }
                    if (orderApplied) {
                        applied = true
                        Log.i(
                            TAG,
                            "PINNED_ORDER_SYNC_APPLIED via=$via " +
                                "entries=${com.repl.bubbledrawer.pin.PinnedOrderCodec.decode(order).size} " +
                                "len=${order.length} rev=$orderRev",
                        )
                    } else {
                        Log.i(TAG, "PINNED_ORDER_STALE_DROPPED via=$via rev=$orderRev len=${order.length}")
                    }
                }
            }
            return applied
        }

        /**
         * Grammar check for the encoded pin string: `pkg#user;pkg#user;…` (PinCodec
         * format). Empty = "no pins", which is legal (manage mode removes everything).
         */
        fun isPlausiblePinsValue(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return true
            if (trimmed.length > MAX_VALUE_LEN) return false
            return trimmed.split(';').filter { it.isNotBlank() }.all { entry ->
                entry.length <= 512 &&
                    entry.substringBefore('#').let { it.isNotEmpty() && it.none { c -> c == '#' || c == ';' } }
            }
        }

        /** 功能收藏的语法检查见 [FunctionPinStore.isPlausible]（键是 `pkg#id`，id 不受包名规则约束）。 */
        fun isPlausibleFunctionsValue(raw: String): Boolean = FunctionPinStore.isPlausible(raw)

        /** 混合收藏顺序的语法检查见 [com.repl.bubbledrawer.pin.PinnedOrderCodec.isPlausible]。 */
        fun isPlausibleOrderValue(raw: String): Boolean =
            com.repl.bubbledrawer.pin.PinnedOrderCodec.isPlausible(raw)
    }
}
