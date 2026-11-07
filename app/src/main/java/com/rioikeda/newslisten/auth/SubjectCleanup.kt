package com.rioikeda.newslisten.auth

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * 主体離脱の後始末の手順列を実行する（ADR-104 決定 1・3、shared-leave-01・04）。
 *
 * 手順は宣言順に 1 つずつ独立の try/catch で実行する。失敗しても残りを実行し、失敗した手順の名前を
 * [cleanupIncomplete] に出す。呼出元は完了を待たない（[start] は起動して戻る）。実行は [Mutex] で直列にし、
 * 連続した離脱の手順列が交差せず、[start] の呼出順に走るようにする。[cleanupIncomplete] は主体ごとには持たず、最後に完了した実行の結果を表す。
 */
class SubjectCleanup(
    private val steps: List<CleanupStep>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _cleanupIncomplete = MutableStateFlow<CleanupIncomplete?>(null)
    val cleanupIncomplete: StateFlow<CleanupIncomplete?> = _cleanupIncomplete.asStateFlow()

    fun start(subject: Subject) {
        // UNDISPATCHED: Mutex の待ち行列は取得を試みた順（FIFO）なので、取得の試行を呼出スレッドで済ませ、
        // 呼出順と実行順を一致させる。scope に載せてから取得すると、複数スレッドの dispatcher では起動順が入れ替わりうる。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                // 取得後はいったん scope に載せ直し、手順を呼出元のスレッドで走らせない（戻りを待たない）。
                yield()
                val failed = linkedSetOf<String>()
                for (step in steps) {
                    try {
                        step.run(subject)
                    } catch (e: CancellationException) {
                        // 中断は握らない。後続の手順も結果の更新もしない。
                        throw e
                    } catch (e: Exception) {
                        failed += step.name
                    }
                }
                _cleanupIncomplete.value =
                    if (failed.isEmpty()) null else CleanupIncomplete(Collections.unmodifiableSet(failed))
            }
        }
    }
}
