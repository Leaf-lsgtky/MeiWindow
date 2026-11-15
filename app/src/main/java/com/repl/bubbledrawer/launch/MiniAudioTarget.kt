package com.repl.bubbledrawer.launch

import android.content.Context
import android.content.pm.ApplicationInfo

/** Candidates must already belong to the requested package; never use another user's copy. */
internal fun selectMiniAudioUid(taskUserId: Int?, packageUid: Int?, activityUid: Int?, effectiveUid: Int?): Int {
    val user = taskUserId?.takeIf { it >= 0 }
        ?: activityUid?.takeIf { it > 0 }?.div(100_000)
        ?: effectiveUid?.takeIf { it > 0 }?.div(100_000)
        ?: return -1
    return listOf(packageUid, activityUid, effectiveUid)
        .firstOrNull { it != null && it > 0 && it / 100_000 == user } ?: -1
}

internal fun resolveMiniAudioUid(context: Context, packageName: String, task: Any?): Int {
    if (packageName.isBlank() || task == null) return -1
    fun field(target: Any?, name: String): Any? = target?.let {
        runCatching { it.javaClass.getField(name).get(it) }.getOrNull()
    }
    val user = (field(task, "userId") as? Int)?.takeIf { it >= 0 }
    val app = field(field(task, "topActivityInfo"), "applicationInfo") as? ApplicationInfo
    val topUid = app?.takeIf { it.packageName == packageName }?.uid
    val effective = (field(task, "effectiveUid") as? Int)?.takeIf { uid ->
        uid > 0 && runCatching { context.packageManager.getPackagesForUid(uid)?.contains(packageName) == true }
            .getOrDefault(false)
    }
    val targetUser = user ?: topUid?.takeIf { it > 0 }?.div(100_000) ?: effective?.div(100_000)
    val packageUid = targetUser?.let {
        runCatching {
            context.packageManager.javaClass.getMethod(
                "getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(context.packageManager, packageName, 0, it) as? Int
        }.getOrNull()
    }
    return selectMiniAudioUid(targetUser, packageUid, topUid, effective)
}
