package com.rioikeda.newslisten.catalog.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.podcast.app.EpisodeLookup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Podcast 一覧の取得・写し・取得の失敗の保持を持つ application（android-dec-68）。
 * Playback の状態と、取得後に同期される側の型は知らない。取得の後に必要な処理は [afterRefresh] で配線される。
 *
 * @param afterRefresh 取得に成功した一覧を受ける hook。
 * @param dispatcher 状態の書込を直列化する dispatcher。
 */
class EpisodeCatalog(
    private val podcastApi: PodcastApi,
    private val dispatcher: CoroutineDispatcher,
    private val afterRefresh: suspend (List<Episode>) -> Unit,
) : EpisodeLookup {
    // WHY 1 つの参照で丸ごと入れ替える: 別 dispatcher の find が半端な一覧を読まないため。episodes より先に入れ替える。
    private val loaded = MutableStateFlow<List<Episode>>(emptyList())

    private val _episodes = MutableStateFlow<List<EpisodeRow>>(emptyList())
    val episodes: StateFlow<List<EpisodeRow>> = _episodes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 一覧を取得して写す。失敗しても前の一覧は残り、[afterRefresh] は呼ばれない。 */
    suspend fun refresh(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val fetched = podcastApi.fetchPodcasts()
            loaded.value = fetched
            _episodes.value = fetched.map { toRow(it) }
            afterRefresh(fetched)
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isLoading.value = false
    }

    /** この Catalog 自身の失敗だけを消す。 */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun find(episodeId: String): Episode? = loaded.value.firstOrNull { it.id == episodeId }

    private fun toRow(episode: Episode): EpisodeRow {
        val status = when (episode) {
            is Episode.Playable -> EpisodeStatusKind.Playable
            is Episode.Generating -> EpisodeStatusKind.Generating
            is Episode.Failed -> EpisodeStatusKind.Failed
        }
        return EpisodeRow(
            episodeId = episode.id,
            displayTitle = episode.title.take(60),
            difficultyCode = episode.difficultyCode,
            durationSeconds = episode.durationSeconds,
            createdAt = episode.createdAt,
            status = status,
            canDownload = status == EpisodeStatusKind.Playable,
        )
    }
}
