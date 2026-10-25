package com.repl.bubbledrawer.xposed

import android.content.SharedPreferences

/**
 * Cross-process config protocol (same mechanism as the FlymeFreeform reference:
 * ModuleMain.kt:46 getRemotePreferences(GROUP) + ProcessConfiguration.kt:25-30
 * registering a listener on the remote SharedPreferences proxy — LSPosed mirrors
 * this single file into every hooked process, so the settings UI (app process)
 * and the live monitor (SystemUI) share ONE source of truth).
 *
 * Corners: BOTTOM-LEFT / BOTTOM-RIGHT only — the physical bottom corners (user
 * request "真正的左右下角内滑触发"; the decompiled flyme original and the reference
 * are both bottom-corner triggers, and the fan geometry pivots at the bottom edge).
 */
object RemotePrefs {
    const val GROUP = "bubble_runtime"

    const val KEY_ENABLED = "enabled"
    const val KEY_LEFT = "corner_left_enabled"
    const val KEY_RIGHT = "corner_right_enabled"
    const val KEY_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_FREEFORM = "freeform"

    /**
     * Trigger REGION, not just its size: the corner is a quarter-ELLIPSE whose two
     * semi-axes are set independently — how far the zone reaches ALONG the bottom edge
     * ([KEY_BOTTOM_DP]) and how far it reaches UP the side edge ([KEY_EDGE_DP]). Equal
     * values give the plain quarter-disc; a large edge axis with a small bottom axis is
     * "swipe up along the side", the opposite is "swipe in along the bottom". Both fall
     * back to [KEY_RANGE_DP] so an existing install keeps its single slider behaviour.
     */
    const val KEY_BOTTOM_DP = "corner_trigger_bottom_dp"
    const val KEY_EDGE_DP = "corner_trigger_edge_dp"

    /**
     * Written by the system_server arbiter hook ([LauncherMonitorRegion]) once MiuiHome's
     * "[Gesture Monitor] swipe-up" carries a touchable region with the corner boxes cut
     * out — i.e. the launcher's own recogniser no longer receives corner DOWNs. While that
     * proof is present SystemUI can stay a pure observer and take nothing from the app;
     * without it the bottom strip is owned at DOWN, because otherwise one corner swipe
     * also commits MiuiHome's HOME animation (its recogniser decides from the first
     * upward sample). LSPosed remote preferences are mirrored into every hooked process,
     * which is exactly the cross-process channel this needs.
     */
    const val KEY_ARBITER_REGION = "arbiter_launcher_region_epoch_ms"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_LEFT = true
    const val DEFAULT_RIGHT = true
    const val DEFAULT_RANGE_DP = 96
    const val DEFAULT_FREEFORM = false

    /** 更多面板长宽/图标/文字 默认值与范围 */
    const val KEY_PANEL_W_PCT = "panel_width_pct"
    const val KEY_PANEL_H_PCT = "panel_height_pct"
    const val KEY_PANEL_ICON_DP = "panel_icon_dp"
    const val KEY_PANEL_TEXT_SP = "panel_text_sp"

    const val PANEL_PCT_MIN = 40
    const val PANEL_PCT_MAX = 100

    const val DEFAULT_PANEL_W_PCT = 80
    const val PANEL_W_PCT_MIN = PANEL_PCT_MIN
    const val PANEL_W_PCT_MAX = PANEL_PCT_MAX

    const val DEFAULT_PANEL_H_PCT = 70
    const val PANEL_H_PCT_MIN = PANEL_PCT_MIN
    const val PANEL_H_PCT_MAX = PANEL_PCT_MAX

    const val DEFAULT_PANEL_ICON_DP = 45
    const val PANEL_ICON_MIN = 28
    const val PANEL_ICON_MAX = 64

    const val DEFAULT_PANEL_TEXT_SP = 10
    const val PANEL_TEXT_MIN = 8
    const val PANEL_TEXT_MAX = 18

    /** 更多面板点击外部收起方式：0 = 单击，1 = 双击 */
    const val KEY_PANEL_DISMISS_OUTSIDE = "panel_dismiss_outside"
    const val DISMISS_OUTSIDE_SINGLE = 0
    const val DISMISS_OUTSIDE_DOUBLE = 1
    const val DEFAULT_PANEL_DISMISS_OUTSIDE = DISMISS_OUTSIDE_SINGLE

    /** 扇形面板应用图标数量：5 或 6 个 */
    const val KEY_FAN_ICON_COUNT = "fan_icon_count"
    const val DEFAULT_FAN_ICON_COUNT = 6

    /**
     * 扇形层数：1 = 单层（原样），2 = 双层。
     *
     * 双层时 [KEY_FAN_ICON_COUNT] 是**内层**的应用数：内层再带一枚「更多」，外层比内层多 2 个应用
     * （内层 5 应用 → 外层 7 应用），外层没有「更多」入口。规则与几何见
     * [com.repl.bubbledrawer.bubble.FanLayout]。
     */
    const val KEY_FAN_LAYERS = "fan_layers"
    const val DEFAULT_FAN_LAYERS = 1
    const val FAN_LAYERS_SINGLE = 1
    const val FAN_LAYERS_DOUBLE = 2

    /** 展开扇形半径（dp）。用户 2026-10-16 之后它的含义是**内圈**半径（见 [KEY_FAN_OUTER_RADIUS_ENABLED]）。 */
    const val KEY_FAN_RADIUS_DP = "fan_radius_dp"
    const val DEFAULT_FAN_RADIUS_DP = 260
    const val FAN_RADIUS_MIN = 180
    const val FAN_RADIUS_MAX = 360

    /**
     * 外圈半径单独控制（用户 2026-10-16：「加一个外圈半径单独控制的开关和滑条」）。
     *
     * 关（默认）：外圈跟着内圈走 —— 外圈 = 内圈 + 一圈图标 + 径向间隙（刚好不叠的那个位置）。
     * 开：外圈用 [KEY_FAN_OUTER_RADIUS_DP] 那个滑条，**不会小于内圈**（比内圈小的自动回到内圈半径）。
     */
    const val KEY_FAN_OUTER_RADIUS_ENABLED = "fan_outer_radius_enabled"
    const val DEFAULT_FAN_OUTER_RADIUS_ENABLED = false

    /**
     * 外圈半径（dp，只在上一个开关打开时生效）。
     *
     * 默认 312 = 内圈默认 260 + 一圈图标 44 + 间隙 8，也就是「自动」那条算出来的值 —— 打开开关的
     * 一瞬间扇形不该跳一下。
     */
    const val KEY_FAN_OUTER_RADIUS_DP = "fan_outer_radius_dp"
    const val DEFAULT_FAN_OUTER_RADIUS_DP = 312
    const val FAN_OUTER_RADIUS_MIN = FAN_RADIUS_MIN
    const val FAN_OUTER_RADIUS_MAX = FAN_RADIUS_MAX

    /** 扇形滑出时振动一下（默认开启，与 Flyme 的手感一致）。 */
    const val KEY_FAN_SHOW_VIBRATE = "fan_show_vibrate"
    const val DEFAULT_FAN_SHOW_VIBRATE = true

    /**
     * 扇形滑出后不点选就自动收回的时间（秒）。0 = 一直停留，直到点选应用、点按外部或熄屏。
     *
     * 参考实现固定 5s（CornerRadialOverlayView 的 GESTURE_TIMEOUT_MS）；这里做成可调，默认取
     * 10s —— 5s 那份曾被用户否掉（"扇形面板弹出后不要过几秒就消失"），10s 既满足"不点就退回"，
     * 又不至于让人还没看清就没了。想要老手感就拖到 5，想永远停留就拖到最左。
     */
    const val KEY_FAN_AUTO_RETRACT_SEC = "fan_auto_retract_sec"
    const val DEFAULT_FAN_AUTO_RETRACT_SEC = 10
    const val FAN_AUTO_RETRACT_MIN = 0
    const val FAN_AUTO_RETRACT_MAX = 30

    /**
     * 角落手势的黑名单（应用包名，换行分隔）。
     *
     * 用户 2026-10-18：「在启用角落手势下面加一个手势黑名单和横屏时需滑动两次才打开的开关」。
     * 黑名单里的应用在前台时，角落手势**什么都不做**（不弹扇形、也不吞那一笔）：看视频、玩游戏时
     * 手指贴边很容易误触。存字符串而不是 StringSet：远程组是按 key 逐个镜像的，StringSet 在那边
     * 没有对应类型（见 `RemoteBridge.syncLocal`）。
     */
    const val KEY_FAN_BLACKLIST = "fan_blacklist"
    const val DEFAULT_FAN_BLACKLIST = ""
    const val FAN_BLACKLIST_SEPARATOR = "\n"

    /**
     * 横屏时角落手势要**滑两次**才打开扇形（默认关）。
     *
     * 同一个要求的另一半：横屏第一次上滑只提示一句「再次上滑」，[FAN_SECOND_SWIPE_MS] 内同一侧
     * 再滑一次才真的弹出 —— 横屏时边角是游戏/视频最常用的触控区域。
     */
    const val KEY_FAN_LANDSCAPE_TWO_SWIPES = "fan_landscape_two_swipes"
    const val DEFAULT_FAN_LANDSCAPE_TWO_SWIPES = false

    /** 第一次上滑之后多久内再滑一次算"第二次"（毫秒）。 */
    const val FAN_SECOND_SWIPE_MS = 1600L
    /**
     * 扇形面板「双击外部关闭」（默认**关** = 点一下扇形外面就收起）。
     *
     * 用户 2026-10-17：「在扇形面板设置里加一个双击外部关闭的开关，默认关。」
     *
     * 与「更多面板」的 [KEY_PANEL_DISMISS_OUTSIDE] 是两件事，互不影响：那个是面板自己的
     * 单击/双击选择，这一项只管**扇形**的 canvas。它也不影响「上下滑翻页」——
     * 竖直滑永远是翻页，只有"点一下"才走这里的收起判定（见 `FanHost` 的 canvas 手势）。
     */
    const val KEY_FAN_DOUBLE_TAP_DISMISS = "fan_double_tap_dismiss"
    const val DEFAULT_FAN_DOUBLE_TAP_DISMISS = false

    /**
     * 「下一次某某应用的启动请给小窗」的**一次性口令**（"包名|截止 elapsedRealtime"）。
     *
     * 与 [com.repl.bubbledrawer.lightopen.LightOpen.EXTRA_FREEFORM_REQUEST] 配套：Intent 上那个
     * 标记是**首选**通道（同一次 binder 调用、零延迟），但安全中心的「xx 想要打开 xx」拦截框
     * 有可能重造 Intent 而丢掉 extras —— 用户点「允许」之后的那次启动就只剩这个口令能认出来。
     *
     * 为什么是"包名 + 截止时间"而不是布尔：它必须**只对那一次启动**生效。3 秒之后自动作废，
     * 免得用户点了一下没成功、几秒后再从别处打开同一个应用也被塞进小窗。
     */
    const val KEY_LAUNCH_PRIME = "launch_prime"

    /** 小窗启动口令的有效期（毫秒，从写入那一刻算）。要盖得住"看弹窗 + 点允许"的思考时间。 */
    const val LAUNCH_PRIME_TTL_MS = 8_000L

    fun encodeLaunchPrime(packageName: String, deadlineElapsedMs: Long): String =
        "$packageName|$deadlineElapsedMs"

    /**
     * @return 口令里的包名与截止时间；格式不对/为空返回 null（老版本写的值、半截写入都不认）。
     */
    fun decodeLaunchPrime(raw: String?): Pair<String, Long>? {
        if (raw.isNullOrEmpty()) return null
        val sep = raw.lastIndexOf('|')
        if (sep <= 0 || sep == raw.length - 1) return null
        val pkg = raw.substring(0, sep)
        val deadline = raw.substring(sep + 1).toLongOrNull() ?: return null
        if (pkg.isEmpty() || deadline <= 0L) return null
        return pkg to deadline
    }

    /**
     * 校验一次性口令在当前时刻是否有效，返回有效口令的目标包名（无效返回 null）。
     *
     * 必须同时拦住三件事：
     *  1. 已被消费过的同一条口令（[consumedRaw]）：`XSharedPreferences` 在 system_server 侧只读，
     *     必须在内存里记下已消费的原串，保证「一次性」语义，防止 8 秒内回桌面再点图标仍命中；
     *  2. 已过期的口令（`nowElapsedMs > deadline`）；
     *  3. 跨重启残留的旧口令（`deadline - nowElapsedMs > LAUNCH_PRIME_TTL_MS`）：`elapsedRealtime`
     *     在手机重启后从 0 重新计时，若重启前落盘了一个较大的 deadline（如开机 20 小时后写的），
     *     重启后单纯比 `now <= deadline` 会在接下来 20 小时内恒为真，导致即使没开轻量打开、从桌面
     *     直接打开该应用也会被强制变小窗。
     */
    fun activeLaunchPrime(
        raw: String?,
        nowElapsedMs: Long,
        consumedRaw: String? = null,
    ): String? {
        if (raw.isNullOrEmpty() || raw == consumedRaw) return null
        val (pkg, deadline) = decodeLaunchPrime(raw) ?: return null
        val remaining = deadline - nowElapsedMs
        if (remaining < 0L || remaining > LAUNCH_PRIME_TTL_MS) return null
        return pkg
    }

    /** 扇形图标大小（dp，默认 = launcher_app_item_icon_width 44dp）。 */
    const val KEY_FAN_ICON_DP = "fan_icon_dp"
    const val DEFAULT_FAN_ICON_DP = 44
    const val FAN_ICON_MIN = 32
    const val FAN_ICON_MAX = 64

    /** 扇形图标形状：0 = 圆形（Flyme 原样），1 = 系统样式圆角矩形。 */
    const val KEY_FAN_ICON_SHAPE = "fan_icon_shape"
    const val FAN_ICON_SHAPE_CIRCLE = 0
    const val FAN_ICON_SHAPE_ROUNDED = 1
    const val DEFAULT_FAN_ICON_SHAPE = FAN_ICON_SHAPE_CIRCLE

    /**
     * 分身应用（MIUI 应用双开 / user 999，以及其它用户配置）图标右下角的分身角标。
     *
     * 无论开关如何，**图本身永远取主空间那张**（见 `AppRepository.mainSpaceIcon`）—— 这一项只管
     * 要不要在它上面叠一个角标：开启时一眼能分出「主」和「分身」，关掉时分身图标与主空间完全一致。
     */
    const val KEY_CLONE_BADGE = "clone_badge_enabled"
    const val DEFAULT_CLONE_BADGE = true

    /** 扇形应用未摆满时用推荐应用填满（默认关闭） */
    const val KEY_FAN_AUTO_FILL_RECOMMEND = "fan_auto_fill_recommend"
    const val DEFAULT_FAN_AUTO_FILL_RECOMMEND = false

    /** 扇形应用重按翻页（默认开启） */
    const val KEY_FAN_PRESSURE_PAGE_TURN = "fan_pressure_page_turn"
    const val DEFAULT_FAN_PRESSURE_PAGE_TURN = true

    /** 扇形重按灵敏度（0: 标准 1.2hPa, 1: 灵敏 0.8hPa, 2: 较重 1.8hPa）—— 仅在**未校准**时生效 */
    const val KEY_FAN_PRESSURE_SENSITIVITY = "fan_pressure_sensitivity"
    const val DEFAULT_FAN_PRESSURE_SENSITIVITY = 0

    fun sensitivityToThreshold(sensitivity: Int): Float = when (sensitivity) {
        1 -> 0.8f
        2 -> 1.8f
        else -> 1.2f
    }

    /**
     * 气压重按**校准**（参考实现 `com.oxohang.fanfreeform` 的「按压手势 → 气压校准」）。
     *
     * 灵敏度预设是「三档拍脑袋」，校准是「照着这台机器的实际按压幅度算」：实按
     * [com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS] 次，用样本的中位数与
     * 最小值的中点当阈值。校准过以后 [KEY_FAN_PRESSURE_CALIBRATED_HPA] 优先，没校准过才回退到预设
     * —— 见 [pressureThresholdFor]。
     *
     * 这些键跨进程共享（与其它设置同一套路）：`x` 系列由 App 写、SystemUI 读；`y` 系列由 SystemUI
     * 通过广播交回 App 落盘（hook 进程写不了远程组，见 `PinSyncReceiver` 的注释）。
     */
    const val KEY_FAN_PRESSURE_CALIBRATED = "fan_pressure_calibrated"
    const val DEFAULT_FAN_PRESSURE_CALIBRATED = false

    /** 校准出的阈值（hPa）。0 表示没有可用值。 */
    const val KEY_FAN_PRESSURE_CALIBRATED_HPA = "fan_pressure_calibrated_hpa"
    const val DEFAULT_FAN_PRESSURE_CALIBRATED_HPA = 0f

    /** 会话进行中：App 置 true 请求开始，SystemUI 结束后由结果事件写回 false。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_ACTIVE = "fan_pressure_calibration_active"

    /** 本次会话已完成的按压次数（含无效样本）。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_ATTEMPTS = "fan_pressure_calibration_attempts"

    /** 本次会话已收集的有效样本数。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_VALID_COUNT = "fan_pressure_calibration_valid_count"

    /** 最近一次按压的峰值（hPa）。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_LAST_DELTA = "fan_pressure_calibration_last_delta"

    /** 最近一次按压是否记为有效样本。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_LAST_VALID = "fan_pressure_calibration_last_valid"

    /** 上一次会话的结果（[com.repl.bubbledrawer.bubble.PressureCalibration.Outcome]）。 */
    const val KEY_FAN_PRESSURE_CALIBRATION_OUTCOME = "fan_pressure_calibration_outcome"

    /**
     * 更多面板推荐应用开关与显示数量。
     *
     * 原来是 4–20 的档位滑块（4/8/12/16/20），用户 2026-10-14 要求「开放为用户手动输入」：现在任意
     * 整数都收，默认 [DEFAULT_RECOMMEND_COUNT]，范围 [RECOMMEND_COUNT_MIN]–[RECOMMEND_COUNT_MAX]。
     * 面板里一次最多铺 20 个（`pin/Sections.kt` 的 `VISIBLE_MAX`），其余折在「展开」后面 —— 所以上限
     * 可以比 20 大。
     */
    const val KEY_RECOMMEND_ENABLED = "recommend_enabled"
    const val KEY_RECOMMEND_COUNT = "recommend_count"
    const val DEFAULT_RECOMMEND_ENABLED = true
    const val DEFAULT_RECOMMEND_COUNT = 12
    const val RECOMMEND_COUNT_MIN = 4
    const val RECOMMEND_COUNT_MAX = 50

    /**
     * 推荐数量的唯一收口：**只钳位，不吸附档位**。
     *
     * 抽成函数是为了让「手输了 7 就该是 7」这条规则可测 —— 读取（[read]）、写入
     * （`SettingsStore.setRecommendCount`）两边各写一遍 `coerceIn` 迟早会分叉。
     */
    fun clampRecommendCount(value: Int): Int = value.coerceIn(RECOMMEND_COUNT_MIN, RECOMMEND_COUNT_MAX)
    /** 实验性：Flyme 样式轻量小窗设置 */
    const val KEY_FLYME_FREEFORM_ENABLED = "flyme_freeform_enabled"
    const val KEY_FLYME_FREEFORM_CENTER = "flyme_freeform_center"
    const val KEY_FLYME_FREEFORM_VERTICAL_PCT = "flyme_freeform_vertical_pct"
    const val KEY_FLYME_FREEFORM_NO_OFFSET = "flyme_freeform_no_offset"
    const val KEY_FLYME_FREEFORM_SCALE = "flyme_freeform_scale"
    const val KEY_FLYME_FREEFORM_OUTSIDE_DISMISS = "flyme_freeform_outside_dismiss"
    const val KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION = "flyme_freeform_outside_dismiss_action"
    const val KEY_FLYME_FREEFORM_SWIPE_UP_MINI = "flyme_freeform_swipe_up_mini"
    const val KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE = "flyme_freeform_swipe_up_hold_free"
    const val KEY_FLYME_FREEFORM_SWIPE_DOWN_FULL = "flyme_freeform_swipe_down_full"
    const val KEY_FLYME_FREEFORM_DIM_BG = "flyme_freeform_dim_bg"
    const val KEY_FLYME_MINI_MENU = "flyme_mini_menu"
    const val DEFAULT_FLYME_MINI_MENU = true

    /**
     * 迷你小窗长按菜单里「放大」的倍率（百分数）。
     *
     * 用户 2026-10-18：「再做一个大号迷你小窗的放大倍率滑块，现在默认是 1.5 倍」。
     * 正常迷你的缩放由 ROM 自己算（`MiuiMultiWindowUtils.getMiniFreeformScale`），这里只存倍数：
     * 放大后的 scale = 正常迷你 scale × 这个比例（`MiniScaleToggle.decideMiniScale`）。
     * 几何上限仍是 `MINI_SCALE_MAX`（0.95，再大就不是迷你小窗了）—— 250% 在部分机型上会先撞到
     * 这个上限再夹回来，滑块本身不隐藏该值。
     */
    const val KEY_FLYME_MINI_ENLARGE_PCT = "flyme_mini_enlarge_pct"
    const val DEFAULT_FLYME_MINI_ENLARGE_PCT = 150
    const val FLYME_MINI_ENLARGE_MIN = 100
    const val FLYME_MINI_ENLARGE_MAX = 250

    /**
     * 哪些应用进入迷你小窗时保持「放大」尺寸 —— 用户 2026-10-24：
     * 「保存放大的状态，如果已经是大迷你小窗了下次收为迷你小窗时还是大的」。
     *
     * 用户在长按菜单点「放大 / 还原」时写这个集合（`FlymeFreeformController.toggleMiniWindowScale`）；
     * `MiuiMultiWindowUtils.getMiniFreeformScale` 的 hook 在 ROM 每次计算迷你尺寸时按 [KEY_FLYME_MINI_ENLARGE_PCT]
     * 放大，所以拖动、回桌面、从最近任务开迷你窗等所有 ROM 路径都会直接按保存的尺寸生成，不需要事后再补动画。
     * 按包名保存（taskId 会变，包名才是「下次打开」还能命中的键）。
     *
     * 实际存储不在共享组里：hook 进程的 remote prefs 是只读镜像，`FlymeFreeformController` 把它写在
     * SystemUI 自己的 device-protected `bubbledrawer_mini_state` 中，这里保留键名作为唯一标识。
     */
    const val KEY_FLYME_MINI_ENLARGED_PACKAGES = "flyme_mini_enlarged_packages"

    /**
     * 「回桌面不收迷你」：全面屏手势上滑回桌面时，ROM 会把**所有**小窗一次性收成迷你小窗。
     *
     * 用户 2026-10-17：「目前上滑时会把所有小窗变为迷你小窗……在实验性功能里加一个“回桌面不收迷你”
     * 的开关。」这不是模块的行为，也不是「上滑小白条」那条单窗口路径 —— 它走的是框架广播
     * `com.miui.fullscreen_state_change`（`state=toHome` / `toRecents`）→ WMShell
     * `MiuiFreeformModeSettingsObserver.FreeFormBroadcastReceiver` →
     * `MiuiFreeformModeController.allFreeformFromFreeformToMini()`，遍历 Z 序上每一个小窗调
     * `fromFreeformToMini(taskId)`（缩小 + 贴到上方左/右角 + 迷你看转场）。
     *
     * 打开后模块在系统界面进程里把「批量」那两处入口拦掉（见
     * `FlymeFreeformController.hookHomeKeepFreeform`）：小窗保持原样，`toHome` 与 `toRecents` 都拦
     * （用户说的"上滑"落在这两个状态上都意味着"系统自动收迷你"，只拦一半的话同一开关会时灵时不灵）。
     * 模块自己的「小白条上滑 → 迷你小窗」是 `fromFreeformToMini(int)` 一层，**不受影响**；
     * ROM 从最近任务以小窗打开（`startEnterMiniShellTransition(..., "fromRecent")`）也不受影响。
     *
     * 与「Flyme 样式轻量小窗」总开关**无关**：像 [DEFAULT_FLYME_FREEFORM_NO_OFFSET] 一样，这是对
     * HyperOS 自身行为的开关，原生小窗模式下同样生效，所以不做 `flymeFreeformEnabled` 门控。
     */
    const val KEY_HOME_KEEP_FREEFORM = "home_keep_freeform"
    const val DEFAULT_HOME_KEEP_FREEFORM = false

    const val DEFAULT_FLYME_FREEFORM_ENABLED = false
    const val DEFAULT_FLYME_FREEFORM_CENTER = true

    /**
     * 小窗**上下位置**：竖屏时相对「正中的上下余量」的百分比 —— 0 = 正中、-100 = 贴顶、+100 = 贴底。
     *
     * 为什么是余量的百分比而不是 dp：余量 = 屏幕高 − 小窗可见高，所以这个值永远落在屏幕内，
     * 换分辨率/换缩放都不用重算。用户 2026-10-11：「竖方向默认居中但可以上下位置调整」，
     * 并且**横屏不要生效** —— 横屏小窗几乎占满高度，挪一下就出屏（那一边由 [KEY_FLYME_FREEFORM_CENTER]
     * 的居中负责，维持原样）。
     */
    const val DEFAULT_FLYME_FREEFORM_VERTICAL_PCT = 0
    const val FLYME_FREEFORM_VERTICAL_MIN = -100
    const val FLYME_FREEFORM_VERTICAL_MAX = 100

    /**
     * 禁止小窗偏移：已经有小窗时，新开的小窗不再为了避开它而挪位。
     *
     * HyperOS 会给第二个小窗加一个「错开」偏移（`MiuiMultiWindowUtils.avoidIfNeeded`：X +78dp、
     * Y +44dp，正好是往右下偏一点），Flyme 的小窗每次都开在同一个默认位置。
     * 默认关闭：不动系统原有摆放，想要 Flyme 那样"每次都开在同一处"再手动打开。
     *
     * 与 `flymeFreeformEnabled`（Flyme 样式小窗总开关）**无关**：这是对 HyperOS 自身避让行为的
     * 开关，原生小窗模式下同样生效。
     */
    const val DEFAULT_FLYME_FREEFORM_NO_OFFSET = false
    const val DEFAULT_FLYME_FREEFORM_SCALE = 80
    const val FLYME_FREEFORM_SCALE_MIN = 60
    const val FLYME_FREEFORM_SCALE_MAX = 95
    const val DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS = true
    const val DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION = 0
    const val DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI = true
    const val DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE = true
    const val DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL = true
    const val DEFAULT_FLYME_FREEFORM_DIM_BG = true

    /**
     * 小窗底部联系人条 — the port of Flyme's 「联系人头像」 (`com.flyme.systemuitools`
     * `notification_show_contact_list` System setting, default on). Independent of the Flyme-style
     * freeform takeover above: it rides HyperOS's own 小窗.
     */
    const val KEY_CONTACT_BAR = "contact_bar_enabled"
    const val DEFAULT_CONTACT_BAR = true

    /**
     * 联系人条的尺寸阈值：小窗可视宽度小于「屏幕宽度 × N%」时整条隐藏（0 = 不按尺寸隐藏）。
     * 默认 60%（用户 2026-10-09 指定）：小窗宽度约屏幕 70%，所以只要明显缩窄就隐藏。
     */
    const val KEY_CONTACT_BAR_MIN_WIDTH_PCT = "contact_bar_min_width_pct"
    const val DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT = 60
    const val CONTACT_BAR_MIN_WIDTH_MIN = 0
    const val CONTACT_BAR_MIN_WIDTH_MAX = 90

    /**
     * 「轻量打开」六开关（判定逻辑见 com.repl.bubbledrawer.lightopen.LightOpen）。
     *
     * 全部默认**关闭**：这些开关会改变系统启动路径的结果，第一次上机必须是"什么都不做"的状态。
     * [KEY_LIGHT_OPEN_JUMP_LIST] 存换行分隔的包名列表（编解码见 LightOpen.encodeTargets）；
     * [KEY_LIGHT_OPEN_JUMP_MODE]：0 = 白名单（名单内才小窗），1 = 黑名单（名单外全部小窗）。
     */
    const val KEY_LIGHT_OPEN_ENABLED = "light_open_enabled"
    const val KEY_LIGHT_OPEN_NOTIF_PORTRAIT = "light_open_notif_portrait"
    const val KEY_LIGHT_OPEN_NOTIF_LANDSCAPE = "light_open_notif_landscape"
    const val KEY_LIGHT_OPEN_CONTROL_CENTER = "light_open_control_center"
    const val KEY_LIGHT_OPEN_SHARE = "light_open_share"
    const val KEY_LIGHT_OPEN_TEXT = "light_open_text"
    const val KEY_LIGHT_OPEN_JUMP = "light_open_jump"
    const val KEY_LIGHT_OPEN_JUMP_MODE = "light_open_jump_mode"
    const val KEY_LIGHT_OPEN_JUMP_LIST = "light_open_jump_list"

    const val DEFAULT_LIGHT_OPEN_ENABLED = false
    const val DEFAULT_LIGHT_OPEN_NOTIF_PORTRAIT = false
    const val DEFAULT_LIGHT_OPEN_NOTIF_LANDSCAPE = false
    const val DEFAULT_LIGHT_OPEN_CONTROL_CENTER = false
    const val DEFAULT_LIGHT_OPEN_SHARE = false
    const val DEFAULT_LIGHT_OPEN_TEXT = false
    const val DEFAULT_LIGHT_OPEN_JUMP = false
    const val DEFAULT_LIGHT_OPEN_JUMP_MODE = com.repl.bubbledrawer.lightopen.LightOpen.MODE_WHITE
    const val DEFAULT_LIGHT_OPEN_JUMP_LIST = ""

    @androidx.compose.runtime.Immutable
    data class Snapshot(
        val enabled: Boolean,
        val left: Boolean,
        val right: Boolean,
        val rangeDp: Int,
        val bottomDp: Int,
        val edgeDp: Int,
        val freeform: Boolean,
        val pins: String,
        /** 更多面板：宽/高百分比，图标 dp / 文字 sp */
        val panelWidthPct: Int = DEFAULT_PANEL_W_PCT,
        val panelHeightPct: Int = DEFAULT_PANEL_H_PCT,
        val panelIconDp: Int = DEFAULT_PANEL_ICON_DP,
        val panelTextSp: Int = DEFAULT_PANEL_TEXT_SP,
        /** 更多面板点击外部收起方式：0 = 单击，1 = 双击 */
        val panelDismissOutside: Int = DEFAULT_PANEL_DISMISS_OUTSIDE,
        /** 扇形面板应用图标数量（5 或 6） */
        val fanIconCount: Int = DEFAULT_FAN_ICON_COUNT,
        /** 扇形层数：1 = 单层，2 = 双层（见 [KEY_FAN_LAYERS]） */
        val fanLayers: Int = DEFAULT_FAN_LAYERS,
        /** 展开扇形半径（dp）—— 双层时它是**内圈**；外圈见 [fanOuterRadiusDp] */
        val fanRadiusDp: Int = DEFAULT_FAN_RADIUS_DP,
        /** 外圈半径单独控制（见 [KEY_FAN_OUTER_RADIUS_ENABLED]） */
        val fanOuterRadiusEnabled: Boolean = DEFAULT_FAN_OUTER_RADIUS_ENABLED,
        /**
         * 外圈半径（dp，仅在上一个开关打开时生效）。
         *
         * **读出来就一定 ≥ [fanRadiusDp]**：内圈被调大时外圈跟着回到内圈半径（用户 2026-10-16：
         * 「滑条滑到了比内圈半径更小的会自动弹回内圈半径」）。写侧也夹一次（`SettingsStore`），
         * 这里再夹一次是为了兜住"先有外圈值、后被调大的内圈"这种顺序。
         */
        val fanOuterRadiusDp: Int = DEFAULT_FAN_OUTER_RADIUS_DP,
        /** 扇形滑出时振动 */
        val fanShowVibrate: Boolean = DEFAULT_FAN_SHOW_VIBRATE,
        /** 扇形滑出后不点选自动收回的秒数（0 = 不自动收回） */
        val fanAutoRetractSec: Int = DEFAULT_FAN_AUTO_RETRACT_SEC,
        /** 扇形「双击外部关闭」（默认关 = 单击即收起，见 [KEY_FAN_DOUBLE_TAP_DISMISS]） */
        val fanDoubleTapDismiss: Boolean = DEFAULT_FAN_DOUBLE_TAP_DISMISS,
        /** 角落手势黑名单（应用包名集合，见 [KEY_FAN_BLACKLIST]） */
        val fanBlacklist: Set<String> = emptySet(),
        /** 横屏时需滑动两次才打开扇形（见 [KEY_FAN_LANDSCAPE_TWO_SWIPES]） */
        val fanLandscapeTwoSwipes: Boolean = DEFAULT_FAN_LANDSCAPE_TWO_SWIPES,
        /** 扇形图标大小（dp） */
        val fanIconDp: Int = DEFAULT_FAN_ICON_DP,
        /** 扇形图标形状：0 = 圆形，1 = 系统样式圆角矩形 */
        val fanIconShape: Int = DEFAULT_FAN_ICON_SHAPE,
        /** 分身应用图标上的角标（图始终取主空间那张，这一项只管角标） */
        val cloneBadge: Boolean = DEFAULT_CLONE_BADGE,
        /** 扇形未摆满时用推荐应用填满 */
        val fanAutoFillRecommend: Boolean = DEFAULT_FAN_AUTO_FILL_RECOMMEND,
        /** 扇形重按翻页开关与灵敏度 */
        val fanPressurePageTurn: Boolean = DEFAULT_FAN_PRESSURE_PAGE_TURN,
        val fanPressureSensitivity: Int = DEFAULT_FAN_PRESSURE_SENSITIVITY,
        /** 气压重按校准：可用阈值 + 会话进度 + 上次结果 */
        val fanPressureCalibrated: Boolean = DEFAULT_FAN_PRESSURE_CALIBRATED,
        val fanPressureCalibratedHpa: Float = DEFAULT_FAN_PRESSURE_CALIBRATED_HPA,
        val fanPressureCalibrationActive: Boolean = false,
        val fanPressureCalibrationAttempts: Int = 0,
        val fanPressureCalibrationValidCount: Int = 0,
        val fanPressureCalibrationLastDelta: Float = 0f,
        val fanPressureCalibrationLastValid: Boolean = false,
        val fanPressureCalibrationOutcome: Int = com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.NONE,
        /** 推荐应用开关与显示数量（4–50，默认 12） */
        val recommendEnabled: Boolean = DEFAULT_RECOMMEND_ENABLED,
        val recommendCount: Int = DEFAULT_RECOMMEND_COUNT,
        /** 实验性：Flyme 样式轻量小窗设置 */
        val flymeFreeformEnabled: Boolean = DEFAULT_FLYME_FREEFORM_ENABLED,
        val flymeFreeformCenter: Boolean = DEFAULT_FLYME_FREEFORM_CENTER,
        /** 小窗上下位置：占「上下余量」的百分比，0 = 正中（横屏不生效） */
        val flymeFreeformVerticalPct: Int = DEFAULT_FLYME_FREEFORM_VERTICAL_PCT,
        /** 已经有小窗时，新小窗不再为避让而偏移（HyperOS 默认 X+78dp / Y+44dp） */
        val flymeFreeformNoOffset: Boolean = DEFAULT_FLYME_FREEFORM_NO_OFFSET,
        val flymeFreeformScale: Int = DEFAULT_FLYME_FREEFORM_SCALE,
        val flymeFreeformOutsideDismiss: Boolean = DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS,
        val flymeFreeformOutsideDismissAction: Int = DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION,
        val flymeFreeformSwipeUpMini: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI,
        val flymeFreeformSwipeUpHoldFree: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE,
        val flymeFreeformSwipeDownFull: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL,
        val flymeFreeformDimBg: Boolean = DEFAULT_FLYME_FREEFORM_DIM_BG,
        val flymeMiniMenu: Boolean = DEFAULT_FLYME_MINI_MENU,
        /** 长按菜单「放大」的倍率（百分数），见 [KEY_FLYME_MINI_ENLARGE_PCT] */
        val flymeMiniEnlargePct: Int = DEFAULT_FLYME_MINI_ENLARGE_PCT,
        /** 回桌面不收迷你（见 [KEY_HOME_KEEP_FREEFORM]，与 Flyme 样式总开关无关） */
        val homeKeepFreeform: Boolean = DEFAULT_HOME_KEEP_FREEFORM,
        /** 小窗底部联系人条（Flyme「联系人头像」） */
        val contactBar: Boolean = DEFAULT_CONTACT_BAR,
        /** 联系人条尺寸阈值：小窗宽度低于屏幕宽度的 N% 时隐藏（0 = 关闭该限制） */
        val contactBarMinWidthPct: Int = DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT,
        /** 轻量打开：六个开关 + 应用间跳转的模式与名单（名单是换行分隔的原始字符串） */
        val lightOpenEnabled: Boolean = DEFAULT_LIGHT_OPEN_ENABLED,
        val lightOpenNotifPortrait: Boolean = DEFAULT_LIGHT_OPEN_NOTIF_PORTRAIT,
        val lightOpenNotifLandscape: Boolean = DEFAULT_LIGHT_OPEN_NOTIF_LANDSCAPE,
        val lightOpenControlCenter: Boolean = DEFAULT_LIGHT_OPEN_CONTROL_CENTER,
        val lightOpenShare: Boolean = DEFAULT_LIGHT_OPEN_SHARE,
        val lightOpenText: Boolean = DEFAULT_LIGHT_OPEN_TEXT,
        val lightOpenJump: Boolean = DEFAULT_LIGHT_OPEN_JUMP,
        val lightOpenJumpMode: Int = DEFAULT_LIGHT_OPEN_JUMP_MODE,
        val lightOpenJumpList: String = DEFAULT_LIGHT_OPEN_JUMP_LIST,
    )

    /** 轻量打开判定用的设置对象（hook 侧与 UI 侧读同一份）。 */
    fun lightOpenSettings(snap: Snapshot): com.repl.bubbledrawer.lightopen.LightOpenSettings =
        com.repl.bubbledrawer.lightopen.LightOpen.from(
            enabled = snap.lightOpenEnabled,
            notificationPortrait = snap.lightOpenNotifPortrait,
            notificationLandscape = snap.lightOpenNotifLandscape,
            controlCenter = snap.lightOpenControlCenter,
            shareToApp = snap.lightOpenShare,
            textAction = snap.lightOpenText,
            appJump = snap.lightOpenJump,
            jumpMode = snap.lightOpenJumpMode,
            jumpListRaw = snap.lightOpenJumpList,
        )

    fun enabledFor(side: com.repl.bubbledrawer.gesture.SpySide): (Snapshot) -> Boolean = { snap ->
        snap.enabled && when (side) {
            com.repl.bubbledrawer.gesture.SpySide.LEFT -> snap.left
            com.repl.bubbledrawer.gesture.SpySide.RIGHT -> snap.right
        }
    }

    /**
     * 实际生效的重按阈值（hPa）：**校准值优先，没有校准过才用灵敏度预设**
     * （[com.repl.bubbledrawer.bubble.PressureCalibration.effectiveThreshold]）。
     *
     * 收在这里是为了让 SystemUI 的三处赋值（初始化、设置变更、校准结束）读同一个来源 —— 之前
     * 「按哪一份值」这种事散在三处，就是收藏那次拉锯的同款隐患。
     */
    fun pressureThresholdFor(snap: Snapshot): Float =
        com.repl.bubbledrawer.bubble.PressureCalibration.effectiveThreshold(
            calibrated = snap.fanPressureCalibrated,
            calibratedHpa = snap.fanPressureCalibratedHpa,
            presetHpa = sensitivityToThreshold(snap.fanPressureSensitivity),
        )

    /** Pins share PrefsPinBackend.KEY so hooked and app processes use one key. */
    fun read(sp: SharedPreferences?): Snapshot {
        if (sp == null) {
            return Snapshot(false, false, false, DEFAULT_RANGE_DP, DEFAULT_RANGE_DP, DEFAULT_RANGE_DP, false, "")
        }
        val range = sp.getInt(KEY_RANGE_DP, DEFAULT_RANGE_DP).coerceIn(MIN_DP, MAX_DP)
        // 手输的整数直接收（不再吸附到 4 的倍数）：越界只钳位，档位由用户自己给。
        val recommendCount = clampRecommendCount(sp.getInt(KEY_RECOMMEND_COUNT, DEFAULT_RECOMMEND_COUNT))
        // 半径（dp）：双层时内圈 = 扇形半径设置，外圈另外一项。**外圈永远不小于内圈** ——
        // 内圈后来被调大时外圈跟着回到内圈半径（用户 2026-10-16 的规则，写侧也夹一次）。
        val fanRadius = sp.getInt(KEY_FAN_RADIUS_DP, DEFAULT_FAN_RADIUS_DP)
            .let { if (it <= 0) DEFAULT_FAN_RADIUS_DP else it.coerceIn(FAN_RADIUS_MIN, FAN_RADIUS_MAX) }
        val fanOuterRadius = sp.getInt(KEY_FAN_OUTER_RADIUS_DP, DEFAULT_FAN_OUTER_RADIUS_DP)
            .let { if (it <= 0) DEFAULT_FAN_OUTER_RADIUS_DP else it.coerceIn(FAN_OUTER_RADIUS_MIN, FAN_OUTER_RADIUS_MAX) }
            .coerceAtLeast(fanRadius)
        return Snapshot(
            enabled = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            left = sp.getBoolean(KEY_LEFT, DEFAULT_LEFT),
            right = sp.getBoolean(KEY_RIGHT, DEFAULT_RIGHT),
            rangeDp = range,
            // The two axes default to the single "size" value, so an existing install keeps
            // its behaviour until the new sliders are touched.
            bottomDp = sp.getInt(KEY_BOTTOM_DP, range).coerceIn(MIN_DP, MAX_DP),
            edgeDp = sp.getInt(KEY_EDGE_DP, range).coerceIn(MIN_DP, MAX_DP),
            freeform = sp.getBoolean(KEY_FREEFORM, DEFAULT_FREEFORM),
            panelWidthPct = sp.getInt(KEY_PANEL_W_PCT, DEFAULT_PANEL_W_PCT).let { if (it <= 0) DEFAULT_PANEL_W_PCT else it.coerceIn(PANEL_W_PCT_MIN, PANEL_W_PCT_MAX) },
            panelHeightPct = sp.getInt(KEY_PANEL_H_PCT, DEFAULT_PANEL_H_PCT).let { if (it <= 0) DEFAULT_PANEL_H_PCT else it.coerceIn(PANEL_H_PCT_MIN, PANEL_H_PCT_MAX) },
            panelIconDp = sp.getInt(KEY_PANEL_ICON_DP, DEFAULT_PANEL_ICON_DP).let { if (it <= 0) DEFAULT_PANEL_ICON_DP else it.coerceIn(PANEL_ICON_MIN, PANEL_ICON_MAX) },
            panelTextSp = sp.getInt(KEY_PANEL_TEXT_SP, DEFAULT_PANEL_TEXT_SP).let { if (it <= 0) DEFAULT_PANEL_TEXT_SP else it.coerceIn(PANEL_TEXT_MIN, PANEL_TEXT_MAX) },
            panelDismissOutside = sp.getInt(KEY_PANEL_DISMISS_OUTSIDE, DEFAULT_PANEL_DISMISS_OUTSIDE).coerceIn(0, 1),
            fanIconCount = sp.getInt(KEY_FAN_ICON_COUNT, DEFAULT_FAN_ICON_COUNT).coerceIn(5, 6),
            fanLayers = sp.getInt(KEY_FAN_LAYERS, DEFAULT_FAN_LAYERS).coerceIn(FAN_LAYERS_SINGLE, FAN_LAYERS_DOUBLE),
            fanRadiusDp = fanRadius,
            fanOuterRadiusEnabled = sp.getBoolean(KEY_FAN_OUTER_RADIUS_ENABLED, DEFAULT_FAN_OUTER_RADIUS_ENABLED),
            fanOuterRadiusDp = fanOuterRadius,
            fanShowVibrate = sp.getBoolean(KEY_FAN_SHOW_VIBRATE, DEFAULT_FAN_SHOW_VIBRATE),
            fanAutoRetractSec = sp.getInt(KEY_FAN_AUTO_RETRACT_SEC, DEFAULT_FAN_AUTO_RETRACT_SEC)
                .coerceIn(FAN_AUTO_RETRACT_MIN, FAN_AUTO_RETRACT_MAX),
            fanDoubleTapDismiss = sp.getBoolean(KEY_FAN_DOUBLE_TAP_DISMISS, DEFAULT_FAN_DOUBLE_TAP_DISMISS),
            fanBlacklist = decodeFanBlacklist(sp.getString(KEY_FAN_BLACKLIST, DEFAULT_FAN_BLACKLIST)),
            fanLandscapeTwoSwipes = sp.getBoolean(
                KEY_FAN_LANDSCAPE_TWO_SWIPES,
                DEFAULT_FAN_LANDSCAPE_TWO_SWIPES,
            ),
            fanIconDp = sp.getInt(KEY_FAN_ICON_DP, DEFAULT_FAN_ICON_DP).let { if (it <= 0) DEFAULT_FAN_ICON_DP else it.coerceIn(FAN_ICON_MIN, FAN_ICON_MAX) },
            fanIconShape = sp.getInt(KEY_FAN_ICON_SHAPE, DEFAULT_FAN_ICON_SHAPE).coerceIn(0, 1),
            cloneBadge = sp.getBoolean(KEY_CLONE_BADGE, DEFAULT_CLONE_BADGE),
            fanAutoFillRecommend = sp.getBoolean(KEY_FAN_AUTO_FILL_RECOMMEND, DEFAULT_FAN_AUTO_FILL_RECOMMEND),
            fanPressurePageTurn = sp.getBoolean(KEY_FAN_PRESSURE_PAGE_TURN, DEFAULT_FAN_PRESSURE_PAGE_TURN),
            fanPressureSensitivity = sp.getInt(KEY_FAN_PRESSURE_SENSITIVITY, DEFAULT_FAN_PRESSURE_SENSITIVITY).coerceIn(0, 2),
            fanPressureCalibrated = sp.getBoolean(KEY_FAN_PRESSURE_CALIBRATED, DEFAULT_FAN_PRESSURE_CALIBRATED),
            // 逐个字段都做一次「落到可解释区间」的清洗：老版本/半截写入留下的 0、NaN、越界值，
            // 到了这里必须变成"没有校准值"，否则会算出一个永远触发不了的阈值。
            fanPressureCalibratedHpa = sp.getFloat(KEY_FAN_PRESSURE_CALIBRATED_HPA, DEFAULT_FAN_PRESSURE_CALIBRATED_HPA)
                .let { if (it.isFinite() && it > 0f) it else 0f },
            fanPressureCalibrationActive = sp.getBoolean(KEY_FAN_PRESSURE_CALIBRATION_ACTIVE, false),
            fanPressureCalibrationAttempts = sp.getInt(KEY_FAN_PRESSURE_CALIBRATION_ATTEMPTS, 0)
                .coerceIn(0, com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS),
            fanPressureCalibrationValidCount = sp.getInt(KEY_FAN_PRESSURE_CALIBRATION_VALID_COUNT, 0)
                .coerceIn(0, com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS),
            fanPressureCalibrationLastDelta = sp.getFloat(KEY_FAN_PRESSURE_CALIBRATION_LAST_DELTA, 0f)
                .let { if (it.isFinite() && it > 0f) it else 0f },
            fanPressureCalibrationLastValid = sp.getBoolean(KEY_FAN_PRESSURE_CALIBRATION_LAST_VALID, false),
            fanPressureCalibrationOutcome = sp.getInt(
                KEY_FAN_PRESSURE_CALIBRATION_OUTCOME,
                com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.NONE,
            ).coerceIn(
                com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.NONE,
                com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.UNSUPPORTED,
            ),
            recommendEnabled = sp.getBoolean(KEY_RECOMMEND_ENABLED, DEFAULT_RECOMMEND_ENABLED),
            recommendCount = recommendCount,
            flymeFreeformEnabled = sp.getBoolean(KEY_FLYME_FREEFORM_ENABLED, DEFAULT_FLYME_FREEFORM_ENABLED),
            flymeFreeformCenter = sp.getBoolean(KEY_FLYME_FREEFORM_CENTER, DEFAULT_FLYME_FREEFORM_CENTER),
            flymeFreeformVerticalPct = sp.getInt(KEY_FLYME_FREEFORM_VERTICAL_PCT, DEFAULT_FLYME_FREEFORM_VERTICAL_PCT)
                .coerceIn(FLYME_FREEFORM_VERTICAL_MIN, FLYME_FREEFORM_VERTICAL_MAX),
            flymeFreeformNoOffset = sp.getBoolean(KEY_FLYME_FREEFORM_NO_OFFSET, DEFAULT_FLYME_FREEFORM_NO_OFFSET),
            flymeFreeformScale = sp.getInt(KEY_FLYME_FREEFORM_SCALE, DEFAULT_FLYME_FREEFORM_SCALE).coerceIn(FLYME_FREEFORM_SCALE_MIN, FLYME_FREEFORM_SCALE_MAX),
            flymeFreeformOutsideDismiss = sp.getBoolean(KEY_FLYME_FREEFORM_OUTSIDE_DISMISS, DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS),
            flymeFreeformOutsideDismissAction = sp.getInt(KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION, DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION).coerceIn(0, 1),
            flymeFreeformSwipeUpMini = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_UP_MINI, DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI),
            flymeFreeformSwipeUpHoldFree = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE, DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE),
            flymeFreeformSwipeDownFull = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_DOWN_FULL, DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL),
            flymeFreeformDimBg = sp.getBoolean(KEY_FLYME_FREEFORM_DIM_BG, DEFAULT_FLYME_FREEFORM_DIM_BG),
            flymeMiniMenu = sp.getBoolean(KEY_FLYME_MINI_MENU, DEFAULT_FLYME_MINI_MENU),
            flymeMiniEnlargePct = sp.getInt(KEY_FLYME_MINI_ENLARGE_PCT, DEFAULT_FLYME_MINI_ENLARGE_PCT)
                .coerceIn(FLYME_MINI_ENLARGE_MIN, FLYME_MINI_ENLARGE_MAX),
            homeKeepFreeform = sp.getBoolean(KEY_HOME_KEEP_FREEFORM, DEFAULT_HOME_KEEP_FREEFORM),
            contactBar = sp.getBoolean(KEY_CONTACT_BAR, DEFAULT_CONTACT_BAR),
            contactBarMinWidthPct = sp.getInt(KEY_CONTACT_BAR_MIN_WIDTH_PCT, DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT)
                .coerceIn(CONTACT_BAR_MIN_WIDTH_MIN, CONTACT_BAR_MIN_WIDTH_MAX),
            lightOpenEnabled = sp.getBoolean(KEY_LIGHT_OPEN_ENABLED, DEFAULT_LIGHT_OPEN_ENABLED),
            lightOpenNotifPortrait = sp.getBoolean(KEY_LIGHT_OPEN_NOTIF_PORTRAIT, DEFAULT_LIGHT_OPEN_NOTIF_PORTRAIT),
            lightOpenNotifLandscape = sp.getBoolean(KEY_LIGHT_OPEN_NOTIF_LANDSCAPE, DEFAULT_LIGHT_OPEN_NOTIF_LANDSCAPE),
            lightOpenControlCenter = sp.getBoolean(KEY_LIGHT_OPEN_CONTROL_CENTER, DEFAULT_LIGHT_OPEN_CONTROL_CENTER),
            lightOpenShare = sp.getBoolean(KEY_LIGHT_OPEN_SHARE, DEFAULT_LIGHT_OPEN_SHARE),
            lightOpenText = sp.getBoolean(KEY_LIGHT_OPEN_TEXT, DEFAULT_LIGHT_OPEN_TEXT),
            lightOpenJump = sp.getBoolean(KEY_LIGHT_OPEN_JUMP, DEFAULT_LIGHT_OPEN_JUMP),
            lightOpenJumpMode = sp.getInt(KEY_LIGHT_OPEN_JUMP_MODE, DEFAULT_LIGHT_OPEN_JUMP_MODE)
                .coerceIn(com.repl.bubbledrawer.lightopen.LightOpen.MODE_WHITE, com.repl.bubbledrawer.lightopen.LightOpen.MODE_BLACK),
            lightOpenJumpList = sp.getString(KEY_LIGHT_OPEN_JUMP_LIST, DEFAULT_LIGHT_OPEN_JUMP_LIST).orEmpty(),
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }
    /** Slider bounds for every axis (kept in one place so UI and reader cannot drift). */
    const val MIN_DP = 24
    const val MAX_DP = 160
}

// ---------------------------------------------------------------------------------------------
// 手势黑名单的编解码（纯算术，见 `FanBlacklistCodecTest`）
// ---------------------------------------------------------------------------------------------

/**
 * 黑名单落盘格式：包名按行分隔。
 *
 * 为什么不是 `StringSet`：远程组是按 key 逐个镜像的（`RemoteBridge.syncLocal` 里那些
 * putInt/putBoolean），StringSet 在那套协议里没有对应类型。行分隔还顺手带来了可读性 ——
 * 一份坏掉的名单用 `cat` 就能看出来。
 */
fun encodeFanBlacklist(packages: Set<String>): String = packages
    .asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .sorted()
    .joinToString(RemotePrefs.FAN_BLACKLIST_SEPARATOR)

/** 反过来：空串 / 只有分隔符 → 空集合（默认= 不拦任何应用）。 */
fun decodeFanBlacklist(raw: String?): Set<String> = raw
    .orEmpty()
    .split(RemotePrefs.FAN_BLACKLIST_SEPARATOR)
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()
