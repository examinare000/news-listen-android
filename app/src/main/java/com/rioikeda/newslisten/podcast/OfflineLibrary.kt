package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.AudioCacheException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.PodcastApi
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音声の端末内キャッシュ（オフライン用ダウンロード）を、主体（`user_id`）ごとに持つ capsule（android-dec-02）。
 *
 * ダウンロードの開始時に主体を 1 回だけ捕捉し、書込はその主体の置き場へ入れる（ADR-104 決定 9）。
 * 主体が離れても完了を待たない: [cancelDownloads] は進行中の Job を cancel するだけで join しない。
 * cancel が間に合わず完了した書込は離脱主体の置き場に入り、次回起動の [reclaim] が消す（shared-leave-06）。
 *
 * 主体は `String?` で受ける（`auth/` を import しない）。`null` は未認証・`user_id` の欠落・形式不正で、
 * キャッシュを無効にして動作を続ける（決定 16）。
 *
 * @param currentSubject 現在の主体の id を読む関数。
 * @param dispatcher 状態の書込と Job の追跡を行う dispatcher（単一並列を想定）。
 */
class OfflineLibrary(
    private val cacheManager: AudioCacheManager,
    private val podcastApi: PodcastApi,
    private val currentSubject: () -> String?,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 進行中ダウンロードの追跡の key。同じ id でも主体が違えば別の Job になる。 */
    private data class DownloadKey(val subject: String, val id: String)

    /**
     * 進行中の Job の追跡表。書込は [dispatcher] の上だけで行い、[syncDownloadedState] が別の dispatcher から読むので
     * `StateFlow` に持つ。更新順は常に「追跡表 → 投影」で、投影の `downloadingIds` は追跡表から導く。
     */
    private val tracked = MutableStateFlow<Map<DownloadKey, Job>>(emptyMap())

    private val _downloadingIds = MutableStateFlow<Set<String>>(readOnlyCopy(emptySet()))

    /** 現在の主体でダウンロード中の Podcast ID の集合。 */
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(readOnlyCopy(emptySet()))

    /** 現在の主体のダウンロード済み（キャッシュ済み）Podcast ID の集合。 */
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** 直近の操作の失敗の文言。[download]・[removeDownload] の冒頭で `null` に戻る。 */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * 指定 Podcast の音声を現在の主体の置き場へダウンロードし、[downloadedIds] に追加する。完了まで suspend する。
     *
     * 再生できない値、主体が `null`、既にダウンロード中・済みのときは何もしない。
     * 取り直した値が再生できなければ保存せず [lastError] に文言を出す（android-deriv-17）。
     */
    suspend fun download(episode: Episode) {
        _lastError.value = null
        if (episode !is Episode.Playable) return
        val subject = currentSubject() ?: return
        val id = episode.id
        val job = withContext(dispatcher) {
            if (id in _downloadingIds.value || id in _downloadedIds.value) return@withContext null
            val key = DownloadKey(subject, id)
            // 追跡表へ載せてから開始する。登録と開始の間に cancel や同期が入っても、Job が追跡から漏れない。
            val created = scope.launch(start = CoroutineStart.LAZY) { performDownload(subject, key, id) }
            tracked.update { it + (key to created) }
            // 捕捉の後に主体が離れていたら、現在の主体の画面へ出さない（終了時の更新と同じ条件）。
            if (subject == currentSubject()) publishDownloading(subject)
            created.start()
            created
        }
        job?.join()
    }

    private suspend fun performDownload(subject: String, key: DownloadKey, id: String) {
        val self = currentCoroutineContext()[Job]
        try {
            // 署名付き URL を新たに取得（ダウンロード時点での最新 URL を確保）。
            val fresh = podcastApi.fetchPodcast(id)
            if (fresh !is Episode.Playable) {
                _lastError.value = playabilityError(fresh)
                return
            }
            val audioData = podcastApi.downloadAudio(fresh.audioUrl)
            cacheManager.cache(subject, id, audioData)
            // 離脱後に完了した書込は離脱主体の置き場に入るだけで、現在の主体の画面へは出さない。
            if (subject == currentSubject()) {
                _downloadedIds.update { readOnlyCopy(it + id) }
            }
            // 他の Job の失敗の文言を、成功した操作の中継が再び写さないようにする。
            _lastError.value = null
        } catch (e: ApiException) {
            _lastError.value = e.message
        } catch (e: AudioCacheException) {
            _lastError.value = e.message
        } finally {
            // 自分の登録だけを外す。cancel の後に同じ key で始まった新しい Job の登録を消さない。
            tracked.update { if (it[key] === self) it - key else it }
            if (subject == currentSubject()) publishDownloading(subject)
        }
    }

    /**
     * 指定主体で進行中のダウンロードを cancel する。**join しない**（完了を待たない）。
     * 追跡表から除き、[downloadingIds]・[downloadedIds] を空にし、[lastError] を `null` に戻す。
     */
    suspend fun cancelDownloads(subject: String): Unit = withContext(dispatcher) {
        val targets = tracked.value.filterKeys { it.subject == subject }
        targets.values.forEach { it.cancel() }
        tracked.update { current -> current.filterKeys { it.subject != subject } }
        _downloadingIds.value = readOnlyCopy(emptySet())
        _downloadedIds.value = readOnlyCopy(emptySet())
        _lastError.value = null
    }

    /** 現在の主体のキャッシュからダウンロード済み Podcast を削除する。主体が `null` なら何もしない。 */
    suspend fun removeDownload(id: String): Unit = withContext(dispatcher) {
        _lastError.value = null
        val subject = currentSubject() ?: return@withContext
        try {
            cacheManager.remove(subject, id)
            _downloadedIds.update { readOnlyCopy(it - id) }
        } catch (e: AudioCacheException) {
            _lastError.value = e.message
        }
    }

    /**
     * 一覧に合わせて投影を現在の主体へ合わせ直す。[downloadedIds] は主体の置き場の実体、[downloadingIds] は
     * 追跡表のうち現在の主体の id。値は `update` の中で計算し、同時に完了した Job の更新を上書きしない。
     * 主体が `null` なら両方を空にする。
     */
    fun syncDownloadedState(podcasts: List<Episode>) {
        val subject = currentSubject()
        if (subject == null) {
            _downloadedIds.update { readOnlyCopy(emptySet()) }
            _downloadingIds.update { readOnlyCopy(emptySet()) }
            return
        }
        _downloadedIds.update {
            readOnlyCopy(podcasts.filter { podcast -> cacheManager.isCached(subject, podcast.id) }.map { it.id }.toSet())
        }
        publishDownloading(subject)
    }

    /** 主体が `null` なら false。 */
    fun isCached(subject: String?, id: String): Boolean =
        subject != null && cacheManager.isCached(subject, id)

    /** 主体が `null` またはキャッシュが無ければ `null`。 */
    fun cachedFileUri(subject: String?, id: String): String? =
        subject?.let { cacheManager.cachedFileUri(it, id) }

    /** 再生に失敗したキャッシュを無効化する。主体が `null` なら何もしない。失敗は再生の遷移を止めないよう握る。 */
    fun invalidate(subject: String?, id: String) {
        if (subject == null) return
        try {
            cacheManager.invalidate(subject, id)
        } catch (_: AudioCacheException) {
            // best-effort: 無効化に失敗しても、失敗した再生の遷移と以後の購読を止めない。
        }
    }

    fun removeSubject(subject: String) = cacheManager.removeSubject(subject)

    fun reclaim(currentSubject: String?) = cacheManager.reclaim(currentSubject)

    private fun publishDownloading(subject: String) {
        _downloadingIds.update {
            readOnlyCopy(tracked.value.keys.filter { key -> key.subject == subject }.map { key -> key.id }.toSet())
        }
    }

    private fun readOnlyCopy(ids: Set<String>): Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(ids))

    // PodcastViewModel の再生ゲートと同じ文言の暫定の複製（android-dec-47）。
    private fun playabilityError(episode: Episode): String? =
        when (episode) {
            is Episode.Playable -> null
            is Episode.Generating -> "生成中のため再生できません"
            is Episode.Failed -> episode.errorMessage?.takeIf { it.isNotEmpty() } ?: "生成に失敗しました"
        }
}
