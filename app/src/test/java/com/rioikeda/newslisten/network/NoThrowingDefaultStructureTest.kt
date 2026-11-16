package com.rioikeda.newslisten.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * production interface（`ApiClient`）から throwing default を排除したことの構造検査（android-contract-18）。
 *
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §4 android-contract-18。
 */
class NoThrowingDefaultStructureTest {

    /**
     * main ソースセットの走査ルートを解決する（Gradle の作業ディレクトリが module か root かに
     * 依存しないよう候補を複数持つ）。
     * ルートが解決できない、`.kt` が1件もない、`ApiClient.kt` を含まない場合は
     * vacuous pass（0件のまま緑になる誤判定）を防ぐためここで fail する。
     */
    private fun resolveMainSourceRoot(): File {
        val candidates = listOf(
            File("app/src/main/java/com/rioikeda/newslisten"),
            File("src/main/java/com/rioikeda/newslisten"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error(
                "走査ルートが見つからない（cwd=${File(".").canonicalPath}）。" +
                    "候補ディレクトリのいずれも存在しない"
            )
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(ktFiles.isNotEmpty()) {
            "走査ルート $root に .kt ファイルが1件もない（vacuous pass ガード）"
        }
        check(ktFiles.any { it.name == "ApiClient.kt" }) {
            "走査ルート $root に network/ApiClient.kt が見つからない（vacuous pass ガード）"
        }
        return root
    }

    @Test
    fun mainソースセット全体でthrowing_defaultのerror呼出が0件() {
        // verifies: android-contract-18
        val root = resolveMainSourceRoot()
        val hits = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("error(")) {
                        hits += "${file.path}:${index + 1}"
                    }
                }
            }
        assertTrue(
            "throwing default の error( 呼出が main ソースセットに残っている: $hits",
            hits.isEmpty(),
        )
    }

    @Test
    fun EpisodeCatalogは再生5操作をpodcastApi経由で呼びPodcastViewModelはpodcastApiもapiClientも呼ばない() {
        // verifies: android-contract-18
        val root = resolveMainSourceRoot()
        val catalogFile = root.walkTopDown().firstOrNull { it.name == "EpisodeCatalog.kt" }
            ?: error("走査ルート $root に EpisodeCatalog.kt が見つからない")
        val viewModelFile = root.walkTopDown().firstOrNull { it.name == "PodcastViewModel.kt" }
            ?: error("走査ルート $root に PodcastViewModel.kt が見つからない")
        val catalogText = catalogFile.readText()
        val viewModelText = viewModelFile.readText()

        val playbackOps = setOf(
            "fetchPodcasts", "fetchPodcast", "updatePlaybackPosition", "markCompleted", "downloadAudio",
        )

        val catalogPodcastApiCalls = Regex("""\bpodcastApi\.(\w+)\(""")
            .findAll(catalogText).map { it.groupValues[1] }.toSet()
        val viewModelPodcastApiCalls = Regex("""\bpodcastApi\.(\w+)\(""")
            .findAll(viewModelText).map { it.groupValues[1] }.toSet()
        val viewModelApiClientCalls = Regex("""\bapiClient\.(\w+)\(""")
            .findAll(viewModelText).map { it.groupValues[1] }.toSet()

        assertTrue(
            "EpisodeCatalog の podcastApi 経由の呼出は空でなく再生5操作のみであるべき（実際: $catalogPodcastApiCalls）",
            catalogPodcastApiCalls.isNotEmpty() && catalogPodcastApiCalls.all { it in playbackOps },
        )
        assertTrue(
            "PodcastViewModel は podcastApi を呼ばない（実際: $viewModelPodcastApiCalls）",
            viewModelPodcastApiCalls.isEmpty(),
        )
        assertTrue(
            "PodcastViewModel は apiClient を呼ばない（実際: $viewModelApiClientCalls）",
            viewModelApiClientCalls.isEmpty(),
        )
    }

    @Test
    fun AppContainerはEpisodeCatalogのpodcastApiへPodcastApiAdapterを渡す() {
        // verifies: android-contract-18
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val text = file.readText()

        val callStart = text.indexOf("EpisodeCatalog(")
        check(callStart >= 0) { "${file.path} に EpisodeCatalog( の呼び出しが見つからない（vacuous pass ガード）" }
        val call = text.substring(callStart)
        val podcastApiArg = Regex("""podcastApi\s*=\s*(\w+)\(""").find(call)
        assertTrue(
            "podcastApi = の引数が PodcastApiAdapter( で始まっていない（実際: ${podcastApiArg?.groupValues?.get(1)}）",
            podcastApiArg?.groupValues?.get(1) == "PodcastApiAdapter",
        )
    }

    @Test
    fun AppContainerはPlaybackCoordinatorのpodcastApiへPodcastApiAdapterを渡す() {
        // verifies: android-contract-18 / android-dec-60 (c)。ApiClient が PodcastApi を継承しない契約の対象は Coordinator にも及ぶ。
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val text = file.readText()

        val callStart = text.indexOf("PlaybackCoordinator(")
        check(callStart >= 0) { "${file.path} に PlaybackCoordinator( の呼び出しが見つからない（vacuous pass ガード）" }
        val call = text.substring(callStart)
        val podcastApiArg = Regex("""podcastApi\s*=\s*(\w+)\(""").find(call)
        assertTrue(
            "podcastApi = の引数が PodcastApiAdapter( で始まっていない（実際: ${podcastApiArg?.groupValues?.get(1)}）",
            podcastApiArg?.groupValues?.get(1) == "PodcastApiAdapter",
        )
    }

    @Test
    fun ApiClientは再生5操作のportであるPodcastApiを継承しない() {
        // verifies: android-contract-18
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "ApiClient.kt" }
            ?: error("走査ルート $root に ApiClient.kt が見つからない")
        val text = file.readText()

        val declStart = text.indexOf("interface ApiClient")
        check(declStart >= 0) { "${file.path} に interface ApiClient の宣言が見つからない（vacuous pass ガード）" }
        val bodyStart = text.indexOf('{', declStart)
        check(bodyStart > declStart) { "${file.path} の interface ApiClient に本体の { が見つからない" }
        val supertypes = text.substring(declStart, bodyStart)

        assertFalse(
            "interface ApiClient の上位型に PodcastApi が残っている: $supertypes",
            Regex("""\bPodcastApi\b""").containsMatchIn(supertypes),
        )
    }

    @Test
    fun ApiClientは単語テストのportを継承しない() {
        // verifies: android-slice-learning-03 旧い port の撤去。LearningApi・VocabularyTestApi は learning/app の port に移った
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "ApiClient.kt" }
            ?: error("走査ルート $root に ApiClient.kt が見つからない")
        val text = file.readText()

        val declStart = text.indexOf("interface ApiClient")
        check(declStart >= 0) { "${file.path} に interface ApiClient の宣言が見つからない（vacuous pass ガード）" }
        val bodyStart = text.indexOf('{', declStart)
        check(bodyStart > declStart) { "${file.path} の interface ApiClient に本体の { が見つからない" }
        val supertypes = text.substring(declStart, bodyStart)

        assertFalse(
            "interface ApiClient の上位型に LearningApi / VocabularyTestApi が残っている: $supertypes",
            Regex("""\b(LearningApi|VocabularyTestApi)\b""").containsMatchIn(supertypes),
        )
    }

    @Test
    fun AppContainerは単語テストの入口を呼ぶたびに新しく作りbylazyで持たない() {
        // verifies: android-dec-106。単語テストは開くたびに新しい VocabularyTestCommands を持つ
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val text = file.readText()

        val funStart = text.indexOf("fun createVocabularyTestViewModel")
        check(funStart >= 0) { "${file.path} に fun createVocabularyTestViewModel が見つからない（vacuous pass ガード）" }
        val nextFun = text.indexOf("fun ", funStart + 1)
        val body = if (nextFun > funStart) text.substring(funStart, nextFun) else text.substring(funStart)

        assertTrue(
            "createVocabularyTestViewModel の本体が VocabularyTestCommands( を構築していない",
            body.contains("VocabularyTestCommands("),
        )
        assertFalse(
            "VocabularyTestCommands を by lazy の 1 instance で持っている",
            Regex("""\bVocabularyTestCommands\b[^\n]*by\s+lazy|by\s+lazy[^\n]*\bVocabularyTestCommands\b""")
                .containsMatchIn(text),
        )
    }

    @Test
    fun AppContainerはFeedStateを1つだけ作りFeedCommandsとFeedQueriesへ同じ値を渡す() {
        // verifies: android-dec-108。状態を 2 つ作ると、画面が読む一覧と Star が除く一覧が別になり画面に何も起きない
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val code = file.readLines()
            .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
            .joinToString("\n")

        val stateConstructions = Regex("""\bFeedState\(""").findAll(code).count()
        check(stateConstructions > 0) { "${file.path} に FeedState( の構築が見つからない（vacuous pass ガード）" }
        assertTrue("FeedState( の構築が 1 回でない（実際: $stateConstructions 回）", stateConstructions == 1)

        val stateVal = Regex("""\bval\s+(\w+)\s*(?::\s*FeedState)?\s*(?:=|by\s+lazy\s*\{)\s*FeedState\(""")
            .find(code)?.groupValues?.get(1)
        check(stateVal != null) { "${file.path} に FeedState( を初期化子に持つ val が見つからない（vacuous pass ガード）" }

        val commandsState = Regex("""\bFeedCommands\(\s*\w+\s*,\s*(\w+)\s*\)""").find(code)?.groupValues?.get(1)
        val queriesState = Regex("""\bFeedQueries\(\s*\w+\s*,\s*(\w+)\s*\)""").find(code)?.groupValues?.get(1)
        check(commandsState != null) { "${file.path} に FeedCommands(x, y) の構築が見つからない（vacuous pass ガード）" }
        check(queriesState != null) { "${file.path} に FeedQueries(x, y) の構築が見つからない（vacuous pass ガード）" }
        assertTrue(
            "FeedCommands の状態（$commandsState）が FeedState を作る val（$stateVal）でない",
            commandsState == stateVal,
        )
        assertTrue(
            "FeedQueries の状態（$queriesState）が FeedState を作る val（$stateVal）でない",
            queriesState == stateVal,
        )
    }

    /**
     * `SourcesState(` の構築が 1 回で、`SourcesCommands(x, state, …)` と `SourcesQueries(x, state)` が
     * その同じ val を受けているかを調べ、違反の説明を返す。0 件なら構造が一致している。
     */
    private fun sourcesStateWiringViolations(code: String): List<String> {
        val violations = mutableListOf<String>()
        val stateConstructions = Regex("""\bSourcesState\(""").findAll(code).count()
        if (stateConstructions != 1) violations += "SourcesState( の構築が 1 回でない（実際: $stateConstructions 回）"

        val stateVal = Regex("""\bval\s+(\w+)\s*(?::\s*SourcesState)?\s*(?:=|by\s+lazy\s*\{)\s*SourcesState\(""")
            .find(code)?.groupValues?.get(1)
        val commandsState = Regex("""\bSourcesCommands\(\s*\w+\s*,\s*(\w+)\s*,""").find(code)?.groupValues?.get(1)
        val queriesState = Regex("""\bSourcesQueries\(\s*\w+\s*,\s*(\w+)\s*\)""").find(code)?.groupValues?.get(1)
        if (stateVal == null) violations += "SourcesState( を初期化子に持つ val が無い"
        if (commandsState == null) violations += "SourcesCommands(x, y, …) の構築が無い"
        if (queriesState == null) violations += "SourcesQueries(x, y) の構築が無い"
        if (stateVal != null && commandsState != null && commandsState != stateVal) {
            violations += "SourcesCommands の状態（$commandsState）が SourcesState を作る val（$stateVal）でない"
        }
        if (stateVal != null && queriesState != null && queriesState != stateVal) {
            violations += "SourcesQueries の状態（$queriesState）が SourcesState を作る val（$stateVal）でない"
        }
        return violations
    }

    @Test
    fun AppContainerはSourcesStateを1つだけ作りSourcesCommandsとSourcesQueriesへ同じ値を渡す() {
        // verifies: android-dec-114。状態を 2 つ作ると、addSource が書く一覧と画面が読む一覧が別になり画面に何も起きない
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val code = file.readLines()
            .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
            .joinToString("\n")

        assertTrue("AppContainer の Sources の結線が android-dec-114 と違う: ${sourcesStateWiringViolations(code)}", sourcesStateWiringViolations(code).isEmpty())
    }

    @Test
    fun SourcesStateの結線の検査は正しい形を通し0回と2回と別の値を落とす_vacuous_passガード() {
        val correct = """
            private val sourcesState by lazy { SourcesState(Dispatchers.Default.limitedParallelism(1)) }
            private val sourcesCommands by lazy { SourcesCommands(sourcesApi, sourcesState, isAdminProvider = { false }) }
            private val sourcesQueries by lazy { SourcesQueries(sourcesApi, sourcesState) }
        """.trimIndent()
        assertTrue(sourcesStateWiringViolations(correct).isEmpty())

        assertFalse(sourcesStateWiringViolations(correct.replace("SourcesState(Dispatchers", "Other(Dispatchers")).isEmpty())
        assertFalse(
            sourcesStateWiringViolations(
                correct + "\nprivate val second by lazy { SourcesState(Dispatchers.Default.limitedParallelism(1)) }",
            ).isEmpty(),
        )
        assertFalse(sourcesStateWiringViolations(correct.replace("SourcesQueries(sourcesApi, sourcesState)", "SourcesQueries(sourcesApi, other)")).isEmpty())
        assertFalse(sourcesStateWiringViolations(correct.replace("SourcesCommands(sourcesApi, sourcesState,", "SourcesCommands(sourcesApi, other,")).isEmpty())
    }

    private fun String.withoutCommentLines(): String = lines()
        .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    /** 宣言（`PodcastApi.kt`・`ApiClient.kt`）と実装（`PodcastApiAdapter.kt`・`OkHttpApiClient.kt`）を除いた、位置の書込の呼出の持ち主。 */
    private val positionWriteDeclarationFiles = setOf("PodcastApi.kt", "ApiClient.kt", "PodcastApiAdapter.kt", "OkHttpApiClient.kt")

    /** [sources]（パス → ソース）のうち、`updatePlaybackPosition(` を呼ぶ `PositionCommands.kt` 以外のファイルを返す。 */
    private fun positionWriteViolations(sources: Map<String, String>): List<String> {
        val callers = sources
            .filterKeys { path -> positionWriteDeclarationFiles.none { path.endsWith("/$it") || path == it } }
            .filter { (_, code) -> Regex("""\bupdatePlaybackPosition\(""").containsMatchIn(code.withoutCommentLines()) }
            .keys
        return callers.filterNot { it.endsWith("podcast/app/PositionCommands.kt") }
    }

    @Test
    fun `android-possync-06 位置の書込は全部PositionCommands_recordを通る`() {
        val root = resolveMainSourceRoot()
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.path to it.readText() }

        assertTrue("位置の書込の呼出が PositionCommands.kt 以外にある: ${positionWriteViolations(sources)}", positionWriteViolations(sources).isEmpty())
        assertTrue(
            "PositionCommands.kt が updatePlaybackPosition( を呼んでいない（vacuous pass ガード）",
            sources.filterKeys { it.endsWith("podcast/app/PositionCommands.kt") }.values.any { Regex("""\bupdatePlaybackPosition\(""").containsMatchIn(it) },
        )
    }

    @Test
    fun `android-possync-06 位置の書込の検査はPlaybackCoordinatorに呼出を足した文字列を落とし正しい形を通す`() {
        val ok = mapOf(
            "app/src/main/java/x/podcast/app/PositionCommands.kt" to "podcastApi.updatePlaybackPosition(id, s, at)",
            "app/src/main/java/x/network/PodcastApi.kt" to "suspend fun updatePlaybackPosition(id: String, s: Double, at: Instant): ServerPosition",
        )
        assertTrue(positionWriteViolations(ok).isEmpty())

        val bad = ok + ("app/src/main/java/x/podcast/app/PlaybackCoordinator.kt" to "podcastApi.updatePlaybackPosition(id, s, at)")
        assertEquals(listOf("app/src/main/java/x/podcast/app/PlaybackCoordinator.kt"), positionWriteViolations(bad))
        assertTrue("コメントの言及は呼出ではない", positionWriteViolations(ok + ("a/Doc.kt" to "// updatePlaybackPosition( は PositionCommands だけ")).isEmpty())
    }

    private fun lambdaBodyOf(code: String, name: String): String? =
        Regex("""\b$name\s*=\s*\{([^}]*)\}""").find(code)?.groupValues?.get(1)

    /** order の「一覧の取得の後・主体の確定・後始末」の 3 つの結線（`AppContainer` の source の文字列）の欠けを返す。 */
    private fun positionWiringViolations(code: String): List<String> {
        val violations = mutableListOf<String>()
        if (lambdaBodyOf(code, "afterRefresh")?.contains("adoptEpisodes(") != true) violations += "afterRefresh の lambda が adoptEpisodes( を呼ばない"
        if (lambdaBodyOf(code, "onSubjectResolved")?.contains("positionCommands.onSubjectResolved(") != true) {
            violations += "onSubjectResolved の lambda が positionCommands.onSubjectResolved( を呼ばない"
        }
        val step = code.indexOf("CleanupStep(\"position_records\")")
        val audio = code.indexOf("CleanupStep(\"audio_cache\")")
        if (step < 0) violations += "subjectCleanup に CleanupStep(\"position_records\") が無い"
        if (step >= 0 && audio >= 0 && step < audio) violations += "position_records が audio_cache の前にある"
        return violations
    }

    @Test
    fun `android-possync-20 shared-sync-07 AppContainerの一覧の取得の後の結線がadoptEpisodesを呼ぶ`() {
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val code = file.readText().withoutCommentLines()

        assertTrue("AppContainer の位置の結線が足りない: ${positionWiringViolations(code)}", positionWiringViolations(code).isEmpty())
    }

    @Test
    fun `android-possync-20 shared-sync-07 位置の結線の検査は正しい形を通しどれか1つを外した文字列を落とす`() {
        val correct = """
            afterRefresh = { offlineLibrary.syncDownloadedState(it); positionCommands.adoptEpisodes(authSession.currentSubjectId(), it) },
            onSubjectResolved = { s -> offlineLibrary.reclaim(s?.userId); positionCommands.onSubjectResolved(s?.userId) },
            CleanupStep("audio_cache") { s -> s.userId?.let { offlineLibrary.removeSubject(it) } },
            CleanupStep("position_records") { s -> s.userId?.let { positionCommands.discardSubject(it) } },
        """.trimIndent()
        assertTrue(positionWiringViolations(correct).isEmpty())

        assertFalse(positionWiringViolations(correct.replace("positionCommands.adoptEpisodes", "other.run")).isEmpty())
        assertFalse(positionWiringViolations(correct.replace("positionCommands.onSubjectResolved(s?.userId)", "Unit")).isEmpty())
        assertFalse(positionWiringViolations(correct.replace("CleanupStep(\"position_records\")", "CleanupStep(\"other\")")).isEmpty())
    }

    /** `DataStorePositionStore(`・`PositionCommands(`・`PositionQueries(` の構築が 1 回ずつで、同じ store の値を両方へ渡しているかの違反を返す。 */
    private fun positionStoreWiringViolations(code: String): List<String> {
        val violations = mutableListOf<String>()
        for (type in listOf("DataStorePositionStore", "PositionCommands", "PositionQueries")) {
            val count = Regex("""\b$type\(""").findAll(code).count()
            if (count != 1) violations += "$type( の構築が 1 回でない（実際: $count 回）"
        }
        val storeVal = Regex("""\bval\s+(\w+)\s*(?::\s*\w+)?\s*(?:=|by\s+lazy\s*\{)\s*DataStorePositionStore\(""")
            .find(code)?.groupValues?.get(1)
        val commandsStore = Regex("""\bPositionCommands\(\s*[^,]+,\s*(\w+)\s*,""").find(code)?.groupValues?.get(1)
        val queriesStore = Regex("""\bPositionQueries\(\s*(\w+)\s*\)""").find(code)?.groupValues?.get(1)
        if (storeVal == null) violations += "DataStorePositionStore( を初期化子に持つ val が無い"
        if (commandsStore == null) violations += "PositionCommands(x, store, …) の構築が無い"
        if (queriesStore == null) violations += "PositionQueries(store) の構築が無い"
        if (storeVal != null && commandsStore != null && commandsStore != storeVal) violations += "PositionCommands の store（$commandsStore）が $storeVal でない"
        if (storeVal != null && queriesStore != null && queriesStore != storeVal) violations += "PositionQueries の store（$queriesStore）が $storeVal でない"
        return violations
    }

    @Test
    fun `android-possync-37 AppContainerは位置のstoreを1つ作り両方に渡す`() {
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "AppContainer.kt" }
            ?: error("走査ルート $root に AppContainer.kt が見つからない")
        val code = file.readText().withoutCommentLines()

        assertTrue("AppContainer の位置の store の結線が違う: ${positionStoreWiringViolations(code)}", positionStoreWiringViolations(code).isEmpty())
    }

    @Test
    fun `android-possync-37 位置のstoreの結線の検査は正しい形を通し0回と2回と別の値を落とす（vacuous passガード）`() {
        val correct = """
            private val playbackPositionStore: PositionStore by lazy { DataStorePositionStore(dataStore) }
            private val positionCommands by lazy { PositionCommands(PodcastApiAdapter(apiClient), playbackPositionStore, Clock.systemUTC(), authSession::currentSubjectId, dispatcher) }
            private val positionQueries by lazy { PositionQueries(playbackPositionStore) }
        """.trimIndent()
        assertTrue(positionStoreWiringViolations(correct).isEmpty())

        assertFalse(positionStoreWiringViolations(correct.replace("DataStorePositionStore(", "Other(")).isEmpty())
        assertFalse(
            positionStoreWiringViolations(correct + "\nprivate val second by lazy { DataStorePositionStore(dataStore) }").isEmpty(),
        )
        assertFalse(positionStoreWiringViolations(correct.replace("PositionQueries(playbackPositionStore)", "PositionQueries(other)")).isEmpty())
        assertFalse(positionStoreWiringViolations(correct.replace("PodcastApiAdapter(apiClient), playbackPositionStore", "PodcastApiAdapter(apiClient), other")).isEmpty())
    }

    private fun String.hasFunDeclarationOf(name: String): Boolean = Regex("""\bfun\s+(\w+\.)?$name\(""").containsMatchIn(this)

    /**
     * 再開の確認を作る入口の違反を返す（コメント行は除く）:
     * (1) 確認の値の生成（`class ResumePrompt(` の宣言を除く）が `PlaybackCoordinator.kt` の 1 か所だけか、
     * (2) `togglePlayPause(` の呼出（宣言を除く）が `PodcastViewModel.kt`・`AudioPlayerSection.kt` だけか、
     * (3) MediaSession（`PlaybackService.kt`）と player の実装（`ExoPlayerController.kt`）が確認を知らないか。
     */
    private fun resumePromptEntryViolations(sources: Map<String, String>): List<String> {
        val code = sources.mapValues { it.value.withoutCommentLines() }
        val violations = mutableListOf<String>()
        val creations = code
            .mapValues { (_, text) -> Regex("""\bResumePrompt\(""").findAll(text.replace("class ResumePrompt(", "")).count() }
            .filterValues { it > 0 }
        if (creations.values.sum() != 1 || creations.keys.single().endsWith("podcast/app/PlaybackCoordinator.kt").not()) {
            violations += "確認の値の生成が PlaybackCoordinator.kt の 1 か所でない: $creations"
        }
        code.filter { (_, text) -> text.lines().any { it.contains("togglePlayPause(") && !it.hasFunDeclarationOf("togglePlayPause") } }.keys
            .filterNot { it.endsWith("/PodcastViewModel.kt") || it.endsWith("/AudioPlayerSection.kt") }
            .forEach { violations += "$it が togglePlayPause( を呼ぶ" }
        for ((path, text) in code) {
            if (!path.endsWith("/PlaybackService.kt") && !path.endsWith("/ExoPlayerController.kt")) continue
            for (word in listOf("PlaybackCoordinator", "resumePrompt", "ResumePrompt", "togglePlayPause")) {
                if (text.contains(word)) violations += "$path が $word を知っている"
            }
        }
        return violations
    }

    @Test
    fun `android-possync-50 shared-sync-11 確認を作る入口はtogglePlayPauseだけ`() {
        val root = resolveMainSourceRoot()
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.path to it.readText() }

        for (name in listOf("PlaybackCoordinator.kt", "PodcastViewModel.kt", "AudioPlayerSection.kt", "PlaybackService.kt", "ExoPlayerController.kt")) {
            assertTrue("$name が走査ルートに無い（vacuous pass ガード）", sources.keys.any { it.endsWith("/$name") })
        }
        assertTrue("確認を作る入口が togglePlayPause だけでない: ${resumePromptEntryViolations(sources)}", resumePromptEntryViolations(sources).isEmpty())
    }

    @Test
    fun `android-possync-50 shared-sync-11 確認の入口の検査は反例の文字列を落とし正しい形を通す`() {
        val ok = mapOf(
            "app/src/main/java/x/podcast/app/PlaybackCoordinator.kt" to "_resumePrompt.value = ResumePrompt(id, a, b)\noverride suspend fun togglePlayPause() {}",
            "app/src/main/java/x/podcast/app/PlaybackReadModels.kt" to "data class ResumePrompt(val episodeId: String)",
            "app/src/main/java/x/podcast/PodcastViewModel.kt" to "suspend fun togglePlayPause() = coordinator.togglePlayPause()",
            "app/src/main/java/x/podcast/AudioPlayerSection.kt" to "scope.launch { viewModel.togglePlayPause() }",
            "app/src/main/java/x/playbackservice/PlaybackService.kt" to "class PlaybackService",
            "app/src/main/java/x/podcast/ExoPlayerController.kt" to "class ExoPlayerController",
        )
        assertTrue(resumePromptEntryViolations(ok).isEmpty())
        assertTrue(
            "コメントの言及は違反にしない",
            resumePromptEntryViolations(ok + ("app/src/main/java/x/playbackservice/PlaybackService.kt" to "// PlaybackCoordinator を知らない\nclass PlaybackService")).isEmpty(),
        )

        val service = "app/src/main/java/x/playbackservice/PlaybackService.kt"
        assertFalse("MediaSession が Coordinator の togglePlayPause を呼ぶ", resumePromptEntryViolations(ok + (service to "coordinator.togglePlayPause()")).isEmpty())
        assertFalse("MediaSession が確認の語を知る", resumePromptEntryViolations(ok + (service to "val p = resumePrompt")).isEmpty())
        assertFalse(
            "画面と中継以外が togglePlayPause( を呼ぶ",
            resumePromptEntryViolations(ok + ("app/src/main/java/x/Other.kt" to "coordinator.togglePlayPause()")).isEmpty(),
        )
        assertFalse(
            "確認の生成が 2 か所",
            resumePromptEntryViolations(ok + ("app/src/main/java/x/podcast/AudioPlayerSection.kt" to "val p = ResumePrompt(\"p1\", 1.0, 2.0)")).isEmpty(),
        )
        assertFalse(
            "確認の生成が 0 か所",
            resumePromptEntryViolations(ok + ("app/src/main/java/x/podcast/app/PlaybackCoordinator.kt" to "override suspend fun togglePlayPause() {}")).isEmpty(),
        )
    }

    /** `PlaybackCoordinator.kt` の `positionCommands.record(` の呼出が 1 か所で、その直前の関数宣言が `recordPosition` かの違反を返す（コメント行は除く）。 */
    private fun recordPositionViolations(coordinatorSource: String): List<String> {
        val lines = coordinatorSource.withoutCommentLines().lines()
        val calls = lines.indices.filter { lines[it].contains("positionCommands.record(") }
        if (calls.size != 1) return listOf("positionCommands.record( の呼出が 1 か所でない（実際: ${calls.size} か所）")
        val owner = lines.subList(0, calls.single()).lastOrNull { Regex("""\bfun\s+\w+\(""").containsMatchIn(it) }
            ?.let { Regex("""\bfun\s+(\w+)\(""").find(it)?.groupValues?.get(1) }
        return if (owner == "recordPosition") emptyList() else listOf("positionCommands.record( が recordPosition の外（$owner）にある")
    }

    @Test
    fun `android-possync-56 shared-sync-13 印の更新はrecordPositionだけを通る`() {
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "PlaybackCoordinator.kt" }
            ?: error("走査ルート $root に PlaybackCoordinator.kt が見つからない（vacuous pass ガード）")

        assertTrue("印の更新が recordPosition だけを通らない: ${recordPositionViolations(file.readText())}", recordPositionViolations(file.readText()).isEmpty())
    }

    @Test
    fun `android-possync-56 shared-sync-13 印の更新の検査は2か所と0か所と別の関数を落とし正しい形を通す`() {
        val ok = "private suspend fun recordPosition(subject: String?, id: String, s: Double) {\n    val at = positionCommands.record(subject, id, s)\n}"
        assertTrue(recordPositionViolations(ok).isEmpty())
        assertTrue("コメントの言及は呼出ではない", recordPositionViolations("// positionCommands.record( は recordPosition だけ\n$ok").isEmpty())

        assertFalse("2 か所", recordPositionViolations("$ok\nprivate fun other() {\n    positionCommands.record(a, b, c)\n}").isEmpty())
        assertFalse("0 か所", recordPositionViolations("private suspend fun recordPosition() {}").isEmpty())
        assertFalse("別の関数の中", recordPositionViolations("private fun other() {\n    positionCommands.record(a, b, c)\n}").isEmpty())
    }
}
