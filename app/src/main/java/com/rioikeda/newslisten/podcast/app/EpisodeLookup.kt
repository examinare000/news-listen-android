package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * id から [Episode] を引く application の入口（android-dec-64）。Playback は具象の Catalog を知らずにこれだけを読む。
 * 一覧に無い id は `null` で、呼び手は何もしない。
 */
interface EpisodeLookup {
    fun find(episodeId: String): Episode?
}
