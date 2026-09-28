package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [PodcastViewModel] のテスト専用フェイク。
 *
 * フェーズ5（Podcast 再生）で使う fetchPodcasts/fetchPodcast/updatePlaybackPosition のみ
 * 挙動を差し替え可能にする。それ以外はこのテストスイートのスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる（auth/feed の Fake と同じ方針）。
 */
class FakePodcastApiClient(
    private val onFetchPodcasts: suspend () -> PodcastListResponse =
        { error("fetchPodcasts is not stubbed") },
    private val onFetchPodcast: suspend (id: String) -> PodcastResponse =
        { error("fetchPodcast is not stubbed") },
    private val onUpdatePlaybackPosition: suspend (id: String, positionSeconds: Double) -> PodcastResponse =
        { id, _ -> error("updatePlaybackPosition is not stubbed for id=$id") },
    private val onDownloadAudio: suspend (url: String) -> ByteArray =
        { error("downloadAudio is not stubbed") },
    private val onMarkCompleted: suspend (id: String) -> Unit = {},
    private val onSubmitQuizAnswers: suspend (podcastId: String, request: com.rioikeda.newslisten.model.QuizAnswerRequest) -> com.rioikeda.newslisten.model.QuizAnswerResponse =
        { podcastId, _ -> error("submitQuizAnswers is not stubbed for podcastId=$podcastId") },
    private val onFetchVocabulary: suspend () -> VocabularyListResponse =
        { VocabularyListResponse(emptyList(), 0) },
    private val onSaveVocabulary: suspend (podcastId: String, term: String) -> VocabularyItemResponse =
        { podcastId, term -> error("saveVocabulary is not stubbed for podcastId=$podcastId term=$term") },
) : BaseFakeApiClient() {
    /** fetchPodcasts が呼ばれた回数。 */
    var fetchPodcastsCallCount = 0
        private set

    /** fetchPodcast に渡された id の呼び出し履歴。 */
    val fetchPodcastCalls: MutableList<String> = mutableListOf()

    /** updatePlaybackPosition に渡された (id, positionSeconds) の呼び出し履歴。 */
    val updatePlaybackPositionCalls: MutableList<Pair<String, Double>> = mutableListOf()

    /** downloadAudio に渡された url の呼び出し履歴。 */
    val downloadAudioCalls: MutableList<String> = mutableListOf()

    /** markCompleted に渡された完聴 ID。 */
    val markCompletedCalls: MutableList<String> = mutableListOf()
    val saveVocabularyCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun fetchPodcasts(): PodcastListResponse {
        fetchPodcastsCallCount++
        return onFetchPodcasts()
    }

    override suspend fun fetchPodcast(id: String): PodcastResponse {
        fetchPodcastCalls.add(id)
        return onFetchPodcast(id)
    }

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse {
        updatePlaybackPositionCalls.add(id to positionSeconds)
        return onUpdatePlaybackPosition(id, positionSeconds)
    }

    override suspend fun markCompleted(id: String) {
        markCompletedCalls.add(id)
        onMarkCompleted(id)
    }

    override suspend fun submitQuizAnswers(
        podcastId: String,
        request: com.rioikeda.newslisten.model.QuizAnswerRequest,
    ): com.rioikeda.newslisten.model.QuizAnswerResponse =
        onSubmitQuizAnswers(podcastId, request)

    override suspend fun fetchVocabulary(): VocabularyListResponse = onFetchVocabulary()

    override suspend fun saveVocabulary(podcastId: String, term: String): VocabularyItemResponse {
        saveVocabularyCalls += podcastId to term
        return onSaveVocabulary(podcastId, term)
    }

    override suspend fun downloadAudio(url: String): ByteArray {
        downloadAudioCalls.add(url)
        return onDownloadAudio(url)
    }
}
