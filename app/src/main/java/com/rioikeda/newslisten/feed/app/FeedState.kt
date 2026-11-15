package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.feed.domain.Article
import com.rioikeda.newslisten.feed.domain.PendingCuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [FeedCommands] と [FeedQueries] が共有する書込側の状態（android-dec-108）。別々に作ると、画面が読む一覧と
 * Star が除く一覧が別になる。全メンバーを internal にして公開面に出さない。
 */
class FeedState(internal val dispatcher: CoroutineDispatcher) {
    internal val articles = MutableStateFlow<List<Article>>(emptyList())
    internal val pending = MutableStateFlow<PendingCuration?>(null)
    internal val notice = MutableStateFlow<FeedNotice?>(null)

    // 収集者の受け取りを待たないよう tryEmit で出す。連続する確定音は古い方を捨てる。
    internal val feedbackEvents = MutableSharedFlow<FeedFeedbackEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
