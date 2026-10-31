package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient
import com.rioikeda.newslisten.network.PodcastApi

/**
 * [PodcastViewModel] のテスト専用フェイク。
 *
 * 再生 5 操作は [PodcastApi]（domain の値 [Episode] を返す port）として実装する。学習の中継 3 操作
 * （クイズ・語彙）は ApiClient のままなので、内側の [learning] が持つ（VM の第 2 引数に渡す）。
 * stub していない操作が誤って呼ばれた場合は即座に失敗させる（auth/feed の Fake と同じ方針）。
 */
class FakePodcastApiClient(
    private val onFetchPodcasts: suspend () -> List<Episode> =
        { error("fetchPodcasts is not stubbed") },
    private val onFetchPodcast: suspend (id: String) -> Episode =
        { error("fetchPodcast is not stubbed") },
    private val onUpdatePlaybackPosition: suspend (id: String, positionSeconds: Double) -> Episode =
        { id, _ -> error("updatePlaybackPosition is not stubbed for id=$id") },
    private val onDownloadAudio: suspend (url: String) -> ByteArray =
        { error("downloadAudio is not stubbed") },
    private val onMarkCompleted: suspend (id: String) -> Unit = {},
    private val onSubmitQuizAnswers: suspend (podcastId: String, request: QuizAnswerRequest) -> QuizAnswerResponse =
        { podcastId, _ -> error("submitQuizAnswers is not stubbed for podcastId=$podcastId") },
    private val onFetchVocabulary: suspend () -> VocabularyListResponse =
        { VocabularyListResponse(emptyList(), 0) },
    private val onSaveVocabulary: suspend (podcastId: String, term: String) -> VocabularyItemResponse =
        { podcastId, term -> error("saveVocabulary is not stubbed for podcastId=$podcastId term=$term") },
) : PodcastApi {
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

    /** saveVocabulary に渡された (podcastId, term)。記録は [learning] の呼び出しで増える。 */
    val saveVocabularyCalls: MutableList<Pair<String, String>> = mutableListOf()

    /** 学習の中継 3 操作を受け持つ ApiClient。`PodcastViewModel` の第 2 引数に渡す。 */
    val learning: BaseFakeApiClient = object : BaseFakeApiClient() {
        override suspend fun submitQuizAnswers(
            id: String,
            request: QuizAnswerRequest,
        ): QuizAnswerResponse = onSubmitQuizAnswers(id, request)

        override suspend fun fetchVocabulary(): VocabularyListResponse = onFetchVocabulary()

        override suspend fun saveVocabulary(podcastId: String, term: String): VocabularyItemResponse {
            saveVocabularyCalls += podcastId to term
            return onSaveVocabulary(podcastId, term)
        }
    }

    override suspend fun fetchPodcasts(): List<Episode> {
        fetchPodcastsCallCount++
        return onFetchPodcasts()
    }

    override suspend fun fetchPodcast(id: String): Episode {
        fetchPodcastCalls.add(id)
        return onFetchPodcast(id)
    }

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): Episode {
        updatePlaybackPositionCalls.add(id to positionSeconds)
        return onUpdatePlaybackPosition(id, positionSeconds)
    }

    override suspend fun markCompleted(id: String) {
        markCompletedCalls.add(id)
        onMarkCompleted(id)
    }

    override suspend fun downloadAudio(url: String): ByteArray {
        downloadAudioCalls.add(url)
        return onDownloadAudio(url)
    }
}
