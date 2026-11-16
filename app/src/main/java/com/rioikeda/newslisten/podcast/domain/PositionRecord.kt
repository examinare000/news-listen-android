package com.rioikeda.newslisten.podcast.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 端末に残す位置の記録。主体 × エピソードで最新の 1 件。
 *
 * [recordedAt] をミリ秒に切り捨て済みで要求するのは、送った値と保存した値がずれて、自分の記録の反響が
 * 「サーバーの方が新しい」に見えるのを型で防ぐため（shared-dec-35）。
 */
data class PositionRecord(
    val subjectId: String,
    val episodeId: String,
    val seconds: Double,
    val recordedAt: Instant,
    val unsent: Boolean,
) {
    init {
        require(seconds.isFinite() && seconds >= 0) { "seconds は有限で 0 以上: $seconds" }
        require(recordedAt == recordedAt.truncatedTo(ChronoUnit.MILLIS)) { "recordedAt はミリ秒に切り捨て済み: $recordedAt" }
    }
}
