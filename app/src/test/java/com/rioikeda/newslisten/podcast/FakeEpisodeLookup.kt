package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.podcast.app.EpisodeLookup

/**
 * [EpisodeLookup] 専用の test double。呼出の前に使う [Episode] を登録する。
 * 同じ id を登録し直したら、最後に登録した値が勝つ。登録の無い id は `null`。
 */
class FakeEpisodeLookup : EpisodeLookup {
    private val episodes = LinkedHashMap<String, Episode>()

    fun register(vararg episodes: Episode) {
        episodes.forEach { this.episodes[it.id] = it }
    }

    override fun find(episodeId: String): Episode? = episodes[episodeId]
}
