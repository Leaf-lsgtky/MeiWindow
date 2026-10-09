package com.repl.bubbledrawer.contactbar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy

/** One recent conversation, rebuilt from a posted notification (Flyme's `C0653b` analogue). */
data class Conversation(
    val pkg: String,
    val key: String,
    val title: String,
    val icon: Drawable?,
    val pendingIntent: PendingIntent?,
    val postTime: Long,
)

/**
 * The contact bar's data source — recent conversations of the app that currently owns the
 * small window.
 *
 * Flyme builds this from a NotificationListenerService
 * (`com.flyme.systemuitools.SystemUIToolsNotificationListenerService`, registered at app start
 * with `registerAsSystemService`), buckets notifications per `包名:UserHandle{n}`, requires a
 * large icon plus a non-empty title, drops QQ's 「下线通知」 and caps the list at 20
 * (`windowmode/C2763k.java:405`, `views/C2831I.java:530-554`). We are already inside the
 * SystemUI process, so none of that plumbing is needed: `NotifPipeline` owns every posted
 * notification and hands it to us directly.
 *
 * Verified against the device dump (`out/SystemUI1703_src`):
 *  - `NotifPipeline implements CommonNotifCollection`, constructor
 *    `NotifPipeline(NotifCollection, ShadeListBuilder, RenderStageManager, NotifPipelineInjectorImpl)`
 *    — a single instance for the process, so hooking the constructor gives a permanent handle.
 *  - `NotifPipeline.addCollectionListener(NotifCollectionListener)` (:34) → live updates.
 *  - `NotifPipeline.getAllNotifs()` (:128) returns `mNotifCollection.mReadOnlyNotificationSet`
 *    but asserts the main thread, so the field is read directly and the method kept as fallback.
 *  - `NotificationEntry` exposes the key and the `ExpandedNotification` (fields `key`, `mSbn`).
 */
class RecentConversations(
    private val context: Context,
    private val log: (Int, String, Throwable?) -> Unit,
) {

    /** One rebuild is at most this old; notification callbacks normally refresh immediately. */
    private val refreshIntervalMs = 1_500L

    private var cached: List<Conversation> = emptyList()
    private var cachedPkg: String? = null
    private var cachedAt = 0L
    private var cachedIcons = HashMap<String, Drawable?>()

    /**
     * Conversations of [pkg], newest first. Empty when the app posted nothing that looks like a
     * chat (no title / no avatar) — the bar then stays hidden, exactly like Flyme's
     * `m9397W(false)` path.
     */
    fun conversations(pkg: String, force: Boolean = false): List<Conversation> {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && pkg == cachedPkg && now - cachedAt < refreshIntervalMs) return cached
        val built = runCatching { build(pkg) }.getOrElse {
            log(Log.WARN, "CONTACT_BAR_BUILD_FAILED", it)
            emptyList()
        }
        cached = built
        cachedPkg = pkg
        cachedAt = now
        return built
    }

    private fun build(pkg: String): List<Conversation> {
        val entries = allEntries()
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<Conversation>()
        val keys = HashSet<String>()
        for (entry in entries) {
            val conv = toConversation(entry) ?: continue
            if (conv.pkg != pkg) continue
            // One avatar per conversation: a chat that re-posts (or a summary line) keeps the
            // newest notification only — Flyme keeps a per-key bucket with the same effect.
            if (!keys.add(conv.title)) continue
            out.add(conv)
        }
        out.sortByDescending { it.postTime }
        return if (out.size > MAX_ITEMS) ArrayList(out.subList(0, MAX_ITEMS)) else out
    }

    private fun toConversation(entry: Any): Conversation? {
        val sbn = member(entry, "getSbn", "mSbn") as? StatusBarNotification ?: return null
        val pkg = (runCatching { sbn.javaClass.getMethod("getOrigPackageName").invoke(sbn) as? String }.getOrNull())
            ?: sbn.packageName
        if (pkg !in IM_WHITELIST) return null
        val key = (member(entry, "getKey", "key") as? String) ?: sbn.key ?: return null
        val n: Notification = runCatching { sbn.notification }.getOrNull() ?: return null
        val title = runCatching {
            n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        }.getOrNull().orEmpty()
        // Same gate as Flyme's `m8903m()`: a chat notification always carries the sender as the
        // title and the sender's avatar as the large icon. System chatter («下线通知» …) fails it.
        if (title.isEmpty() || title in IGNORED_TITLES) return null
        val pi = n.contentIntent ?: return null

        if (!cachedIcons.containsKey(key)) {
            cachedIcons[key] = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
                ?: runCatching {
                    val extra = n.extras?.get(Notification.EXTRA_LARGE_ICON)
                    (extra as? android.graphics.drawable.Icon)?.loadDrawable(context)
                }.getOrNull()
        }
        return Conversation(
            pkg = pkg,
            key = key,
            title = title,
            icon = cachedIcons[key],
            pendingIntent = pi,
            postTime = runCatching { sbn.postTime }.getOrNull() ?: 0L,
        )
    }

    /** Every posted notification: the collection's read-only set, or `getAllNotifs()` on main. */
    private fun allEntries(): List<Any> {
        val pipeline = pipelineRef ?: acquirePipeline() ?: return emptyList()
        runCatching {
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline) ?: return@runCatching
            val set = collection.javaClass.getField("mReadOnlyNotificationSet").get(collection) as? Collection<*>
            if (set != null) return set.filterNotNull()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return emptyList()
        return runCatching {
            (pipeline.javaClass.getMethod("getAllNotifs").invoke(pipeline) as? Collection<*>)?.filterNotNull()
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /** `getX()` method first, then the `x` / `mX` field — jadx exposes Kotlin properties either way. */
    private fun member(target: Any, getter: String, vararg fields: String): Any? {
        runCatching {
            target.javaClass.getMethod(getter).invoke(target)?.let { return it }
        }
        for (name in fields) {
            runCatching {
                target.javaClass.getField(name).get(target)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "BubbleDrawer"
        private const val MAX_ITEMS = 20

        /** Flyme's own whitelist (`common/windowmode/model/AbstractC2304a.java:49-53`). */
        val IM_WHITELIST = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.alibaba.android.rimet",
            "com.tencent.wework",
        )

        /** Flyme drops QQ's 「下线通知」 explicitly (`C2831I.java:533-539`). */
        private val IGNORED_TITLES = setOf("下线通知", "微信", "QQ", "微信支付", "服务通知")

        @Volatile
        private var pipelineRef: Any? = null

        @Volatile
        private var listenerAttached = false

        @Volatile
        private var listenerClassRef: Class<*>? = null

        @Volatile
        private var moduleRef: XposedModule? = null

        /** Fired on the main thread whenever a notification appears, updates or goes away. */
        @Volatile
        var onChanged: (() -> Unit)? = null

        /**
         * Hook the one `NotifPipeline` instance of this process. Called before the bar exists, so
         * the captured reference is kept statically and read on demand.
         */
        fun installHooks(module: XposedModule, classLoader: ClassLoader) {
            moduleRef = module
            val pipelineClass = runCatching {
                classLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotifPipeline")
            }.getOrNull() ?: run {
                module.log(Log.WARN, TAG, "CONTACT_BAR_NOTIF_PIPELINE_MISSING", null)
                return
            }
            listenerClassRef = runCatching {
                classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener",
                )
            }.getOrNull()

            pipelineClass.declaredConstructors.forEach { ctor ->
                runCatching {
                    module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.contactbar.pipeline.${ctor.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val instance = chain.thisObject
                            if (instance != null) {
                                pipelineRef = instance
                                attachListener(instance)
                            }
                            result
                        }
                }
            }
            // A hot reload happens long after SystemUI built its object graph, so the constructor
            // hook above never fires — pull the same singleton out of the Dagger component instead
            // (`SystemUIAppComponentFactoryBase.systemUIInitializer` → `getSysUIComponent()` →
            // `ReferenceSysUIComponentImpl.notifPipelineProvider`, verified in the device dump).
            if (pipelineRef == null) acquirePipeline(classLoader)
            module.log(Log.INFO, TAG, "CONTACT_BAR_HOOKS_INSTALLED pipeline=${pipelineRef != null}")
        }

        /**
         * Late binding of the process-wide `NotifPipeline`: the Dagger component exposes it as a
         * public provider field. Silent no-op on any other ROM.
         */
        private fun acquirePipeline(classLoader: ClassLoader? = null): Any? {
            pipelineRef?.let { return it }
            val loader = classLoader ?: listenerClassRef?.classLoader ?: return null
            val instance = runCatching {
                val factory = Class.forName("com.android.systemui.SystemUIAppComponentFactoryBase", false, loader)
                val initializer = factory.getField("systemUIInitializer").get(null) ?: return@runCatching null
                val component = initializer.javaClass.getMethod("getSysUIComponent").invoke(initializer)
                    ?: return@runCatching null
                component.javaClass.getField("notifPipelineProvider").get(component)?.let { provider ->
                    provider.javaClass.getMethod("get").invoke(provider)
                }
            }.getOrNull() ?: return null
            if (!instance.javaClass.name.endsWith("NotifPipeline")) return null
            pipelineRef = instance
            attachListener(instance)
            return instance
        }

        /** `addCollectionListener` keeps a plain listener set — no main-thread assertion. */
        private fun attachListener(instance: Any) {
            val listenerClass = listenerClassRef ?: return
            val module = moduleRef ?: return
            if (listenerAttached) return
            runCatching {
                val add = instance.javaClass.methods.firstOrNull {
                    it.name == "addCollectionListener" && it.parameterCount == 1
                } ?: return
                val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, _ ->
                    if (method.name.startsWith("onEntry")) {
                        onChanged?.invoke()
                    }
                    null
                }
                add.invoke(instance, proxy)
                listenerAttached = true
                module.log(Log.INFO, TAG, "CONTACT_BAR_NOTIF_LISTENER_ATTACHED")
            }.onFailure { module.log(Log.WARN, TAG, "CONTACT_BAR_NOTIF_LISTENER_FAILED", it) }
        }

        /** Diagnostics hook (adb broadcast) — how many notifications the pipeline currently holds. */
        fun debugCount(): Int = runCatching {
            val pipeline = pipelineRef ?: return 0
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline)
            (collection.javaClass.getField("mReadOnlyNotificationSet").get(collection) as? Collection<*>)?.size ?: 0
        }.getOrDefault(0)
    }
}
