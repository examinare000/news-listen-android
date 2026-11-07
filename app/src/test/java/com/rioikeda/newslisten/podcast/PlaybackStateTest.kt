package com.rioikeda.newslisten.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaybackState` の値の同一性と、`PlayerController.state` の Fake 注入の準拠テスト。
 *
 * verifies: android-contract-03
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §3.1・§4 android-contract-03。
 * エラーコードの分類のテストは adapter の層の `ExoPlayerControllerTest` にある（android-rule-playback-09）。
 */
class PlaybackStateTest {

    @Test
    fun `T2a Unknown はコードが違えば等しくない`() {
        assertNotEquals(PlaybackFailureReason.Unknown(1), PlaybackFailureReason.Unknown(2))
    }

    @Test
    fun `T2a fake は初期状態が Idle`() {
        assertEquals(PlaybackState.Idle, FakePlayerController().state.value)
    }

    @Test
    fun `T2a fake に Failed を注入すると Paused や Idle と区別できる`() {
        val fake = FakePlayerController()

        fake.setState(PlaybackState.Failed(PlaybackFailureReason.Source))

        assertEquals(PlaybackState.Failed(PlaybackFailureReason.Source), fake.state.value)
        assertTrue(fake.state.value !is PlaybackState.Paused)
        assertNotEquals(PlaybackState.Idle, fake.state.value)
    }

    @Test
    fun `T2a fake は Paused から Failed へ同じ実体の上で遷移する`() {
        val fake = FakePlayerController()
        fake.setState(PlaybackState.Paused(10.0, 600.0))
        assertEquals(PlaybackState.Paused(10.0, 600.0), fake.state.value)

        fake.setState(PlaybackState.Failed(PlaybackFailureReason.Network))

        assertEquals(PlaybackState.Failed(PlaybackFailureReason.Network), fake.state.value)
        assertNotEquals(PlaybackState.Paused(10.0, 600.0), fake.state.value)
        assertNotEquals(PlaybackState.Idle, fake.state.value)
    }
}
