package com.repl.bubbledrawer.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音按钮「意图 vs 事实」的状态机（`MiniMuteState.kt`）。
 *
 * 两个场景是用户真的报过的：
 *
 *  - 应用**没在出声**时按静音（没有 `AudioPlaybackConfiguration` 可调）—— 按下去必须算数，
 *    等它出声再压到 0，期间图标显示"已静音"；
 *  - 静音后**用别的方式**（音量键 / 音量面板）把声音开回来 —— 图标必须自己变回"静音"，
 *    不能像旧版那样一次静音就永久卡在"已静音"。
 */
class MiniMuteStateTest {

    private companion object {
        const val KEY = "com.example.player@10234"
    }

    @Test
    fun aMutePressedWhileNothingPlaysStaysPendingAndShowsMuted() {
        val state = MiniMuteState()
        state.requestMute(KEY)

        // 应用还没出声：现场事实是"没静音"，但用户的意思要显示出来。
        assertTrue(state.observe(KEY, liveMuted = false))
        assertTrue(state.hasPendingMute(KEY))
        assertEquals(setOf(KEY), state.pendingKeys())
    }

    @Test
    fun thePendingMuteIsDroppedOnceItActuallyTookEffectAndTheSoundComesBack() {
        val state = MiniMuteState()
        state.requestMute(KEY)

        // 应用开始出声，播放回调把音量压到 0 → 事实=已静音。
        assertTrue(state.observe(KEY, liveMuted = true))
        assertFalse(state.hasPendingMute(KEY))

        // 用户用音量键把声音开回来：事实优先，图标跟着回去，而且不会在下次播放时又被静音。
        assertFalse(state.observe(KEY, liveMuted = false))
        assertTrue(state.pendingKeys().isEmpty())
        assertFalse(state.observe(KEY, liveMuted = false))
    }

    @Test
    fun aPendingMuteThatNeverTookEffectKeepsShowingMutedEvenIfTheAppIsSilentAnyway() {
        val state = MiniMuteState()
        state.requestMute(KEY)

        // 应用一直没出声（比如视频暂停着），事实一直是"没静音"：仍然是待生效状态。
        repeat(3) { assertTrue(state.observe(KEY, liveMuted = false)) }
        assertTrue(state.hasPendingMute(KEY))
    }

    @Test
    fun mutingSomethingThatIsAlreadyPlayingIsImmediatelyEffective() {
        val state = MiniMuteState()
        state.requestMute(KEY)

        assertTrue(state.observe(KEY, liveMuted = true))
        assertTrue(state.pendingKeys().isEmpty())
    }

    @Test
    fun unmutingClearsBothTheIntentAndThePendingWatch() {
        val state = MiniMuteState()
        state.requestMute(KEY)
        state.requestUnmute(KEY)

        assertFalse(state.observe(KEY, liveMuted = false))
        assertTrue(state.pendingKeys().isEmpty())
        assertFalse(state.hasPendingMute(KEY))
    }

    @Test
    fun forgettingAWindowAlsoForgetsItsMute() {
        val state = MiniMuteState()
        state.requestMute(KEY)
        state.forget(KEY)

        assertFalse(state.hasPendingMute(KEY))
        assertTrue(state.pendingKeys().isEmpty())
        assertFalse(state.observe(KEY, liveMuted = false))
    }

    @Test
    fun twoAppsKeepSeparateStates() {
        val other = "com.example.video@10321"
        val state = MiniMuteState()
        state.requestMute(KEY)
        state.requestMute(other)

        assertTrue(state.observe(KEY, liveMuted = true))
        assertEquals(setOf(other), state.pendingKeys())
        assertTrue(state.observe(other, liveMuted = false))
    }

    @Test
    fun teardownStillFindsAnAppliedMuteAfterThePendingWatchEnds() {
        val state = MiniMuteState()
        state.requestMute(KEY)
        state.observe(KEY, liveMuted = true)
        assertTrue(state.pendingKeys().isEmpty())
        assertEquals(setOf(KEY), state.requestedKeys())
        state.forget(KEY)
        assertTrue(state.requestedKeys().isEmpty())
    }
}
