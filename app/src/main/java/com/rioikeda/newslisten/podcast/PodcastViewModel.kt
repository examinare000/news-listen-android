package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.PlaybackQueue
import com.rioikeda.newslisten.core.PlaybackSource
import com.rioikeda.newslisten.core.resolvePlaybackSource
import com.rioikeda.newslisten.core.resolveResumePosition
import com.rioikeda.newslisten.engagement.ListeningStreakStore
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.AudioCacheException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.NetworkMonitoring
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Podcast タブの状態とロジックを担う ViewModel（フェーズ5 前半: データ層。Media3 実装はフェーズ7）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー。
 * [AuthViewModel]/[com.rioikeda.newslisten.feed.FeedViewModel] と同様、
 * androidx.lifecycle.ViewModel は継承しないプレーンな Kotlin クラスとして実装し、
 * Dispatcher をコンストラクタ注入する（viewModelScope 直書きだと Dispatcher をテストで
 * 差し替えられず TDD が回せないため）。
 *
 * 実際の音声再生は [PlayerController] に委譲する（フェーズ5 は Fake、フェーズ7 で Media3 実装に差し替え）。
 */
class PodcastViewModel(
    private val podcastApi: PodcastApi,
    private val apiClient: ApiClient,
    private val playerController: PlayerController,
    private val cacheManager: AudioCacheManager,
    private val networkMonitor: NetworkMonitoring,
    private val dispatcher: CoroutineDispatcher,
    private val preferencesStore: PreferencesStore,
    private val listeningStreakStore: ListeningStreakStore? = null,
    private val currentSubject: () -> String? = { null },
) {
    // 位置同期タイマー（15秒毎）を動かすための内部スコープ。play()/suspend 関数の呼び出しを跨いで
    // 生存する必要があるため、ViewModel 自身が Dispatcher から生成して保持する
    // （呼び出し元の suspend コンテキストに寿命を委ねると play() が返った時点でキャンセルされてしまう）。
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var syncJob: Job? = null

    // 開始を確定した時点の再生元と主体。NothingPlaying へ戻るときに null にする。
    // sessionSubject は保持だけで、invalidate には渡さない（invalidate は id 1 引数のまま。主体別の扱いは android-slice-playback-04 以降）。
    private var sessionSource: PlaybackSource? = null
    private var sessionSubject: String? = null

    // 開始（startEpisode）全体を直列化する Mutex。WHY: 開始は fetchPodcast という suspend 境界を挟むため、
    // Mutex なしで2回連続呼び出すと両方が settlePlayback() の「前の再生なし」判定をすり抜けて
    // 並行に進行し、startPositionSync() が同期タイマーの参照を奪い合って片方が孤児化する
    // （キャンセルされずに動き続け、切り替え後も古い Podcast への PATCH を送り続ける）。
    private val playMutex = Mutex()

    /** [PlayerController.isPlaying] の単純委譲公開。Compose 側が [playerController] へ直接触れずに済むように。 */
    val isPlaying: StateFlow<Boolean> = playerController.isPlaying

    /** [PlayerController.positionSeconds] の単純委譲公開。 */
    val positionSeconds: StateFlow<Double> = playerController.positionSeconds

    /** [PlayerController.durationSeconds] の単純委譲公開。 */
    val durationSeconds: StateFlow<Double?> = playerController.durationSeconds

    /** [PlayerController.playbackSpeed] の単純委譲公開。 */
    val playbackSpeed: StateFlow<Float> = playerController.playbackSpeed

    private val _podcasts = MutableStateFlow<List<Episode>>(emptyList())
    val podcasts: StateFlow<List<Episode>> = _podcasts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _session = MutableStateFlow<PlaybackSession>(PlaybackSession.NothingPlaying)

    /** 現在再生中のものの正本。[nowPlaying]・[currentPodcast] はここからの派生で、[applySession] だけが書く。 */
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** 何が再生中かの読み取り用の値。NothingPlaying と Errored では null。 */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _currentPodcast = MutableStateFlow<Episode.Playable?>(null)

    /** 現在再生対象の Podcast（[nowPlaying] と同じ非 null 条件）。署名付き URL 再取得後の最新値。 */
    val currentPodcast: StateFlow<Episode.Playable?> = _currentPodcast.asStateFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())

    /** ダウンロード中 Podcast ID の集合（進捗率は持たない。フェーズ8-B・ADR-027）。 */
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())

    /** ダウンロード済み（キャッシュ済み）Podcast ID の集合。 */
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _registeredVocabularyKeys = MutableStateFlow<Set<String>>(emptySet())
    val registeredVocabularyKeys: StateFlow<Set<String>> = _registeredVocabularyKeys.asStateFlow()

    private val _savingVocabularyKeys = MutableStateFlow<Set<String>>(emptySet())
    val savingVocabularyKeys: StateFlow<Set<String>> = _savingVocabularyKeys.asStateFlow()

    /**
     * 進行中ダウンロードの Job 追跡（[cancelDownloadsAndClearCache] からの明示的キャンセル用）。
     *
     * WHY: [download] は fetchPodcast という suspend 境界を挟むため、logout 時の
     * cancelDownloadsAndClearCache（別コルーチンから呼ばれる）と無同期だと、
     * 削除後にファイル書き込みが発生し downloadedIds が残留し得る（spec §6.3 違反）。
     * 読み書きは常に [dispatcher]（limitedParallelism(1)）上でのみ行われるため、
     * 追加の同期プリミティブなしで安全に共有できる（[_downloadingIds] 等の StateFlow と同じ規律）。
     */
    private val downloadJobs: MutableMap<String, Job> = mutableMapOf()

    private val _queue = MutableStateFlow(PlaybackQueue<Episode>())

    /**
     * 再生キュー（連続再生・プレイリスト / issue #81 相当）。UI は current/upNext を購読する。
     * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift:270-320。
     */
    val queue: StateFlow<PlaybackQueue<Episode>> = _queue.asStateFlow()

    // WHY 全プロパティの宣言の後: 購読は dispatcher 上で非同期に動き始めるため、構築の途中で未初期化の値を読ませない。
    init {
        scope.launch { playerController.state.collect { handlePlayerState(it) } }
    }

    /**
     * Podcast 一覧を取得して [podcasts] を更新する。失敗時は [errorMessage] に反映する。
     * 正本: PodcastViewModel.swift:99-110（loadPodcasts）。
     */
    suspend fun fetchPodcasts(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            _podcasts.value = podcastApi.fetchPodcasts()
            syncDownloadedState()
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
     * ローカルキャッシュから、ダウンロード済み ID を同期する。
     * 正本: PodcastViewModel.swift:112-115（syncDownloadedState）。
     */
    private fun syncDownloadedState() {
        _downloadedIds.value = _podcasts.value.filter { cacheManager.isCached(it.id) }.map { it.id }.toSet()
    }

    /**
     * 指定 Podcast の音声をダウンロード・キャッシュし、[downloadedIds] に追加する。
     * ダウンロード中の重複を防ぐため、既に downloading/downloaded 中なら何もしない
     * （正本: PodcastViewModel.swift:141-163 の二重起動防止と同じガード）。
     *
     * 実処理は [scope]（[dispatcher] 上）で起動した Job として [downloadJobs] に登録し、
     * 呼び出し元には従来どおり完了まで suspend する契約を維持するため join する。
     * こうして [cancelDownloadsAndClearCache] が外部（logout 経路）から個々のダウンロードを
     * 明示的にキャンセルできるようにする（Job 参照を保持しない限りキャンセル不能なため）。
     *
     * @param podcast ダウンロード対象の Podcast。
     */
    suspend fun download(podcast: Episode) {
        val job = withContext(dispatcher) {
            if (_downloadingIds.value.contains(podcast.id) || _downloadedIds.value.contains(podcast.id)) {
                return@withContext null
            }
            _downloadingIds.value = _downloadingIds.value + podcast.id
            scope.launch { performDownload(podcast) }.also { downloadJobs[podcast.id] = it }
        }
        job?.join()
    }

    /** [download] の実処理本体。[scope] 上の Job として起動され、[dispatcher] に確定して実行される。 */
    private suspend fun performDownload(podcast: Episode) {
        try {
            // 署名付き URL を新たに取得（ダウンロード時点での最新 URL を確保）。
            val fresh = podcastApi.fetchPodcast(podcast.id)
            if (fresh !is Episode.Playable) {
                _errorMessage.value = playabilityError(fresh)
                return
            }
            val audioData = podcastApi.downloadAudio(fresh.audioUrl)
            cacheManager.cache(podcast.id, audioData)
            _downloadedIds.value = _downloadedIds.value + podcast.id
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        } catch (e: AudioCacheException) {
            _errorMessage.value = e.message
        } finally {
            _downloadingIds.value = _downloadingIds.value - podcast.id
            downloadJobs.remove(podcast.id)
        }
    }

    /**
     * logout 時のクリーンアップ本体（spec §6.3・共有端末対応）。
     *
     * 進行中のダウンロードをすべて [Job.cancelAndJoin] で確実に中断・完了待機してから
     * キャッシュを全削除する。こうすることで、fetchPodcast の suspend 境界中に logout が
     * 割り込んでも、中断済みの download が後から cache() を呼んでファイルを復活させる
     * ことがない（[downloadJobs] の doc 参照）。
     *
     * ダウンロード中でなく既にキャッシュ済みの分も含め、[downloadedIds]/[downloadingIds] を
     * 空にする（logout 後に前ユーザーのダウンロード状態が UI に残らないようにする副次的な修正）。
     */
    suspend fun cancelDownloadsAndClearCache(): Unit = withContext(dispatcher) {
        val inFlightJobs = downloadJobs.values.toList()
        inFlightJobs.forEach { it.cancelAndJoin() }
        cacheManager.removeAll()
        _downloadedIds.value = emptySet()
        _downloadingIds.value = emptySet()
    }

    /**
     * キャッシュからダウンロード済み Podcast を削除する。
     * 正本: PodcastViewModel.swift:165-174（removeDownload）。
     *
     * @param id 削除対象の Podcast ID。
     */
    suspend fun removeDownload(id: String): Unit = withContext(dispatcher) {
        try {
            cacheManager.remove(id)
            _downloadedIds.value = _downloadedIds.value - id
        } catch (e: AudioCacheException) {
            _errorMessage.value = e.message
        }
    }

    /**
     * 指定 Podcast の再生を手動で開始する（[playNow] と同じ）。
     *
     * 判定 → 再生元の決定 → （NETWORK なら）取得 → 取り直した値の判定を先に行い、通ったときだけキューと [session] を変える。
     * 再生できないと分かったときは、再生中のものを止めずキューも [session] も変えず、[errorMessage] だけを出す。
     * 再生元の決定は [resolvePlaybackSource]（正本: docs/design/shared-playback-spec.md §6.1）:
     * CACHED は一覧の値で始め（オフラインでも可）、NETWORK は署名付き URL を再取得してから始める（ADR-009）。
     *
     * @param podcast 再生対象（一覧の要素）。
     */
    suspend fun play(podcast: Episode): Unit = withContext(dispatcher) {
        startEpisode(podcast, StartTrigger.MANUAL)
    }

    /** 開始の要求の出どころ。出どころで、開始前の失敗の扱いと確定時に使う辺が変わる。 */
    private enum class StartTrigger { MANUAL, ADVANCE, RETRY }

    /**
     * 開始の本体。[playMutex] で全体を直列化する（fetchPodcast の suspend 境界を挟んで 2 つが並行に進むと、
     * 同期タイマーが孤児になる）。主体は要求の冒頭で 1 回だけ捕捉する。
     */
    private suspend fun startEpisode(episode: Episode, trigger: StartTrigger) {
        val subject = currentSubject()
        playMutex.withLock {
            // 並んで待っていた 2 回目の retry は、1 回目の確定後の session では対象外。
            if (trigger == StartTrigger.RETRY && _session.value !is PlaybackSession.Errored) return@withLock
            if (episode !is Episode.Playable) {
                rejectStart(episode.id, trigger, SessionErrorReason.NotPlayable, playabilityError(episode))
                return@withLock
            }
            val source = resolvePlaybackSource(hasCached = cacheManager.isCached(episode.id), isOnline = networkMonitor.isOnline.value)
            val playable: Episode.Playable
            val url: String
            when (source) {
                PlaybackSource.CACHED -> {
                    // fetchPodcast を経由しないため、一覧の値をそのまま採用する（transcript を含む表示用情報を持つ）。
                    // requireNotNull: CACHED と判定された直後なので cachedFileUri は必ず非 null。
                    playable = episode
                    url = requireNotNull(cacheManager.cachedFileUri(episode.id)) {
                        "CACHED と判定されたのに cachedFileUri が null: id=${episode.id}"
                    }
                }
                PlaybackSource.NETWORK -> {
                    val fresh = try {
                        podcastApi.fetchPodcast(episode.id)
                    } catch (e: ApiException) {
                        rejectStart(episode.id, trigger, SessionErrorReason.FetchFailed, e.message)
                        return@withLock
                    }
                    if (fresh !is Episode.Playable) {
                        rejectStart(episode.id, trigger, SessionErrorReason.NotPlayable, playabilityError(fresh))
                        return@withLock
                    }
                    playable = fresh
                    url = fresh.audioUrl
                }
                PlaybackSource.UNAVAILABLE -> {
                    rejectStart(episode.id, trigger, SessionErrorReason.SourceUnavailable, "オフラインのため再生できません")
                    return@withLock
                }
            }

            if (trigger == StartTrigger.MANUAL) {
                val (jumped, found) = _queue.value.jump(episode.id)
                _queue.value = if (found) jumped else _queue.value.playNext(episode).jump(episode.id).first
            }
            // 前の再生の最終同期・タイマー停止・stop は、切り替えが確定した後で行う（NETWORK は取得の間、前の音声を鳴らし続ける）。
            settlePlayback()
            val resume = resolveResumePosition(playable.resumeHintSeconds, playable.durationSeconds)
            val speed = preferencesStore.defaultPlaybackSpeed.value
            applySession(beginSession(playable, resume, speed, trigger))
            sessionSource = source
            sessionSubject = subject
            _errorMessage.value = null
            playerController.onPlaybackCompleted = { scope.launch { handlePlaybackEnded() } }
            playerController.prepare(url, playable.toPlaybackMetadata())
            playerController.setSpeed(speed.toFloat())
            if (resume > 0) playerController.seekTo(resume)
            playerController.play()
            startPositionSync(playable.id)
        }
    }

    /**
     * 開始前の失敗。手動の開始は何も変えず [errorMessage] だけを出す（前の再生は続く）。
     * キューが既にそのエピソードを現在にしている経路（自動前進・retry）は、domain の辺 failBeforeStart の戻り値を置く。
     */
    private suspend fun rejectStart(id: String, trigger: StartTrigger, reason: SessionErrorReason, message: String?) {
        if (trigger != StartTrigger.MANUAL) {
            settlePlayback()
            // 手動の開始が先に確定していた競合では、失敗の辺は再生の開始後から呼べないので先に停止する。
            if (_session.value is PlaybackSession.Starting || _session.value is PlaybackSession.Active) {
                applySession(_session.value.stop())
            }
            applySession(_session.value.failBeforeStart(SessionEpisodeRef.IdOnly(id), reason))
        }
        _errorMessage.value = message
    }

    /** 開始を確定するときの辺の選び方。呼ぶ前に [settlePlayback] 済みで、確定時点の [session] で選ぶ。 */
    private fun beginSession(playable: Episode.Playable, resume: Double, speed: Double, trigger: StartTrigger): PlaybackSession.Starting =
        when {
            trigger == StartTrigger.ADVANCE && _session.value is PlaybackSession.Completed -> _session.value.advance(playable, resume, speed)
            trigger == StartTrigger.RETRY && _session.value is PlaybackSession.Errored -> _session.value.retry(playable, resume, speed)
            _session.value is PlaybackSession.Active -> _session.value.playNow(playable, resume, speed)
            _session.value is PlaybackSession.Starting || _session.value is PlaybackSession.Completed -> {
                applySession(_session.value.stop())
                _session.value.start(playable, resume, speed)
            }
            else -> _session.value.start(playable, resume, speed)
        }

    /** [session] を置き、[nowPlaying]・[currentPodcast] を同期して派生させる（これらを書く唯一の場所）。 */
    private fun applySession(next: PlaybackSession) {
        _session.value = next
        val shown = when (next) {
            is PlaybackSession.Starting -> next.episode
            is PlaybackSession.Active -> next.episode
            is PlaybackSession.Completed -> next.episode
            is PlaybackSession.Stopped -> next.episode
            is PlaybackSession.NothingPlaying, is PlaybackSession.Errored -> null
        }
        if (next is PlaybackSession.NothingPlaying) {
            sessionSource = null
            sessionSubject = null
        }
        _currentPodcast.value = shown
        _nowPlaying.value = shown?.toNowPlaying()
    }

    private fun Episode.Playable.toNowPlaying() = NowPlaying(
        episodeId = id,
        displayTitle = displayTitle,
        japaneseIntroText = japaneseIntroText,
        segments = transcript,
        vocabulary = glossary,
        quiz = quiz,
        difficulty = difficultyCode,
    )

    /** player の状態を [session] へ写す（android-deriv-15 のうち Starting の Playing/Paused と Failed）。表に無い組合せは無視する。 */
    private fun handlePlayerState(state: PlaybackState) {
        when (state) {
            is PlaybackState.Playing, is PlaybackState.Paused -> {
                if (_session.value is PlaybackSession.Starting) applySession(_session.value.playerStarted())
            }
            is PlaybackState.Failed -> handlePlayerFailure(state.reason)
            else -> Unit
        }
    }

    private fun handlePlayerFailure(reason: PlaybackFailureReason) {
        val episode = when (val current = _session.value) {
            is PlaybackSession.Starting -> current.episode
            is PlaybackSession.Active -> current.episode
            else -> return
        }
        val unplayableCache = reason is PlaybackFailureReason.Source || reason is PlaybackFailureReason.Decode
        if (sessionSource == PlaybackSource.CACHED && unplayableCache) {
            try {
                cacheManager.invalidate(episode.id)
            } catch (_: AudioCacheException) {
                // WHY 握る: 不正な id は消せないが、失敗した再生の状態遷移と以後の購読を止めない。
            }
        }
        applySession(_session.value.fail(SessionEpisodeRef.Loaded(episode), SessionErrorReason.Player(reason)))
    }

    /**
     * 失敗した再生を、取得からやり直す。[session] が Errored のときだけ働き、それ以外は何もしない。
     * 再試行は再生元の解決からやり直す（共有仕様 §2.11）。
     */
    suspend fun retry(): Unit = withContext(dispatcher) {
        if (_session.value !is PlaybackSession.Errored) return@withContext
        val current = _queue.value.current ?: return@withContext
        startEpisode(current, StartTrigger.RETRY)
    }

    // --- 再生キュー（issue #81 相当。フェーズ6 T1） ---

    /**
     * 再生終了時の自動次再生（完聴コールバックの旧経路。android-temp-02）。
     * キューに次があれば再生し、無ければ Stopped にする。正本: PodcastViewModel.swift:272-281（handlePlaybackEnded）。
     * 開始は [startEpisode] の判定・取得・Mutex・同期タイマーの規律をすべて経由する。
     *
     * Starting のまま届いても（Fake は state を動かさない）playerStarted を経由して完聴にする。
     * 再生中でなければ何もしない。
     */
    private suspend fun handlePlaybackEnded() {
        // WHY 冒頭で値に取る: 記録の通信を待つ間にロックが外れることがあり、待ちの後で読むと
        // 別の episode の取得〜開始の途中に届いた通知を見分けられない。
        val playInProgress = playMutex.isLocked

        // キューを書き換える前に完聴対象を確定する。advance 後の session は次のエピソードを指す。
        val completedPodcastId = when (val current = _session.value) {
            is PlaybackSession.Starting -> current.episode.id
            is PlaybackSession.Active -> current.episode.id
            else -> return
        }
        // 別の episode の開始の途中で届いた通知では、その開始が決める session を壊さない（完聴の記録だけ行う）。
        if (!playInProgress) {
            if (_session.value is PlaybackSession.Starting) applySession(_session.value.playerStarted())
            applySession(_session.value.playerEnded())
        }
        try {
            podcastApi.markCompleted(completedPodcastId)
        } catch (_: ApiException) {
            // 完聴記録は best-effort。失敗しても次エピソードへの遷移を止めない。
        }
        listeningStreakStore?.refresh()

        // 別の episode の再生開始の途中で届いた通知では、その再生を優先して前進しない。
        if (playInProgress) return

        val (advanced, next) = _queue.value.advance()
        _queue.value = advanced
        if (next != null) {
            startEpisode(next, StartTrigger.ADVANCE)
        } else if (_session.value is PlaybackSession.Completed) {
            settlePlayback()
            applySession(_session.value.queueExhausted())
        }
    }

    /**
     * このエピソードを今すぐ再生する（一覧タップの導線）。[play] と同じ手動の開始。
     * キュー内に既にあればそこへジャンプ、無ければ現在の次に挿入してジャンプする（利用者が組んだ待機列を保つ。#81 review）。
     * 正本: PodcastViewModel.swift:283-292（playNow）。
     */
    suspend fun playNow(podcast: Episode): Unit = play(podcast)

    /**
     * 現在の次に割り込む（「次に再生」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:303-310（playNext）。
     */
    suspend fun playNext(podcast: Episode): Unit = withContext(dispatcher) {
        val nothingPlaying = _session.value is PlaybackSession.NothingPlaying
        _queue.value = _queue.value.playNext(podcast)
        if (nothingPlaying) playNow(podcast)
    }

    /**
     * キュー末尾に追加する（「キューに追加」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:294-301（addToQueue）。
     */
    suspend fun addToQueue(podcast: Episode): Unit = withContext(dispatcher) {
        val nothingPlaying = _session.value is PlaybackSession.NothingPlaying
        _queue.value = _queue.value.add(podcast)
        if (nothingPlaying) playNow(podcast)
    }

    /**
     * キューから取り除く。純粋なキューモデル操作であり、実際の再生（[playerController]）には
     * 一切触れない。正本: PodcastViewModel.swift:313-315（removeFromQueue）。
     *
     * WHY 再生に影響させない: iOS の [QueueSheet] は待機列（upNext）のみ削除対象にでき、
     * 現在再生中の項目を削除する導線は存在しない。仮に現在再生中の id を渡しても、
     * キューモデル上は次の要素が currentIndex に昇格するだけで、[currentPodcast]/実再生は
     * 不変（iOS 忠実写像）。
     *
     * WHY suspend + withContext(dispatcher): [moveUpNext] と同じくロストアップデート防止のため
     * 他のキュー変更操作と同じ直列化規律に揃える。
     */
    suspend fun removeFromQueue(id: String): Unit = withContext(dispatcher) {
        _queue.value = _queue.value.remove(id)
    }

    /**
     * 待機列（upNext）を並べ替える。`toOffset` は削除前オフセット方式（SwiftUI `onMove` 規約）。
     * 正本: PodcastViewModel.swift:317-320（moveUpNext）。
     *
     * WHY suspend + withContext(dispatcher): [play] 系と同じく _queue への書き込みを
     * dispatcher 上に直列化する。連打（例: 上へ移動を連続タップ）で複数の呼び出しが
     * 並行に _queue.value を読み書きすると、片方の更新がもう片方に上書きされて消える
     * （ロストアップデート）ため、他のキュー変更操作と同じ直列化規律に揃える。
     */
    suspend fun moveUpNext(from: Int, toOffset: Int): Unit = withContext(dispatcher) {
        _queue.value = _queue.value.moveUpNext(from, toOffset)
    }

    /** 再生中なら一時停止し、停止中なら再生を再開する。 */
    fun togglePlayPause() {
        if (playerController.isPlaying.value) {
            playerController.pause()
        } else {
            playerController.play()
        }
    }

    /** [PlaybackConstants.SKIP_BACKWARD_SECONDS] 秒戻る。下限 0 でクランプする。 */
    fun skipBackward() {
        val newPosition = (playerController.positionSeconds.value - PlaybackConstants.SKIP_BACKWARD_SECONDS)
            .coerceAtLeast(0.0)
        playerController.seekTo(newPosition)
    }

    /** [PlaybackConstants.SKIP_FORWARD_SECONDS] 秒進む。総再生時間が判明していれば上限でクランプする。 */
    fun skipForward() {
        val target = playerController.positionSeconds.value + PlaybackConstants.SKIP_FORWARD_SECONDS
        val duration = playerController.durationSeconds.value
        val newPosition = if (duration != null) target.coerceAtMost(duration) else target
        playerController.seekTo(newPosition)
    }

    /** 指定位置（秒）へシークする。 */
    fun seekTo(seconds: Double) {
        playerController.seekTo(seconds)
    }

    /** 再生速度（倍率）を設定する。 */
    fun setSpeed(speed: Double) {
        playerController.setSpeed(speed.toFloat())
    }

    /**
     * 再生を停止する。最終同期 → タイマー停止 → PlayerController の stop → session を NothingPlaying へ（キューは保持）。
     * 正本: PodcastViewModel.swift:353-376（stopPlayback）。何も再生していなければ何もしない。
     */
    suspend fun stopPlayback(): Unit = withContext(dispatcher) {
        if (_session.value is PlaybackSession.NothingPlaying) return@withContext
        settlePlayback()
        applySession(_session.value.stop())
    }

    /** エラーメッセージをクリアする（UI の AlertDialog 閉じ時に呼ばれる）。 */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 指定 Podcast の回答をサーバーへ送り、採点結果を返す。
     *
     * WHY podcastId を明示的に引数化: iOS PodcastViewModel.swift:411 と同形。シートが開かれた時点の
     * Podcast を保持し、その後 currentPodcast が変わってもシート内では元の podcastId で一貫性を保つ。
     * AudioPlayerSection は quizPodcast のスナップショット方式で実装する（要件1）。
     */
    suspend fun submitQuizAnswers(podcastId: String, answers: List<Int>): QuizAnswerResponse = withContext(dispatcher) {
        apiClient.submitQuizAnswers(podcastId, QuizAnswerRequest(answers))
    }

    /**
     * 前の再生の後始末（停止直前の位置の最終同期 → タイマー停止 → player の stop）。dispatcher コンテキスト内から呼ぶ。
     * 最終同期の対象は player の失敗（Errored の Loaded）も含む現在のエピソード。開始前の失敗（Errored の IdOnly）は
     * まだ読み込んでいないので送らない。何も再生していない状態では何もしない。
     *
     * WHY session で判定する: currentPodcast は Errored で null になるため、それで判定すると
     * player の失敗の後にタイマーが止まらず孤児になる。
     * WHY release() ではなく stop(): [playerController] はアプリ生存期間中のシングルトンで、
     * release() は「以後再利用不可」の終端操作（[PlayerController] の doc 参照）。
     *
     * [keepCurrentPodcast] は改名前から残した宣言で、もう読まない（Stopped が代替し、呼出は 0 件）。
     * シンボルの削除は android-slice-playback-05 の担当（order の禁止事項）。
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun settlePlayback(keepCurrentPodcast: Boolean = false) {
        val podcastId = when (val current = _session.value) {
            is PlaybackSession.NothingPlaying -> return
            is PlaybackSession.Starting -> current.episode.id
            is PlaybackSession.Active -> current.episode.id
            is PlaybackSession.Completed -> current.episode.id
            is PlaybackSession.Stopped -> current.episode.id
            is PlaybackSession.Errored -> (current.ref as? SessionEpisodeRef.Loaded)?.episode?.id
        }
        if (podcastId != null) syncPosition(podcastId)
        syncJob?.cancel()
        syncJob = null
        playerController.stop()
    }

    /**
     * 再生位置をサーバーへ定期同期するタイマー（15秒毎）を開始する。
     *
     * WHY: iOS 実挙動の忠実写像（PodcastViewModel.swift:580-603）。syncTimer は stopPlayback でのみ
     * 停止し、isPlaying によるガードは行わない（一時停止中も送信され続ける）。これはストリーク集計の
     * 起点として「再生を開始したこと」自体を継続的に記録する現行仕様だが、一時停止中の送信継続は
     * 仕様として妥当か再検討の余地があり、spec 改訂候補として follow-up 対象とする。
     */
    private fun startPositionSync(podcastId: String) {
        // 防御的キャンセル: 通常は settlePlayback() が事前に前のタイマーを止めているが、
        // 呼び出し順に依らず「新しいタイマーを張る前に古いタイマーを必ず止める」ことを
        // このメソッド自身の責務としても保証し、同期タイマーの孤児化を構造的に防ぐ。
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(POSITION_SYNC_INTERVAL_MS)
                syncPosition(podcastId)
            }
        }
    }

    /** 現在の再生位置をサーバーへ同期する。失敗時はサイレント（iOS syncPlaybackPositionIfNeeded 同様）。 */
    private suspend fun syncPosition(podcastId: String) {
        try {
            podcastApi.updatePlaybackPosition(podcastId, playerController.positionSeconds.value)
        } catch (e: ApiException) {
            // ネットワーク一時的な失敗等をログしない（iOS 同様のベストエフォート）。
        }
    }

    /**
     * 再生可否のゲート判定。再生可能なら null、不可なら理由メッセージを返す。
     * 判定は [Episode] の種類で決まる（[Episode.Playable] だけが再生できる）。
     */
    private fun playabilityError(podcast: Episode): String? =
        when (podcast) {
            is Episode.Playable -> null
            is Episode.Generating -> "生成中のため再生できません"
            is Episode.Failed -> podcast.errorMessage?.takeIf { it.isNotEmpty() } ?: "生成に失敗しました"
        }

    private companion object {
        const val POSITION_SYNC_INTERVAL_MS = 15_000L
        const val VOCABULARY_SAVE_ERROR_MESSAGE = "語彙の登録に失敗しました"
    }
}
