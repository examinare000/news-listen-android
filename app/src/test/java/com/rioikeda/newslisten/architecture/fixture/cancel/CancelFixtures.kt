package com.rioikeda.newslisten.architecture.fixture.cancel

import com.rioikeda.newslisten.network.ApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 背景の書込を止めて同じ件数を書く「相殺」の 3 つの形。どの所有者も、warm-up で呼ぶ command `start()` が
// 15 秒ごとに `updatePlaybackPosition(同じ値)` を呼ぶ timer を張る。

/** query が timer の Job を cancel して、同じ書込を直接 2 回呼ぶ。 */
class CancelTimerAndWrite(private val api: ApiClient, dispatcher: CoroutineDispatcher) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start() {
        job = timer()
    }

    suspend fun takeOver() {
        job?.cancel()
        api.updatePlaybackPosition("p", 1.0)
        api.updatePlaybackPosition("p", 1.0)
    }

    private fun timer(): Job = scope.launch {
        while (true) {
            delay(15_000)
            api.updatePlaybackPosition("p", 1.0)
        }
    }
}

/** query が timer を cancel して、同じ位相で張り直す。 */
class RestartTimerInPhase(private val api: ApiClient, dispatcher: CoroutineDispatcher) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun start() {
        job = timer()
    }

    fun resync() {
        job?.cancel()
        job = timer()
    }

    private fun timer(): Job = scope.launch {
        while (true) {
            delay(15_000)
            api.updatePlaybackPosition("p", 1.0)
        }
    }
}

/** timer の書込は flag の中にあり、query が flag を落として、同じ書込を内部 scope の coroutine から 2 回呼ぶ。 */
class SuppressTimerAndWriteNow(private val api: ApiClient, dispatcher: CoroutineDispatcher) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var enabled = true

    fun start() {
        job = scope.launch {
            while (true) {
                delay(15_000)
                if (enabled) api.updatePlaybackPosition("p", 1.0)
            }
        }
    }

    fun pause() {
        enabled = false
        scope.launch {
            api.updatePlaybackPosition("p", 1.0)
            api.updatePlaybackPosition("p", 1.0)
        }
    }
}
