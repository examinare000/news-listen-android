package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.feed.domain.PendingCuration
import com.rioikeda.newslisten.feed.ui.pendingActionMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 取り消しトーストの文言。以前 `PendingArticleAction.message` が持っていた文面を固定する。 */
class FeedPresentationTextTest {

    @Test
    fun Starの保留はスターしましたと表示する() {
        assertEquals("スターしました", pendingActionMessage(PendingCuration.Kind.STAR))
    }

    @Test
    fun Dismissの保留は削除しましたと表示する() {
        assertEquals("削除しました", pendingActionMessage(PendingCuration.Kind.DISMISS))
    }
}
