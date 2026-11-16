package com.rioikeda.newslisten.podcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置の記録の値の制約と、時刻で決める規則（last write wins。android-rule-playback-12〜14）の検証。
 * 規則の正本は [PositionRules]。fake（`FakePositionStore`）は規則を呼ばず自前で比べるので、ここが規則の誤りを捕まえる。
 */
class PositionRulesTest {

    private val t1 = Instant.parse("2026-11-16T01:02:03.456Z")
    private val t2 = Instant.parse("2026-11-16T01:02:04.456Z")

    private fun device(seconds: Double, at: Instant, unsent: Boolean = true) =
        PositionRecord("u1", "p1", seconds, at, unsent)

    @Test
    fun `android-possync-22 shared-resume-08 時刻の比較の表（新旧・時刻なし・同時刻）`() {
        // 端末の記録が新しい / サーバーが新しい / サーバーの時刻なし / 同時刻 / 端末なしで時刻なし / 端末なしで 0 秒
        assertEquals(40.0, PositionRules.resumeSeconds(device(40.0, t2), ServerPosition(10.0, t1)), 0.0)
        assertEquals(90.0, PositionRules.resumeSeconds(device(40.0, t1), ServerPosition(90.0, t2)), 0.0)
        assertEquals(40.0, PositionRules.resumeSeconds(device(40.0, t1), ServerPosition(90.0, null)), 0.0)
        assertEquals(40.0, PositionRules.resumeSeconds(device(40.0, t1), ServerPosition(90.0, t1)), 0.0)
        assertEquals(90.0, PositionRules.resumeSeconds(null, ServerPosition(90.0, null)), 0.0)
        assertEquals(0.0, PositionRules.resumeSeconds(null, ServerPosition(0.0, null)), 0.0)
    }

    @Test
    fun `android-possync-22 shared-resume-08 isNewerはnullを最古とし同じ時刻は新しくない`() {
        assertFalse("candidate が null", PositionRules.isNewer(null, t1))
        assertFalse("両方 null", PositionRules.isNewer(null, null))
        assertTrue("than が null", PositionRules.isNewer(t1, null))
        assertTrue("新しい", PositionRules.isNewer(t2, t1))
        assertFalse("古い", PositionRules.isNewer(t1, t2))
        assertFalse("同じ時刻", PositionRules.isNewer(t1, t1))
    }

    @Test
    fun `android-possync-22 shared-sync-06 shouldAdoptは端末の記録が無ければ時刻が新しくても採らない`() {
        assertFalse("記録の無い episode に記録を作らない", PositionRules.shouldAdopt(null, ServerPosition(90.0, t2)))
        assertFalse("時刻なしのサーバーの値", PositionRules.shouldAdopt(device(40.0, t1), ServerPosition(90.0, null)))
        assertFalse("同じ時刻", PositionRules.shouldAdopt(device(40.0, t1), ServerPosition(90.0, t1)))
        assertFalse("古い", PositionRules.shouldAdopt(device(40.0, t2), ServerPosition(90.0, t1)))
        assertTrue("厳密に新しい（正方向の対照）", PositionRules.shouldAdopt(device(40.0, t1), ServerPosition(90.0, t2)))
    }

    @Test
    fun `android-possync-61 shared-sync-09 記録時刻はミリ秒に切り捨てた値どうしを同時刻として端末を保つ`() {
        val base = Instant.parse("2026-11-16T12:00:00.123Z")
        val withSubMilli = Instant.parse("2026-11-16T12:00:00.123400000Z")
        val truncated = withSubMilli.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

        assertEquals(base, truncated)
        assertFalse(PositionRules.isNewer(truncated, base))
        assertEquals(40.0, PositionRules.resumeSeconds(device(40.0, base), ServerPosition(90.0, truncated)), 0.0)
    }

    @Test
    fun `android-possync-41 shared-sync-10 needsResumeConfirmationの表（新旧と15秒の境界）`() {
        assertTrue("差 60・サーバーが新しい", PositionRules.needsResumeConfirmation(t1, device(90.0, t2), 30.0))
        assertTrue("差がちょうど 15 秒は確認する", PositionRules.needsResumeConfirmation(t1, device(45.0, t2), 30.0))
        assertFalse("差 14.999 秒は確認しない", PositionRules.needsResumeConfirmation(t1, device(44.999, t2), 30.0))
        assertFalse("同じ時刻は新しくない", PositionRules.needsResumeConfirmation(t1, device(90.0, t1), 30.0))
        assertFalse("サーバーが古い", PositionRules.needsResumeConfirmation(t2, device(90.0, t1), 30.0))
        assertTrue("この端末の記録が無く、サーバーの値がある", PositionRules.needsResumeConfirmation(null, device(90.0, t1), 30.0))
        assertFalse("サーバーの値が無い", PositionRules.needsResumeConfirmation(t1, null, 30.0))
        assertTrue("サーバーの位置が手前でも差が 25 秒", PositionRules.needsResumeConfirmation(t1, device(5.0, t2), 30.0))
        // 正方向の対照: 1 行目と同じ入力で、端末の記録の時刻だけをサーバーと同じにすると偽へ変わる。
        assertFalse(PositionRules.needsResumeConfirmation(t2, device(90.0, t2), 30.0))
    }

    @Test
    fun `android-possync-41 shared-sync-12 ResumeChoiceは別の端末とこの端末の2値を持つ`() {
        assertEquals(listOf(ResumeChoice.OtherDevice, ResumeChoice.ThisDevice), ResumeChoice.entries.toList())
    }

    @Test
    fun `PositionRecordは有限で0以上の位置とミリ秒に切り捨て済みの時刻だけを受ける`() {
        assertEquals(0.0, device(0.0, t1).seconds, 0.0)
        assertThrows(IllegalArgumentException::class.java) { device(-0.1, t1) }
        assertThrows(IllegalArgumentException::class.java) { device(Double.NaN, t1) }
        assertThrows(IllegalArgumentException::class.java) { device(Double.POSITIVE_INFINITY, t1) }
        assertThrows(IllegalArgumentException::class.java) {
            device(1.0, Instant.parse("2026-11-16T01:02:03.456789Z"))
        }
    }

    @Test
    fun `ServerPositionは値を保持するだけで時刻なしを許す`() {
        val server = ServerPosition(12.5, null)

        assertEquals(12.5, server.seconds, 0.0)
        assertEquals(null, server.recordedAt)
    }
}
