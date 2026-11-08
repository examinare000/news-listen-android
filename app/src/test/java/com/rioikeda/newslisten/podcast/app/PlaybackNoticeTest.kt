package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.EpisodeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PlaybackNotice]（再生の知らせの種類と固定の文言）の検証。
 *
 * 文言の期待値は型の側とは独立にリテラルで持つ（android-dec-70）。基点で文言が変わる 4 種
 * （FetchOffline・FetchFailed・PlayerFailed・QUOTA_EXHAUSTED）は、このテストだけが固定する。
 */
class PlaybackNoticeTest {

    private fun episode(status: String, audioUrl: String = "https://example.com/e.mp3", errorMessage: String? = null): Episode =
        EpisodeDecoder.decode(
            PodcastResponse(
                id = "e1",
                type = "daily",
                articleIds = listOf("a1"),
                difficulty = "toeic_600",
                audioUrl = audioUrl,
                japaneseIntroText = "intro",
                durationSeconds = 300,
                status = status,
                errorMessage = errorMessage,
                playbackPositionSeconds = 0.0,
                createdAt = "2026-07-01T00:00:00Z",
            ),
        )

    private fun failed(identifier: String?): Episode = episode(status = "failed", errorMessage = identifier)

    @Test
    fun `android-dec-70 Offlineの文言は基点と同じ`() {
        assertEquals("オフラインのため再生できません", PlaybackNotice.Offline.message)
    }

    @Test
    fun `android-dec-70 CacheRemovedの文言は基点と同じ`() {
        assertEquals("ダウンロード済みの音声が削除されたため再生できません", PlaybackNotice.CacheRemoved.message)
    }

    @Test
    fun `android-dec-70 StillGeneratingの文言は基点と同じ`() {
        assertEquals("生成中のため再生できません", PlaybackNotice.StillGenerating.message)
    }

    @Test
    fun `android-dec-70 android-dec-06 GenerationFailedのGENERATION_FAILEDは生成に失敗しましたを持つ`() {
        assertEquals("生成に失敗しました", PlaybackNotice.GenerationFailed(GenerationFailureReason.GENERATION_FAILED).message)
    }

    @Test
    fun `android-dec-70 android-dec-06 GenerationFailedのQUOTA_EXHAUSTEDは上限に達した旨の文言を持つ`() {
        assertEquals("生成の上限に達したため、生成できませんでした", PlaybackNotice.GenerationFailed(GenerationFailureReason.QUOTA_EXHAUSTED).message)
    }

    @Test
    fun `android-dec-70 android-dec-08 FetchOfflineの文言は接続の確認を促す`() {
        assertEquals("オフラインです。接続を確認してから、もう一度お試しください", PlaybackNotice.FetchOffline.message)
    }

    @Test
    fun `android-dec-70 android-dec-06 FetchFailedの文言は取得の失敗を伝える`() {
        assertEquals("エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください", PlaybackNotice.FetchFailed.message)
    }

    @Test
    fun `android-dec-70 android-dec-69 PlayerFailedの文言は再生エンジンの失敗を伝える`() {
        assertEquals("再生できませんでした。もう一度お試しください", PlaybackNotice.PlayerFailed.message)
    }

    @Test
    fun `android-dec-70 SaveFailedの文言は基点と同じ`() {
        assertEquals("音声を保存できませんでした", PlaybackNotice.SaveFailed.message)
    }

    @Test
    fun `android-dec-70 RemoveFailedの文言は基点と同じ`() {
        assertEquals("音声を削除できませんでした", PlaybackNotice.RemoveFailed.message)
    }

    @Test
    fun `android-dec-70 VocabularyFailedの文言は基点と同じ`() {
        assertEquals("語彙の登録に失敗しました", PlaybackNotice.VocabularyFailed.message)
    }

    @Test
    fun `android-dec-70 unplayableは再生できる行ならnullを返す`() {
        assertNull(PlaybackNotice.unplayable(episode(status = "completed")))
    }

    @Test
    fun `android-dec-70 unplayableは生成中の行ならStillGeneratingを返す`() {
        assertEquals(PlaybackNotice.StillGenerating, PlaybackNotice.unplayable(episode(status = "processing")))
    }

    @Test
    fun `android-dec-06 android-dec-69 unplayableは識別子generation_failedをGENERATION_FAILEDに写す`() {
        assertEquals(
            PlaybackNotice.GenerationFailed(GenerationFailureReason.GENERATION_FAILED),
            PlaybackNotice.unplayable(failed("generation_failed")),
        )
    }

    @Test
    fun `android-dec-06 unplayableは識別子quota_exhaustedをQUOTA_EXHAUSTEDに写し他の識別子とは区別する`() {
        val quota = PlaybackNotice.unplayable(failed("quota_exhausted"))
        val generic = PlaybackNotice.unplayable(failed("generation_failed"))

        assertEquals(PlaybackNotice.GenerationFailed(GenerationFailureReason.QUOTA_EXHAUSTED), quota)
        assertEquals("生成の上限に達したため、生成できませんでした", quota?.message)
        assertNotEquals(generic, quota)
    }

    @Test
    fun `android-dec-06 unplayableは識別子がnullでも空でもGENERATION_FAILEDに倒す`() {
        val expected = PlaybackNotice.GenerationFailed(GenerationFailureReason.GENERATION_FAILED)

        assertEquals(expected, PlaybackNotice.unplayable(failed(null)))
        assertEquals(expected, PlaybackNotice.unplayable(failed("")))
    }

    @Test
    fun `android-dec-06 android-dec-70 unplayableは未知の識別子や自由文をGENERATION_FAILEDに倒し入力の文字列を知らせに残さない`() {
        for (raw in listOf("partial_failed", "TTS生成に失敗しました", "boom", "QUOTA_EXHAUSTED")) {
            val notice = PlaybackNotice.unplayable(failed(raw))

            assertEquals(PlaybackNotice.GenerationFailed(GenerationFailureReason.GENERATION_FAILED), notice)
            assertEquals("生成に失敗しました", notice?.message)
            assertFalse("入力の識別子 $raw が知らせに残っている", notice.toString().contains(raw))
        }
    }
}
