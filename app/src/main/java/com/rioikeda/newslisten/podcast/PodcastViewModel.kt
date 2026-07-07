package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val apiClient: ApiClient,
    private val playerController: PlayerController,
    private val dispatcher: CoroutineDispatcher,
) {
    // 位置同期タイマー（15秒毎）を動かすための内部スコープ。play()/suspend 関数の呼び出しを跨いで
    // 生存する必要があるため、ViewModel 自身が Dispatcher から生成して保持する
    // （呼び出し元の suspend コンテキストに寿命を委ねると play() が返った時点でキャンセルされてしまう）。
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var syncJob: Job? = null

    // play() 全体を直列化する Mutex。WHY: play() は fetchPodcast という suspend 境界を挟むため、
    // Mutex なしで2回連続呼び出すと両方が stopInternal() の「前の再生なし」判定をすり抜けて
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

    private val _podcasts = MutableStateFlow<List<PodcastResponse>>(emptyList())
    val podcasts: StateFlow<List<PodcastResponse>> = _podcasts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentPodcast = MutableStateFlow<PodcastResponse?>(null)

    /** 現在再生対象の Podcast（未再生なら null）。署名付き URL 再取得後の最新値。 */
    val currentPodcast: StateFlow<PodcastResponse?> = _currentPodcast.asStateFlow()

    /**
     * Podcast 一覧を取得して [podcasts] を更新する。失敗時は [errorMessage] に反映する。
     * 正本: PodcastViewModel.swift:99-110（loadPodcasts）。
     */
    suspend fun fetchPodcasts(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val response = apiClient.fetchPodcasts()
            _podcasts.value = response.podcasts
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isLoading.value = false
    }

    /**
     * 指定 Podcast の再生を開始する。
     *
     * 1. まず再生可否をゲートする（processing/failed/partial_failed は再生不可）。
     *    iOS は暗黙的に再生失敗するだけだが、Android は明示的にガードし [errorMessage] へ理由を出す改善。
     * 2. 再生直前に [ApiClient.fetchPodcast] で署名付き audio_url を再取得する。
     *    正本: docs/design/shared-playback-spec.md §6.1-2 / web useStartPodcast / ADR-009。
     *    iOS（PodcastViewModel.swift:207-268）は一覧取得時点の URL に依存するが、
     *    署名 URL は時間経過で失効しうるため、Android は再生直前の再取得を意図的な改善として行う。
     *
     * @param podcast 再生対象（一覧の要素。gate 判定はこの引数の status で行う）。
     */
    suspend fun play(podcast: PodcastResponse): Unit = withContext(dispatcher) {
        // 二重呼び出し（連打・エピソード切替の取り違え）で古い同期タイマーが即座に見えなくなる
        // 事態を避けるため、Mutex 待ちに入る前にまず止める。最終同期・実際の解放は
        // Mutex 内の stopInternal() が担うので、ここでの cancel は多重防御。
        syncJob?.cancel()

        val gateError = playabilityError(podcast)
        if (gateError != null) {
            _errorMessage.value = gateError
            return@withContext
        }

        // play() 全体を Mutex で直列化する。fetchPodcast の suspend 境界を挟んで2回連続で
        // play() が呼ばれても、片方が完全に終わるまでもう片方が stopInternal() 以降へ
        // 進めないようにし、同期タイマーの孤児化（[playMutex] の doc 参照）を防ぐ。
        playMutex.withLock {
            // 前の再生があれば、最終同期・タイマー停止・PlayerController の停止（release ではなく
            // stop。release すると以後 prepare/play できなくなるため）をしてから新しい再生を開始する
            // （iOS play() 冒頭で無条件に stopPlayback() を呼ぶのと同じ設計）。
            stopInternal()

            try {
                val fresh = apiClient.fetchPodcast(podcast.id)
                _currentPodcast.value = fresh
                _errorMessage.value = null
                playerController.onPlaybackCompleted = { scope.launch { stopInternal() } }
                playerController.prepare(fresh.audioUrl)
                playerController.play()
                startPositionSync(fresh.id)
            } catch (e: ApiException) {
                _errorMessage.value = e.message
            }
        }
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
    fun setSpeed(speed: Float) {
        playerController.setSpeed(speed)
    }

    /**
     * 再生を停止する。最終同期 → タイマー停止 → PlayerController 解放の順で行う。
     * 正本: PodcastViewModel.swift:353-376（stopPlayback）。
     */
    suspend fun stopPlayback(): Unit = withContext(dispatcher) { stopInternal() }

    /** エラーメッセージをクリアする（UI の AlertDialog 閉じ時に呼ばれる）。 */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * [stopPlayback] の本体。既に dispatcher コンテキスト内（play 等）から直接呼べるよう分離。
     *
     * WHY: 何も再生していない（[_currentPodcast] が null）ならここで何もせず抜ける。
     * play() が冒頭で無条件にこれを呼ぶため、ガード無しだと初回再生のたびに
     * 何もしていない [PlayerController.stop] が誤って呼ばれてしまう。
     *
     * WHY release() ではなく stop(): [playerController] はアプリ生存期間中のシングルトンで
     * あり、release() は「以後再利用不可」の終端操作（[PlayerController] の doc 参照）。
     * ここは「次のエピソードのために止める」操作なので stop() が正しい。
     */
    private suspend fun stopInternal() {
        val podcastId = _currentPodcast.value?.id ?: return
        // 停止直前の位置を最後に一度だけ同期する（iOS stopPlayback():355 転記）。
        syncPosition(podcastId)
        syncJob?.cancel()
        syncJob = null
        playerController.stop()
        _currentPodcast.value = null
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
        // 防御的キャンセル: 通常は stopInternal() が事前に前のタイマーを止めているが、
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
            apiClient.updatePlaybackPosition(podcastId, playerController.positionSeconds.value)
        } catch (e: ApiException) {
            // ネットワーク一時的な失敗等をログしない（iOS 同様のベストエフォート）。
        }
    }

    /**
     * 再生可否のゲート判定。再生可能なら null、不可なら理由メッセージを返す。
     * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift:104-121 のステータス粒度
     * （[PodcastStatusBadge] と同一の分類）に基づく。
     */
    private fun playabilityError(podcast: PodcastResponse): String? =
        when (val badge = PodcastStatusBadge.from(podcast)) {
            is PodcastStatusBadge.None -> null
            is PodcastStatusBadge.Processing -> "生成中のため再生できません"
            is PodcastStatusBadge.Failed -> badge.message ?: "生成に失敗しました"
        }

    private companion object {
        const val POSITION_SYNC_INTERVAL_MS = 15_000L
    }
}
