package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.duplicate.first.Dup
import com.rioikeda.newslisten.architecture.fixture.enumeration.Outer
import com.rioikeda.newslisten.architecture.fixture.implname.MoneyHolder
import com.rioikeda.newslisten.architecture.fixture.valueclass.KeyedFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 母集団と公開操作の列挙（層の値・名前の suffix・interface の有無で母集団を狭めない）。
 * 本番は main の class の根、fixture は test の `architecture.fixture` 配下の class の根を、同じ関数に渡す。
 */
class OperationEnumeratorTest {

    private fun List<PublicOperation>.keys(): List<Pair<String, String>> = map { it.id to it.signature }

    // ---- 本番 ----

    @Test
    fun 本番の母集団は全母集団ファイルがclassを持ち主要な型と操作を含む() {
        val population = ProductionWorld.population

        assertTrue("母集団ファイルが 0 件", population.classesByFile.isNotEmpty())
        val emptyFiles = population.classesByFile.filterValues { it.isEmpty() }.keys
        assertTrue("class を持たない母集団ファイル: $emptyFiles", emptyFiles.isEmpty())

        val vocabulary = population.classesByFile["vocabulary/VocabularyTestViewModel.kt"].orEmpty().map { it.simpleName }
        assertTrue(
            "VocabularyTestViewModel.kt の 5 型が揃っていない: $vocabulary",
            vocabulary.containsAll(
                listOf(
                    "VocabularyTestPhase",
                    "RetestFeedback",
                    "VocabularyTestSummary",
                    "VocabularyTestUiState",
                    "VocabularyTestViewModel",
                ),
            ),
        )

        val ids = population.operations.map { it.id }.toSet()
        listOf("PodcastViewModel#fetchPodcasts", "FeedViewModel#getArticles", "ListeningStreakStore#refresh").forEach {
            assertTrue("公開操作に $it が無い", it in ids)
        }
    }

    @Test
    fun 本番の母集団はComposableを含まず操作は一意でsignatureつきで整列される() {
        val population = ProductionWorld.population

        assertTrue(
            "PodcastScreen.kt の facade が母集団に無い（Composable を除く前の母集団が空）",
            population.classesByFile["podcast/PodcastScreen.kt"].orEmpty().any { it.simpleName == "PodcastScreenKt" },
        )
        val composables = population.operations.filter { op ->
            op.parameterTypes.any { it.typeName == "androidx.compose.runtime.Composer" }
        }
        assertTrue("Composer を引数に取る操作が残っている: ${composables.map { it.id }}", composables.isEmpty())
        assertTrue(population.operations.none { it.id == "PodcastScreenKt#PodcastScreen" })

        val keys = population.operations.keys()
        assertTrue("(操作 ID, signature) が重複している", keys.size == keys.toSet().size)
        assertEquals("ID と signature で整列されていない", keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertTrue("操作 ID に / が含まれる", population.operations.none { '/' in it.id })

        val again = OperationEnumerator.enumerate(
            ProductionWorld.classRoot,
            ProductionWorld.PACKAGE_PREFIX,
            LayerMap.byFile,
            ProductionWorld.loader,
        )
        assertEquals("同じ入力に同じ集合を返さない", keys, again.operations.keys())
    }

    @Test
    fun 層の付け替えでも母集団は縮まない() {
        val moved = LayerMap.byFile.mapValues { (path, layer) ->
            if (path.endsWith("ViewModel.kt") && layer == Layer.APPLICATION) Layer.PRESENTATION else layer
        }
        assertTrue(
            "付け替えた ViewModel のファイルが無い",
            moved.count { (path, layer) -> path.endsWith("ViewModel.kt") && layer == Layer.PRESENTATION } >= 12,
        )

        val original = ProductionWorld.population.operations.keys()
        val remapped = OperationEnumerator.enumerate(
            ProductionWorld.classRoot,
            ProductionWorld.PACKAGE_PREFIX,
            moved,
            ProductionWorld.loader,
        ).operations.keys()

        assertTrue("本番の母集団が空", original.isNotEmpty())
        assertEquals(original, remapped)
    }

    // ---- fixture: 除外の規則 ----

    @Test
    fun コンパイラ生成の操作とComposableを除き通常の操作とgetterは残す() {
        val world = FixtureWorld(
            anchor = Outer::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.enumeration",
            files = listOf("EnumerationFixtures.kt"),
            rows = emptyList(),
        )
        val operations = world.population.operations

        val expected = setOf(
            "Point#getX" to "()",
            "Point#getY" to "()",
            "Point#move" to "(int)",
            "Outer#getX" to "()",
            "Outer#setX" to "(int)",
            "Outer#fetch" to "suspend (String)",
            "Outer#find" to "(int)",
            "Outer.Inner#hello" to "()",
            "Outer.Companion#make" to "()",
            "EnumerationFixturesKt#topLevel" to "(String)",
        )
        assertEquals(expected, operations.keys().toSet())

        val find = operations.single { it.id == "Outer#find" }
        assertTrue(
            "前提: value class を引数に取る find の JVM 名は find-<hash>",
            find.method.name.startsWith("find-"),
        )
        val classNames = world.population.classesByFile["EnumerationFixtures.kt"].orEmpty().map { it.simpleName }
        assertTrue("enum が母集団の class に無い: $classNames", "Mode" in classNames)
    }

    // ---- fixture: 失敗（空集合を返さない） ----

    private fun enumerate(prefix: String, layerOf: Map<String, Layer>, anchor: Class<*>) =
        OperationEnumerator.enumerate(anchor.protectionDomain!!.codeSource!!.location, prefix, layerOf, anchor.classLoader!!)

    @Test
    fun LayerMapに行が無いclassは失敗しpathとclass名を含む() {
        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate("com.rioikeda.newslisten.architecture.fixture.enumeration", emptyMap(), Outer::class.java)
        }
        val message = failure.message.orEmpty()
        assertTrue("class 名を含まない: $message", message.contains("EnumerationFixtures"))
    }

    @Test
    fun classを持たない母集団ファイルは失敗する() {
        val layerOf = mapOf("EnumerationFixtures.kt" to Layer.APPLICATION, "Missing.kt" to Layer.APPLICATION)
        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate("com.rioikeda.newslisten.architecture.fixture.enumeration", layerOf, Outer::class.java)
        }
        assertTrue(failure.message.orEmpty().contains("Missing.kt"))
    }

    @Test
    fun 母集団ファイルが0件なら失敗する() {
        val layerOf = mapOf("EnumerationFixtures.kt" to Layer.DOMAIN)
        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate("com.rioikeda.newslisten.architecture.fixture.enumeration", layerOf, Outer::class.java)
        }
        assertTrue(failure.message.orEmpty().isNotEmpty())
    }

    @Test
    fun 型名が重複すると失敗し型名を含む() {
        val layerOf = mapOf("first/Dup.kt" to Layer.APPLICATION, "second/Dup.kt" to Layer.APPLICATION)
        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate("com.rioikeda.newslisten.architecture.fixture.duplicate", layerOf, Dup::class.java)
        }
        assertTrue(failure.message.orEmpty().contains("Dup"))
    }

    @Test
    fun 引数のvalue_classだけが違うoverloadは同じ操作IDとsignatureになり両方のJVM名を含めて失敗する() {
        val jvmNames = KeyedFinder::class.java.declaredMethods.map { it.name }.filter { it.startsWith("find-") }
        assertEquals("前提: JVM 名の違う find が 2 つ", 2, jvmNames.size)

        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate(
                "com.rioikeda.newslisten.architecture.fixture.valueclass",
                mapOf("KeyedFinder.kt" to Layer.APPLICATION),
                KeyedFinder::class.java,
            )
        }
        val message = failure.message.orEmpty()
        jvmNames.forEach { assertTrue("JVM 名 $it を含まない: $message", message.contains(it)) }
    }

    @Test
    fun 名前にimplを含む公開メソッドは除外せず失敗する() {
        val failure = assertThrows(EnumerationFailure::class.java) {
            enumerate(
                "com.rioikeda.newslisten.architecture.fixture.implname",
                mapOf("MoneyHolder.kt" to Layer.APPLICATION),
                MoneyHolder::class.java,
            )
        }
        assertTrue(failure.message.orEmpty().contains("-impl"))
    }
}
