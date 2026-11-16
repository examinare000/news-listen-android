package com.rioikeda.newslisten.podcast.domain

import java.time.Instant

/** 位置を時刻で決める規則（last write wins。android-rule-playback-12〜14 の正本）。 */
object PositionRules {
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
}
