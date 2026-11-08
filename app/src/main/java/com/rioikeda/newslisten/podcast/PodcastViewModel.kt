package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.app.EpisodeRow
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.podcast.app.NowPlaying
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import com.rioikeda.newslisten.podcast.app.PlaybackNotice
import com.rioikeda.newslisten.podcast.app.QueueView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一覧の 1 行に「再生中か」を付けた画面の値。突き合わせは [PodcastViewModel.episodes] の 1 箇所だけで行う。 */
data class EpisodeListItem(val row: EpisodeRow, val isNowPlaying: Boolean)

/**
 * Podcast タブの画面の状態と中継を担う ViewModel（presentation。android-dec-09）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー。
 * [AuthViewModel]/[com.rioikeda.newslisten.feed.FeedViewModel] と同様、
 * androidx.lifecycle.ViewModel は継承しないプレーンな Kotlin クラスとして実装し、
 * Dispatcher をコンストラクタ注入する（viewModelScope 直書きだと Dispatcher をテストで
 * 差し替えられず TDD が回せないため）。
 *
 * 再生の手順は [PlaybackCoordinator]、一覧の取得は [EpisodeCatalog] が持ち、操作と読みはそこへ中継する。
 * ここで持つのは一覧への「再生中」の付与・学習の中継・ダウンロードの中継と、それらの失敗の文言だけ。
 */
class PodcastViewModel(
    private val apiClient: ApiClient,
    private val coordinator: PlaybackCoordinator,
    private val catalog: EpisodeCatalog,
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

    /**
     * 一覧の各行に、再生中かを付けたもの。読んだ時点の [EpisodeCatalog.episodes] と [PlaybackCoordinator.nowPlaying] を突き合わせる。
     * 別の coroutine へ写すと書込の直後の同期の読みが遅れるため、値は保存しない（android-dec-59 (e)）。
     */
    val episodes: StateFlow<List<EpisodeListItem>> =
        derived(combine(catalog.episodes, coordinator.nowPlaying) { rows, nowPlaying -> withNowPlaying(rows, nowPlaying) }) {
            withNowPlaying(catalog.episodes.value, coordinator.nowPlaying.value)
        }

    /** [EpisodeCatalog.isLoading] の中継。 */
    val isLoading: StateFlow<Boolean> = catalog.isLoading

    // 自前の失敗（語彙の登録・ダウンロードの中継）。取得の失敗は catalog、再生に由来する失敗は coordinator が持つ（android-dec-59・android-dec-63）。
    private val _notice = MutableStateFlow<PlaybackNotice?>(null)

    /** 優先は自前 > 取得 > 再生。3 つの持ち主の値を保存せず、読むたびに合成する。 */
    val notice: StateFlow<PlaybackNotice?> = coordinator.noticeOver(_notice.asStateFlow(), catalog.notice)

    /** [PlaybackCoordinator.queueView] の中継。 */
    val queueView: StateFlow<QueueView> = coordinator.queueView

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

    /** 読んだ時点で [read] を計算する [StateFlow]。[collect] は最初の値と、変わった値を出す。メンバー関数にして台帳の列挙に出さない。 */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private fun <T> derived(flow: Flow<T>, read: () -> T): StateFlow<T> = object : StateFlow<T> {
        override val value: T get() = read()
        override val replayCache: List<T> get() = listOf(read())
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            flow.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    private fun withNowPlaying(rows: List<EpisodeRow>, nowPlaying: NowPlaying?): List<EpisodeListItem> =
        rows.map { EpisodeListItem(it, it.episodeId == nowPlaying?.episodeId) }

    /** Podcast 一覧の取得を [EpisodeCatalog] へ中継する。失敗は [notice] に出る。 */
    suspend fun fetchPodcasts(): Unit = catalog.refresh()

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
            _notice.value = PlaybackNotice.VocabularyFailed
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
     * 中継と失敗の知らせの写しは [scope] の上で行う。呼び手（画面の scope）はタブ移動で cancel されるが、
     * cancel は [join] を止めるだけで、中継の戻りの後に [notice] へ写す処理は完走する。
     */
    suspend fun download(episodeId: String) {
        scope.launch {
            offlineLibrary.download(episodeId)
            relayDownloadNotice()
        }.join()
    }

    /** キャッシュからダウンロード済み Podcast を削除する。実体は [OfflineLibrary.removeDownload]。 */
    suspend fun removeDownload(id: String) {
        scope.launch {
            offlineLibrary.removeDownload(id)
            relayDownloadNotice()
        }.join()
    }

    /** 中継の戻りの後に読む（常駐の collect は作らない）。成功では `null` を写さず、既存の通知を消さない。 */
    private fun relayDownloadNotice() {
        offlineLibrary.notice.value?.let { _notice.value = it }
    }

    /** 失敗した再生を、取得からやり直す。 */
    suspend fun retry(): Unit = coordinator.retry()

    /** このエピソードを今すぐ再生する（一覧タップの導線）。正本: PodcastViewModel.swift:283-292（playNow）。 */
    suspend fun playNow(episodeId: String): Unit = coordinator.playNow(episodeId)

    /** 現在の次に割り込む（「次に再生」）。正本: PodcastViewModel.swift:303-310（playNext）。 */
    suspend fun playNext(episodeId: String): Unit = coordinator.playNext(episodeId)

    /** キュー末尾に追加する（「キューに追加」）。正本: PodcastViewModel.swift:294-301（addToQueue）。 */
    suspend fun addToQueue(episodeId: String): Unit = coordinator.addToQueue(episodeId)

    /** キューから取り除く。正本: PodcastViewModel.swift:313-315（removeFromQueue）。 */
    suspend fun removeFromQueue(episodeId: String): Unit = coordinator.removeFromQueue(episodeId)

    /** 待機列の中で 1 つ前へ動かす。 */
    suspend fun moveUp(episodeId: String): Unit = coordinator.moveUp(episodeId)

    /** 待機列の中で 1 つ後ろへ動かす。 */
    suspend fun moveDown(episodeId: String): Unit = coordinator.moveDown(episodeId)

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

    /** 知らせをクリアする（UI の AlertDialog 閉じ時に呼ばれる）。3 つの持ち主の失敗をすべて消す。 */
    fun dismissNotice() {
        // 優先の低い順に消す: 優先の高い自前を先に消すと、隠れていた失敗が一瞬だけ見える。
        coordinator.dismissNotice()
        catalog.dismissNotice()
        _notice.value = null
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
}
