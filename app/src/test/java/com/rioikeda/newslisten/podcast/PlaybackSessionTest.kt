package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.podcast.PlaybackSession.Active
import com.rioikeda.newslisten.podcast.PlaybackSession.Completed
import com.rioikeda.newslisten.podcast.PlaybackSession.Errored
import com.rioikeda.newslisten.podcast.PlaybackSession.NothingPlaying
import com.rioikeda.newslisten.podcast.PlaybackSession.Starting
import com.rioikeda.newslisten.podcast.PlaybackSession.Stopped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再生セッション状態機械の遷移表と INV-P1 述語の検証。
 *
 * verifies: CI-T1
 * 正本: android/docs/design/2026-09-16-implementation-spec-playback-auth.md §3.1・§4 CI-T1。
 * 状態型は PlaybackSession の入れ子（PlaybackState と同じ形）。11 遷移だけが起き、表外は IllegalStateException。停止操作は持たない（停止は呼び出し側が NothingPlaying を代入する）。
 */
class PlaybackSessionTest {

    private fun podcast(id: String): PodcastResponse = PodcastResponse(
        id = id,
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/$id.mp3",
        japaneseIntroText = "intro",
        durationSeconds = 300,
        status = "completed",
        createdAt = "2026-07-01T00:00:00Z",
    )

    private val a = podcast("a")
    private val b = podcast("b")
    private val c = podcast("c")

    @Test
    fun `T-T1 01 NothingPlaying から start で Starting`() {
        assertEquals(Starting(a, 120.0, 1.5), NothingPlaying.start(a, 120.0, 1.5))
    }

    @Test
    fun `T-T1 02 Starting から playerStarted で Active に episode と speed を引き継ぐ`() {
        assertEquals(Active(a, 1.25), Starting(a, 0.0, 1.25).playerStarted())
    }

    @Test
    fun `T-T1 03 Starting から fail で Errored`() {
        assertEquals(
            Errored(SessionEpisodeRef.Loaded(a), SessionErrorReason.Player(PlaybackFailureReason.Network)),
            Starting(a, 0.0, 1.0).fail(
                SessionEpisodeRef.Loaded(a),
                SessionErrorReason.Player(PlaybackFailureReason.Network),
            ),
        )
    }

    @Test
    fun `T-T1 04 Active から playerEnded で Completed`() {
        assertEquals(Completed(a), Active(a, 1.0).playerEnded())
    }

    @Test
    fun `T-T1 05 Active から fail で Errored`() {
        assertEquals(
            Errored(SessionEpisodeRef.Loaded(a), SessionErrorReason.Player(PlaybackFailureReason.Decode)),
            Active(a, 1.0).fail(
                SessionEpisodeRef.Loaded(a),
                SessionErrorReason.Player(PlaybackFailureReason.Decode),
            ),
        )
    }

    @Test
    fun `T-T1 06 Active から playNow で Starting`() {
        assertEquals(Starting(b, 0.0, 1.0), Active(a, 1.0).playNow(b, 0.0, 1.0))
    }

    @Test
    fun `T-T1 07 Completed から advance で次のエピソードの Starting`() {
        assertEquals(Starting(b, 30.0, 1.0), Completed(a).advance(b, 30.0, 1.0))
    }

    @Test
    fun `T-T1 08 Completed から queueExhausted で Stopped が Completed の episode を持つ`() {
        assertEquals(Stopped(a), Completed(a).queueExhausted())
    }

    @Test
    fun `T-T1 09 Stopped から start で Starting`() {
        assertEquals(Starting(b, 0.0, 1.0), Stopped(a).start(b, 0.0, 1.0))
    }

    @Test
    fun `T-T1 10 Errored から retry で Starting`() {
        val errored = Errored(SessionEpisodeRef.IdOnly("b"), SessionErrorReason.FetchFailed)
        assertEquals(Starting(b, 0.0, 1.0), errored.retry(b, 0.0, 1.0))
    }

    @Test
    fun `T-T1 11 Errored から start で Starting`() {
        val errored = Errored(SessionEpisodeRef.IdOnly("b"), SessionErrorReason.SourceUnavailable)
        assertEquals(Starting(c, 0.0, 1.0), errored.start(c, 0.0, 1.0))
    }

    @Test
    fun `T-T1 X1 Errored から Active への遷移は IllegalStateException`() {
        val errored = Errored(SessionEpisodeRef.Loaded(a), SessionErrorReason.NotPlayable)
        assertThrows(IllegalStateException::class.java) { errored.playerStarted() }
    }

    @Test
    fun `T-T1 X2 Stopped から Completed への遷移は IllegalStateException`() {
        assertThrows(IllegalStateException::class.java) { Stopped(a).playerEnded() }
    }

    @Test
    fun `T-T1 D Errored は Stopped と別の状態`() {
        val errored: PlaybackSession = Errored(SessionEpisodeRef.Loaded(a), SessionErrorReason.NotPlayable)
        val stopped: PlaybackSession = Stopped(a)

        assertTrue(errored !is Stopped)
        assertTrue(stopped !is Errored)
        assertNotEquals(stopped, errored)
    }

    @Test
    fun `T-T1 遷移は受信側インスタンスを変えない`() {
        val starting = Starting(a, 10.0, 1.0)

        starting.playerStarted()

        assertEquals(Starting(a, 10.0, 1.0), starting)
    }

    // ---- INV-P1 述語（PS-04 の session 側。共有仕様 PS-04 の保留解除は A-S2b2） ----

    @Test
    fun `PS-04 NothingPlaying は queueCurrentId によらず true`() {
        assertTrue(NothingPlaying.satisfiesInvariantP1(null))
        assertTrue(NothingPlaying.satisfiesInvariantP1("a"))
    }

    @Test
    fun `PS-04 Starting Active Completed Stopped は episode の id が一致するときだけ true`() {
        val sessions: List<PlaybackSession> = listOf(
            Starting(a, 0.0, 1.0),
            Active(a, 1.0),
            Completed(a),
            Stopped(a),
        )
        sessions.forEach { session ->
            assertTrue("$session / a", session.satisfiesInvariantP1("a"))
            assertFalse("$session / b", session.satisfiesInvariantP1("b"))
        }
    }

    @Test
    fun `PS-04 Errored の Loaded は episode の id で判定する`() {
        val errored = Errored(SessionEpisodeRef.Loaded(a), SessionErrorReason.NotPlayable)

        assertTrue(errored.satisfiesInvariantP1("a"))
        assertFalse(errored.satisfiesInvariantP1("b"))
    }

    @Test
    fun `PS-04 Errored の IdOnly は id で判定し null は不一致`() {
        val errored = Errored(SessionEpisodeRef.IdOnly("a"), SessionErrorReason.FetchFailed)

        assertTrue(errored.satisfiesInvariantP1("a"))
        assertFalse(errored.satisfiesInvariantP1(null))
    }
}
