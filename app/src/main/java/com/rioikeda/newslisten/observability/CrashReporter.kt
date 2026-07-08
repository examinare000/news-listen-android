package com.rioikeda.newslisten.observability

import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.NewsListenJson
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.util.UUID

/**
 * 未捕捉例外を捕まえ、次回起動時に送信するクラッシュレポータ（フェーズ12・issue #140）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/CrashReporter.swift のミラー。
 * iOS は MetricKit がクラッシュ診断を「次回起動時」にまとめて配信する設計だが、Android には
 * 同等 API がないため、[Thread.setDefaultUncaughtExceptionHandler] で未捕捉例外を捕まえて
 * [crashFile]（filesDir 配下）へ即座に永続化し、次回起動時の [flush] で送信する方式に置換する。
 *
 * ADR-066 準拠: androidx.lifecycle を継承しないプレーンクラス。Dispatcher は constructor 注入。
 *
 * 制約（複数クラッシュ蓄積）: [persist] は単一ファイルへの上書き（last-write-wins）であり、
 * 複数回分のクラッシュを積み上げる設計にはなっていない。[flush] が一度も呼ばれないまま
 * 複数回クラッシュした場合、[crashFile] には最後のクラッシュだけが残り、それより前のクラッシュは
 * 上書きされて失われる。[flush] 内の rename（送信中ファイルへの退避）が救済するのは
 * 「flush 実行中（送信の待機中）に新しいクラッシュが発生するケース」のみであり、
 * 「flush を一度も実行する前に複数回クラッシュするケース」までは救済しない。
 * 複数クラッシュの蓄積対応（永続化ファイルの複数化・キュー化）は本 issue のスコープ外とし、
 * 必要になった時点で別 issue として起票する。
 *
 * @param crashFile 永続化先ファイル（テストでは temp file を注入して検証可能にする）。
 * @param deviceInfo クラッシュレポートに積む端末/アプリ情報（PII を含まない）。
 * @param apiClient 送信先 APIClient（未ログイン時にも送信可能な reportClientError を使う）。
 * @param dispatcher [flush] の実行 dispatcher（テストで StandardTestDispatcher に差し替え可能）。
 */
class CrashReporter(
    private val crashFile: File,
    private val deviceInfo: DeviceInfo,
    private val apiClient: ApiClient,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * 未捕捉例外ハンドラを設定する（Application.onCreate から一度だけ呼ぶ）。
     *
     * 設定時点の defaultUncaughtExceptionHandler をチェーン保持し、永続化後に必ず委譲する。
     * 永続化処理自体が失敗してもクラッシュ処理（プロセス終了）を妨げてはならないため、
     * 例外は握って委譲へ進む。
     */
    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persist(throwable)
            } catch (t: Throwable) {
                // 永続化失敗（ディスク容量不足・OOM 等）でもプロセス終了を妨げない。
                // 旧実装は catch (e: Exception) だったため Error 系（OOM 等）を捕まえられず
                // 下の委譲呼び出しに到達しない不具合があった。Throwable を広く捕まえた上で、
                // 万一 catch 節の変更で穴ができても委譲だけは必ず動くよう finally に置く。
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun persist(throwable: Throwable) {
        val report = CrashReportFormatter.format(throwable, deviceInfo)
        val json = NewsListenJson.encodeToString(ClientErrorReport.serializer(), report)
        crashFile.writeText(json)
    }

    /**
     * 永続化済みのクラッシュレポートがあれば送信する（起動時に一度呼ぶ）。
     *
     * 送信成功時のみファイルを削除する。送信失敗（通信エラー等）はファイルを残し次回再試行する
     * （クラッシュ報告の欠落よりベストエフォート再送を優先する）。壊れた永続化ファイル
     * （デコード不能）は再試行しても回復しないため破棄する。
     *
     * WHY rename してから読む: [crashFile] を直接読み取って送信し、成功時に無条件で
     * [crashFile] を削除する実装だと、送信中（通信待機中）に別スレッドの新しいクラッシュが
     * [persist] で [crashFile] を上書きした場合、送信成功後の削除で「まだ送っていない
     * 新しいクラッシュ」ごと消してしまう（data loss）。[flush] の先頭で [crashFile] を
     * 一意な「送信中」ファイル名へ rename しておけば、その後の [persist] は空になった
     * 元の [crashFile] へ新規作成するため、送信対象（rename 後のファイル）と衝突しない。
     * 送信中ファイルは成功時のみ削除し、失敗時は残す（次回 [flush] 呼び出し時に
     * [pendingFiles] で再び拾われ再送される）。
     */
    suspend fun flush(): Unit = withContext(dispatcher) {
        rotateCrashFileToPending()
        pendingFiles().forEach { pendingFile -> sendPendingFile(pendingFile) }
    }

    /** [crashFile] が存在すれば、一意な「送信中」ファイル名へ rename して読み取り対象から退避する。 */
    private fun rotateCrashFileToPending() {
        if (!crashFile.exists()) return
        val pendingFile = File(pendingDir(), "${crashFile.name}$PENDING_SUFFIX${UUID.randomUUID()}")
        crashFile.renameTo(pendingFile)
    }

    private fun pendingDir(): File = crashFile.parentFile ?: File(".")

    /**
     * 送信待ちファイル一覧（今回 rename した分 + 前回以前の flush で送信に失敗し残っている分）。
     * ファイル名の昇順で決定的に処理する。
     */
    private fun pendingFiles(): List<File> =
        pendingDir().listFiles { file -> file.name.startsWith("${crashFile.name}$PENDING_SUFFIX") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private suspend fun sendPendingFile(file: File) {
        val report = try {
            NewsListenJson.decodeFromString(ClientErrorReport.serializer(), file.readText())
        } catch (e: SerializationException) {
            file.delete()
            return
        }

        try {
            apiClient.reportClientError(report)
            file.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // ベストエフォート: 通信失敗や一時的なサーバエラーは次回起動時に再試行する。
        }
    }

    private companion object {
        /** [crashFile] の名前に付与する「送信中」ファイルのサフィックス（一意性は UUID で担保）。 */
        const val PENDING_SUFFIX = ".sending-"
    }
}
