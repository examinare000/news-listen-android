package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.PositionRules
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.IOException

/** 再開位置の候補を読むだけの入口（android-query-playback-06）。書かない・送らない。 */
class PositionQueries(private val store: PositionStore) {
    /** 端末の記録とサーバーの値のうち時刻の新しい方。主体が null なら端末の記録は読まない。 */
    suspend fun resumeCandidate(subjectId: String?, episode: Episode): Double {
        val server = ServerPosition(episode.resumeHintSeconds, episode.resumeRecordedAt)
        return PositionRules.resumeSeconds(readDevice(subjectId, episode.id), server)
    }

    /** 主体と episode の端末の記録（android-query-playback-06）。主体が null・読めないときは null。 */
    suspend fun latestRecord(subjectId: String?, episodeId: String): PositionRecord? = readDevice(subjectId, episodeId)

    // 端末の記録を読めないときは、null に倒す（候補はサーバーの値、確認は出さない）。
    private suspend fun readDevice(subjectId: String?, episodeId: String): PositionRecord? {
        if (subjectId == null) return null
        return try {
            store.read(subjectId, episodeId)
        } catch (_: IOException) {
            null
        }
    }
}
