package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeKind
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceArticle
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceKind
import com.rioikeda.newslisten.catalog.domain.EpisodeStatus
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PodcastSourceArticleResponse

/**
 * DTO の文字列を domain の語へ写してから [Episode.create] を呼ぶ（android-dec-17）。
 * 判別は domain が持つので、ここでは variant の工場を直接呼ばない。
 */
object EpisodeDecoder {
    fun decode(response: PodcastResponse): Episode = Episode.create(
        id = response.id,
        kind = kindOf(response.type),
        status = statusOf(response.status),
        audioUrl = response.audioUrl,
        errorMessage = response.errorMessage,
        title = response.title,
        japaneseIntroText = response.japaneseIntroText,
        difficultyCode = response.difficulty,
        durationSeconds = response.durationSeconds,
        createdAt = response.createdAt,
        resumeHintSeconds = response.playbackPositionSeconds,
        articleIds = response.articleIds,
        transcript = response.segments?.map { EpisodeTranscriptSegment(it.speaker, it.text, it.role) },
        glossary = response.vocabulary?.map { EpisodeGlossaryEntry(it.term, it.meaningJa, it.example) },
        quiz = response.quiz?.map { EpisodeQuizQuestion(it.question, it.options) },
        sourceKind = sourceKindOf(response.sourceKind),
        sourceArticles = response.sourceArticles.orEmpty().map(::sourceArticleOf),
    )

    private fun kindOf(type: String): EpisodeKind =
        if (type == "digest") EpisodeKind.Digest else EpisodeKind.Single

    private fun statusOf(status: String): EpisodeStatus = when (status) {
        "completed" -> EpisodeStatus.Completed
        "processing" -> EpisodeStatus.Processing
        "failed" -> EpisodeStatus.Failed
        "partial_failed" -> EpisodeStatus.PartialFailed
        else -> EpisodeStatus.Unknown
    }

    private fun sourceKindOf(sourceKind: String?): EpisodeSourceKind = when (sourceKind) {
        "featured" -> EpisodeSourceKind.Featured
        "user" -> EpisodeSourceKind.User
        else -> EpisodeSourceKind.Unknown
    }

    private fun sourceArticleOf(article: PodcastSourceArticleResponse): EpisodeSourceArticle =
        EpisodeSourceArticle(
            articleId = article.articleId.orEmpty(),
            title = article.title.orEmpty(),
            url = article.url.orEmpty(),
            source = article.source.orEmpty(),
        )
}
