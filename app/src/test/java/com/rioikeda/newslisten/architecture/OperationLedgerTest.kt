package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.classification.ContextTaker
import com.rioikeda.newslisten.architecture.fixture.overload.OverloadedLoader
import com.rioikeda.newslisten.architecture.fixture.unregistered.UnregisteredOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 台帳と列挙の照合、分類の整合。台帳は層を持たず、interface と実装は 1 行に寄る。 */
class OperationLedgerTest {

    private val viewModels = listOf(
        "PodcastViewModel", "AuthViewModel", "AccountViewModel", "SessionsViewModel",
        "PasskeyCredentialsViewModel", "PasskeyLoginViewModel", "PasskeyRegistrationViewModel",
        "FeedViewModel", "LearningViewModel", "VocabularyTestViewModel", "SettingsViewModel", "OnboardingViewModel",
    )

    // ---- 本番の台帳 ----

    @Test
    fun 本番の台帳は全公開操作と1対1で対応する() {
        val rows = OperationLedger.rows
        val findings = LedgerChecks.reconcile(ProductionWorld.population, rows)

        assertTrue("本番の台帳が空", rows.isNotEmpty())
        viewModels.forEach { owner ->
            assertTrue(
                "$owner に PROBE の query 行が無い（母集団を狭めていないことの確認）",
                rows.any { it.kind == OperationKind.QUERY && it.evidence == QueryEvidence.PROBE && it.id.startsWith("$owner#") },
            )
        }
        assertTrue("台帳と列挙の不一致:\n${findings.render()}", findings.isEmpty())
    }

    @Test
    fun 台帳から1行を抜くと台帳に無い操作で失敗する() {
        val rows = OperationLedger.rows
        val victim = rows.single { it.id == "FeedViewModel#loadFeed" }

        val findings = LedgerChecks.reconcile(ProductionWorld.population, rows - victim)

        assertEquals("抜いた 1 行だけが失敗する: ${findings.render()}", 1, findings.size)
        findings.requireFinding(Headings.MISSING_ROW, "FeedViewModel#loadFeed")
    }

    @Test
    fun 実在しない行を足すと列挙に無い台帳の行で失敗する() {
        val ghost = commandRow("GhostViewModel#vanish")

        val findings = LedgerChecks.reconcile(ProductionWorld.population, OperationLedger.rows + ghost)

        assertEquals(1, findings.size)
        findings.requireFinding(Headings.STALE_ROW, "GhostViewModel#vanish")
    }

    @Test
    fun 同じ操作に一致する2行は失敗する() {
        val rival = commandRow("RivalRow#load").copy(aliases = setOf("FeedViewModel#loadFeed"))

        val findings = LedgerChecks.reconcile(ProductionWorld.population, OperationLedger.rows + rival)

        findings.requireFinding(Headings.AMBIGUOUS_ROW, "FeedViewModel#loadFeed")
    }

    @Test
    fun interfaceと実装を別の行にすると失敗する() {
        val rows = OperationLedger.rows
        val merged = rows.single { "ApiListeningStreakStore#refresh" in it.aliases + it.id }
        assertEquals("前提: interface の行が実装を alias に持つ", "ListeningStreakStore#refresh", merged.id)
        val split = rows - merged +
            merged.copy(aliases = merged.aliases - "ApiListeningStreakStore#refresh") +
            probeQuery("ApiListeningStreakStore#refresh")

        val findings = LedgerChecks.reconcile(ProductionWorld.population, split)

        findings.requireFinding(Headings.SPLIT_INTERFACE_IMPLEMENTATION, "ApiListeningStreakStore#refresh")
    }

    @Test
    fun 本番の台帳の分類は整合しSTRUCTURALの代表が通る() {
        val rows = OperationLedger.rows
        val findings = LedgerChecks.classification(ProductionWorld.population, rows, EffectTypeCatalog.production)

        val structural = rows.filter { it.evidence == QueryEvidence.STRUCTURAL }.map { it.id }.toSet()
        listOf("ArticleOpener#resolveOpenAction", "VocabularyTestUiState#getPhase", "PendingArticleAction#getArticle").forEach {
            assertTrue("$it が STRUCTURAL で台帳に無い（効果を持たない型の操作）", it in structural)
        }
        assertTrue(
            "ColorKt の行は STRUCTURAL のはず",
            rows.filter { it.id.startsWith("ColorKt#") }.all { it.evidence == QueryEvidence.STRUCTURAL },
        )
        assertTrue("分類の不整合:\n${findings.render()}", findings.isEmpty())
    }

    // ---- fixture: 分類の整合 ----

    private val classificationWorld = FixtureWorld(
        anchor = ContextTaker::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.classification",
        files = listOf("ClassificationFixtures.kt"),
        rows = listOf(
            probeQuery("PortHolder#getLabel"),
            commandRow("ContextTaker#open"),
            structuralQuery("PureValue#getValue"),
            structuralQuery("PureValue#pure"),
        ),
    )

    private fun classify(world: FixtureWorld, rows: List<LedgerRow>) =
        LedgerChecks.classification(world.population, rows, world.catalog)

    @Test
    fun 正しく分類した台帳は分類の整合を満たす() {
        val world = classificationWorld
        assertEquals(
            "前提: fixture の公開操作が列挙されていない",
            setOf("PortHolder#getLabel", "ContextTaker#open", "PureValue#getValue", "PureValue#pure"),
            world.population.operations.map { it.id }.toSet(),
        )
        assertTrue(classify(world, world.rows).render(), classify(world, world.rows).isEmpty())
    }

    @Test
    fun portのfieldを持つ型のgetterをSTRUCTURALにすると型と経路を名指しして失敗する() {
        val world = classificationWorld
        val rows = world.rows.map { if (it.id == "PortHolder#getLabel") structuralQuery(it.id) else it }

        classify(world, rows).requireFinding(Headings.CLASSIFICATION, "PortHolder#getLabel", "SessionStore")
    }

    @Test
    fun Contextを引数に取る操作をSTRUCTURALにすると引数の型を名指しして失敗する() {
        val world = classificationWorld
        val rows = world.rows.map { if (it.id == "ContextTaker#open") structuralQuery(it.id) else it }

        classify(world, rows).requireFinding(Headings.CLASSIFICATION, "ContextTaker#open", "Context")
    }

    @Test
    fun getterをCOMMANDにすると失敗する() {
        val world = classificationWorld
        val rows = world.rows.map { if (it.id == "PureValue#getValue") commandRow(it.id) else it }

        classify(world, rows).requireFinding(Headings.CLASSIFICATION, "PureValue#getValue")
    }

    // ---- fixture: 後続 slice の追加の検出 ----

    @Test
    fun 台帳に無い公開メソッドを足すと台帳に無い操作で失敗する() {
        val world = FixtureWorld(
            anchor = UnregisteredOps::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.unregistered",
            files = listOf("UnregisteredOps.kt"),
            rows = listOf(probeQuery("UnregisteredOps#listed")),
        )

        val findings = LedgerChecks.reconcile(world.population, world.rows)

        assertEquals(1, findings.size)
        findings.requireFinding(Headings.MISSING_ROW, "UnregisteredOps#unlisted")
    }

    // ---- fixture: overload は signature の単位で照合する ----

    private val overloadWorld = FixtureWorld(
        anchor = OverloadedLoader::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.overload",
        files = listOf("OverloadFixtures.kt"),
        rows = listOf(probeQuery("OverloadedLoader#load")),
    )

    @Test
    fun signaturesの欄が無い行にoverloadがあると3つのsignatureを名指しして失敗する() {
        val world = overloadWorld

        val findings = LedgerChecks.reconcile(world.population, world.rows)

        val hit = findings.firstOrNull {
            it.heading.startsWith(Headings.SIGNATURE_MISMATCH) && it.subject.contains("OverloadedLoader#load")
        }
        assertTrue("signature の不一致が無い:\n${findings.render()}", hit != null)
        val text = hit!!.subject + " " + hit.detail
        listOf("()", "(int)", "suspend (String)").forEach { assertTrue("$it を名指ししていない: $text", text.contains(it)) }
    }

    @Test
    fun signaturesが一部だけの行は足りないsignatureを名指しして失敗する() {
        val world = overloadWorld.withRows(listOf(probeQuery("OverloadedLoader#load", signatures = setOf("()"))))

        val findings = LedgerChecks.reconcile(world.population, world.rows)

        findings.requireFinding(Headings.MISSING_SIGNATURE, "OverloadedLoader#load", "(int)", "suspend (String)")
    }

    @Test
    fun signaturesが全部そろった行は照合を通る() {
        val world = overloadWorld.withRows(
            listOf(probeQuery("OverloadedLoader#load", signatures = setOf("()", "(int)", "suspend (String)"))),
        )

        val findings = LedgerChecks.reconcile(world.population, world.rows)

        assertEquals("前提: overload が 3 つ列挙される", 3, world.population.operations.size)
        assertTrue(findings.render(), findings.isEmpty())
    }
}
