package com.rioikeda.newslisten.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * production interface（`ApiClient`）から throwing default を排除したことの構造検査（CI-T16）。
 *
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §4 CI-T16。
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
        // verifies: CI-T16
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
    fun PodcastViewModelの再生5操作はpodcastApi経由で語彙_クイズ3操作はapiClient経由で呼ぶ() {
        // verifies: CI-T16
        val root = resolveMainSourceRoot()
        val file = root.walkTopDown().firstOrNull { it.name == "PodcastViewModel.kt" }
            ?: error("走査ルート $root に PodcastViewModel.kt が見つからない")
        val text = file.readText()

        val playbackOps = setOf(
            "fetchPodcasts", "fetchPodcast", "updatePlaybackPosition", "markCompleted", "downloadAudio",
        )
        val vocabQuizOps = setOf("fetchVocabulary", "saveVocabulary", "submitQuizAnswers")

        val apiClientCalls = Regex("""\bapiClient\.(\w+)\(""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        val podcastApiCalls = Regex("""\bpodcastApi\.(\w+)\(""")
            .findAll(text).map { it.groupValues[1] }.toSet()

        assertTrue(
            "apiClient 経由の呼出は語彙・クイズ3操作のみであるべき（実際: $apiClientCalls）",
            apiClientCalls.all { it in vocabQuizOps },
        )
        assertTrue(
            "podcastApi 経由の呼出は空でなく再生5操作のみであるべき（実際: $podcastApiCalls）",
            podcastApiCalls.isNotEmpty() && podcastApiCalls.all { it in playbackOps },
        )
    }
}
