package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/design/shared-playback-spec.md §6.1 で定義された再生元決定ロジックの検証。
 * Web版 resolvePlaybackSource（web/lib/resolvePlayback.ts）と挙動を一致させる。
 */
class PlaybackSourceResolverTest {

    @Test
    fun キャッシュ有りオンラインならcachedを返す() {
        val result = resolvePlaybackSource(hasCached = true, isOnline = true)

        assertEquals(PlaybackSource.CACHED, result)
    }

    @Test
    fun キャッシュ有りオフラインでもcachedを返す() {
        val result = resolvePlaybackSource(hasCached = true, isOnline = false)

        assertEquals(PlaybackSource.CACHED, result)
    }

    @Test
    fun キャッシュ無しオンラインならnetworkを返す() {
        val result = resolvePlaybackSource(hasCached = false, isOnline = true)

        assertEquals(PlaybackSource.NETWORK, result)
    }

    @Test
    fun キャッシュ無しオフラインならunavailableを返す() {
        val result = resolvePlaybackSource(hasCached = false, isOnline = false)

        assertEquals(PlaybackSource.UNAVAILABLE, result)
    }
}
