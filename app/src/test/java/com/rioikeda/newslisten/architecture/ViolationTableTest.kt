package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.violation.HelperRewritesSameValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 移行違反の表との一致（観測した `(操作 ID, port, op)` の集合と表の組の集合が等しい）と、既知の違反の観測。
 * 観測は fixture の実際の run を使わず、観測の値を直接与えて表の側の判定だけを確かめる。
 */
class ViolationTableTest {

    private val owner = "android-slice-learning-01"

    private val world = FixtureWorld(
        anchor = HelperRewritesSameValue::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.violation",
        files = listOf("QueryEffectFixtures.kt"),
        rows = listOf(
            probeQuery("HelperRewritesSameValue#load"),
            probeQuery("GetterSavesSession#getItems"),
            probeQuery("FlowCollectFiresCallback#observe"),
            probeQuery("FlowCollectFiresCallback#getOnChanged"),
            commandRow("FlowCollectFiresCallback#setOnChanged"),
            probeQuery("HelperReadsOnly#load"),
            probeQuery("GetterReadsOnly#getItems"),
            probeQuery("FlowCollectReadsOnly#observe"),
            probeQuery("FlowCollectReadsOnly#getOnChanged"),
            commandRow("FlowCollectReadsOnly#setOnChanged"),
        ),
    )

    private val helperRow = MigrationViolation(
        operationId = "HelperRewritesSameValue#load",
        port = "PreferencesStore",
        op = "markAchievementsSeen",
        path = "QueryEffectFixtures.kt",
        owner = owner,
        fixTask = owner,
        removeBy = owner,
    )

    private val helperObserved = List(2) {
        ObservedWrite("HelperRewritesSameValue#load", "suspend ()", "PreferencesStore", "markAchievementsSeen", "A", Origin.DIRECT)
    }

    @Before
    fun fixtureWorldIsEnumerated() {
        assertTrue("前提: fixture の母集団が列挙されていない", world.population.operations.isNotEmpty())
    }

    private fun compare(
        table: List<MigrationViolation>,
        observed: List<ObservedWrite> = helperObserved,
        changes: List<BackgroundChange> = emptyList(),
    ): List<Finding> = ViolationChecks.compare(observed, changes, table, world.rows, world.population, SliceIds.all)

    // ---- 表との一致 ----

    @Test
    fun 観測した組と表の組が両方向で一致すれば失敗しない() {
        val findings = compare(listOf(helperRow))

        assertTrue(findings.render(), findings.isEmpty())
    }

    @Test
    fun 同値の書込が複数回あっても組は1つとして一致する() {
        val findings = compare(listOf(helperRow), observed = helperObserved + helperObserved)

        assertTrue(findings.render(), findings.isEmpty())
    }

    @Test
    fun 表から行を抜くと表に無い書込として失敗する() {
        val findings = compare(emptyList())

        findings.requireFinding(Headings.UNLISTED_WRITE, "HelperRewritesSameValue#load", "PreferencesStore", "markAchievementsSeen")
    }

    @Test
    fun 書込の無い行を足すと縮め忘れとして失敗する() {
        val stale = helperRow.copy(operationId = "GetterSavesSession#getItems", port = "SessionStore", op = "save")

        val findings = compare(listOf(helperRow, stale))

        findings.requireFinding(Headings.STALE_VIOLATION, "GetterSavesSession#getItems", "SessionStore", "save")
        assertTrue("観測された組は縮め忘れにならない", findings.withHeading(Headings.STALE_VIOLATION).none { it.subject.contains("HelperRewritesSameValue") })
    }

    @Test
    fun ownerがsliceIdsに無い行はowner不正として失敗する() {
        val bogus = "android-slice-bogus-99"

        val findings = compare(listOf(helperRow.copy(owner = bogus)))

        findings.requireFinding(Headings.INVALID_OWNER, "HelperRewritesSameValue#load", bogus)
    }

    @Test
    fun fixTaskとremoveByがsliceIdsに無い行も不正として失敗する() {
        val bogus = "android-slice-bogus-98"

        compare(listOf(helperRow.copy(fixTask = bogus))).requireFinding(Headings.INVALID_OWNER, bogus)
        compare(listOf(helperRow.copy(removeBy = bogus))).requireFinding(Headings.INVALID_OWNER, bogus)
    }

    @Test
    fun 同じ組の行が2つなら重複として失敗する() {
        compare(listOf(helperRow, helperRow)).requireFinding(Headings.DUPLICATE_VIOLATION, "HelperRewritesSameValue#load")
    }

    @Test
    fun 背景処理の変化は表に載せても許可されず別の見出しで失敗する() {
        val change = BackgroundChange(
            "HelperRewritesSameValue#load", "suspend ()", "A", BackgroundChangeKind.JOB_STATE_CHANGED, "job: ACTIVE -> CANCELLED",
        )

        val findings = compare(listOf(helperRow), changes = listOf(change))

        findings.requireFinding(Headings.BACKGROUND_CHANGE, "HelperRewritesSameValue#load", "suspend ()", "A", "JOB_STATE_CHANGED")
        assertTrue("表と観測の一致は別に判定される", findings.withHeading(Headings.UNLISTED_WRITE).isEmpty())
    }

    // ---- 既知の違反 ----

    private val known = KnownViolation("HelperRewritesSameValue#load", "PreferencesStore", "markAchievementsSeen", "fixture", owner)

    @Test
    fun 既知の違反が表に含まれ観測されていれば失敗しない() {
        val findings = ViolationChecks.knownObserved(listOf(known), listOf(helperRow), helperObserved, SliceIds.all)

        assertTrue(findings.render(), findings.isEmpty())
    }

    @Test
    fun 既知の違反が観測されなければprobeの欠陥として失敗する() {
        val findings = ViolationChecks.knownObserved(listOf(known), listOf(helperRow), emptyList(), SliceIds.all)

        findings.requireFinding(Headings.PROBE_DEFECT, "HelperRewritesSameValue#load", "PreferencesStore", "markAchievementsSeen")
    }

    @Test
    fun 既知の違反が表に無ければ失敗しremoveByがsliceIdsに無くても失敗する() {
        val notInTable = ViolationChecks.knownObserved(listOf(known), emptyList(), helperObserved, SliceIds.all)
        assertTrue(
            "表に含まれない既知の違反を名指ししていない:\n${notInTable.render()}",
            notInTable.any { (it.subject + it.detail).contains("HelperRewritesSameValue#load") },
        )

        val bogus = "android-slice-bogus-97"
        val badRemoveBy = ViolationChecks.knownObserved(listOf(known.copy(removeBy = bogus)), listOf(helperRow), helperObserved, SliceIds.all)
        assertTrue(
            "removeBy を名指ししていない:\n${badRemoveBy.render()}",
            badRemoveBy.any { (it.subject + it.detail).contains(bogus) },
        )
    }
}
