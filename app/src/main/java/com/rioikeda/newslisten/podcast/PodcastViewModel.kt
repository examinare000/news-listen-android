package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.core.PlaybackQueue
import com.rioikeda.newslisten.core.PlaybackSource
import com.rioikeda.newslisten.core.resolvePlaybackSource
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.AudioCacheException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.NetworkMonitoring
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
    private val apiClient: ApiClient,
    private val playerController: PlayerController,
    private val cacheManager: AudioCacheManager,
    private val networkMonitor: NetworkMonitoring,
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

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())

    /** ダウンロード中 Podcast ID の集合（進捗率は持たない。フェーズ8-B・ADR-027）。 */
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())

    /** ダウンロード済み（キャッシュ済み）Podcast ID の集合。 */
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

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

    private val _queue = MutableStateFlow(PlaybackQueue<PodcastResponse>())

    /**
     * 再生キュー（連続再生・プレイリスト / issue #81 相当）。UI は current/upNext を購読する。
     * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift:270-320。
     */
    val queue: StateFlow<PlaybackQueue<PodcastResponse>> = _queue.asStateFlow()

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
            syncDownloadedState()
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isLoading.value = false
    }

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
    suspend fun download(podcast: PodcastResponse) {
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
    private suspend fun performDownload(podcast: PodcastResponse) {
        try {
            // 署名付き URL を新たに取得（ダウンロード時点での最新 URL を確保）。
            val fresh = apiClient.fetchPodcast(podcast.id)
            val audioData = apiClient.downloadAudio(fresh.audioUrl)
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
     * 指定 Podcast の再生を開始する。
     *
     * 1. まず再生可否をゲートする（processing/failed/partial_failed は再生不可）。
     *    iOS は暗黙的に再生失敗するだけだが、Android は明示的にガードし [errorMessage] へ理由を出す改善。
     * 2. [resolvePlaybackSource] で再生元を決定する（正本: docs/design/shared-playback-spec.md §6.1
     *    / core.PlaybackSourceResolver）。
     *    - CACHED: ローカルキャッシュを最優先（署名 URL 失効・オフラインと無関係に常に成立）。
     *      [ApiClient.fetchPodcast] を経由しない独立経路のため、オフラインでも再生できる。
     *    - NETWORK: [ApiClient.fetchPodcast] で署名付き audio_url を再取得してから再生する。
     *      正本: spec §6.1-2 / web useStartPodcast / ADR-009。iOS（PodcastViewModel.swift:207-268）は
     *      一覧取得時点の URL に依存するが、署名 URL は時間経過で失効しうるため、
     *      Android は再生直前の再取得を意図的な改善として行う（この差異は spec 側が正）。
     *    - UNAVAILABLE: 未キャッシュ + オフラインは再生不可。[errorMessage] へ理由を出す。
     *
     * @param podcast 再生対象（一覧の要素。gate 判定・CACHED 経路のメタデータ生成はこの引数から行う）。
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

            when (resolvePlaybackSource(hasCached = cacheManager.isCached(podcast.id), isOnline = networkMonitor.isOnline.value)) {
                PlaybackSource.CACHED -> {
                    // fetchPodcast を経由しないため、引数の podcast をそのまま採用する
                    // （一覧レスポンスは segments を含む表示用情報を既に持つため再取得不要）。
                    // requireNotNull: resolvePlaybackSource が CACHED を返した直後なので
                    // isCached(id) は true のはずであり、cachedFileUri は必ず非 null。
                    val cachedUri = requireNotNull(cacheManager.cachedFileUri(podcast.id)) {
                        "CACHED と判定されたのに cachedFileUri が null: id=${podcast.id}"
                    }
                    beginPlayback(podcast, cachedUri)
                }
                PlaybackSource.NETWORK -> {
                    try {
                        val fresh = apiClient.fetchPodcast(podcast.id)
                        beginPlayback(fresh, fresh.audioUrl)
                    } catch (e: ApiException) {
                        _errorMessage.value = e.message
                    }
                }
                PlaybackSource.UNAVAILABLE -> {
                    _errorMessage.value = "オフラインのため再生できません"
                }
            }
        }
    }

    /**
     * 再生元（キャッシュ/ネットワーク）決定後の共通開始処理。
     * WHY 抽出: CACHED/NETWORK 分岐は「どの URL とメタデータ元 Podcast を使うか」だけが異なり、
     * 状態更新・PlayerController 起動・同期タイマー開始の手順は完全に同一のため重複を避ける。
     */
    private fun beginPlayback(podcast: PodcastResponse, url: String) {
        _currentPodcast.value = podcast
        _errorMessage.value = null
        playerController.onPlaybackCompleted = { scope.launch { handlePlaybackEnded() } }
        playerController.prepare(url, podcast.toPlaybackMetadata())
        playerController.play()
        startPositionSync(podcast.id)
    }

    // --- 再生キュー（issue #81 相当。フェーズ6 T1） ---

    /**
     * 再生終了時の自動次再生。キューに次があれば [play] で再生し、無ければ停止する。
     * 正本: PodcastViewModel.swift:272-281（handlePlaybackEnded）。
     * [play] の fetch/gate/Mutex/同期タイマー規律をすべて経由させるため、ここでは
     * `prepare`/`play` を直接呼ばず既存の [play] に委譲する。
     *
     * WHY 遷移前にゲート判定する: [play] 自身もゲート判定するが、ゲートされた場合は
     * Mutex に入る前に早期 return するため stopInternal() が呼ばれない。advance() で
     * queue.current は既に次へ進んでいるのに、[play] に委譲するだけだと currentPodcast は
     * 終了した旧エピソードのまま残り、queue と currentPodcast が不整合になる
     * （review指摘）。ここで先にゲート判定し、弾かれる場合は明示的に errorMessage を設定して
     * stopInternal() を呼び、状態を整合させる。
     */
    private suspend fun handlePlaybackEnded() {
        val (advanced, next) = _queue.value.advance()
        _queue.value = advanced
        val gateError = next?.let { playabilityError(it) }
        if (next != null && gateError == null) {
            play(next)
        } else {
            if (gateError != null) _errorMessage.value = gateError
            stopInternal()
        }
    }

    /**
     * このエピソードを今すぐ再生する（一覧タップの導線）。
     * キュー内に既にあればそこへジャンプ、無ければ現在の次に挿入してそこへジャンプしてから再生する。
     *
     * WHY(#81 review 転記): start/setQueue で丸ごと置換すると利用者が組んだ待機列が消えるため、
     * 挿入方式（[PlaybackQueue.playNext] + [PlaybackQueue.jump]）でキューを保持する。
     * 正本: PodcastViewModel.swift:283-292（playNow）。
     */
    suspend fun playNow(podcast: PodcastResponse): Unit = withContext(dispatcher) {
        val (jumped, found) = _queue.value.jump(podcast.id)
        _queue.value = if (found) jumped else _queue.value.playNext(podcast).jump(podcast.id).first
        play(podcast)
    }

    /**
     * 現在の次に割り込む（「次に再生」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:303-310（playNext）。
     */
    suspend fun playNext(podcast: PodcastResponse): Unit = withContext(dispatcher) {
        val nothingPlaying = _currentPodcast.value == null
        _queue.value = _queue.value.playNext(podcast)
        if (nothingPlaying) playNow(podcast)
    }

    /**
     * キュー末尾に追加する（「キューに追加」）。何も再生していなければ即再生する。
     * 正本: PodcastViewModel.swift:294-301（addToQueue）。
     */
    suspend fun addToQueue(podcast: PodcastResponse): Unit = withContext(dispatcher) {
        val nothingPlaying = _currentPodcast.value == null
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
