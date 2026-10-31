package com.rioikeda.newslisten.catalog.domain

import com.rioikeda.newslisten.core.QueueItem
import java.util.Collections

enum class EpisodeKind { Single, Digest }

/** `Unknown` は decoder が知らない status 文字列に与える。判別では `Failed` と同じく再生不可に倒す。 */
enum class EpisodeStatus { Completed, Processing, Failed, PartialFailed, Unknown }

enum class EpisodeSourceKind { Featured, User, Unknown }

data class EpisodeSourceArticle(
    val articleId: String,
    val title: String,
    val url: String,
    val source: String,
)

data class EpisodeTranscriptSegment(
    val speaker: String,
    val text: String,
    val role: String?,
)

data class EpisodeGlossaryEntry(
    val term: String,
    val meaningJa: String,
    val example: String,
)

data class EpisodeQuizQuestion(
    val question: String,
    val options: List<String>,
)

private const val DEFAULT_DISPLAY_TITLE = "ニュースポッドキャスト"

/** 渡された list と後から共有しないための読み取り専用の複製。`toList()` は要素が 2 個以上で書き換え可能な list を返すので使わない。 */
private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))

private fun List<EpisodeQuizQuestion>.frozenQuiz(): List<EpisodeQuizQuestion> =
    map { EpisodeQuizQuestion(it.question, it.options.frozen()) }.frozen()

/**
 * 再生可能の判定（fail-closed）の正本（shared-session-08・shared-session-09）。
 *
 * 構築子と `copy` は外から呼べない。生成の入口は [Episode.create] だけで、各 variant の
 * `internal` の工場へ振り分ける（android-dec-18・android-dec-19）。
 * 出典（[sourceKind]・[sourceArticles]）は判別に使わない（android-dec-10）。
 */
sealed class Episode private constructor() : QueueItem {
    abstract override val id: String
    abstract val kind: EpisodeKind
    abstract val title: String
    abstract val japaneseIntroText: String
    abstract val difficultyCode: String
    abstract val durationSeconds: Int
    abstract val createdAt: String
    abstract val resumeHintSeconds: Double
    abstract val articleIds: List<String>

    /** `null` は「未提供」で、空 list と区別する（ADR-059）。 */
    abstract val transcript: List<EpisodeTranscriptSegment>?
    abstract val glossary: List<EpisodeGlossaryEntry>?
    abstract val quiz: List<EpisodeQuizQuestion>?
    abstract val sourceKind: EpisodeSourceKind
    abstract val sourceArticles: List<EpisodeSourceArticle>

    /** android-rule-catalog-02: 題名 → 日本語イントロ → 既定の文言。 */
    val displayTitle: String
        get() = when {
            title.trim().isNotEmpty() -> title.trim()
            japaneseIntroText.trim().isNotEmpty() -> japaneseIntroText.trim()
            else -> DEFAULT_DISPLAY_TITLE
        }

    @ConsistentCopyVisibility
    data class Playable private constructor(
        override val id: String,
        override val kind: EpisodeKind,
        override val title: String,
        override val japaneseIntroText: String,
        override val difficultyCode: String,
        override val durationSeconds: Int,
        override val createdAt: String,
        override val resumeHintSeconds: Double,
        override val articleIds: List<String>,
        override val transcript: List<EpisodeTranscriptSegment>?,
        override val glossary: List<EpisodeGlossaryEntry>?,
        override val quiz: List<EpisodeQuizQuestion>?,
        override val sourceKind: EpisodeSourceKind,
        override val sourceArticles: List<EpisodeSourceArticle>,
        val audioUrl: String,
    ) : Episode() {
        companion object {
            internal fun create(
                id: String,
                kind: EpisodeKind,
                title: String,
                japaneseIntroText: String,
                difficultyCode: String,
                durationSeconds: Int,
                createdAt: String,
                resumeHintSeconds: Double,
                articleIds: List<String>,
                transcript: List<EpisodeTranscriptSegment>?,
                glossary: List<EpisodeGlossaryEntry>?,
                quiz: List<EpisodeQuizQuestion>?,
                sourceKind: EpisodeSourceKind,
                sourceArticles: List<EpisodeSourceArticle>,
                audioUrl: String,
            ): Playable {
                require(audioUrl.isNotEmpty()) { "Playable には空でない音声 URL が要る" }
                return Playable(
                    id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                    resumeHintSeconds, articleIds.frozen(), transcript?.frozen(), glossary?.frozen(),
                    quiz?.frozenQuiz(), sourceKind, sourceArticles.frozen(), audioUrl,
                )
            }
        }
    }

    @ConsistentCopyVisibility
    data class Generating private constructor(
        override val id: String,
        override val kind: EpisodeKind,
        override val title: String,
        override val japaneseIntroText: String,
        override val difficultyCode: String,
        override val durationSeconds: Int,
        override val createdAt: String,
        override val resumeHintSeconds: Double,
        override val articleIds: List<String>,
        override val transcript: List<EpisodeTranscriptSegment>?,
        override val glossary: List<EpisodeGlossaryEntry>?,
        override val quiz: List<EpisodeQuizQuestion>?,
        override val sourceKind: EpisodeSourceKind,
        override val sourceArticles: List<EpisodeSourceArticle>,
    ) : Episode() {
        companion object {
            internal fun create(
                id: String,
                kind: EpisodeKind,
                title: String,
                japaneseIntroText: String,
                difficultyCode: String,
                durationSeconds: Int,
                createdAt: String,
                resumeHintSeconds: Double,
                articleIds: List<String>,
                transcript: List<EpisodeTranscriptSegment>?,
                glossary: List<EpisodeGlossaryEntry>?,
                quiz: List<EpisodeQuizQuestion>?,
                sourceKind: EpisodeSourceKind,
                sourceArticles: List<EpisodeSourceArticle>,
            ): Generating = Generating(
                id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                resumeHintSeconds, articleIds.frozen(), transcript?.frozen(), glossary?.frozen(),
                quiz?.frozenQuiz(), sourceKind, sourceArticles.frozen(),
            )
        }
    }

    @ConsistentCopyVisibility
    data class Failed private constructor(
        override val id: String,
        override val kind: EpisodeKind,
        override val title: String,
        override val japaneseIntroText: String,
        override val difficultyCode: String,
        override val durationSeconds: Int,
        override val createdAt: String,
        override val resumeHintSeconds: Double,
        override val articleIds: List<String>,
        override val transcript: List<EpisodeTranscriptSegment>?,
        override val glossary: List<EpisodeGlossaryEntry>?,
        override val quiz: List<EpisodeQuizQuestion>?,
        override val sourceKind: EpisodeSourceKind,
        override val sourceArticles: List<EpisodeSourceArticle>,
        /** 失敗の識別子。`null` は識別子なし（`""` は識別子あり: shared-session-08）。 */
        val errorMessage: String?,
    ) : Episode() {
        companion object {
            internal fun create(
                id: String,
                kind: EpisodeKind,
                title: String,
                japaneseIntroText: String,
                difficultyCode: String,
                durationSeconds: Int,
                createdAt: String,
                resumeHintSeconds: Double,
                articleIds: List<String>,
                transcript: List<EpisodeTranscriptSegment>?,
                glossary: List<EpisodeGlossaryEntry>?,
                quiz: List<EpisodeQuizQuestion>?,
                sourceKind: EpisodeSourceKind,
                sourceArticles: List<EpisodeSourceArticle>,
                errorMessage: String?,
            ): Failed = Failed(
                id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                resumeHintSeconds, articleIds.frozen(), transcript?.frozen(), glossary?.frozen(),
                quiz?.frozenQuiz(), sourceKind, sourceArticles.frozen(), errorMessage,
            )
        }
    }

    companion object {
        /**
         * 判別の規則（android-rule-catalog-01）。`Completed` かつ音声 URL が空でなく識別子が無いときだけ
         * `Playable`、`Processing` は `Generating`、それ以外（未知の status・矛盾する組を含む）は `Failed`。
         */
        fun create(
            id: String,
            kind: EpisodeKind,
            status: EpisodeStatus,
            audioUrl: String,
            errorMessage: String?,
            title: String,
            japaneseIntroText: String,
            difficultyCode: String,
            durationSeconds: Int,
            createdAt: String,
            resumeHintSeconds: Double,
            articleIds: List<String>,
            transcript: List<EpisodeTranscriptSegment>?,
            glossary: List<EpisodeGlossaryEntry>?,
            quiz: List<EpisodeQuizQuestion>?,
            sourceKind: EpisodeSourceKind,
            sourceArticles: List<EpisodeSourceArticle>,
        ): Episode = when {
            status == EpisodeStatus.Processing -> Generating.create(
                id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                resumeHintSeconds, articleIds, transcript, glossary, quiz, sourceKind, sourceArticles,
            )
            status == EpisodeStatus.Completed && audioUrl.isNotEmpty() && errorMessage == null -> Playable.create(
                id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                resumeHintSeconds, articleIds, transcript, glossary, quiz, sourceKind, sourceArticles, audioUrl,
            )
            else -> Failed.create(
                id, kind, title, japaneseIntroText, difficultyCode, durationSeconds, createdAt,
                resumeHintSeconds, articleIds, transcript, glossary, quiz, sourceKind, sourceArticles, errorMessage,
            )
        }
    }
}
