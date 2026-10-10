package com.repl.bubbledrawer.contactbar

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    /** Notification still posted → the bar shows the unread dot (Flyme's `NewMessageView`). */
    val live: Boolean = false,
    /**
     * `PendingIntent.getIntent()` — the launch request behind the tap, kept the way Flyme keeps it
     * (`C0653b.m3435c()` is exactly this, and `m9364J` sends it as the fill-in Intent).
     *
     * This is what makes the tap reliable on HyperOS: opening an app cancels its notifications, and
     * WeChat cancels their PendingIntents with them, after which `PendingIntent.send` throws
     * `CanceledException` — while the Intent itself still starts fine.
     */
    val launchIntent: Intent? = null,
    val userId: Int = 0,
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
 *
 * ## Why the list is REMEMBERED, not just read live (HyperOS-specific)
 *
 * MIUI/HyperOS clears an app's notifications as soon as that app is opened — by any route, our
 * freeform launch included (the logic lives in system_server; `out/SystemUI1703_src` contains no
 * auto-clear implementation at all). A bar fed purely by the live notification set would therefore
 * be empty exactly when the small window appears, which is the whole point of the feature. Flyme has
 * no such behaviour, which is why its original can read the notification list directly
 * (`C2763k.java:405`).
 *
 * So notifications are used as a **discovery channel**: every chat notification upserts a
 * [Remembered] record (title, avatar, click intent, last post time) that outlives the notification
 * itself. The bar shows the merge — live first, remembered after — and [TTL_MS] plus the per-app cap
 * keep a stale chat from living forever. Consequence worth knowing: swiping a notification away no
 * longer removes an avatar (Flyme's rule); only time and the caps do.
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

    /** Conversation memory that survives MIUI's "opening the app clears its notifications". */
    private class Remembered(
        val pkg: String,
        val title: String,
        var key: String?,
        var icon: Drawable?,
        var pendingIntent: PendingIntent?,
        var launchIntent: Intent?,
        var userId: Int,
        var postTime: Long,
        var lastSeenAt: Long,
    )

    private val remembered = HashMap<String, Remembered>()

    /**
     * Conversations the user swiped away, with the post time they were removed at. A newer
     * notification (greater `postTime` — the framework refreshes it on every post *and* update) brings
     * the chat back, which is what "remove from the bar" should mean; [FORGET_TTL_MS] is the backstop.
     */
    private class Forgotten(val postTime: Long, val at: Long)

    private val forgotten = HashMap<String, Forgotten>()

    /** Live notifications seen in the last build — diagnostics only. */
    @Volatile
    var lastLiveCount = 0
        private set

    /**
     * Conversations of [pkg], newest first. Empty when the app never posted anything that looks like
     * a chat (no title / no avatar) — the bar then stays hidden, exactly like Flyme's
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
        val now = android.os.SystemClock.elapsedRealtime()
        val live = LinkedHashMap<String, Conversation>()
        for (entry in allEntries()) {
            val conv = toConversation(entry) ?: continue
            // Remember every whitelisted app, not just the one in the small window: by the time a
            // window opens, HyperOS has usually cleared that app's notifications already.
            remember(conv, now)
            if (conv.pkg != pkg || isForgotten(conv)) continue
            // One avatar per conversation: a chat that re-posts (or a summary line) keeps the
            // newest notification only — Flyme keeps a per-key bucket with the same effect.
            live[conv.title] = conv.copy(live = true)
        }
        lastLiveCount = live.size
        prune(now)
        return merge(pkg, live)
    }

    /**
     * Drop one conversation from the bar (Flyme's swipe-to-remove, `ContactListView.C2929d.onSwiped` →
     * `C2831I.m9363I` → the store's remove). The notification itself is left alone; the chat returns
     * once something newer arrives for it.
     */
    fun forget(conversation: Conversation) {
        val entry = key(conversation.pkg, conversation.title)
        remembered.remove(entry)
        forgotten[entry] = Forgotten(conversation.postTime, android.os.SystemClock.elapsedRealtime())
        cachedPkg = null
        log(Log.INFO, "CONTACT_BAR_FORGET pkg=${conversation.pkg} title=${conversation.title}", null)
    }

    private fun isForgotten(conv: Conversation): Boolean {
        val entry = forgotten[key(conv.pkg, conv.title)] ?: return false
        // A newer notification means the chat has something to say again.
        if (conv.postTime > entry.postTime) {
            forgotten.remove(key(conv.pkg, conv.title))
            return false
        }
        return true
    }

    /**
     * Snapshot every whitelisted conversation into the memory. Called on every notification event
     * and once at startup, so the bar has something to show even when the small window is opened
     * long after the notifications were posted — and after HyperOS cancelled them.
     */
    fun observe() {
        runCatching {
            val now = android.os.SystemClock.elapsedRealtime()
            for (entry in allEntries()) {
                toConversation(entry)?.let { remember(it, now) }
            }
            prune(now)
        }.onFailure { log(Log.WARN, "CONTACT_BAR_OBSERVE_FAILED", it) }
    }

    private fun remember(conv: Conversation, now: Long) {
        if (isForgotten(conv)) return
        val record = remembered.getOrPut(key(conv.pkg, conv.title)) {
            Remembered(conv.pkg, conv.title, null, null, null, null, conv.userId, 0L, now)
        }
        if (conv.icon != null) record.icon = conv.icon
        if (conv.pendingIntent != null) record.pendingIntent = conv.pendingIntent
        if (conv.launchIntent != null) record.launchIntent = conv.launchIntent
        record.userId = conv.userId
        record.key = conv.key
        record.postTime = maxOf(record.postTime, conv.postTime)
        record.lastSeenAt = now
    }

    /** Memory for [pkg], overlaid with whatever is still posted (live wins: newer avatar/intent). */
    private fun merge(pkg: String, live: Map<String, Conversation>): List<Conversation> {
        val merged = LinkedHashMap<String, Conversation>()
        for (record in remembered.values) {
            if (record.pkg != pkg) continue
            merged[record.title] = Conversation(
                pkg = record.pkg,
                key = record.key.orEmpty(),
                title = record.title,
                icon = record.icon,
                pendingIntent = record.pendingIntent,
                postTime = record.postTime,
                launchIntent = record.launchIntent,
                userId = record.userId,
            )
        }
        merged.putAll(live)
        val out = merged.values.sortedByDescending { it.postTime }
        return if (out.size > MAX_ITEMS) ArrayList(out.subList(0, MAX_ITEMS)) else out
    }

    /** Drop conversations nobody refreshed for [TTL_MS], then enforce the per-app and global caps. */
    private fun prune(now: Long) {
        remembered.values.removeAll { now - it.lastSeenAt > TTL_MS }
        forgotten.entries.removeAll { now - it.value.at > FORGET_TTL_MS }
        remembered.values.groupBy { it.pkg }.forEach { (_, list) ->
            if (list.size > MAX_REMEMBERED_PER_APP) {
                list.sortedByDescending { it.lastSeenAt }
                    .drop(MAX_REMEMBERED_PER_APP)
                    .forEach { remembered.remove(key(it.pkg, it.title)) }
            }
        }
        if (remembered.size <= MAX_REMEMBERED_TOTAL) return
        remembered.values
            .sortedByDescending { it.lastSeenAt }
            .drop(MAX_REMEMBERED_TOTAL)
            .forEach { remembered.remove(key(it.pkg, it.title)) }
    }

    private fun key(pkg: String, title: String) = "$pkg|$title"

    private fun toConversation(entry: Any): Conversation? {
        val sbn = member(entry, "getSbn", "mSbn") as? StatusBarNotification ?: return null
        val pkg = (runCatching { sbn.javaClass.getMethod("getOrigPackageName").invoke(sbn) as? String }.getOrNull())
            ?: sbn.packageName
        if (pkg !in IM_WHITELIST) return null
        val key = (member(entry, "getKey", "key") as? String) ?: sbn.key ?: return null
        val n: Notification = runCatching { sbn.notification }.getOrNull() ?: return null
        // Group summaries ("微信 · 3 条新消息") carry the app name, not a person — they would add a
        // bogus avatar and are exactly what the title gate below is meant to reject.
        if (runCatching { n.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.getOrDefault(false)) return null
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
            // Flyme's `C0653b.m3435c()`: the Intent behind the click, so a later tap still works when
            // the PendingIntent itself has been cancelled along with the notification.
            // `PendingIntent.getIntent()` is hidden in the current SDK, hence reflection.
            launchIntent = runCatching {
                PendingIntent::class.java.getMethod("getIntent").invoke(pi) as? Intent
            }.getOrNull(),
            userId = notificationUserId(sbn),
        )
    }

    /** The notification's user, for `startActivityAsUser` (reflection: `getUserId`/`getUser` are hidden). */
    private fun notificationUserId(sbn: StatusBarNotification): Int {
        runCatching {
            (sbn.javaClass.getMethod("getUserId").invoke(sbn) as? Number)?.let { return it.toInt() }
        }
        runCatching {
            val user = sbn.javaClass.getMethod("getUser").invoke(sbn) ?: return@runCatching
            val id = user.javaClass.getMethod("getIdentifier").invoke(user) as? Number
                ?: user.javaClass.getField("mHandle").get(user) as? Number
            if (id != null) return id.toInt()
        }
        return 0
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

        /**
         * How long a conversation stays in the bar after its notification is gone. HyperOS cancels
         * an app's notifications when the app is opened, so this window is what makes the feature
         * usable at all: recent chats keep showing, yesterday's don't.
         */
        private const val TTL_MS = 12 * 60 * 60 * 1000L

        /** How long a swiped-away conversation stays away even if no new notification arrives. */
        private const val FORGET_TTL_MS = 60 * 60 * 1000L

        /** Memory bounds: the bar shows at most 6 avatars, so 12 per app / 60 overall is plenty. */
        private const val MAX_REMEMBERED_PER_APP = 12
        private const val MAX_REMEMBERED_TOTAL = 60

        private const val PROXY_NAME = "BubbleDrawerNotificationListener"

        /** Every class this module ships lives under this prefix — how a stale proxy is recognised. */
        private const val MODULE_PACKAGE = "com.repl.bubbledrawer"

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

        /** The proxy we registered, so [detachListener] can take it back out on hot reload. */
        @Volatile
        private var attachedProxy: Any? = null

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
                // A java.lang.reflect.Proxy must answer equals/hashCode itself: the default handler
                // returning null makes `Intrinsics.areEqual` unbox null into a boolean and crash the
                // SystemUI process (`NamedListenerSet.remove` compares every registered listener,
                // which runs on unrelated paths such as ModalControllerImpl.exitModal when the
                // keyguard is shown). Identity semantics are the correct answer here anyway.
                val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { instance, method, args ->
                    when (method.name) {
                        "equals" -> instance === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(instance)
                        "toString" -> PROXY_NAME
                        else -> {
                            if (method.name.startsWith("onEntry")) {
                                onChanged?.invoke()
                                // Diagnostics for the HyperOS behaviour this class exists to survive:
                                // when the app is opened its notifications are cancelled, so an
                                // `onEntryRemoved` burst right before the small window appears is
                                // expected — the memory keeps the bar populated (see the class KDoc).
                                if (method.name == "onEntryRemoved") logRemoval(args?.firstOrNull())
                            }
                            // Never null: the listener interface is all-void today, but a future/other
                            // method with a primitive return would blow up on unboxing (that exact bug
                            // already cost us one SystemUI crash on the input-receiver proxy).
                            com.repl.bubbledrawer.launch.FlymeFreeformController.proxyDefault(method.returnType)
                        }
                    }
                }
                // Drop proxies left behind by an earlier generation of this module BEFORE adding the
                // new one. A hot reload replaces our classes but not the objects the previous
                // generation already registered, and a stale proxy from a buggy build keeps crashing
                // every `NamedListenerSet.remove` until the process restarts. Removal goes through the
                // raw `listeners` list on purpose: the set's own `remove` compares with `equals`,
                // which is exactly what must not be called on the object being cleaned up.
                purgeModuleProxies(instance, module)
                add.invoke(instance, proxy)
                attachedProxy = proxy
                listenerAttached = true
                module.log(Log.INFO, TAG, "CONTACT_BAR_NOTIF_LISTENER_ATTACHED")
            }.onFailure { module.log(Log.WARN, TAG, "CONTACT_BAR_NOTIF_LISTENER_FAILED", it) }
        }

        /** The `NamedListenerSet` behind `NotifPipeline.mNotifCollection` — see the dump notes. */
        private fun listenerList(pipeline: Any): java.util.Collection<Any?>? = runCatching {
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline) ?: return null
            val set = collection.javaClass.getField("mNotifCollectionListeners").get(collection) ?: return null
            @Suppress("UNCHECKED_CAST")
            set.javaClass.getField("listeners").get(set) as? java.util.Collection<Any?>
        }.getOrNull()

        /** `true` for a `java.lang.reflect.Proxy` this module created (any generation). */
        private fun isModuleProxy(candidate: Any?): Boolean {
            if (candidate == null || !Proxy.isProxyClass(candidate.javaClass)) return false
            return runCatching {
                Proxy.getInvocationHandler(candidate).javaClass.name.startsWith(MODULE_PACKAGE)
            }.getOrDefault(false)
        }

        private fun purgeModuleProxies(pipeline: Any, module: XposedModule) {
            runCatching {
                val listeners = listenerList(pipeline) ?: return
                var removed = 0
                listeners.removeIf { wrapper ->
                    val inner = runCatching {
                        wrapper?.javaClass?.getField("listener")?.get(wrapper)
                    }.getOrNull()
                    val stale = isModuleProxy(inner)
                    if (stale) removed++
                    stale
                }
                if (removed > 0) {
                    module.log(Log.INFO, TAG, "CONTACT_BAR_STALE_PROXY_PURGED removed=$removed", null)
                }
            }.onFailure { module.log(Log.WARN, TAG, "CONTACT_BAR_PURGE_FAILED", it) }
        }

        /**
         * Unregister our listener. Called from `ContactBarController.dispose()` so a hot reload does
         * not leave a proxy of the outgoing generation inside SystemUI's listener set.
         */
        fun detachListener() {
            val pipeline = pipelineRef ?: return
            val proxy = attachedProxy
            runCatching {
                val listeners = listenerList(pipeline) ?: return
                listeners.removeIf { wrapper ->
                    runCatching { wrapper?.javaClass?.getField("listener")?.get(wrapper) }.getOrNull() === proxy
                }
            }
            attachedProxy = null
            listenerAttached = false
        }

        /** Diagnostics hook (adb broadcast) — how many notifications the pipeline currently holds. */
        fun debugCount(): Int = runCatching {
            val pipeline = pipelineRef ?: return 0
            val collection = pipeline.javaClass.getField("mNotifCollection").get(pipeline)
            (collection.javaClass.getField("mReadOnlyNotificationSet").get(collection) as? Collection<*>)?.size ?: 0
        }.getOrDefault(0)

        /** Rate-limited `CONTACT_BAR_NOTIF_REMOVED` line: proof of the clear-on-open behaviour. */
        @Volatile
        private var lastRemovalLogAt = 0L

        private fun logRemoval(entry: Any?) {
            val module = moduleRef ?: return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRemovalLogAt < 250L) return
            lastRemovalLogAt = now
            val sbn = runCatching {
                (entry?.javaClass?.getField("mSbn")?.get(entry)) as? StatusBarNotification
            }.getOrNull()
            val pkg = sbn?.packageName ?: "?"
            val title = runCatching {
                sbn?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            }.getOrNull()
            module.log(Log.INFO, TAG, "CONTACT_BAR_NOTIF_REMOVED pkg=$pkg title=$title", null)
        }
    }
}
