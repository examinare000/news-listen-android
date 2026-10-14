package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.model.AchievementResponse
import com.rioikeda.newslisten.model.ArticleResponse
import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.QuizStatsResponse
import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import com.rioikeda.newslisten.network.ApiException

/**
 * 各 context の probe set が使う canned の値と scenario の道具。
 *
 * canned の列は、読みの回数（組立・warm-up・treatment の 2 回の呼出・期限までの背景処理の合計）より十分長く置く。
 * 尽きれば記録 fake が `ProbeFailure` を積むので、長さの不足は黙って通らない。
 */
internal object ProbeData {

    /** 列の読みの canned の長さ。 */
    private const val DEPTH = 24

    fun <T> repeated(value: T, count: Int = DEPTH): List<Any?> = List(count) { value }

    val article: ArticleResponse = ArticleResponse(
        id = "article-1",
        title = "A probe article",
        url = "https://example.com/articles/1",
        source = "example",
        score = 0.9,
        publishedAt = "2026-10-14T00:00:00Z",
    )

    val feed: FeedResponse = FeedResponse(articles = listOf(article), date = "2026-10-14")

    val podcast: PodcastResponse = PodcastResponse(
        id = "podcast-1",
        type = "daily",
        articleIds = listOf("article-1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/audio/podcast-1.mp3",
        japaneseIntroText = "",
        durationSeconds = 120,
        status = "completed",
        createdAt = "2026-10-14T00:00:00Z",
    )

    val podcastList: PodcastListResponse = PodcastListResponse(podcasts = listOf(podcast))

    val user: UserResponse = UserResponse(username = "probe-user", role = "user", displayName = "Probe User")

    val vocabularyItem: VocabularyItemResponse = VocabularyItemResponse(
        vocabularyId = "vocabulary-1",
        podcastId = "podcast-1",
        term = "term",
        meaning = "meaning",
        example = "example sentence",
        registeredAt = "2026-10-14T00:00:00Z",
    )

    val vocabularyList: VocabularyListResponse = VocabularyListResponse(vocabulary = listOf(vocabularyItem), count = 1)

    val vocabularyTestSession: VocabularyTestSessionResponse = VocabularyTestSessionResponse(
        items = listOf(
            VocabularyTestItemResponse(
                vocabularyId = "vocabulary-1",
                term = "term",
                meaning = "meaning",
                example = "example sentence",
                distractors = listOf("other-1", "other-2", "other-3"),
            ),
        ),
    )

    val dashboard: LearningDashboardResponse = LearningDashboardResponse(
        streak = ListeningStreakResponse(currentStreakDays = 1, todayListened = true, lastListenedDay = "2026-10-14"),
        totalEpisodes = 1,
        vocabularyAcquired = 1,
        quiz = QuizStatsResponse(quizzedEpisodes = 0, averageCorrectRate = null, trend = emptyList()),
        monthlyActivity = emptyList(),
        currentDifficulty = "toeic_600",
        weeklyGoal = null,
        achievements = listOf(AchievementResponse(id = "first-listen", unlockedAt = "2026-10-14T00:00:00Z")),
    )

    val featuredSite: FeaturedSite = FeaturedSite(id = "site-1", name = "Example", url = "https://example.com/rss")

    val featuredSites: FeaturedSitesResponse = FeaturedSitesResponse(sites = listOf(featuredSite))

    val sources: RssSourcesResponse = RssSourcesResponse(sources = listOf(RssSource(name = "Example", url = "https://example.com/rss")))

    val generationQuota: GenerationQuotaResponse =
        GenerationQuotaResponse(limit = 3, used = 1, remaining = 2, resetAt = "2026-10-15T00:00:00Z")

    val onboardingStatus: OnboardingStatusResponse = OnboardingStatusResponse(onboardingCompleted = false)

    val sessions: SessionsListResponse = SessionsListResponse(
        sessions = listOf(
            SessionResponse(id = "session-current", deviceLabel = "this device", createdAt = "2026-10-14T00:00:00Z", lastUsedAt = null, current = true),
            SessionResponse(id = "session-other", deviceLabel = "other device", createdAt = "2026-10-13T00:00:00Z", lastUsedAt = null, current = false),
        ),
    )

    val passkeyCredentials: PasskeyCredentialsListResponse = PasskeyCredentialsListResponse(
        credentials = listOf(
            PasskeyCredential(
                credentialId = "credential-1",
                username = "probe-user",
                name = null,
                transports = emptyList(),
                aaguid = null,
                signCount = 0,
                createdAt = "2026-10-14T00:00:00Z",
                lastUsedAt = null,
            ),
        ),
    )

    /** 聴取日数が呼ぶたびに 1 日ずつ増える列（初回より 2 回目が大きい）。 */
    fun increasingStreaks(): List<Any?> = List(DEPTH) {
        ListeningStreakResponse(currentStreakDays = it + 1, todayListened = true, lastListenedDay = "2026-10-14")
    }

    /** scenario A（読み成功）。`warmUp` が無ければ、無い理由が要る。 */
    fun readSuccess(
        canned: Map<String, List<Any?>> = emptyMap(),
        warmUp: (suspend (Any, ProbeEnvironment) -> Unit)? = null,
        noWarmUpReason: String? = null,
        name: String = "A",
    ): ProbeScenario = ProbeScenario(
        name = name,
        canned = canned,
        warmUp = warmUp,
        noWarmUpReason = if (warmUp == null) noWarmUpReason ?: "warm-up を要する保留・開始済みの処理が無い基本形" else null,
    )

    /** scenario B（ApiClient 系の列の読みが `ApiException.HttpError(500)`。書込と property の読みは失敗しない）。 */
    fun readFailure(
        canned: Map<String, List<Any?>> = emptyMap(),
        warmUp: (suspend (Any, ProbeEnvironment) -> Unit)? = null,
        noWarmUpReason: String? = null,
    ): ProbeScenario = ProbeScenario(
        name = "B",
        canned = canned,
        readFailure = ApiException.HttpError(500),
        warmUp = warmUp,
        noWarmUpReason = if (warmUp == null) noWarmUpReason ?: "読みが失敗する scenario では、成功を前提にした warm-up を置かない" else null,
    )
}
