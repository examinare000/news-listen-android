package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.PlaybackQueue
import com.rioikeda.newslisten.core.PlaybackSource
import com.rioikeda.newslisten.core.resolvePlaybackSource
import com.rioikeda.newslisten.core.resolveResumePosition
import com.rioikeda.newslisten.engagement.ListeningStreakStore
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.NetworkMonitoring
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.podcast.OfflineLibrary
import com.rioikeda.newslisten.podcast.PlaybackConstants
import com.rioikeda.newslisten.podcast.PlaybackFailureReason
import com.rioikeda.newslisten.podcast.PlaybackSession
import com.rioikeda.newslisten.podcast.PlaybackState
import com.rioikeda.newslisten.podcast.PlayerController
import com.rioikeda.newslisten.podcast.SessionEpisodeRef
import com.rioikeda.newslisten.podcast.SessionErrorReason
import com.rioikeda.newslisten.podcast.toPlaybackMetadata
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 再生の command の入口。エピソードは id で受け、解決は [EpisodeLookup] が行う（未知の id は何もしない: android-dec-64）。
 * 戻り値は無い。
 */
interface PlaybackCommands {
    suspend fun playNow(episodeId: String)

    suspend fun playNext(episodeId: String)

    suspend fun addToQueue(episodeId: String)

    suspend fun removeFromQueue(episodeId: String)

    /** 待機列の中で 1 つ前へ動かす。先頭と待機列に無い id では何もしない。 */
    suspend fun moveUp(episodeId: String)

    /** 待機列の中で 1 つ後ろへ動かす。末尾と待機列に無い id では何もしない。 */
    suspend fun moveDown(episodeId: String)

    fun togglePlayPause()

    fun skipBackward()

    fun skipForward()

    fun seekTo(seconds: Double)

    fun setSpeed(speed: Double)

    suspend fun retry()

    suspend fun stopForSubjectLeave()

    fun clearError()
}

/** 再生の query の入口。読むだけで、書かない。 */
interface PlaybackQueries {
    val nowPlaying: StateFlow<NowPlaying?>

    /** 画面が読むキューのリードモデル。読んだ時点の [PlaybackCoordinator.queue] と [nowPlaying] から導く。 */
    val queueView: StateFlow<QueueView>

    /** `state` が再生中のときだけ真。読んだ時点の `state` から導く。 */
    val isPlaying: StateFlow<Boolean>

    val positionSeconds: StateFlow<Double>

    val durationSeconds: StateFlow<Double?>

    val playbackSpeed: StateFlow<Float>

    /** 再生に由来する失敗だけ（android-dec-59）。 */
    val errorMessage: StateFlow<String?>

    val downloadingIds: StateFlow<Set<String>>

    val downloadedIds: StateFlow<Set<String>>
}

/**
 * 再生の use case（application）。再生元の決定・取得・キューと [session] の更新・player の操作・位置同期・
 * 完聴と自動前進・主体の離脱での停止を持つ。画面の状態は持たない。
 *
 * [session] と [queue] はテストの oracle で、interface には入れない。[session] の値は domain の辺の戻り値だけを置く。
 *
 * 利用者の開始（[playNow]・[retry]）を自動の前進より優先する（android-rule-playback-11・shared-dec-20）:
 * 利用者の開始は要求の時点で番号を進め、自動の前進は完聴の時点で読んだ番号が最新のときだけ確定する。
 *
 * @param currentSubject 現在の主体の id を読む関数。
 * @param dispatcher 状態の書込を直列化する dispatcher。
 */
class PlaybackCoordinator(
    private val podcastApi: PodcastApi,
    private val playerController: PlayerController,
    private val offlineLibrary: OfflineLibrary,
    private val networkMonitor: NetworkMonitoring,
    private val preferencesStore: PreferencesStore,
    private val listeningStreakStore: ListeningStreakStore?,
    private val dispatcher: CoroutineDispatcher,
    private val currentSubject: () -> String?,
    private val episodeLookup: EpisodeLookup,
) : PlaybackCommands, PlaybackQueries {
    // 位置同期タイマー（15秒毎）を動かすための内部スコープ。suspend 関数の呼び出しを跨いで生存する必要があるため、
    // Coordinator 自身が Dispatcher から生成して保持する（呼び出し元の寿命に委ねると、返った時点でキャンセルされる）。
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var syncJob: Job? = null

    // 直前に購読が受けた player の事象。Active の Paused で位置を送るかどうかの判定だけに使う。
    private var lastPlayerState: PlaybackState? = null

    // 主体の離脱の世代。離脱より前に要求された開始が、離脱の後に player や session を書かないための印（android-dec-42）。
    private var leaveGeneration: Long = 0

    // 利用者の開始の世代。利用者の開始が playMutex を待つ前に進め、自動の前進が自分の読んだ世代と比べて、
    // 待つ間に利用者の開始が来ていたら何も書かずに捨てる（android-rule-playback-11）。
    private var userStartGeneration: Long = 0

    // 完聴の記録を開始 1 回につき 1 回にするための番号。startSerial は session に Starting を置くたびに進み、
    // playerStarted（Starting → Active）では進めない（同じ開始に属する）。
    private var startSerial: Long = 0
    private var completionRecordedSerial: Long? = null

    // 開始を確定した時点の再生元と主体。NothingPlaying へ戻るときに null にする。
    // sessionSubject は再生開始時に固定した主体で、無効化（offlineLibrary.invalidate）に渡す。
    private var sessionSource: PlaybackSource? = null
    private var sessionSubject: String? = null

    // 開始（startEpisode）全体を直列化する Mutex。WHY: 開始は fetchPodcast・settlePlayback の最終同期という
    // suspend 境界を挟むため、Mutex なしで2回連続呼び出すと、両方が前の再生の後始末と session・キュー・player の
    // 書込を交互に行い、session とキューの現在位置が食い違う。保持中に届いた完聴の扱い（android-dec-38）も
    // isLocked で判定する。
    private val playMutex = Mutex()

    private val _session = MutableStateFlow<PlaybackSession>(PlaybackSession.NothingPlaying)

    /** 現在再生中のものの正本。[nowPlaying] はここからの派生で、[applySession] だけが書く。 */
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** 何が再生中かの読み取り用の値。NothingPlaying と Errored では null。 */
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    override val isPlaying: StateFlow<Boolean> =
        derived(playerController.state.map { it is PlaybackState.Playing }) { playerController.state.value is PlaybackState.Playing }

    override val positionSeconds: StateFlow<Double> = playerController.positionSeconds

    override val durationSeconds: StateFlow<Double?> = playerController.durationSeconds

    override val playbackSpeed: StateFlow<Float> = playerController.playbackSpeed

    private val _errorMessage = MutableStateFlow<String?>(null)

    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    override val downloadingIds: StateFlow<Set<String>> = offlineLibrary.downloadingIds

    override val downloadedIds: StateFlow<Set<String>> = offlineLibrary.downloadedIds

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
     * 読んだ時点で [read] を計算する [StateFlow]。`stateIn` や常駐の `collect` で別の coroutine へ写すと、
     * 書込の直後の同期の読みが遅れるため、値は保存しない（android-dec-59 (e)）。[collect] は最初の値と、変わった値を出す。
     * 新しい型を増やさないよう、メンバー関数の中の無名 object で作る。
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private fun <T> derived(flow: Flow<T>, read: () -> T): StateFlow<T> = object : StateFlow<T> {
        override val value: T get() = read()
        override val replayCache: List<T> get() = listOf(read())
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            flow.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    override val queueView: StateFlow<QueueView> =
        derived(combine(_queue, _nowPlaying) { queue, nowPlaying -> buildQueueView(queue, nowPlaying) }) {
            buildQueueView(_queue.value, _nowPlaying.value)
        }

    /** [nowPlaying] が null のときは区画も null（android-dec-67）。最上位の関数にすると台帳の列挙に出るため、メンバーに置く。 */
    private fun buildQueueView(queue: PlaybackQueue<Episode>, nowPlaying: NowPlaying?): QueueView {
        val current = if (nowPlaying == null) null else queue.current
        val upNext = queue.upNext
        return QueueView(
            nowPlaying = current?.let { QueueEntry(it.id, it.japaneseIntroText, it.difficultyCode, it.durationSeconds) },
            upNext = upNext.mapIndexed { index, episode ->
                UpNextEntry(
                    episodeId = episode.id,
                    displayTitle = episode.japaneseIntroText,
                    difficultyCode = episode.difficultyCode,
                    durationSeconds = episode.durationSeconds,
                    canMoveUp = index > 0,
                    canMoveDown = index < upNext.size - 1,
                )
            },
            upNextCount = upNext.size,
        )
    }

    /**
     * 画面側の自前の失敗 [own]・取得の失敗 [catalog]・再生の失敗を合成する（優先は own > catalog > 再生）。
     * `.value` は読んだ時点で計算し、`collect` は合成の値の変化を出す。最上位の関数にすると台帳の列挙に出るため、メンバーに置く。
     */
    internal fun errorMessageOver(own: StateFlow<String?>, catalog: StateFlow<String?>): StateFlow<String?> =
        derived(combine(own, catalog, errorMessage) { ownMessage, catalogMessage, playbackMessage -> ownMessage ?: catalogMessage ?: playbackMessage }) {
            own.value ?: catalog.value ?: errorMessage.value
        }

    /** 開始の要求の出どころ。出どころで、開始前の失敗の扱いと確定時に使う辺が変わる。 */
    private enum class StartTrigger { MANUAL, ADVANCE, RETRY }

    /**
     * 指定 Podcast の再生を手動で開始する。
     *
     * 判定 → 再生元の決定 → （NETWORK なら）取得 → 取り直した値の判定を先に行い、通ったときだけキューと [session] を変える。
     * 再生できないと分かったときは、再生中のものを止めずキューも [session] も変えず、[errorMessage] だけを出す。
     * 再生元の決定は [resolvePlaybackSource]（正本: docs/design/shared-playback-spec.md §6.1）:
     * CACHED は一覧の値で始め（オフラインでも可）、NETWORK は署名付き URL を再取得してから始める（ADR-009）。
     * キュー内に既にあればそこへジャンプ、無ければ現在の次に挿入してジャンプする（利用者が組んだ待機列を保つ）。
     * 正本: PodcastViewModel.swift:283-292（playNow）。
     */
    override suspend fun playNow(episodeId: String): Unit = withContext(dispatcher) {
        // 解決は世代を進める前に行う: 未知の id が待っている自動の前進を捨てさせないため。
        val episode = episodeLookup.find(episodeId) ?: return@withContext
        startManually(episode)
    }

    private suspend fun startManually(episode: Episode) {
        userStartGeneration++
        startEpisode(episode, StartTrigger.MANUAL, leaveGeneration, userStartGeneration)
    }

    /**
     * 開始の本体。[playMutex] で全体を直列化する（suspend 境界を挟んで 2 つが並行に進むと、session・キュー・
     * player の書込が交互になる）。主体は要求の冒頭で 1 回だけ捕捉する。
     *
     * [generation] は呼出側が要求の冒頭（[playMutex] を待つ前）で読んだ [leaveGeneration]。中断点のうち、
     * [playMutex] を取った直後・取得の後・[settlePlayback] の後の 3 箇所で比べ、離脱を挟んでいたら何も書かずに捨てる。
     * 再開位置の読みと player の開始は中断点ではないので、比較点にしない。
     *
     * [userGeneration] は呼出側が同じ位置で読んだ [userStartGeneration]。自動の前進だけが、[playMutex] を取った直後・
     * 取得の後（取り直した値の判定の前）・取得の失敗の後（失敗を出す前）で比べ、待つ間に利用者の開始が来ていたら、
     * キュー・session・player・[errorMessage] のどれも書かずに捨てる。[settlePlayback] の後には置かない:
     * 自動の前進のキューの書込と player の停止はそれより前に済み、中断するのは Active のときだけで、
     * 自動の前進の時点の session は Completed か離脱後の NothingPlaying だから。
     */
    private suspend fun startEpisode(episode: Episode, trigger: StartTrigger, generation: Long, userGeneration: Long) {
        val subject = currentSubject()
        fun supersededByUser() = trigger == StartTrigger.ADVANCE && userGeneration != userStartGeneration
        playMutex.withLock {
            if (generation != leaveGeneration) return@withLock
            if (supersededByUser()) return@withLock
            // 並んで待っていた 2 回目の retry は、1 回目の確定後の session では対象外。
            if (trigger == StartTrigger.RETRY && _session.value !is PlaybackSession.Errored) return@withLock
            if (episode !is Episode.Playable) {
                rejectStart(episode, trigger, SessionErrorReason.NotPlayable, playabilityError(episode))
                return@withLock
            }
            val source = resolvePlaybackSource(hasCached = offlineLibrary.isCached(subject, episode.id), isOnline = networkMonitor.isOnline.value)
            val playable: Episode.Playable
            val url: String
            when (source) {
                PlaybackSource.CACHED -> {
                    // fetchPodcast を経由しないため、一覧の値をそのまま採用する（transcript を含む表示用情報を持つ）。
                    // 判定と取得の間に別の dispatcher の削除（removeDownload・removeSubject）が入ると null になりうる。
                    // 例外にせず、開始の失敗として表に出す。
                    val cachedUri = offlineLibrary.cachedFileUri(subject, episode.id)
                    if (cachedUri == null) {
                        rejectStart(episode, trigger, SessionErrorReason.SourceUnavailable, "ダウンロード済みの音声が削除されたため再生できません")
                        return@withLock
                    }
                    playable = episode
                    url = cachedUri
                }
                PlaybackSource.NETWORK -> {
                    val fresh = try {
                        podcastApi.fetchPodcast(episode.id)
                    } catch (e: ApiException) {
                        if (generation != leaveGeneration) return@withLock
                        if (supersededByUser()) return@withLock
                        rejectStart(episode, trigger, SessionErrorReason.FetchFailed, e.message)
                        return@withLock
                    }
                    if (generation != leaveGeneration) return@withLock
                    if (supersededByUser()) return@withLock
                    if (fresh !is Episode.Playable) {
                        rejectStart(episode, trigger, SessionErrorReason.NotPlayable, playabilityError(fresh))
                        return@withLock
                    }
                    playable = fresh
                    url = fresh.audioUrl
                }
                PlaybackSource.UNAVAILABLE -> {
                    rejectStart(episode, trigger, SessionErrorReason.SourceUnavailable, "オフラインのため再生できません")
                    return@withLock
                }
            }

            // キューの書込は取得の後・settlePlayback の前（手動も前進も同じ位置）。前進は取得を待つ間、完聴した episode を現在に保つ。
            when (trigger) {
                StartTrigger.MANUAL -> {
                    val (jumped, found) = _queue.value.jump(episode.id)
                    _queue.value = if (found) jumped else _queue.value.playNext(episode).jump(episode.id).first
                }
                StartTrigger.ADVANCE -> _queue.value = _queue.value.playNext(episode).jump(episode.id).first
                StartTrigger.RETRY -> Unit // キューが既にその episode を現在にしている
            }
            // 前の再生の最終同期・タイマー停止・stop は、切り替えが確定した後で行う（NETWORK は取得の間、前の音声を鳴らし続ける）。
            settlePlayback()
            if (generation != leaveGeneration) return@withLock
            val resume = resolveResumePosition(playable.resumeHintSeconds, playable.durationSeconds)
            val speed = preferencesStore.defaultPlaybackSpeed.value
            applySession(beginSession(playable, resume, speed, trigger))
            startSerial++
            sessionSource = source
            sessionSubject = subject
            _errorMessage.value = null
            playerController.prepare(url, playable.toPlaybackMetadata())
            playerController.setSpeed(speed.toFloat())
            if (resume > 0) playerController.seekTo(resume)
            playerController.play()
        }
    }

    /**
     * 開始前の失敗。手動の開始は何も変えず [errorMessage] だけを出す（前の再生は続く）。
     * 自動前進・retry は、domain の辺 failBeforeStart の戻り値を置く。前進はキューを書く前に失敗するので、ここで
     * Errored(IdOnly) と食い違わないよう現在を合わせる（retry は既に合っている）。
     */
    private suspend fun rejectStart(episode: Episode, trigger: StartTrigger, reason: SessionErrorReason, message: String?) {
        if (trigger != StartTrigger.MANUAL) {
            settlePlayback()
            // 防御: 利用者の開始は要求の時点で自動の前進を捨てるので、確定済みの手動の開始と競合する状況は通常は起きない。
            // 失敗の辺は再生の開始後から呼べないため、起きたときは先に停止する。
            if (_session.value is PlaybackSession.Starting || _session.value is PlaybackSession.Active) {
                applySession(_session.value.stop())
            }
            if (trigger == StartTrigger.ADVANCE) _queue.value = _queue.value.playNext(episode).jump(episode.id).first
            applySession(_session.value.failBeforeStart(SessionEpisodeRef.IdOnly(episode.id), reason))
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

    /** [session] を置き、[nowPlaying] を派生させる（これを書く唯一の場所）。 */
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

    /** player の事象を [session]・位置同期・完聴の処理へ写す（android-deriv-15）。表に無い組合せは無視する。 */
    private fun handlePlayerState(state: PlaybackState) {
        when (state) {
            is PlaybackState.Playing -> when (val current = _session.value) {
                is PlaybackSession.Starting -> {
                    applySession(current.playerStarted())
                    startPositionSync(current.episode.id)
                }
                is PlaybackSession.Active -> startPositionSync(current.episode.id)
                else -> Unit
            }
            is PlaybackState.Paused -> when (val current = _session.value) {
                is PlaybackSession.Starting -> applySession(current.playerStarted())
                is PlaybackSession.Active -> {
                    stopPositionSync()
                    if (lastPlayerState is PlaybackState.Playing) {
                        scope.launch { syncPosition(current.episode.id, state.position) }
                    }
                }
                else -> Unit
            }
            is PlaybackState.Ended -> handlePlayerEnded(state.duration)
            is PlaybackState.Failed -> handlePlayerFailure(state.reason)
            else -> Unit
        }
        lastPlayerState = state
    }

    private fun handlePlayerFailure(reason: PlaybackFailureReason) {
        val episode = when (val current = _session.value) {
            is PlaybackSession.Starting -> current.episode
            is PlaybackSession.Active -> current.episode
            else -> return
        }
        stopPositionSync()
        val unplayableCache = reason is PlaybackFailureReason.Source || reason is PlaybackFailureReason.Decode
        if (sessionSource == PlaybackSource.CACHED && unplayableCache) {
            offlineLibrary.invalidate(sessionSubject, episode.id)
        }
        applySession(_session.value.fail(SessionEpisodeRef.Loaded(episode), SessionErrorReason.Player(reason)))
    }

    /**
     * 失敗した再生を、取得からやり直す。[session] が Errored のときだけ働き、それ以外は何もしない。
     * 再試行は再生元の解決からやり直す（共有仕様 §2.11）。利用者の開始なので、確認を通った後で世代を進める。
     */
    override suspend fun retry(): Unit = withContext(dispatcher) {
        if (_session.value !is PlaybackSession.Errored) return@withContext
        val current = _queue.value.current ?: return@withContext
        userStartGeneration++
        startEpisode(current, StartTrigger.RETRY, leaveGeneration, userStartGeneration)
    }

    // --- 再生キュー ---

    /**
     * 完聴（`Ended`）の処理。購読の中は非 suspend なので、launch の後では読めない値（`stop()` が位置と総時間を戻す）と
     * 世代を、ここで同期的に先に読む。自動の前進が読む利用者の開始の世代も、離脱の世代と同じ位置で読む。
     *
     * [playMutex] を保持している間に届いた完聴は、別の episode の開始が決める session を壊さないよう、
     * 記録（`markCompleted`・streak）だけ行う（android-dec-38）。timer はどちらでも止める。
     */
    private fun handlePlayerEnded(endedDuration: Double?) {
        val episode = when (val current = _session.value) {
            is PlaybackSession.Starting -> current.episode
            is PlaybackSession.Active -> current.episode
            else -> return
        }
        val position = playerController.positionSeconds.value
        val playInProgress = playMutex.isLocked
        val generation = leaveGeneration
        val userGeneration = userStartGeneration
        val record = completionRecordedSerial != startSerial
        if (record) completionRecordedSerial = startSerial
        stopPositionSync()

        if (playInProgress) {
            if (record) scope.launch { sendCompletion(episode.id, record = true, totalSeconds = null) }
            return
        }
        if (_session.value is PlaybackSession.Starting) applySession(_session.value.playerStarted())
        applySession(_session.value.playerEnded())
        val totalSeconds = endedDuration?.takeIf { it > 0 }
            ?: episode.durationSeconds.toDouble().takeIf { it > 0 }
            ?: position.takeIf { it > 0 }
        scope.launch { sendCompletion(episode.id, record, totalSeconds) }
        scope.launch { advanceAfterCompletion(generation, userGeneration) }
    }

    /** 完聴の送信の直列。各段は best-effort で、失敗しても次の段へ進む（前進はこの完了を待たない。shared-dec-15）。 */
    private suspend fun sendCompletion(podcastId: String, record: Boolean, totalSeconds: Double?) {
        if (record) {
            try {
                podcastApi.markCompleted(podcastId)
            } catch (_: ApiException) {
                // 完聴記録は best-effort。失敗しても次の段を止めない。
            }
        }
        if (totalSeconds != null) syncPosition(podcastId, totalSeconds)
        listeningStreakStore?.refresh()
    }

    /** 完聴の後の自動前進。次が無ければ Stopped にする。キューは次を読むだけで、書込は startEpisode が取得の後に行う。 */
    private suspend fun advanceAfterCompletion(generation: Long, userGeneration: Long) {
        val (advanced, next) = _queue.value.advance()
        if (next != null) {
            startEpisode(next, StartTrigger.ADVANCE, generation, userGeneration)
        } else if (_session.value is PlaybackSession.Completed) {
            _queue.value = advanced
            settlePlayback()
            applySession(_session.value.queueExhausted())
        }
    }

    /**
     * 現在の次に割り込む（「次に再生」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:303-310（playNext）。
     */
    override suspend fun playNext(episodeId: String): Unit = withContext(dispatcher) {
        val episode = episodeLookup.find(episodeId) ?: return@withContext
        val nothingPlaying = _session.value is PlaybackSession.NothingPlaying
        _queue.value = _queue.value.playNext(episode)
        if (nothingPlaying) startManually(episode)
    }

    /**
     * キュー末尾に追加する（「キューに追加」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:294-301（addToQueue）。
     */
    override suspend fun addToQueue(episodeId: String): Unit = withContext(dispatcher) {
        val episode = episodeLookup.find(episodeId) ?: return@withContext
        val nothingPlaying = _session.value is PlaybackSession.NothingPlaying
        _queue.value = _queue.value.add(episode)
        if (nothingPlaying) startManually(episode)
    }

    /**
     * キューから取り除く。純粋なキューモデル操作であり、実際の再生（[playerController]）には一切触れない。
     * 正本: PodcastViewModel.swift:313-315（removeFromQueue）。
     *
     * WHY 再生に影響させない: 待機列（upNext）のみ削除対象にでき、現在再生中の項目を削除する導線は存在しない。
     * 仮に現在再生中の id を渡しても、キューモデル上は次の要素が currentIndex に昇格するだけで、
     * [nowPlaying]/実再生は不変（iOS 忠実写像）。
     *
     * WHY suspend + withContext(dispatcher): [moveUp] と同じくロストアップデート防止のため
     * 他のキュー変更操作と同じ直列化規律に揃える。
     */
    override suspend fun removeFromQueue(episodeId: String): Unit = withContext(dispatcher) {
        _queue.value = _queue.value.remove(episodeId)
    }

    /**
     * 待機列（upNext）の中で 1 つ前へ動かす。位置は id から引き、`toOffset` は削除前オフセット方式（SwiftUI `onMove` 規約）で渡す。
     *
     * WHY suspend + withContext(dispatcher): 連打で複数の呼び出しが並行に _queue.value を読み書きすると、
     * 片方の更新がもう片方に上書きされて消える（ロストアップデート）ため、他のキュー変更操作と同じ直列化規律に揃える。
     */
    override suspend fun moveUp(episodeId: String): Unit = withContext(dispatcher) {
        val from = _queue.value.upNext.indexOfFirst { it.id == episodeId }
        if (from > 0) _queue.value = _queue.value.moveUpNext(from, from - 1)
    }

    /** 待機列（upNext）の中で 1 つ後ろへ動かす（削除前オフセットは `from + 2`）。直列化の理由は [moveUp] と同じ。 */
    override suspend fun moveDown(episodeId: String): Unit = withContext(dispatcher) {
        val upNext = _queue.value.upNext
        val from = upNext.indexOfFirst { it.id == episodeId }
        if (from in 0 until upNext.size - 1) _queue.value = _queue.value.moveUpNext(from, from + 2)
    }

    /** 再生中なら一時停止し、停止中なら再生を再開する。 */
    override fun togglePlayPause() {
        if (isPlaying.value) {
            playerController.pause()
        } else {
            playerController.play()
        }
    }

    /** [PlaybackConstants.SKIP_BACKWARD_SECONDS] 秒戻る。下限 0 でクランプする。 */
    override fun skipBackward() {
        val newPosition = (playerController.positionSeconds.value - PlaybackConstants.SKIP_BACKWARD_SECONDS)
            .coerceAtLeast(0.0)
        playerController.seekTo(newPosition)
    }

    /** [PlaybackConstants.SKIP_FORWARD_SECONDS] 秒進む。総再生時間が判明していれば上限でクランプする。 */
    override fun skipForward() {
        val target = playerController.positionSeconds.value + PlaybackConstants.SKIP_FORWARD_SECONDS
        val duration = playerController.durationSeconds.value
        val newPosition = if (duration != null) target.coerceAtMost(duration) else target
        playerController.seekTo(newPosition)
    }

    /** 指定位置（秒）へシークする。 */
    override fun seekTo(seconds: Double) {
        playerController.seekTo(seconds)
    }

    /** 再生速度（倍率）を設定する。 */
    override fun setSpeed(speed: Double) {
        playerController.setSpeed(speed.toFloat())
    }

    /**
     * 主体の離脱での停止（shared-leave-01）。世代を進め、timer を止め、player を止め、session とキューを空にする。
     * 位置は送らない（shared-dec-09）。[playMutex] は取らない: 遅い取得の後ろで離脱が待たされないよう、
     * 取得中の開始は世代の比較で捨てさせる（android-dec-42）。session を先に空にするのは、途中の状態でも
     * session とキューの現在位置が食い違わないため。利用者の開始の世代は進めない（離脱は [leaveGeneration] が担う）。
     */
    override suspend fun stopForSubjectLeave(): Unit = withContext(dispatcher) {
        leaveGeneration++
        stopPositionSync()
        playerController.stop()
        applySession(_session.value.stop())
        _queue.value = PlaybackQueue()
    }

    /** 再生に由来する失敗の通知だけを消す。画面側の自前の失敗には触れない。 */
    override fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 前の再生の後始末（停止直前の位置の最終同期 → タイマー停止 → player の stop）。dispatcher コンテキスト内から呼ぶ。
     * 位置を送るのは Active のときだけ。Starting は player がまだ位置を報告していない。Completed・Stopped・Errored は
     * 再生が終わっているか失敗している。何も再生していない状態では何もしない。
     *
     * WHY session で判定する: nowPlaying は Errored で null になるため、それで判定すると
     * player の失敗の後にタイマーが止まらず孤児になる。
     * WHY 終端の解放ではなく stop(): [playerController] はアプリ生存期間中のシングルトンで、
     * 終端の解放は以後の再生を不能にする。
     */
    private suspend fun settlePlayback() {
        val current = _session.value
        if (current is PlaybackSession.NothingPlaying) return
        if (current is PlaybackSession.Active) syncPosition(current.episode.id, playerController.positionSeconds.value)
        stopPositionSync()
        playerController.stop()
    }

    /**
     * 再生位置をサーバーへ定期同期するタイマー（15秒毎）を張る。`Playing` を受けたときに呼び、
     * 張り直すときは古いタイマーを必ず止める（孤児化を構造的に防ぐ）。
     */
    private fun startPositionSync(podcastId: String) {
        stopPositionSync()
        syncJob = scope.launch {
            while (isActive) {
                delay(POSITION_SYNC_INTERVAL_MS)
                syncPosition(podcastId, playerController.positionSeconds.value)
            }
        }
    }

    private fun stopPositionSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /** 位置をサーバーへ同期する。失敗時はサイレント（iOS syncPlaybackPositionIfNeeded 同様）。 */
    private suspend fun syncPosition(podcastId: String, positionSeconds: Double) {
        try {
            podcastApi.updatePlaybackPosition(podcastId, positionSeconds)
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
    }
}
