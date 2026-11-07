package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.PlaybackQueue
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Podcast タブの画面の状態と中継を担う ViewModel（presentation。android-dec-09）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー。
 * [AuthViewModel]/[com.rioikeda.newslisten.feed.FeedViewModel] と同様、
 * androidx.lifecycle.ViewModel は継承しないプレーンな Kotlin クラスとして実装し、
 * Dispatcher をコンストラクタ注入する（viewModelScope 直書きだと Dispatcher をテストで
 * 差し替えられず TDD が回せないため）。
 *
 * 再生の手順は [PlaybackCoordinator] が持ち、再生の操作と読みはそこへ中継する。ここで持つのは
 * 一覧（[fetchPodcasts]）・学習の中継・ダウンロードの中継と、それらの失敗の文言だけ。
 */
class PodcastViewModel(
    private val podcastApi: PodcastApi,
    private val apiClient: ApiClient,
    private val coordinator: PlaybackCoordinator,
    private val offlineLibrary: OfflineLibrary,
    private val dispatcher: CoroutineDispatcher,
) {
    // ダウンロードの中継を、呼び手（画面の scope）の cancel と切り離して完走させるための内部スコープ。
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    /** [PlaybackCoordinator.isPlaying] の中継。Compose 側が player へ直接触れずに済むように。 */
    val isPlaying: StateFlow<Boolean> = coordinator.isPlaying

    /** [PlaybackCoordinator.positionSeconds] の中継。 */
    val positionSeconds: StateFlow<Double> = coordinator.positionSeconds

    /** [PlaybackCoordinator.durationSeconds] の中継。 */
    val durationSeconds: StateFlow<Double?> = coordinator.durationSeconds

    /** [PlaybackCoordinator.playbackSpeed] の中継。 */
    val playbackSpeed: StateFlow<Float> = coordinator.playbackSpeed

    private val _podcasts = MutableStateFlow<List<Episode>>(emptyList())
    val podcasts: StateFlow<List<Episode>> = _podcasts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 自前の失敗（一覧の取得・語彙の登録・ダウンロードの中継）。再生に由来する失敗は coordinator が持つ（android-dec-59）。
    private val _errorMessage = MutableStateFlow<String?>(null)

    /** 自前の失敗が非 null ならそれ、null なら再生の失敗。2 つの持ち主の値を保存せず、読むたびに合成する。 */
    val errorMessage: StateFlow<String?> = coordinator.errorMessageOver(_errorMessage.asStateFlow())

    /** 現在再生中のものの正本。[PlaybackCoordinator.session] の中継（テストの oracle）。 */
    val session: StateFlow<PlaybackSession> = coordinator.session

    /** 何が再生中かの読み取り用の値。NothingPlaying と Errored では null。 */
    val nowPlaying: StateFlow<NowPlaying?> = coordinator.nowPlaying

    /** ダウンロード中 Podcast ID の集合（進捗率は持たない。フェーズ8-B・ADR-027）。実体は [OfflineLibrary]。 */
    val downloadingIds: StateFlow<Set<String>> = coordinator.downloadingIds

    /** ダウンロード済み（キャッシュ済み）Podcast ID の集合。実体は [OfflineLibrary]。 */
    val downloadedIds: StateFlow<Set<String>> = coordinator.downloadedIds

    private val _registeredVocabularyKeys = MutableStateFlow<Set<String>>(emptySet())
    val registeredVocabularyKeys: StateFlow<Set<String>> = _registeredVocabularyKeys.asStateFlow()

    private val _savingVocabularyKeys = MutableStateFlow<Set<String>>(emptySet())
    val savingVocabularyKeys: StateFlow<Set<String>> = _savingVocabularyKeys.asStateFlow()

    /**
     * 再生キュー（連続再生・プレイリスト / issue #81 相当）。UI は current/upNext を購読する。[PlaybackCoordinator.queue] の中継。
     * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift:270-320。
     */
    val queue: StateFlow<PlaybackQueue<Episode>> = coordinator.queue

    /**
     * Podcast 一覧を取得して [podcasts] を更新する。失敗時は [errorMessage] に反映する。
     * 開始で消すのは自前の失敗だけで、再生の失敗は消さない（android-dec-59 (d)）。
     * 正本: PodcastViewModel.swift:99-110（loadPodcasts）。
     */
    suspend fun fetchPodcasts(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            _podcasts.value = podcastApi.fetchPodcasts()
            offlineLibrary.syncDownloadedState(_podcasts.value)
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isLoading.value = false
    }

    /** 語彙グロッサリの習得済み表示用。失敗してもPodcast本体の利用を妨げない。 */
    suspend fun loadVocabularyRegistrations(): Unit = withContext(dispatcher) {
        try {
            _registeredVocabularyKeys.value = apiClient.fetchVocabulary().vocabulary
                .mapTo(mutableSetOf()) { vocabularyKey(it.podcastId, it.term) }
        } catch (_: ApiException) {
            // best-effort: 未取得時はボタンを通常表示し、登録POSTの冪等性へ委ねる。
        }
    }

    suspend fun saveVocabulary(podcastId: String, term: String): Unit = withContext(dispatcher) {
        val key = vocabularyKey(podcastId, term)
        if (key in _registeredVocabularyKeys.value || key in _savingVocabularyKeys.value) {
            return@withContext
        }
        _savingVocabularyKeys.value += key
        try {
            val saved = apiClient.saveVocabulary(podcastId, term)
            _registeredVocabularyKeys.value += vocabularyKey(saved.podcastId, saved.term)
        } catch (_: ApiException) {
            _errorMessage.value = VOCABULARY_SAVE_ERROR_MESSAGE
        } finally {
            _savingVocabularyKeys.value -= key
        }
    }

    fun isVocabularyRegistered(podcastId: String, term: String): Boolean =
        vocabularyKey(podcastId, term) in _registeredVocabularyKeys.value

    private fun vocabularyKey(podcastId: String, term: String): String =
        "$podcastId::${term.trim().lowercase()}"

    /**
     * 指定 Podcast の音声をダウンロードする。実体は [OfflineLibrary.download] で、完了まで suspend する。
     *
     * 中継と失敗の文言の写しは [scope] の上で行う。呼び手（画面の scope）はタブ移動で cancel されるが、
     * cancel は [join] を止めるだけで、中継の戻りの後に [errorMessage] へ写す処理は完走する。
     */
    suspend fun download(podcast: Episode) {
        scope.launch {
            offlineLibrary.download(podcast)
            relayDownloadError()
        }.join()
    }

    /** キャッシュからダウンロード済み Podcast を削除する。実体は [OfflineLibrary.removeDownload]。 */
    suspend fun removeDownload(id: String) {
        scope.launch {
            offlineLibrary.removeDownload(id)
            relayDownloadError()
        }.join()
    }

    /** 中継の戻りの後に読む（常駐の collect は作らない）。成功では `null` を写さず、既存の通知を消さない。 */
    private fun relayDownloadError() {
        offlineLibrary.lastError.value?.let { _errorMessage.value = it }
    }

    /** 失敗した再生を、取得からやり直す。 */
    suspend fun retry(): Unit = coordinator.retry()

    /** このエピソードを今すぐ再生する（一覧タップの導線）。正本: PodcastViewModel.swift:283-292（playNow）。 */
    suspend fun playNow(podcast: Episode): Unit = coordinator.playNow(podcast)

    /** 現在の次に割り込む（「次に再生」）。正本: PodcastViewModel.swift:303-310（playNext）。 */
    suspend fun playNext(podcast: Episode): Unit = coordinator.playNext(podcast)

    /** キュー末尾に追加する（「キューに追加」）。正本: PodcastViewModel.swift:294-301（addToQueue）。 */
    suspend fun addToQueue(podcast: Episode): Unit = coordinator.addToQueue(podcast)

    /** キューから取り除く。正本: PodcastViewModel.swift:313-315（removeFromQueue）。 */
    suspend fun removeFromQueue(id: String): Unit = coordinator.removeFromQueue(id)

    /** 待機列（upNext）を並べ替える。正本: PodcastViewModel.swift:317-320（moveUpNext）。 */
    suspend fun moveUpNext(from: Int, toOffset: Int): Unit = coordinator.moveUpNext(from, toOffset)

    /** 再生中なら一時停止し、停止中なら再生を再開する。 */
    fun togglePlayPause() = coordinator.togglePlayPause()

    /** [PlaybackConstants.SKIP_BACKWARD_SECONDS] 秒戻る。 */
    fun skipBackward() = coordinator.skipBackward()

    /** [PlaybackConstants.SKIP_FORWARD_SECONDS] 秒進む。 */
    fun skipForward() = coordinator.skipForward()

    /** 指定位置（秒）へシークする。 */
    fun seekTo(seconds: Double) = coordinator.seekTo(seconds)

    /** 再生速度（倍率）を設定する。 */
    fun setSpeed(speed: Double) = coordinator.setSpeed(speed)

    /** 主体の離脱での停止（shared-leave-01）。 */
    suspend fun stopForSubjectLeave(): Unit = coordinator.stopForSubjectLeave()

    /** エラーメッセージをクリアする（UI の AlertDialog 閉じ時に呼ばれる）。自前の失敗と再生の失敗の両方を消す。 */
    fun clearError() {
        // 再生の失敗を先に消す: 自前を先に消すと、隠れていた再生の失敗が一瞬だけ見える。
        coordinator.clearError()
        _errorMessage.value = null
    }

    /**
     * 指定 Podcast の回答をサーバーへ送り、採点結果を返す。
     *
     * WHY podcastId を明示的に引数化: iOS PodcastViewModel.swift:411 と同形。シートが開かれた時点の
     * Podcast を保持し、その後 nowPlaying が変わってもシート内では元の podcastId で一貫性を保つ。
     * AudioPlayerSection は quizNowPlaying のスナップショット方式で実装する（要件1）。
     */
    suspend fun submitQuizAnswers(podcastId: String, answers: List<Int>): QuizAnswerResponse = withContext(dispatcher) {
        apiClient.submitQuizAnswers(podcastId, QuizAnswerRequest(answers))
    }

    private companion object {
        const val VOCABULARY_SAVE_ERROR_MESSAGE = "語彙の登録に失敗しました"
    }
}
