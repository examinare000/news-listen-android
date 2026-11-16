package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.time.Instant

/**
 * 位置の記録の端末保存の port（主体 × エピソードで最新 1 件）。失敗は `IOException` のまま呼出側へ出る。
 * 比較と書込が要る操作（[markSent]・[adoptIfNewer]）は、1 つの原子的な更新として行う。
 */
interface PositionStore {
    suspend fun read(subjectId: String, episodeId: String): PositionRecord?

    suspend fun unsent(subjectId: String): List<PositionRecord>

    /** 無条件に [record] で置き換える。 */
    suspend fun write(record: PositionRecord)

    /** 保存されている記録の `recordedAt` が [recordedAt] と等しいときだけ送信済みにして true。 */
    suspend fun markSent(subjectId: String, episodeId: String, recordedAt: Instant): Boolean

    /** [server] の時刻が端末の記録より厳密に新しいときだけ、記録を [server] に置き換えて送信済みにして true。 */
    suspend fun adoptIfNewer(subjectId: String, episodeId: String, server: ServerPosition): Boolean

    suspend fun removeSubject(subjectId: String)

    /** [currentSubjectId] の読める記録だけを残し、ほかをすべて消す。null は全部消す。 */
    suspend fun reclaim(currentSubjectId: String?)
}
