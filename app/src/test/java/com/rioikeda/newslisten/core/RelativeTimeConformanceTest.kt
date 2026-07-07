package com.rioikeda.newslisten.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 相対時刻フォーマッタの準拠テスト。
 *
 * 正本仕様: docs/design/shared-playback-spec.md §3（相対時刻フォーマッタ仕様）・§4.2（RT表）。
 * テストメソッド名は行 ID（RT-01 等）を先頭に含め、正本表の行との対応を目視・機械双方で照合可能にする。
 */
class RelativeTimeConformanceTest {

    // 基準時刻を固定することで、システム時計に依存しない再現可能なテストにする（spec §3.1）。
    private val now: Instant = Instant.parse("2026-07-07T00:00:00Z")

    @Test
    fun `RT-01 未来はもうすぐ`() {
        val target = now.plusSeconds(60)
        assertEquals("もうすぐ", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-02 3秒はたった今`() {
        val target = now.minusSeconds(3)
        assertEquals("たった今", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-03 30秒はたった今`() {
        val target = now.minusSeconds(30)
        assertEquals("たった今", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-04 59秒はたった今`() {
        val target = now.minusSeconds(59)
        assertEquals("たった今", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-05 60秒は1分前`() {
        val target = now.minusSeconds(60)
        assertEquals("1分前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-06 59分は59分前`() {
        val target = now.minusSeconds(59 * 60L)
        assertEquals("59分前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-07 60分は1時間前`() {
        val target = now.minusSeconds(60 * 60L)
        assertEquals("1時間前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-08 23時間は23時間前`() {
        val target = now.minusSeconds(23 * 3600L)
        assertEquals("23時間前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-09 24時間は1日前`() {
        val target = now.minusSeconds(24 * 3600L)
        assertEquals("1日前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-10 29日は29日前`() {
        val target = now.minusSeconds(29 * 86400L)
        assertEquals("29日前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-11 30日は1か月前`() {
        val target = now.minusSeconds(30 * 86400L)
        assertEquals("1か月前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-12 359日は11か月前`() {
        val target = now.minusSeconds(359 * 86400L)
        assertEquals("11か月前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-13 360日は12か月前`() {
        val target = now.minusSeconds(360 * 86400L)
        assertEquals("12か月前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-14 364日は12か月前`() {
        val target = now.minusSeconds(364 * 86400L)
        assertEquals("12か月前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-15 365日は1年前`() {
        val target = now.minusSeconds(365 * 86400L)
        assertEquals("1年前", RelativeTimeFormatter.format(target, now))
    }

    @Test
    fun `RT-A01 空文字列は空文字を返す`() {
        assertEquals("", RelativeTimeFormatter.formatIso("", now))
    }

    @Test
    fun `RT-A02 パース不能文字列は空文字を返す`() {
        assertEquals("", RelativeTimeFormatter.formatIso("not-a-date", now))
    }
}
