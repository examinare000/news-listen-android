package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.podcast.app.PlaybackNotice
import com.rioikeda.newslisten.podcast.ui.playbackNoticeMessage

/** 知らせの種類から、画面に出る文言を読む。文言を選ぶのは presentation の [playbackNoticeMessage]。 */
internal fun PlaybackNotice?.text(): String? = this?.let(::playbackNoticeMessage)
