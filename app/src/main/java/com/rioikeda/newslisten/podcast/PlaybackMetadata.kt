package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.model.PodcastResponse

/**
 * 通知（MediaStyle 通知・ロック画面）に載せる Now Playing 情報。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/NowPlayingInfo.swift:36-37
 * （`MPMediaItemPropertyTitle` に [PodcastResponse.displayTitle]、
 * `MPMediaItemPropertyArtist` に難易度ラベルを載せる写像）。
 */
data class PlaybackMetadata(val title: String, val artist: String)

/**
 * 表示用タイトル。3段フォールバック（title → japaneseIntroText → デフォルト文言）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Models/Podcast.swift:107-112（displayTitle）。
 * - `title`（trim後）が非空ならそれを返す。
 * - `japaneseIntroText`（trim後）が非空ならそれを返す。
 * - 両方空の場合は `"ニュースポッドキャスト"` を返す（空欄は決して表示しない）。
 */
val PodcastResponse.displayTitle: String
    get() {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isNotEmpty()) return trimmedTitle
        val trimmedIntro = japaneseIntroText.trim()
        return trimmedIntro.ifEmpty { "ニュースポッドキャスト" }
    }

/**
 * 通知メタデータへ変換する。artist は難易度コードを [Difficulty] のラベルへ変換したもの
 * （正本: NowPlayingInfo.swift:37。未知の難易度コードはそのまま返す passthrough）。
 */
fun PodcastResponse.toPlaybackMetadata(): PlaybackMetadata =
    PlaybackMetadata(title = displayTitle, artist = Difficulty.entries.firstOrNull { it.code == difficulty }?.label ?: difficulty)
