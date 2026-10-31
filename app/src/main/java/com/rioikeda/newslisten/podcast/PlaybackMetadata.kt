package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * 通知（MediaStyle 通知・ロック画面）に載せる Now Playing 情報。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/NowPlayingInfo.swift:36-37
 * （`MPMediaItemPropertyTitle` に [Episode.displayTitle]、
 * `MPMediaItemPropertyArtist` に難易度ラベルを載せる写像）。
 */
data class PlaybackMetadata(val title: String, val artist: String)

/**
 * 通知メタデータへ変換する。artist は難易度コードを [Difficulty] のラベルへ変換したもの
 * （正本: NowPlayingInfo.swift:37。未知の難易度コードはそのまま返す passthrough）。
 */
fun Episode.toPlaybackMetadata(): PlaybackMetadata =
    PlaybackMetadata(title = displayTitle, artist = Difficulty.entries.firstOrNull { it.code == difficultyCode }?.label ?: difficultyCode)
