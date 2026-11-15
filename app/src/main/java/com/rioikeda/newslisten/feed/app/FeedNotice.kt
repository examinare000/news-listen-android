package com.rioikeda.newslisten.feed.app

/** Feed の失敗の知らせ。種類だけを持ち、文言は presentation が選ぶ（android-dec-110）。 */
sealed interface FeedNotice {
    data object Offline : FeedNotice

    data object FetchFailed : FeedNotice

    /** 取得の 429。生成上限（[GenerationLimit]）とは別の文面になる。 */
    data object FetchRateLimited : FeedNotice

    data object ActionFailed : FeedNotice

    data class GenerationLimit(val retryAfterSeconds: Int?) : FeedNotice
}

/** 画面が音などへ写す Feed の事象。 */
enum class FeedFeedbackEvent { SWIPE_CONFIRMED }
