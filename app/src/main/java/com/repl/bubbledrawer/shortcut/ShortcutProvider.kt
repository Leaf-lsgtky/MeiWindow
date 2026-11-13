package com.repl.bubbledrawer.shortcut

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * 接收来自 SystemUI 进程上报的全量 Shortcuts。
 *
 * 分片按 `scan_id` 归组（见 [ShortcutBridge.reportToApp]）：同一次扫描的片拼成一份，**不同扫描
 * 绝不混**。旧实现把所有片塞进同一个 "default" 桶，两次扫描交错时会把 A 的第 1 片和 B 的第 2 片
 * 拼成一份不存在的列表（还会重复计数）。而且那个桶永远不清 —— 一次没拼齐的上报就是一份常驻内存。
 */
class ShortcutProvider : ContentProvider() {

    private class Session(val startedAt: Long) {
        val chunks = ConcurrentSkipListMap<Int, String>()
    }

    private val sessions = ConcurrentHashMap<Long, Session>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ShortcutBridge.METHOD_REPORT &&
            method != com.repl.bubbledrawer.xposed.PinSyncReceiver.METHOD_SYNC
        ) {
            return null
        }
        val app = context?.applicationContext ?: return null

        val uid = Binder.getCallingUid()
        val packages = runCatching { app.packageManager.getPackagesForUid(uid)?.toList() }.getOrNull()
        if (!ShortcutBridge.isTrustedUid(uid, packages)) {
            Log.w(TAG, "SHORTCUT_REPORT_REJECTED method=$method uid=$uid packages=$packages")
            return null
        }

        if (method == com.repl.bubbledrawer.xposed.PinSyncReceiver.METHOD_SYNC) {
            val applied = com.repl.bubbledrawer.xposed.PinSyncReceiver.applyPayload(
                appContext = app,
                pins = extras?.getString(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_PINS),
                pinsRev = extras?.getLong(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_REV, 0L) ?: 0L,
                functions = extras?.getString(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_FUNCTIONS),
                functionsRev = extras?.getLong(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_FUNCTIONS_REV, 0L) ?: 0L,
                order = extras?.getString(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_ORDER),
                orderRev = extras?.getLong(com.repl.bubbledrawer.xposed.PinSyncReceiver.EXTRA_ORDER_REV, 0L) ?: 0L,
                via = "provider",
            )
            return Bundle().apply { putBoolean(ShortcutBridge.EXTRA_APPLIED, applied) }
        }

        val json = extras?.getString(ShortcutBridge.EXTRA_JSON)
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "SHORTCUT_REPORT_REJECTED empty payload")
            return null
        }
        if (json.length > ShortcutBridge.MAX_PAYLOAD_CHARS) {
            // 可信 uid 也可能被灌：一次 writeText 就是几百 MB 的磁盘 + 内存。
            Log.w(TAG, "SHORTCUT_REPORT_REJECTED payload too large len=${json.length}")
            return null
        }

        val chunkIndex = extras.getInt(ShortcutBridge.EXTRA_CHUNK_INDEX, 0)
        val chunkTotal = extras.getInt(ShortcutBridge.EXTRA_CHUNK_TOTAL, 1).coerceAtLeast(1)
        val scanId = extras.getLong(ShortcutBridge.EXTRA_SCAN_ID, 0L)
        // 「这份清单是完整的」——空清单也算（应用被卸载后要能把缓存清掉）。
        val complete = extras.getBoolean(ShortcutBridge.EXTRA_COMPLETE, false)

        if (chunkTotal <= 1) {
            ShortcutCacheStore.save(app, json, allowEmpty = complete)
            Log.i(TAG, "SHORTCUT_REPORT_APPLIED single chunk jsonLen=${json.length}")
            return Bundle().apply { putBoolean(ShortcutBridge.EXTRA_APPLIED, true) }
        }

        pruneSessions()
        val session = sessions.getOrPut(scanId) { Session(SystemClock.uptimeMillis()) }
        session.chunks[chunkIndex] = json
        if (session.chunks.size < chunkTotal) {
            // 回执只说明「这一片收下了」，**不说明**整份清单已经落盘（最后一片才合并）。
            // 而且只有当会话是从 0 号片开始连续的时候才算收下：进程重启或 60s 超时之后，
            // 会话会从半途重新开始，此时若照样回 true，调用方会拼出「每片都成功」的假象，
            // 而实际上永远不会有合并 —— 于是 App 一直显示旧列表、指纹还被记成"已上报"。
            val contiguous = session.chunks.firstKey() == 0 &&
                session.chunks.keys.toList() == (0..session.chunks.lastKey()).toList()
            Log.i(TAG, "SHORTCUT_REPORT_CHUNK scan=$scanId ${session.chunks.size}/$chunkTotal contiguous=$contiguous")
            return Bundle().apply { putBoolean(ShortcutBridge.EXTRA_APPLIED, contiguous) }
        }

        // 拼齐了：按 index 顺序合并成一份完整列表再落盘。
        val mergedArray = JSONArray()
        var merged = 0
        for (i in 0 until chunkTotal) {
            val partJson = session.chunks[i] ?: continue
            runCatching {
                val partArray = JSONArray(partJson)
                for (j in 0 until partArray.length()) {
                    mergedArray.put(partArray.getJSONObject(j))
                }
                merged++
            }
        }
        sessions.remove(scanId)
        val mergedJson = mergedArray.toString()
        val ok = merged == chunkTotal && mergedArray.length() > 0
        if (ok) {
            ShortcutCacheStore.save(app, mergedJson)
            Log.i(TAG, "SHORTCUT_REPORT_APPLIED merged=$chunkTotal scan=$scanId totalLen=${mergedJson.length}")
        } else {
            Log.w(TAG, "SHORTCUT_REPORT_DISCARDED merged=$merged/$chunkTotal scan=$scanId")
        }
        return Bundle().apply { putBoolean(ShortcutBridge.EXTRA_APPLIED, ok) }
    }

    /** 没拼齐的会话最多留 [SESSION_TTL_MS]：广播/进程重启都会留下半份，不能常驻。 */
    private fun pruneSessions() {
        val cutoff = SystemClock.uptimeMillis() - SESSION_TTL_MS
        for (entry in sessions.entries) {
            if (entry.value.startedAt < cutoff) sessions.remove(entry.key)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "ShortcutProvider"
        const val SESSION_TTL_MS = 60_000L
    }
}
