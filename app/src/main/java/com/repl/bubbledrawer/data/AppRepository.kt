package com.repl.bubbledrawer.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-user helper for Android & MIUI / HyperOS (XSpace / Dual Apps / Work Profile).
 */
object MultiUserHelper {

    /** Returns the user ID (identifier) for a [UserHandle], default 0. */
    fun getUserId(user: UserHandle): Int {
        return runCatching {
            UserHandle::class.java.getMethod("getIdentifier").invoke(user) as Int
        }.getOrElse {
            runCatching {
                val f = UserHandle::class.java.getDeclaredField("mHandle")
                f.isAccessible = true
                f.getInt(user)
            }.getOrElse { user.hashCode() }
        }
    }

    /** Instantiates a [UserHandle] for a given [userId]. */
    fun getUserHandle(userId: Int): UserHandle? {
        if (userId == 0) return Process.myUserHandle()
        return runCatching {
            UserHandle::class.java.getConstructor(Int::class.javaPrimitiveType).newInstance(userId)
        }.getOrNull()
    }
}

/**
 * Enumerates launchable apps across all user profiles (including HyperOS XSpace 999)
 * and caches icons with official badges.
 */
class AppRepository(private val context: Context) {

    @Volatile
    private var cache: ImmutableList<BubbleApp> = kotlinx.collections.immutable.persistentListOf()

    /** Synchronous view of the last [loadAll] (bubble bar reads this on expand). */
    fun cachedAll(): ImmutableList<BubbleApp> = cache

    /** Synchronous lookup for a single app; resolves from LauncherApps/PackageManager if cache is cold. */
    fun findApp(packageName: String, userId: Int = 0): BubbleApp? {
        cache.firstOrNull { it.packageName == packageName && it.userId == userId }?.let { return it }
        return runCatching {
            val userHandle = MultiUserHelper.getUserHandle(userId)
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val label = if (userHandle != null && launcherApps != null) {
                runCatching {
                    launcherApps.getActivityList(packageName, userHandle).firstOrNull()?.label?.toString()
                }.getOrNull()
            } else null

            val finalLabel = label ?: runCatching {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrNull() ?: packageName

            BubbleApp(
                packageName = packageName,
                label = finalLabel,
                userId = userId,
                usageCount = LaunchCountStore.get(context, packageName, userId),
            )
        }.getOrNull()
    }

    suspend fun loadAll(): ImmutableList<BubbleApp> = withContext(Dispatchers.IO) {
        val allApps = mutableListOf<BubbleApp>()
        val own = context.packageName
        val seenKeys = mutableSetOf<String>()

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val pm = context.packageManager

        // 1. Gather all profiles: userManager.userProfiles + explicit XSpace User 999
        val profiles = runCatching { userManager?.userProfiles }.getOrNull() ?: emptyList()
        val userList = profiles.toMutableList()
        if (userList.none { MultiUserHelper.getUserId(it) == 999 }) {
            MultiUserHelper.getUserHandle(999)?.let { userList.add(it) }
        }

        var launcherAppsFound = false
        if (launcherApps != null) {
            for (user in userList) {
                val uid = MultiUserHelper.getUserId(user)
                val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrNull()
                if (!activities.isNullOrEmpty()) {
                    launcherAppsFound = true
                    for (act in activities) {
                        val pkg = act.applicationInfo.packageName
                        if (pkg == own) continue
                        val key = "$pkg#$uid"
                        if (!seenKeys.add(key)) continue
                        val label = act.label.toString()
                        allApps.add(
                            BubbleApp(
                                packageName = pkg,
                                label = label,
                                userId = uid,
                                usageCount = LaunchCountStore.get(context, pkg, uid),
                            )
                        )
                    }
                }
            }
        }

        // 2. Fallback / supplement for User 999 (XSpace) if LauncherApps didn't list it
        if (!seenKeys.any { it.endsWith("#999") }) {
            queryActivitiesForUser(context, pm, 999, own, seenKeys, allApps)
        }

        // 3. Fallback for User 0 if LauncherApps completely failed
        if (!launcherAppsFound && allApps.isEmpty()) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = runCatching { pm.queryIntentActivities(main, 0) }.getOrNull().orEmpty()
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == own) continue
                val key = "$pkg#0"
                if (!seenKeys.add(key)) continue
                val label = ri.loadLabel(pm).toString()
                allApps.add(
                    BubbleApp(
                        packageName = pkg,
                        label = label,
                        userId = 0,
                        usageCount = LaunchCountStore.get(context, pkg, 0),
                    )
                )
            }
        }

        val sorted = allApps.sortedWith(AppSortKey.DEFAULT).toImmutableList()
        cache = sorted
        sorted
    }

    fun icon(app: BubbleApp): Drawable? = getIcon(context, app)

    fun launchIntent(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)

    companion object {
        private val iconCache = LruCache<String, Drawable>(128)

        /**
         * Resolves the app icon with badging for multi-user / cloned apps.
         */
        fun getIcon(context: Context, app: BubbleApp): Drawable? {
            val key = "${app.packageName}#${app.userId}"
            iconCache.get(key)?.let { return it }

            val d = loadIconWithBadge(context, app) ?: return null
            iconCache.put(key, d)
            return d
        }

        private fun loadIconWithBadge(context: Context, app: BubbleApp): Drawable? {
            val pm = context.packageManager
            val userId = app.userId
            val userHandle = MultiUserHelper.getUserHandle(userId)
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

            // 1. If user is main (0), return standard icon directly
            if (userId == 0) {
                return runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
            }

            // 2. Try LauncherApps.getBadgedIcon
            if (launcherApps != null && userHandle != null) {
                val act = runCatching { launcherApps.getActivityList(app.packageName, userHandle).firstOrNull() }.getOrNull()
                if (act != null) {
                    val badged = runCatching {
                        act.getBadgedIcon(context.resources.displayMetrics.densityDpi)
                    }.getOrNull()
                    if (badged != null) return badged
                }
            }

            // 3. Fallback: retrieve base icon
            var baseIcon = runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
            if (baseIcon == null) {
                baseIcon = runCatching {
                    val method = pm.javaClass.getMethod(
                        "getApplicationInfoAsUser",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    val ai = method.invoke(pm, app.packageName, 0, userId) as? android.content.pm.ApplicationInfo
                    ai?.loadIcon(pm)
                }.getOrNull()
            }
            if (baseIcon == null) return null

            // 4. Attach badge based on user type
            if (userId == 999) {
                // HyperOS / MIUI XSpace specific badge
                val xspaceIcon = runCatching {
                    val cls = Class.forName("miui.securityspace.XSpaceUserHandle")
                    val m = cls.getMethod("getXSpaceIcon", Context::class.java, Drawable::class.java)
                    m.invoke(null, context, baseIcon) as? Drawable
                }.getOrNull()
                if (xspaceIcon != null) return xspaceIcon

                // Standard system badged icon
                if (userHandle != null) {
                    val badged = runCatching { pm.getUserBadgedIcon(baseIcon, userHandle) }.getOrNull()
                    if (badged != null && badged !== baseIcon) return badged
                }

                // Custom badge drawing fallback
                return drawXSpaceBadge(context, baseIcon)
            } else {
                // Other profiles (e.g. Work Profile)
                if (userHandle != null) {
                    val badged = runCatching { pm.getUserBadgedIcon(baseIcon, userHandle) }.getOrNull()
                    if (badged != null) return badged
                }
                return baseIcon
            }
        }

        /**
         * Draws an authentic HyperOS / MIUI style dual-app badge onto [baseIcon] as a fallback.
         */
        private fun drawXSpaceBadge(context: Context, baseIcon: Drawable): Drawable {
            val width = baseIcon.intrinsicWidth.coerceAtLeast(144)
            val height = baseIcon.intrinsicHeight.coerceAtLeast(144)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            baseIcon.setBounds(0, 0, width, height)
            baseIcon.draw(canvas)

            // Badge badge radius: ~17% of icon size
            val badgeRadius = width * 0.17f
            val cx = width - badgeRadius - (width * 0.03f)
            val cy = height - badgeRadius - (height * 0.03f)

            // Orange badge circle background (#FF7600)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF7600")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, badgeRadius, bgPaint)

            // Two overlapping white rings/loops (XSpace dual-app logo)
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = badgeRadius * 0.22f
            }
            val ringR = badgeRadius * 0.44f
            val offset = badgeRadius * 0.22f
            canvas.drawCircle(cx - offset, cy - offset * 0.5f, ringR, ringPaint)
            canvas.drawCircle(cx + offset, cy + offset * 0.5f, ringR, ringPaint)

            return BitmapDrawable(context.resources, bitmap)
        }

        private fun queryActivitiesForUser(
            context: Context,
            pm: PackageManager,
            userId: Int,
            own: String,
            seenKeys: MutableSet<String>,
            outList: MutableList<BubbleApp>,
        ) {
            runCatching {
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val method = pm.javaClass.getMethod(
                    "queryIntentActivitiesAsUser",
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                val list = method.invoke(pm, main, 0, userId) as? List<*> ?: return
                for (item in list) {
                    val ri = item as? ResolveInfo ?: continue
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == own) continue
                    val key = "$pkg#$userId"
                    if (!seenKeys.add(key)) continue
                    val label = ri.loadLabel(pm).toString()
                    outList.add(
                        BubbleApp(
                            packageName = pkg,
                            label = label,
                            userId = userId,
                            usageCount = LaunchCountStore.get(context, pkg, userId),
                        )
                    )
                }
            }
        }
    }
}

/** Lightweight local usage counter supporting multi-user keys. */
object LaunchCountStore {
    private const val PREFS = "usage"
    private const val KEY_PREFIX = "c_"

    fun get(context: Context, pkg: String, userId: Int = 0): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (userId == 0) pkg else "$pkg#$userId"
        val count = p.getInt(KEY_PREFIX + key, -1)
        if (count != -1) return count
        return p.getInt(KEY_PREFIX + pkg, 0)
    }

    fun increment(context: Context, pkg: String, userId: Int = 0) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (userId == 0) pkg else "$pkg#$userId"
        p.edit().putInt(KEY_PREFIX + key, get(context, pkg, userId) + 1).apply()
    }
}
