package com.repl.bubbledrawer.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates launchable apps and caches icons.
 * Original: `C2762j.m8870J()` merges config pins + usage list via a Binder query to
 * its own provider; ours uses PackageManager directly (works on any ROM).
 */
class AppRepository(private val context: Context) {

    private val iconCache = LruCache<String, Drawable>(64)

    private var cache: List<BubbleApp> = emptyList()

    /** Synchronous view of the last [loadAll] (bubble bar reads this on expand). */
    fun cachedAll(): List<BubbleApp> = cache

    suspend fun loadAll(): List<BubbleApp> = withContext(Dispatchers.IO) {
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
        list.sortedWith(AppSortKey.DEFAULT).also { cache = it }
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
