package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.catalog.WithPrivateCompanion
import com.rioikeda.newslisten.architecture.fixture.catalognested.NestedPortOuter
import com.rioikeda.newslisten.architecture.fixture.outside.UnknownCollaborator
import com.rioikeda.newslisten.architecture.fixture.unknown.UnknownCollaboratorOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分類表（port 表・型の分類・hook 表）の完全性と、効果を持ちうる型の不動点。 */
class EffectTypeCatalogTest {

    private val production = EffectTypeCatalog.production

    private fun portRow(catalog: EffectTypeCatalog, name: String): PortRow =
        catalog.ports.singleOrNull { it.port == name } ?: throw AssertionError("port 表に $name の行が無い")

    // ---- 本番 ----

    @Test
    fun 本番の分類表は9つのportと16のhookを持つ() {
        assertEquals(
            setOf(
                "ApiClient", "PodcastApi", "PreferencesStore", "SessionStore", "PlayerController",
                "FileSystem", "NetworkMonitoring", "PasskeyProvider", "FeedbackPlayer",
            ),
            production.ports.map { it.port }.toSet(),
        )
        // ApiClient は PodcastApi を継承しなくなったので、PodcastApi は独立した port 行になる（android-contract-18）。
        val podcastApi = portRow(production, "PodcastApi")
        assertEquals(setOf("updatePlaybackPosition", "markCompleted"), podcastApi.writes)
        assertEquals(setOf("fetchPodcasts", "fetchPodcast", "downloadAudio"), podcastApi.sequenceReads)
        assertTrue(podcastApi.propertyReads.isEmpty())
        assertTrue(
            "ApiClient 行の interfaces に PodcastApi が残っている",
            portRow(production, "ApiClient").interfaces.none { it.endsWith(".PodcastApi") },
        )
        val api = portRow(production, "ApiClient")
        assertEquals("ApiClient 系の書込（GET 以外）は 28", 28, api.writes.size)
        assertEquals("ApiClient 系の列の読み（GET）は 16", 16, api.sequenceReads.size)
        assertTrue(api.writes.containsAll(listOf("markCompleted", "updatePlaybackPosition", "starArticle", "dismissArticle")))
        assertTrue(api.sequenceReads.containsAll(listOf("fetchFeed", "fetchVocabularyTestSession", "fetchListeningStreak")))

        val preferences = portRow(production, "PreferencesStore")
        assertEquals(8, preferences.writes.size)
        assertEquals(8, preferences.propertyReads.size)
        assertTrue("markAchievementsSeen" in preferences.writes)

        val player = portRow(production, "PlayerController")
        assertEquals(setOf("prepare", "play", "pause", "seekTo", "setSpeed", "stop", "release", "setOnPlaybackCompleted"), player.writes)
        assertEquals(6, player.propertyReads.size)

        assertEquals(setOf("save", "clear"), portRow(production, "SessionStore").writes)
        assertEquals(setOf("load"), portRow(production, "SessionStore").sequenceReads)
        assertEquals(setOf("write", "delete", "ensureDirectory"), portRow(production, "FileSystem").writes)
        assertEquals(setOf("exists", "listFiles", "fileSize"), portRow(production, "FileSystem").sequenceReads)
        assertEquals(setOf("start", "stop"), portRow(production, "NetworkMonitoring").writes)
        assertEquals(1, portRow(production, "NetworkMonitoring").propertyReads.size)
        assertEquals(setOf("createCredential", "getCredential"), portRow(production, "PasskeyProvider").writes)
        assertEquals(setOf("playSound", "performHaptic"), portRow(production, "FeedbackPlayer").writes)

        assertEquals("hook は 16 個", 16, production.hooks.size)
        assertEquals("公開 callback（SETTER）は 2 つ", 2, production.hooks.count { it.wiring == HookWiring.SETTER })
        assertTrue(production.hooks.filter { it.wiring == HookWiring.SETTER }.all { it.kind == HookKind.WRITE })
    }

    @Test
    fun 本番の母集団は未分類の型とportの操作とhookが0件() {
        val findings = CatalogVerifier.verify(ProductionWorld.population, production)

        assertTrue("母集団が空", ProductionWorld.population.classes.isNotEmpty())
        assertTrue("分類表の不完全:\n${findings.render()}", findings.isEmpty())
    }

    // ---- 本番の写し: 抜いた行を名指しして失敗する ----

    @Test
    fun port表から操作を抜くと未分類のportの操作として名指しされる() {
        val api = portRow(production, "ApiClient")
        assertTrue("前提: markCompleted が書込に載っている", "markCompleted" in api.writes)
        assertTrue(
            "正の対照: 抜く前は失敗しない",
            CatalogVerifier.verify(ProductionWorld.population, production)
                .withHeading(Headings.UNCLASSIFIED_PORT_OPERATION).isEmpty(),
        )

        val trimmed = production.copy(
            ports = production.ports.map { if (it.port == "ApiClient") it.copy(writes = it.writes - "markCompleted") else it },
        )
        val findings = CatalogVerifier.verify(ProductionWorld.population, trimmed)

        findings.requireFinding(Headings.UNCLASSIFIED_PORT_OPERATION, "ApiClient#markCompleted")
    }

    @Test
    fun hook表から行を抜くと未登録のhookとして名指しされる() {
        assertTrue("前提: hook 表に行がある", production.hooks.any { it.ownerField == "AuthViewModel#onSubjectLeave" })

        val trimmed = production.copy(hooks = production.hooks.filterNot { it.ownerField == "AuthViewModel#onSubjectLeave" })
        val findings = CatalogVerifier.verify(ProductionWorld.population, trimmed)

        findings.requireFinding(Headings.UNREGISTERED_HOOK, "AuthViewModel#onSubjectLeave")
    }

    @Test
    fun hook表に実在しない行を足すと余分な行として名指しされる() {
        val ghost = HookRow("GhostViewModel#onGhost", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR)

        val findings = CatalogVerifier.verify(ProductionWorld.population, production.copy(hooks = production.hooks + ghost))

        findings.requireFinding(Headings.SURPLUS_CATALOG_ROW, "GhostViewModel#onGhost")
    }

    // ---- fixture ----

    @Test
    fun どの規則にも当たらない型は現れた場所つきで未分類として失敗する() {
        val world = FixtureWorld(
            anchor = UnknownCollaboratorOwner::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.unknown",
            files = listOf("UnknownCollaboratorOwner.kt"),
            rows = emptyList(),
        )
        val unknown = UnknownCollaborator::class.java.name

        val findings = CatalogVerifier.verify(world.population, world.catalog)
        findings.requireFinding(Headings.UNCLASSIFIED_TYPE, "UnknownCollaborator", "UnknownCollaboratorOwner", "collaborator")

        val added = world.catalog.copy(
            addedTypes = world.catalog.addedTypes + ClassifiedTypeRow(unknown, TypeClass.VALUE, "fixture: 正の対照"),
        )
        assertTrue(
            "追加の規則で分類した型は未分類にならない",
            CatalogVerifier.verify(world.population, added).withHeading(Headings.UNCLASSIFIED_TYPE).isEmpty(),
        )
    }

    @Test
    fun 非publicのcompanionと入れ子のenumの合成fieldも分類され効果を持たない() {
        val world = FixtureWorld(
            anchor = WithPrivateCompanion::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.catalog",
            files = listOf("CatalogFixtures.kt"),
            rows = emptyList(),
        )
        val names = world.population.classes.map { it.name.substringAfterLast('.') }.toSet()
        listOf("WithPrivateCompanion\$Companion", "HiddenPair", "HolderOfEnum\$Kind").forEach {
            assertTrue("母集団ファイルの class に $it が無い: $names", it in names)
        }

        assertTrue(CatalogVerifier.verify(world.population, world.catalog).render(), CatalogVerifier.verify(world.population, world.catalog).isEmpty())
        assertTrue(
            "効果を持たない型だけのはず",
            EffectfulTypes.fixedPoint(world.population, world.catalog).isEmpty(),
        )
    }

    @Test
    fun portのfieldを持つ入れ子のclassを足すと外側の型が効果を持ちうる型になる() {
        val world = FixtureWorld(
            anchor = NestedPortOuter::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.catalognested",
            files = listOf("NestedPortFixtures.kt"),
            rows = emptyList(),
        )

        val effectful = EffectfulTypes.fixedPoint(world.population, world.catalog).map { it.name.substringAfterLast('.') }.toSet()

        assertTrue("入れ子の class が効果を持ちうる型でない: $effectful", "NestedPortOuter\$Inner" in effectful)
        assertTrue("外側の型が効果を持ちうる型でない: $effectful", "NestedPortOuter" in effectful)
    }
}
