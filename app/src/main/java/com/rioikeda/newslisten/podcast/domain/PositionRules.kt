package com.rioikeda.newslisten.podcast.domain

import java.time.Instant
import kotlin.math.abs

/** 位置を時刻で決める規則（last write wins。android-rule-playback-12〜14 の正本）。 */
object PositionRules {
    /** 完聴で記録する総時間。0 以下の値を飛ばし、player・episode・位置の順で採る（android-rule-playback-06）。 */
    fun completionTotalSeconds(endedDuration: Double?, episodeDurationSeconds: Int, position: Double): Double? =
        endedDuration?.takeIf { it > 0 }
            ?: episodeDurationSeconds.toDouble().takeIf { it > 0 }
            ?: position.takeIf { it > 0 }

    /** null は最古。同じ時刻は新しくない。 */
    fun isNewer(candidate: Instant?, than: Instant?): Boolean = when {
        candidate == null -> false
        than == null -> true
        else -> candidate.isAfter(than)
    }

    /** 端末の記録が無いエピソードには記録を作らない（shared-dec-37）。 */
    fun shouldAdopt(device: PositionRecord?, server: ServerPosition): Boolean =
        device != null && isNewer(server.recordedAt, device.recordedAt)

    fun resumeSeconds(device: PositionRecord?, server: ServerPosition): Double =
        if (device != null && !isNewer(server.recordedAt, device.recordedAt)) device.seconds else server.seconds

    /** 再開の確認を出す位置の差（秒）。ちょうどこの差でも確認する（shared-sync-10）。 */
    const val RESUME_CONFIRMATION_THRESHOLD_SECONDS = 15.0

    /**
     * 画面の再生ボタンで再開するときに、別の端末の位置を尋ねるか（android-rule-playback-15）。
     * 別の端末の記録 [latest] が、この端末の最後の記録 [lastLocalRecordedAt] より新しく、位置が [localSeconds] と
     * [RESUME_CONFIRMATION_THRESHOLD_SECONDS] 以上離れているときだけ真。
     */
    fun needsResumeConfirmation(lastLocalRecordedAt: Instant?, latest: PositionRecord?, localSeconds: Double): Boolean =
        latest != null &&
            isNewer(latest.recordedAt, lastLocalRecordedAt) &&
            abs(latest.seconds - localSeconds) >= RESUME_CONFIRMATION_THRESHOLD_SECONDS
}

/** 再開の確認で利用者が選ぶ位置。 */
enum class ResumeChoice { OtherDevice, ThisDevice }
