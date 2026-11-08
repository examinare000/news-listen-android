package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * 画面に出す再生の知らせの種類（android-dec-69）。文言は種類が固定で持ち、画面は [message] だけを読む（android-dec-70）。
 * 例外の message や生成の失敗の識別子の生の値は持たない。application は種類を選ぶだけで、文言を書かない。
 */
sealed interface PlaybackNotice {
    val message: String

    data object Offline : PlaybackNotice {
        override val message = "オフラインのため再生できません"
    }

    data object CacheRemoved : PlaybackNotice {
        override val message = "ダウンロード済みの音声が削除されたため再生できません"
    }

    data object StillGenerating : PlaybackNotice {
        override val message = "生成中のため再生できません"
    }

    data class GenerationFailed(val reason: GenerationFailureReason) : PlaybackNotice {
        override val message: String
            get() = when (reason) {
                GenerationFailureReason.GENERATION_FAILED -> "生成に失敗しました"
                GenerationFailureReason.QUOTA_EXHAUSTED -> "生成の上限に達したため、生成できませんでした"
            }
    }

    /** 取得の失敗のうち通信の失敗（android-dec-08）。 */
    data object FetchOffline : PlaybackNotice {
        override val message = "オフラインです。接続を確認してから、もう一度お試しください"
    }

    /** 取得の失敗のうち通信の失敗以外（android-dec-06）。 */
    data object FetchFailed : PlaybackNotice {
        override val message = "エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください"
    }

    data object PlayerFailed : PlaybackNotice {
        override val message = "再生できませんでした。もう一度お試しください"
    }

    data object SaveFailed : PlaybackNotice {
        override val message = "音声を保存できませんでした"
    }

    data object RemoveFailed : PlaybackNotice {
        override val message = "音声を削除できませんでした"
    }

    data object VocabularyFailed : PlaybackNotice {
        override val message = "語彙の登録に失敗しました"
    }

    companion object {
        /**
         * 再生できない [Episode] の知らせ。再生できるなら `null`。
         * 生成の失敗の識別子（ADR-108 の値域）はここで一度だけ理由に写し、生の文字列は残さない。
         * `quota_exhausted` 以外（`generation_failed`・`null`・空・未知の値）は fail-closed で [GenerationFailureReason.GENERATION_FAILED]。
         */
        fun unplayable(episode: Episode): PlaybackNotice? =
            when (episode) {
                is Episode.Playable -> null
                is Episode.Generating -> StillGenerating
                is Episode.Failed -> GenerationFailed(
                    when (episode.errorMessage) {
                        "quota_exhausted" -> GenerationFailureReason.QUOTA_EXHAUSTED
                        else -> GenerationFailureReason.GENERATION_FAILED
                    },
                )
            }
    }
}

/** 生成の失敗の理由（閉じた集合）。 */
enum class GenerationFailureReason {
    GENERATION_FAILED,
    QUOTA_EXHAUSTED,
}
