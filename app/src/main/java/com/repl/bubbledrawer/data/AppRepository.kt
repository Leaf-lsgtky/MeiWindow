package com.repl.bubbledrawer.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates launchable apps and caches icons.
 * Original: `C2762j.m8870J()` merges config pins + usage list via a Binder query to
 * its own provider; ours uses PackageManager directly (works on any ROM).
 *
 * Returns an [ImmutableList] from the PRODUCER (docs/ui-guidelines.md "强跳过友好的状态
 * 形状") — every composable downstream stays skippable without a UI-layer conversion.
 */
class AppRepository(private val context: Context) {

    private val iconCache = LruCache<String, Drawable>(64)

    @Volatile
    private var cache: ImmutableList<BubbleApp> = kotlinx.collections.immutable.persistentListOf()

    /** Synchronous view of the last [loadAll] (bubble bar reads this on expand). */
    fun cachedAll(): ImmutableList<BubbleApp> = cache

    /** Synchronous lookup for a single app; resolves from PackageManager if cache is cold. */
    fun findApp(packageName: String, userId: Int = 0): BubbleApp? {
        cache.firstOrNull { it.packageName == packageName && it.userId == userId }?.let { return it }
        return runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(ai).toString()
            BubbleApp(
                packageName = packageName,
                label = label,
                userId = userId,
                usageCount = LaunchCountStore.get(context, packageName),
            )
        }.getOrNull()
    }

    suspend fun loadAll(): ImmutableList<BubbleApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val own = context.packageName
        val list = pm.queryIntentActivities(main, 0)
            .asSequence()
            .map { it.activityInfo.packageName to it }
            .filter { (pkg, _) -> pkg != own }
            .distinctBy { (pkg, _) -> pkg }
            .map { (pkg, ri) ->
                val label = ri.loadLabel(pm).toString()
                BubbleApp(
                    packageName = pkg,
                    label = label,
                    usageCount = LaunchCountStore.get(context, pkg),
                )
            }
            .toList()
            .toImmutableList()
        list.sortedWith(AppSortKey.DEFAULT).toImmutableList().also { cache = it }
    }

    fun icon(app: BubbleApp): Drawable? {
        iconCache.get(app.packageName)?.let { return it }
        val d = runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull() ?: return null
        iconCache.put(app.packageName, d)
        return d
    }

    fun launchIntent(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}

/** Lightweight local usage counter (spec §6.1; usageStats optional phase 2). */
object LaunchCountStore {
    private const val PREFS = "usage"
    private const val KEY_PREFIX = "c_"

    fun get(context: Context, pkg: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PREFIX + pkg, 0)

    fun increment(context: Context, pkg: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_PREFIX + pkg, p.getInt(KEY_PREFIX + pkg, 0) + 1).apply()
    }
}
