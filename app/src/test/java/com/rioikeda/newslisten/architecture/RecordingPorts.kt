@file:OptIn(ExperimentalCoroutinesApi::class)

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.designsystem.FeedbackHaptic
import com.rioikeda.newslisten.designsystem.FeedbackPlayer
import com.rioikeda.newslisten.designsystem.FeedbackSound
import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.DeleteVocabularyResponse
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.model.VocabularyTestResultResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.FileSystem
import com.rioikeda.newslisten.network.NetworkMonitoring
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.passkey.PasskeyProvider
import com.rioikeda.newslisten.podcast.PlaybackMetadata
import com.rioikeda.newslisten.podcast.PlaybackState
import com.rioikeda.newslisten.podcast.PlayerController
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject

/** 書込事象の由来。`DIRECT` は積んだ thread に、その実行単位の [ProbeCallMarker] の印が立っていた。 */
enum class Origin { DIRECT, BACKGROUND }

/** 書込事象 1 件。`port` は port 表の行の名前か `hook`、`op` は port の操作名か `<型名>#<field 名>`。 */
data class WriteEvent(val port: String, val op: String, val at: Long, val origin: Origin)

/** runner が今どの手順にいるか（recorder の段階）。runner だけが書く。 */
enum class ProbeStage { ASSEMBLY, WARM_UP, OBSERVE, CLEANUP }

/** 探査の失敗の段。`label` は message に出す日本語の名前。 */
enum class FailureStage(val label: String) {
    ASSEMBLY("組立"),
    WARM_UP("warm-up"),
    CALL("呼出"),
    BACKGROUND("背景"),
    CLEANUP("後始末"),
}

/** 探査の失敗 1 件。`throwable` は同じ例外のインスタンスを複数の受け口で受けたときの照合に使う。 */
data class ProbeFailure(val stage: FailureStage, val cause: String, val throwable: Throwable? = null)

/**
 * treatment の呼出の coroutine の文脈にだけ付ける印。その coroutine が thread の上で走る間だけ thread-local の印を立てる。
 * 値は実行単位ごとに新しく作り、別の実行単位の印を `DIRECT` と読まない。
 */
class ProbeCallMarker : ThreadContextElement<ProbeCallMarker?> {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): ProbeCallMarker? {
        val previous = current.get()
        current.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ProbeCallMarker?) {
        current.set(oldState)
    }

    /** この thread に、この印が立っているか。 */
    fun isSetOnThisThread(): Boolean = current.get() === this

    companion object Key : CoroutineContext.Key<ProbeCallMarker> {
        private val current = ThreadLocal<ProbeCallMarker?>()
    }
}

/** 1 つの実行単位の中の fake と hook が共有する記録。実行単位ごとに新しく作る。 */
class EffectRecorder(val scheduler: TestCoroutineScheduler, val marker: ProbeCallMarker) {
    /** runner だけが書く。recorder は段階 `ASSEMBLY` で作る。 */
    var stage: ProbeStage = ProbeStage.ASSEMBLY

    /** 積んだ書込事象（呼出の順）。runner が `t0` で空にしてから期限で読む。 */
    val writes: MutableList<WriteEvent> = mutableListOf()

    /** 積んだ探査の失敗。production が例外を握りつぶしても消えない。 */
    val failures: MutableList<ProbeFailure> = mutableListOf()

    /** scenario B で投げた [ApiException] のインスタンス（同一性で照合する）。 */
    val injected: MutableList<Throwable> = mutableListOf()

    /** 書込 1 回につき 1 件。引数の値に依らず、同値の書込も積む。`origin` は積む時点で決め、後から変えない。 */
    fun recordWrite(port: String, op: String) {
        val origin = if (marker.isSetOnThisThread()) Origin.DIRECT else Origin.BACKGROUND
        writes += WriteEvent(port, op, scheduler.currentTime, origin)
    }

    /**
     * 受け口が記録 fake か thread の handler のとき、積む時点の段階と thread の印から段を決める。
     * 観測の段階なら印があれば `呼出`、無ければ `背景`。それ以外の段階はその名前。
     */
    fun stageHere(): FailureStage = when (stage) {
        ProbeStage.ASSEMBLY -> FailureStage.ASSEMBLY
        ProbeStage.WARM_UP -> FailureStage.WARM_UP
        ProbeStage.CLEANUP -> FailureStage.CLEANUP
        ProbeStage.OBSERVE -> if (marker.isSetOnThisThread()) FailureStage.CALL else FailureStage.BACKGROUND
    }

    /** 失敗を積む。同じ例外のインスタンス（null でないもの）は 1 件にまとめる。 */
    fun fail(stage: FailureStage, cause: String, throwable: Throwable? = null) {
        if (throwable != null && failures.any { it.throwable === throwable }) return
        failures += ProbeFailure(stage, cause, throwable)
    }

    fun failHere(cause: String, throwable: Throwable? = null) = fail(stageHere(), cause, throwable)
}

/** 工場が返した関数オブジェクトの登録（同一性の表の値）。 */
data class HookRegistration(val label: String, val kind: HookKind, val shape: HookShape)

/**
 * hook の工場（8 関数）。実行単位ごとに新しく作り、WRITE の hook は自分を作った recorder にだけ積む。
 * 返した関数オブジェクトは同一性の表に登録し、[registrationOf] で照合できる。
 */
class Hooks(private val recorder: EffectRecorder) {
    private val registry = IdentityHashMap<Any, HookRegistration>()

    private fun <T : Any> register(function: T, label: String, kind: HookKind, shape: HookShape): T {
        registry[function] = HookRegistration(label, kind, shape)
        return function
    }

    private fun emit(label: String) = recorder.recordWrite(HOOK_PORT, label)

    fun write(label: String): () -> Unit {
        val function: () -> Unit = { emit(label) }
        return register(function, label, HookKind.WRITE, HookShape.NO_ARG)
    }

    fun <R> read(label: String, f: () -> R): () -> R {
        val function: () -> R = { f() }
        return register(function, label, HookKind.READ, HookShape.NO_ARG)
    }

    fun suspendWrite(label: String): suspend () -> Unit {
        val function: suspend () -> Unit = { emit(label) }
        return register(function, label, HookKind.WRITE, HookShape.SUSPEND_NO_ARG)
    }

    fun <R> suspendRead(label: String, f: suspend () -> R): suspend () -> R {
        val function: suspend () -> R = { f() }
        return register(function, label, HookKind.READ, HookShape.SUSPEND_NO_ARG)
    }

    fun <A> write1(label: String): (A) -> Unit {
        val function: (A) -> Unit = { _ -> emit(label) }
        return register(function, label, HookKind.WRITE, HookShape.ONE_ARG)
    }

    fun <A, R> read1(label: String, f: (A) -> R): (A) -> R {
        val function: (A) -> R = { a -> f(a) }
        return register(function, label, HookKind.READ, HookShape.ONE_ARG)
    }

    fun <A> suspendWrite1(label: String): suspend (A) -> Unit {
        val function: suspend (A) -> Unit = { _ -> emit(label) }
        return register(function, label, HookKind.WRITE, HookShape.SUSPEND_ONE_ARG)
    }

    fun <A, R> suspendRead1(label: String, f: suspend (A) -> R): suspend (A) -> R {
        val function: suspend (A) -> R = { a -> f(a) }
        return register(function, label, HookKind.READ, HookShape.SUSPEND_ONE_ARG)
    }

    /** 同一性の表を引く。工場が作った関数でなければ null。 */
    fun registrationOf(function: Any?): HookRegistration? = function?.let { registry[it] }

    companion object {
        const val HOOK_PORT = "hook"
    }
}

/**
 * scenario: 列の読みの canned の値・property の読みの初期値・warm-up の組の種類。
 *
 * - [canned] のキーは `<port>#<操作名>`（例 `ApiClient#fetchFeed`）。値の list を呼出ごとに 1 つ進めて返す。
 * - [initialProperties] のキーは `<port>#get<property 名>`（例 `NetworkMonitoring#getIsOnline`）。
 * - [readFailure] を与えると ApiClient 系と PodcastApi の列の読みがそのインスタンスを投げる（scenario B）。書込と property の読みは失敗しない。
 * - [warmUp] は所有者を組み立てた後、観測の窓の前に query と command を任意の順で呼ぶ。無ければ [noWarmUpReason] に理由を書く。
 */
data class ProbeScenario(
    val name: String,
    val canned: Map<String, List<Any?>> = emptyMap(),
    val initialProperties: Map<String, Any?> = emptyMap(),
    val readFailure: ApiException? = null,
    val warmUp: (suspend (owner: Any, env: ProbeEnvironment) -> Unit)? = null,
    val noWarmUpReason: String? = null,
)

/** 記録 fake の共通の道具。書込・列の読み・property の読みの 3 種の規則をここに集める。 */
internal abstract class RecordingFake(
    protected val port: String,
    protected val recorder: EffectRecorder,
    protected val scenario: ProbeScenario,
) {
    private val cursor = HashMap<String, Int>()

    /** 書込: 1 回 1 件積む。戻り値は呼び出し側が操作ごとの定数で返す。 */
    protected fun written(op: String) = recorder.recordWrite(port, op)

    /** 列の読み: canned の列を呼出ごとに 1 つ進める。尽きたら失敗を積んでから投げる。記録はしない。 */
    @Suppress("UNCHECKED_CAST")
    protected fun <T> sequenced(op: String): T {
        val key = "$port#$op"
        val list = scenario.canned[key]
        val index = cursor[key] ?: 0
        if (list == null || index >= list.size) {
            val message = "canned の列が尽きた: $key（${index + 1} 回目の読み。scenario ${scenario.name}）"
            recorder.failHere(message)
            throw IllegalStateException(message)
        }
        cursor[key] = index + 1
        return list[index] as T
    }

    /** scenario B の注入。ApiClient 系と PodcastApi の列の読みだけが呼ぶ。 */
    protected fun injectedFailure() {
        scenario.readFailure?.let {
            if (recorder.injected.none { existing -> existing === it }) recorder.injected += it
            throw it
        }
    }

    protected fun <T> initial(getter: String, default: T): T {
        val key = "$port#$getter"
        @Suppress("UNCHECKED_CAST")
        return if (scenario.initialProperties.containsKey(key)) scenario.initialProperties[key] as T else default
    }
}

/** 記録 fake の書込が返す固定の既定値と、property の読みの既定の初期値（操作ごとの定数）。 */
object RecordingDefaults {
    val userResponse: UserResponse = UserResponse(username = "default-user", role = "user", displayName = "Default User")
    val loginResponse: LoginResponse = LoginResponse(token = "default-token", user = userResponse)
    val podcastResponse: PodcastResponse = PodcastResponse(
        id = "default-podcast",
        type = "daily",
        articleIds = emptyList(),
        difficulty = "intermediate",
        audioUrl = "",
        japaneseIntroText = "",
        durationSeconds = 0,
        status = "completed",
        createdAt = "2026-01-01T00:00:00Z",
    )
    val episode: Episode = EpisodeDecoder.decode(podcastResponse)
    val actionResponse: ActionResponse = ActionResponse(status = "ok", articleId = "default-article")
    val quizAnswerResponse: QuizAnswerResponse =
        QuizAnswerResponse(correctCount = 0, total = 0, correctRate = 0.0, results = emptyList())
    val rssSourcesResponse: RssSourcesResponse = RssSourcesResponse(sources = emptyList())
    val preferencesResponse: PreferencesResponse = PreferencesResponse(
        defaultDifficulty = Difficulty.DEFAULT.code,
        defaultPlaybackSpeed = 1.0,
        digestEnabled = false,
        digestArticleCount = 0,
        weeklyGoalEpisodes = 3,
    )
    val vocabularyItemResponse: VocabularyItemResponse = VocabularyItemResponse(
        vocabularyId = "default-vocabulary",
        podcastId = "default-podcast",
        term = "term",
        meaning = "meaning",
        example = "example",
        registeredAt = "2026-01-01T00:00:00Z",
    )
    val deleteVocabularyResponse: DeleteVocabularyResponse =
        DeleteVocabularyResponse(status = "ok", vocabularyId = "default-vocabulary")
    val vocabularyTestResultResponse: VocabularyTestResultResponse = VocabularyTestResultResponse(updated = 0)
    val revokeSessionsResponse: RevokeSessionsResponse = RevokeSessionsResponse(revokedCount = 0)
    val onboardingStatusResponse: OnboardingStatusResponse = OnboardingStatusResponse(onboardingCompleted = false)
    val passkeyOptionsResponse: PasskeyOptionsResponse = PasskeyOptionsResponse(challengeId = "default-challenge", options = "{}")

    const val PASSKEY_CREDENTIAL: String = "{}"
    const val INITIAL_SFX_ENABLED: Boolean = true
    const val INITIAL_HAPTICS_ENABLED: Boolean = true
    const val INITIAL_POSITION_SECONDS: Double = 0.0
    const val INITIAL_IS_ONLINE: Boolean = true
}

internal class ApiFake(recorder: EffectRecorder, scenario: ProbeScenario, port: String = "ApiClient") :
    RecordingFake(port, recorder, scenario) {

    /** 列の読み。scenario B では注入した例外を投げる。 */
    fun <T> read(op: String): T {
        injectedFailure()
        return sequenced(op)
    }

    fun write(op: String) = written(op)
}

/** 再生 port [PodcastApi] の記録 fake。列の読みは [ApiFake.read] と同じく先頭で注入された失敗を投げる。 */
class RecordingPodcastApi(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : PodcastApi {
    private val core = ApiFake(recorder, scenario, port = "PodcastApi")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): Episode {
        core.write("updatePlaybackPosition")
        return RecordingDefaults.episode
    }

    override suspend fun markCompleted(id: String) = core.write("markCompleted")

    override suspend fun fetchPodcasts(): List<Episode> = core.read("fetchPodcasts")

    override suspend fun fetchPodcast(id: String): Episode = core.read("fetchPodcast")

    override suspend fun downloadAudio(url: String): ByteArray = core.read("downloadAudio")
}

/** ApiClient 系の 4 つの interface の記録 fake。`BaseFakeApiClient` は継承しない（`error(...)` を引き継がない）。 */
class RecordingApiClient(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : ApiClient {
    private val core = ApiFake(recorder, scenario)

    // ---- 書込（canned を使わず、操作ごとの固定の既定値を返す。どの scenario でも失敗しない） ----
    override suspend fun login(username: String, password: String): LoginResponse {
        core.write("login")
        return RecordingDefaults.loginResponse
    }

    override suspend fun logout() = core.write("logout")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse {
        core.write("starArticle")
        return RecordingDefaults.actionResponse
    }

    override suspend fun dismissArticle(id: String): ActionResponse {
        core.write("dismissArticle")
        return RecordingDefaults.actionResponse
    }

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse {
        core.write("updatePlaybackPosition")
        return RecordingDefaults.podcastResponse
    }

    override suspend fun markCompleted(id: String) = core.write("markCompleted")

    override suspend fun submitQuizAnswers(id: String, request: QuizAnswerRequest): QuizAnswerResponse {
        core.write("submitQuizAnswers")
        return RecordingDefaults.quizAnswerResponse
    }

    override suspend fun registerDeviceToken(token: String, platform: String) = core.write("registerDeviceToken")

    override suspend fun unregisterDeviceToken(token: String, platform: String) = core.write("unregisterDeviceToken")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse {
        core.write("createSource")
        return RecordingDefaults.rssSourcesResponse
    }

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse {
        core.write("updateSource")
        return RecordingDefaults.rssSourcesResponse
    }

    override suspend fun deleteSource(url: String): RssSourcesResponse {
        core.write("deleteSource")
        return RecordingDefaults.rssSourcesResponse
    }

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse {
        core.write("updatePreferences")
        return RecordingDefaults.preferencesResponse
    }

    override suspend fun updateWeeklyGoalEpisodes(weeklyGoalEpisodes: Int): PreferencesResponse {
        core.write("updateWeeklyGoalEpisodes")
        return RecordingDefaults.preferencesResponse
    }

    override suspend fun saveVocabulary(podcastId: String, term: String): VocabularyItemResponse {
        core.write("saveVocabulary")
        return RecordingDefaults.vocabularyItemResponse
    }

    override suspend fun deleteVocabulary(vocabularyId: String): DeleteVocabularyResponse {
        core.write("deleteVocabulary")
        return RecordingDefaults.deleteVocabularyResponse
    }

    override suspend fun submitVocabularyTestResults(results: List<VocabularyTestResultItemRequest>): VocabularyTestResultResponse {
        core.write("submitVocabularyTestResults")
        return RecordingDefaults.vocabularyTestResultResponse
    }

    override suspend fun updateProfile(displayName: String): UserResponse {
        core.write("updateProfile")
        return RecordingDefaults.userResponse
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) = core.write("changePassword")

    override suspend fun revokeSession(id: String) = core.write("revokeSession")

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse {
        core.write("revokeOtherSessions")
        return RecordingDefaults.revokeSessionsResponse
    }

    override suspend fun reportClientError(report: ClientErrorReport) = core.write("reportClientError")

    override suspend fun completeOnboarding(): OnboardingStatusResponse {
        core.write("completeOnboarding")
        return RecordingDefaults.onboardingStatusResponse
    }

    override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse {
        core.write("passkeyRegisterOptions")
        return RecordingDefaults.passkeyOptionsResponse
    }

    override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject) = core.write("passkeyRegisterVerify")

    override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse {
        core.write("passkeyLoginOptions")
        return RecordingDefaults.passkeyOptionsResponse
    }

    override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse {
        core.write("passkeyLoginVerify")
        return RecordingDefaults.loginResponse
    }

    override suspend fun deletePasskeyCredential(credentialId: String) = core.write("deletePasskeyCredential")

    // ---- 列の読み（canned の列を呼出ごとに 1 つ進める。scenario B では注入した例外を投げる） ----
    override suspend fun me(): UserResponse = core.read("me")

    override suspend fun fetchFeed(filter: String): FeedResponse = core.read("fetchFeed")

    override suspend fun fetchPodcasts(): PodcastListResponse = core.read("fetchPodcasts")

    override suspend fun fetchPodcast(id: String): PodcastResponse = core.read("fetchPodcast")

    override suspend fun fetchPreferences(): PreferencesResponse = core.read("fetchPreferences")

    override suspend fun downloadAudio(url: String): ByteArray = core.read("downloadAudio")

    override suspend fun fetchSources(): RssSourcesResponse = core.read("fetchSources")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse = core.read("fetchFeaturedSites")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse = core.read("fetchGenerationQuota")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse = core.read("fetchListeningStreak")

    override suspend fun fetchLearningDashboard(): LearningDashboardResponse = core.read("fetchLearningDashboard")

    override suspend fun fetchVocabulary(): VocabularyListResponse = core.read("fetchVocabulary")

    override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse = core.read("fetchVocabularyTestSession")

    override suspend fun listSessions(): SessionsListResponse = core.read("listSessions")

    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse = core.read("fetchOnboardingStatus")

    override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse = core.read("listPasskeyCredentials")
}

internal class PreferencesFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("PreferencesStore", recorder, scenario) {
    fun write(op: String) = written(op)

    fun <T> property(getter: String, default: T): T = initial(getter, default)
}

class RecordingPreferencesStore(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : PreferencesStore {
    private val core = PreferencesFake(recorder, scenario)

    override val defaultDifficulty: StateFlow<String> =
        MutableStateFlow(core.property("getDefaultDifficulty", Difficulty.DEFAULT.code))
    override val defaultPlaybackSpeed: StateFlow<Double> = MutableStateFlow(core.property("getDefaultPlaybackSpeed", 1.0))
    override val articleOpenMode: StateFlow<ArticleOpenMode> =
        MutableStateFlow(core.property("getArticleOpenMode", ArticleOpenMode.DEFAULT))
    override val timeFormat: StateFlow<TimeFormat> = MutableStateFlow(core.property("getTimeFormat", TimeFormat.DEFAULT))
    override val sfxEnabled: StateFlow<Boolean> =
        MutableStateFlow(core.property("getSfxEnabled", RecordingDefaults.INITIAL_SFX_ENABLED))
    override val hapticsEnabled: StateFlow<Boolean> =
        MutableStateFlow(core.property("getHapticsEnabled", RecordingDefaults.INITIAL_HAPTICS_ENABLED))
    override val weeklyGoalEpisodes: StateFlow<Int> = MutableStateFlow(core.property("getWeeklyGoalEpisodes", 3))
    override val seenAchievementIds: StateFlow<Set<String>> =
        MutableStateFlow(core.property("getSeenAchievementIds", emptySet()))

    override suspend fun setDefaultDifficulty(code: String) = core.write("setDefaultDifficulty")

    override suspend fun setDefaultPlaybackSpeed(speed: Double) = core.write("setDefaultPlaybackSpeed")

    override suspend fun setArticleOpenMode(mode: ArticleOpenMode) = core.write("setArticleOpenMode")

    override suspend fun setTimeFormat(format: TimeFormat) = core.write("setTimeFormat")

    override suspend fun setSfxEnabled(enabled: Boolean) = core.write("setSfxEnabled")

    override suspend fun setHapticsEnabled(enabled: Boolean) = core.write("setHapticsEnabled")

    override suspend fun setWeeklyGoalEpisodes(episodes: Int) = core.write("setWeeklyGoalEpisodes")

    override suspend fun markAchievementsSeen(ids: Set<String>) = core.write("markAchievementsSeen")
}

internal class SessionFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("SessionStore", recorder, scenario) {
    fun write(op: String) = written(op)

    fun <T> read(op: String): T = sequenced(op)
}

class RecordingSessionStore(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : SessionStore {
    private val core = SessionFake(recorder, scenario)

    override fun save(token: String): Boolean {
        core.write("save")
        return true
    }

    override fun load(): String? = core.read("load")

    override fun clear() = core.write("clear")
}

internal class PlayerFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("PlayerController", recorder, scenario) {
    fun write(op: String) = written(op)

    fun <T> property(getter: String, default: T): T = initial(getter, default)
}

class RecordingPlayerController(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : PlayerController {
    private val core = PlayerFake(recorder, scenario)

    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(core.property("getIsPlaying", false))
    override val positionSeconds: StateFlow<Double> =
        MutableStateFlow(core.property("getPositionSeconds", RecordingDefaults.INITIAL_POSITION_SECONDS))
    override val durationSeconds: StateFlow<Double?> = MutableStateFlow(core.property<Double?>("getDurationSeconds", null))
    override val playbackSpeed: StateFlow<Float> = MutableStateFlow(core.property("getPlaybackSpeed", 1.0f))
    override val state: StateFlow<PlaybackState> = MutableStateFlow(core.property<PlaybackState>("getState", PlaybackState.Idle))

    override var onPlaybackCompleted: (() -> Unit)? = null
        set(value) {
            core.write("setOnPlaybackCompleted")
            field = value
        }

    override fun prepare(url: String, metadata: PlaybackMetadata) = core.write("prepare")

    override fun play() = core.write("play")

    override fun pause() = core.write("pause")

    override fun seekTo(seconds: Double) = core.write("seekTo")

    override fun setSpeed(speed: Float) = core.write("setSpeed")

    override fun stop() = core.write("stop")

    override fun release() = core.write("release")
}

internal class FileFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("FileSystem", recorder, scenario) {
    fun write(op: String) = written(op)

    fun <T> read(op: String): T = sequenced(op)
}

class RecordingFileSystem(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : FileSystem {
    private val core = FileFake(recorder, scenario)

    override fun exists(path: String): Boolean = core.read("exists")

    override fun write(path: String, bytes: ByteArray) = core.write("write")

    override fun delete(path: String): Boolean {
        core.write("delete")
        return true
    }

    override fun listFiles(dirPath: String): List<String> = core.read("listFiles")

    override fun fileSize(path: String): Long? = core.read("fileSize")

    override fun ensureDirectory(dirPath: String) = core.write("ensureDirectory")
}

internal class NetworkFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("NetworkMonitoring", recorder, scenario) {
    fun write(op: String) = written(op)

    fun <T> property(getter: String, default: T): T = initial(getter, default)
}

class RecordingNetworkMonitor(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : NetworkMonitoring {
    private val core = NetworkFake(recorder, scenario)

    override val isOnline: StateFlow<Boolean> =
        MutableStateFlow(core.property("getIsOnline", RecordingDefaults.INITIAL_IS_ONLINE))

    override fun start() = core.write("start")

    override fun stop() = core.write("stop")
}

internal class PasskeyFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("PasskeyProvider", recorder, scenario) {
    fun write(op: String) = written(op)
}

class RecordingPasskeyProvider(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : PasskeyProvider {
    private val core = PasskeyFake(recorder, scenario)

    override suspend fun createCredential(optionsJson: String): String {
        core.write("createCredential")
        return RecordingDefaults.PASSKEY_CREDENTIAL
    }

    override suspend fun getCredential(optionsJson: String): String {
        core.write("getCredential")
        return RecordingDefaults.PASSKEY_CREDENTIAL
    }
}

internal class FeedbackFake(recorder: EffectRecorder, scenario: ProbeScenario) :
    RecordingFake("FeedbackPlayer", recorder, scenario) {
    fun write(op: String) = written(op)
}

class RecordingFeedbackPlayer(
    val recorder: EffectRecorder,
    val scenario: ProbeScenario,
) : FeedbackPlayer {
    private val core = FeedbackFake(recorder, scenario)

    override fun playSound(sound: FeedbackSound) = core.write("playSound")

    override fun performHaptic(haptic: FeedbackHaptic) = core.write("performHaptic")
}

/**
 * 1 つの実行単位の環境。scheduler → 印 → recorder → 記録 fake と hooks の順に新しく作る。
 * 組立の lambda はこの環境から取ったオブジェクトと、lambda の中で新しく作るオブジェクトだけでグラフを組む。
 */
class ProbeEnvironment private constructor(val scenario: ProbeScenario) {
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
    val marker: ProbeCallMarker = ProbeCallMarker()
    val recorder: EffectRecorder = EffectRecorder(scheduler, marker)
    val hooks: Hooks = Hooks(recorder)

    val apiClient: RecordingApiClient = RecordingApiClient(recorder, scenario)
    val podcastApi: RecordingPodcastApi = RecordingPodcastApi(recorder, scenario)
    val preferencesStore: RecordingPreferencesStore = RecordingPreferencesStore(recorder, scenario)
    val sessionStore: RecordingSessionStore = RecordingSessionStore(recorder, scenario)
    val playerController: RecordingPlayerController = RecordingPlayerController(recorder, scenario)
    val fileSystem: RecordingFileSystem = RecordingFileSystem(recorder, scenario)
    val networkMonitor: RecordingNetworkMonitor = RecordingNetworkMonitor(recorder, scenario)
    val passkeyProvider: RecordingPasskeyProvider = RecordingPasskeyProvider(recorder, scenario)
    val feedbackPlayer: RecordingFeedbackPlayer = RecordingFeedbackPlayer(recorder, scenario)

    /** この実行単位の記録 fake の全部。配線の検査が「記録 fake であること」を照合する。 */
    val fakes: List<Any> = listOf(
        apiClient, podcastApi, preferencesStore, sessionStore, playerController,
        fileSystem, networkMonitor, passkeyProvider, feedbackPlayer,
    )

    /** この実行単位の scheduler に載る dispatcher。所有者の dispatcher は必ずこれで作る。 */
    fun newDispatcher(): CoroutineDispatcher = StandardTestDispatcher(scheduler)

    companion object {
        fun create(scenario: ProbeScenario): ProbeEnvironment = ProbeEnvironment(scenario)
    }
}
