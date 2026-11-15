package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [SourcesCommands] と [SourcesQueries] が共有する書込側の状態（android-dec-114）。別々に作ると、
 * `addSource` が書く一覧と画面が読む一覧が別になる。全メンバーを internal にして公開面に出さない。
 */
class SourcesState(internal val dispatcher: CoroutineDispatcher) {
    internal val sources = MutableStateFlow<List<RssSourceEntry>>(emptyList())
}
