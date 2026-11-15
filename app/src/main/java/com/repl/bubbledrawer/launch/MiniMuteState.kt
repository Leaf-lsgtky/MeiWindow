package com.repl.bubbledrawer.launch

/**
 * 迷你小窗长按菜单「静音」按钮的状态机 —— 纯逻辑，不碰 Android API，方便单测
 * （真正的音量/AppOps 操作在 `MiniAppAudioHelper`）。
 *
 * 为什么需要这个状态机（2026-10-18，用户报「如果那个应用没播放声音，那么静音按钮按了不起作用」）：
 * 静音靠 `AudioManager.setPlayerVolume(config, 0)`，它需要一条**活跃的** `AudioPlaybackConfiguration`。
 * 应用没在出声时没有这条通道，`OP_PLAY_AUDIO` 那条兜底又不一定有权限（SystemUI 不是 Settings），
 * 于是这一次点击什么也没发生 —— 连图标都不变，看起来就是个死按钮。
 *
 * 所以把「静音」拆成两件事：
 *
 *  - **意图**（[requestMute]）：用户按了，就得算数。应用此刻没出声也要记着，等它出声再压到 0
 *    （`MiniAppAudioHelper` 的播放回调负责这件事）；图标也立刻显示"已静音"。
 *  - **事实**（[observe]）：系统里真实的状态（播放音量为 0 / AppOps 被忽略）。
 *
 * 两条规则让"别处把声音开回来"时图标能自己跟回来（用户 2026-10-18 报的第一个静音问题）：
 *
 *  1. 意图**生效过一次**之后就不再是"意图"了 —— 之后的事实说了算：应用真的在出声（事实=未静音），
 *     就把请求丢掉，图标变回"静音"。否则会退回"点一次静音就永久显示已静音"的老毛病。
 *  2. 意图**还没生效**（应用一直没出声）时，事实=未静音也不代表用户改主意了，继续显示"已静音"。
 *
 * key 用 `包名@uid`：同一个包在分身/工作资料下是两条独立的音频通道。
 */
internal class MiniMuteState {

    /** 用户按过静音、还没解除的应用。 */
    private val requested = LinkedHashSet<String>()

    /** 其中**已经真的生效过**的应用（之后事实优先）。 */
    private val applied = HashSet<String>()

    /** 用户按了静音。 */
    fun requestMute(key: String) {
        requested.add(key)
        applied.remove(key)
    }

    /** 用户取消了静音（或者窗口关了，见 `MiniAppAudioHelper.release`）。 */
    fun requestUnmute(key: String) {
        requested.remove(key)
        applied.remove(key)
    }

    /** 这个应用还欠着一次"等它出声就压到 0"。 */
    fun hasPendingMute(key: String): Boolean = key in requested && key !in applied

    /** 所有还没生效的静音请求（播放回调要按它补刀）。 */
    fun pendingKeys(): Set<String> = requested.filterNot { it in applied }.toSet()

    /** Includes applied requests so teardown can release every mute owned by this instance. */
    fun requestedKeys(): Set<String> = requested.toSet()

    /** 窗口没了：连意图一起忘掉，否则会在后台继续静音一个没有入口恢复的应用。 */
    fun forget(key: String) {
        requested.remove(key)
        applied.remove(key)
    }

    /**
     * 用系统的真实状态刷新一次，返回**界面该显示**的「已静音」。
     *
     * @param liveMuted 现场读到的事实：播放音量为 0 或 `OP_PLAY_AUDIO` 被忽略
     */
    fun observe(key: String, liveMuted: Boolean): Boolean {
        if (liveMuted) {
            if (key in requested) applied.add(key)
            return true
        }
        if (hasPendingMute(key)) {
            // 还没机会生效（应用一直没出声）—— 用户的意思仍然是静音。
            return true
        }
        // 生效过又被别处打开了：事实优先，丢掉请求。
        requested.remove(key)
        applied.remove(key)
        return false
    }
}
