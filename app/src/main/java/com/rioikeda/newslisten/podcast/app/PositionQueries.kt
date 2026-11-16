package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.podcast.domain.PositionRules
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.IOException

/** 再開位置の候補を読むだけの入口（android-query-playback-06）。書かない・送らない。 */
class PositionQueries(private val store: PositionStore) {
    /** 端末の記録とサーバーの値のうち時刻の新しい方。主体が null なら端末の記録は読まない。 */
    suspend fun resumeCandidate(subjectId: String?, episode: Episode): Double {
        val server = ServerPosition(episode.resumeHintSeconds, episode.resumeRecordedAt)
        if (subjectId == null) return server.seconds
        // 端末の記録を読めないときは、サーバーの値に倒す。
        val device = try {
            store.read(subjectId, episode.id)
        } catch (_: IOException) {
            null
        }
        return PositionRules.resumeSeconds(device, server)
    }
}
