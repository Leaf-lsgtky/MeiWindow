package com.repl.bubbledrawer.lightopen

/**
 * 「轻量打开」纯逻辑层 —— 不碰 Android 框架（只用字符串常量），全部可在 JVM 单测里跑。
 *
 * 六个开关对应两种完全不同的实现机制（见 docs/light-open.md）：
 *
 *  - **system_server 闸门**（分享至应用 / 文本操作 / 应用间跳转）：Hook
 *    `MiuiFreeformServiceImpl.startActivityByFreeForm(ActivityInfo, Intent, String): boolean`
 *    的返回值。这个方法本体只是一次纯查询
 *    （`SecurityManagerInternal.checkGameBoosterPayPassAsUser(...)`），返回 true 时外层
 *    `checkStartActivityByFreeForm` 就会用 `MiuiMultiWindowUtils.getActivityOptions(...)`
 *    造出 `launchWindowingMode = 5`（FREEFORM）的 ActivityOptions，再被
 *    `ActivityStarterImpl.resolveActivity` 写回 `Request.activityOptions`，于是这次启动落在小窗里。
 *  - **SystemUI 注入**（竖屏/横屏点通知、控制中心长按）：这些启动的调用进程是
 *    `com.android.systemui`，ROM 在那个闸门里直接早退（"systemui launch return"），改闸门没用；
 *    必须由 SystemUI 自己把小窗 ActivityOptions 塞进这次启动。
 *
 * 本文件只回答「这一次启动该不该被改成小窗」，返回三态：
 * [LightOpenDecision.FORCE_FREEFORM]（强制小窗）/ [LightOpenDecision.FORCE_FULLSCREEN]（强制全屏，
 * 用来压掉 MIUI 原生的轻量打开）/ [LightOpenDecision.LEAVE]（不干预，保持系统默认）。
 */
enum class LightOpenDecision {
    LEAVE,
    FORCE_FREEFORM,
    FORCE_FULLSCREEN,
}

/** 一份不可变的开关快照；由 [LightOpen.from] 从远程 prefs 读出来。 */
data class LightOpenSettings(
    val enabled: Boolean = false,
    val notificationPortrait: Boolean = false,
    val notificationLandscape: Boolean = false,
    val controlCenter: Boolean = false,
    val shareToApp: Boolean = false,
    val textAction: Boolean = false,
    val appJump: Boolean = false,
    val jumpMode: Int = LightOpen.MODE_WHITE,
    val jumpTargets: Set<String> = emptySet(),
) {
    val jumpTargetCount: Int get() = jumpTargets.size

    /** 黑名单模式（名单外一律小窗）——UI 上要给出更重的提示，所以单独暴露。 */
    val globalFreeform: Boolean get() = appJump && jumpMode == LightOpen.MODE_BLACK
}

object LightOpen {

    /** 应用间跳转：名单内才小窗。 */
    const val MODE_WHITE = 0

    /** 应用间跳转：名单外全部小窗（全局小窗），名单内强制全屏。 */
    const val MODE_BLACK = 1

    const val MAX_TARGETS = 200
    const val MAX_TARGET_LEN = 200

    /**
     * 小米安全中心的"XX 想打开 XX，是否允许"拦截弹窗与分身/应用锁核心包。它们是系统弹窗：
     * 带名单的跳转被拦下来时，这次启动的目标就是它们（真实目标在后面那一次启动里）——
     * 绝不能把拦截框本身变成小窗（真机反馈 2026-10-14）。
     */
    const val SECURITY_CENTER = "com.miui.securitycenter"
    const val SECURITY_CORE = "com.miui.securitycore"

    /** 模块自身包名：`GateLaunchStrategy` 点名小窗只从模块自身（或安全中心二次确认后）发起。 */
    const val MODULE_PACKAGE = "com.repl.bubbledrawer"

    /** 桌面、负一屏与全局搜索：无论 PackageManager 查询是否就绪，这些包在前台时都算桌面环境。 */
    const val MIUI_HOME = "com.miui.home"
    const val MIUI_PERSONAL_ASSISTANT = "com.miui.personalassistant"
    const val ANDROID_QUICK_SEARCH_BOX = "com.android.quicksearchbox"

    val BUILTIN_HOME_PACKAGES = setOf(
        MIUI_HOME,
        MIUI_PERSONAL_ASSISTANT,
        ANDROID_QUICK_SEARCH_BOX,
    )

    private val ALLOWED_GATE_TOP_PACKAGES = setOf(
        MODULE_PACKAGE,
        SECURITY_CENTER,
        SECURITY_CORE,
    )

    /**
     * 「这次启动请给我小窗」——**模块自己的 App 进程**放在启动 Intent 上的标记。
     *
     * 为什么需要它（用户 2026-10-17：「在 app 主页的收藏应用管理里，点击 app 不会小窗打开，而会
     * 把魅窗自身变为小窗。可能是因为安全中心此时弹出了'xx 想要打开 xx，是否继续'的弹窗。小窗打开里
     * 的应用间跳转修过这个。」）：
     *
     * 从 App 进程直接带 MIUI 的 freeform `ActivityOptions` 发给 `startActivity` 是走不通的 ——
     * `MiuiMultiWindowUtils.getActivityOptions` 先过 `checkAuthority`，第三方调用者拿到 null，我们只
     * 剩一个裸的 `setLaunchWindowingMode(5)`；而系统还要再经一次「应用间跳转」判定与安全中心拦截框，
     * 结果那次"小窗"落到了调用者（魅窗）自己身上，目标应用反而没有小窗。
     *
     * 所以改用**与「应用间跳转」完全相同的那条系统路径**：Intent 只带这个标记、不带任何 options，
     * system_server 里的闸门（[com.repl.bubbledrawer.xposed.LightOpenGate]）看到标记就返回 true，
     * ROM 自己用 `MiuiMultiWindowUtils.getActivityOptions(ctx, 目标包, true)` 生成正式的小窗参数 ——
     * 与扇形面板（SystemUI 身份）打开的完全是同一条链。
     */
    const val EXTRA_FREEFORM_REQUEST = "com.repl.bubbledrawer.extra.FREEFORM_REQUEST"

    // 动作常量自己写：纯逻辑层不 import android.content.Intent（单测里那是 stub）。
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"

    /**
     * Android TextClassifier 附带的标志位（API 28+）。
     * 当用户选中文本后在系统浮动工具栏（ActionMode）里点击识别出来的条目（打开链接 / 电话 / 地图等）时，
     * Android 系统会在生成的 Intent 里附带此 Extra。
     */
    const val EXTRA_FROM_TEXT_CLASSIFIER = "android.view.textclassifier.extra.FROM_TEXT_CLASSIFIER"

    /**
     * Google Gemini 在 Android 上的入口包名。
     * 本质是桌面点击 Gemini 图标时，bard 作为轻量蹦床立刻发 Intent 拉起主 Google 应用。
     */
    const val BARD_PACKAGE = "com.google.android.apps.bard"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    // ---------------------------------------------------------------- 名单编解码

    /**
     * 包名列表 → 单条字符串（换行分隔）。排序 + 去重 + 清洗，保证 UI 与 hook 两侧看到同一份。
     * 远程 prefs 里存 String 而不是 StringSet：StringSet 在 LSPosed 的跨进程镜像上语义更绕，
     * 而这份数据本来就是"一次写、多次读"。
     */
    fun encodeTargets(packages: Collection<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TARGET_LEN }
            .distinct()
            .sorted()
            .take(MAX_TARGETS)
            .joinToString("\n")

    /** 容错解析：换行/逗号/分号都当分隔符，顺带做与 [encodeTargets] 相同的清洗。 */
    fun decodeTargets(raw: String?): Set<String> {
        if (raw.isNullOrEmpty()) return emptySet()
        return raw.split('\n', ',', ';')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TARGET_LEN }
            .distinct()
            .take(MAX_TARGETS)
            .toSet()
    }

    /** 从快照字段拼出判定用的设置对象。 */
    fun from(
        enabled: Boolean,
        notificationPortrait: Boolean,
        notificationLandscape: Boolean,
        controlCenter: Boolean,
        shareToApp: Boolean,
        textAction: Boolean,
        appJump: Boolean,
        jumpMode: Int,
        jumpListRaw: String?,
    ): LightOpenSettings = LightOpenSettings(
        enabled = enabled,
        notificationPortrait = notificationPortrait,
        notificationLandscape = notificationLandscape,
        controlCenter = controlCenter,
        shareToApp = shareToApp,
        textAction = textAction,
        appJump = appJump,
        jumpMode = if (jumpMode == MODE_BLACK) MODE_BLACK else MODE_WHITE,
        jumpTargets = decodeTargets(jumpListRaw),
    )

    /** 是否属于内建的桌面/负一屏/全局搜索包名。 */
    fun isBuiltInHomePackage(packageName: String?): Boolean =
        packageName != null && packageName in BUILTIN_HOME_PACKAGES

    /** 是否为标准桌面图标主入口启动（`ACTION_MAIN` + `CATEGORY_LAUNCHER`）。 */
    fun isMainLauncherIntent(action: String?, categories: Collection<String>?): Boolean =
        action == ACTION_MAIN && categories != null && CATEGORY_LAUNCHER in categories

    /**
     * 「模块自己的 App 进程点名要小窗」（Intent 带 [EXTRA_FREEFORM_REQUEST] 或一次性口令）该不该被闸门接管。
     *
     * 护栏：
     *  1. 目标解析不出来、或目标本身是安全中心的拦截框/分身选择框（[SECURITY_CENTER] / [SECURITY_CORE]）
     *     → 不接管（拦截框绝不能变成小窗，真实目标在它后面那一次启动里）；
     *  2. 当前前台是桌面（[isHomeTop]）或前台全屏应用既不是模块自身也不是安全中心确认框
     *     → 绝不接管！防止残留口令或被复用的 Root Intent 在用户从桌面直接点图标时误触发小窗。
     *
     * 这条判定与「轻量打开」的六个开关无关（它跟的是设置页的「小窗启动」），所以不接收
     * [LightOpenSettings]，纯函数、有单测。
     */
    fun requestedFreeformDecision(
        targetPackage: String?,
        topFullscreenPackage: String? = null,
        isHomeTop: Boolean = false,
    ): LightOpenDecision = when {
        targetPackage.isNullOrBlank() -> LightOpenDecision.LEAVE
        targetPackage == SECURITY_CENTER || targetPackage == SECURITY_CORE -> LightOpenDecision.LEAVE
        isHomeTop || isBuiltInHomePackage(topFullscreenPackage) -> LightOpenDecision.LEAVE
        topFullscreenPackage != null && topFullscreenPackage !in ALLOWED_GATE_TOP_PACKAGES -> LightOpenDecision.LEAVE
        else -> LightOpenDecision.FORCE_FREEFORM
    }

    // ---------------------------------------------------------------- 开关判定

    /**
     * 竖屏/横屏点通知是否强制小窗。
     *
     * @param creatorPackage 通知的 PendingIntent 创建者（`PendingIntent.getCreatorPackage()`），
     *        也就是"这个通知属于哪个应用" —— 与目标 Activity 可能不同（例如系统代理通知），
     *        但它是这次启动真正要带起来的应用，所以按它判定。
     * @param isPortrait 点击发生时屏幕是否为竖屏。
     */
    fun notificationDecision(
        settings: LightOpenSettings,
        creatorPackage: String?,
        isPortrait: Boolean,
    ): LightOpenDecision {
        if (!settings.enabled) return LightOpenDecision.LEAVE
        if (creatorPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        val on = if (isPortrait) settings.notificationPortrait else settings.notificationLandscape
        return if (on) LightOpenDecision.FORCE_FREEFORM else LightOpenDecision.LEAVE
    }

    /** 控制中心长按磁贴是否强制小窗。 */
    fun controlCenterDecision(
        settings: LightOpenSettings,
        targetPackage: String?,
    ): LightOpenDecision {
        if (!settings.enabled || !settings.controlCenter) return LightOpenDecision.LEAVE
        if (targetPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        return LightOpenDecision.FORCE_FREEFORM
    }

    /**
     * system_server 闸门判定（分享至应用 / 文本操作 / 应用间跳转）。
     *
     * 护栏（真机 ROM 之外我们自己加的，缺一个就会把"正常启动"当成"跳转"）：
     *  1. 当前前台是桌面/负一屏/最近任务（`isHomeTop` 或 [BUILTIN_HOME_PACKAGES]）→ 不干预。桌面点图标、
     *     桌面文件夹、负一屏卡片、HyperOS 最近任务卡片都落在这里；少了这条，全局小窗模式下桌面启动也会变
     *     小窗（用户 2026-10 明确否掉）。
     *  2. 本次启动本身是标准桌面主入口（`ACTION_MAIN` + `CATEGORY_LAUNCHER`）→ 不干预。兜住上滑回桌面
     *     动画尚未结束时快速点桌面图标（此时 ATMS 的 `topFullscreenPackage` 仍停留在上一个全屏应用）或
     *     桌面加锁/分身应用解锁后继续启动主入口的场景。
     *  3. 前台包为空（锁屏/息屏/没有明确前台）→ 不干预。
     *  4. 目标包 == 前台包（同应用内跳转）→ 不干预。ROM 自己也会拦，这里是双保险。
     *
     * @param action 目标 Intent 的 action
     * @param targetPackage 目标 Activity 的包名（ROM 内部叫 rawPackageName）
     * @param topFullscreenPackage ROM 传进来的"当前前台全屏应用包名"（排除小窗后的顶层 Activity 包名）
     * @param isHomeTop [topFullscreenPackage] 或发起调用方是否属于 HOME 包集合
     * @param hasSelectedText 本次 Intent 是否带着"选中的文本"extra（文本操作那条规则要用，见 [isTextAction]）
     * @param fromTextClassifier 本次 Intent 是否来自系统 TextClassifier（选中文本后浮动工具栏条目）
     * @param isMainLauncherIntent 本次 Intent 是否为 `ACTION_MAIN` + `CATEGORY_LAUNCHER` 桌面图标主入口
     */
    fun jumpDecision(
        settings: LightOpenSettings,
        action: String?,
        targetPackage: String?,
        topFullscreenPackage: String?,
        isHomeTop: Boolean,
        hasSelectedText: Boolean = false,
        fromTextClassifier: Boolean = false,
        isMainLauncherIntent: Boolean = false,
    ): LightOpenDecision {
        if (!settings.enabled) return LightOpenDecision.LEAVE
        if (topFullscreenPackage.isNullOrBlank()) return LightOpenDecision.LEAVE
        if (isHomeTop || isBuiltInHomePackage(topFullscreenPackage)) return LightOpenDecision.LEAVE
        if (isMainLauncherIntent) return LightOpenDecision.LEAVE
        val target = targetPackage
        if (target.isNullOrBlank()) return LightOpenDecision.LEAVE
        if (target == topFullscreenPackage) return LightOpenDecision.LEAVE
        // 系统拦截框（安全中心的"是否允许"或分身选择框）永远不参与小窗：它出现时，"目标应用"其实是它，
        // 真正的目标在用户点"允许"之后的第二次启动里 —— 那一次才该由我们判定。
        if (target == SECURITY_CENTER || target == SECURITY_CORE) return LightOpenDecision.LEAVE
        // Gemini 启动蹦床（com.google.android.apps.bard → com.google.android.googlequicksearchbox）：
        // 本质是桌面点击 Gemini 时的入口蹦床，保持全屏。
        if (topFullscreenPackage == BARD_PACKAGE && target == GOOGLE_SEARCH_PACKAGE) {
            return LightOpenDecision.LEAVE
        }

        // 黑名单是「否决票」：名单内的应用即使命中了分享/文本开关也保持全屏。
        val listed = target in settings.jumpTargets
        if (settings.appJump && settings.jumpMode == MODE_BLACK && listed) {
            return LightOpenDecision.FORCE_FULLSCREEN
        }
        if (settings.appJump) {
            if (settings.jumpMode == MODE_WHITE) {
                if (listed) return LightOpenDecision.FORCE_FREEFORM
            } else {
                return LightOpenDecision.FORCE_FREEFORM
            }
        }
        if (settings.shareToApp && isShareAction(action)) return LightOpenDecision.FORCE_FREEFORM
        if (settings.textAction && isTextAction(action, hasSelectedText, fromTextClassifier)) {
            return LightOpenDecision.FORCE_FREEFORM
        }
        return LightOpenDecision.LEAVE
    }

    /** 分享至应用：系统的分享动作（含"分享多个"）。 */
    fun isShareAction(action: String?): Boolean =
        action == ACTION_SEND || action == ACTION_SEND_MULTIPLE

    /**
     * 文本操作：编辑条/选中文本工具栏里的条目。
     *
     *  - `fromTextClassifier`：来自系统 TextClassifier 生成的动作（打开链接 / 电话 / 地图等，
     *    带 `android.view.textclassifier.extra.FROM_TEXT_CLASSIFIER` extra），直接认。
     *  - `PROCESS_TEXT` / `WEB_SEARCH` 是明确的"文本操作"动作，直接认。
     *  - `ACTION_VIEW` 并且携带选中文本 extra（`EXTRA_PROCESS_TEXT` / `EXTRA_TEXT` / `EXTRA_HTML_TEXT`）。
     *
     * 之前只看 http(s) scheme 就认，真机误伤：从桌面打开 Gemini，Gemini 自己 VIEW 跳 Google 应用被
     * 误判为文本操作。现在区分 TextClassifier 标记、选中文本 extra 与普通链接跳转。
     */
    fun isTextAction(
        action: String?,
        hasSelectedText: Boolean,
        fromTextClassifier: Boolean = false,
    ): Boolean {
        if (fromTextClassifier) return true
        if (action == ACTION_PROCESS_TEXT || action == ACTION_WEB_SEARCH) return true
        return action == ACTION_VIEW && hasSelectedText
    }
}
