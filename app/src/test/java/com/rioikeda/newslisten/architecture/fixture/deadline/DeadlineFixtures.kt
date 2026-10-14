package com.rioikeda.newslisten.architecture.fixture.deadline

import com.rioikeda.newslisten.network.PodcastApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** command `start()` が 15 秒ごとに書く。getter `getState()` は所有者自身の `StateFlow` で、副作用が無い。 */
class PeriodicWriterWithStateGetter(private val api: PodcastApi, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val mutableState = MutableStateFlow(0)

    val state: StateFlow<Int> get() = mutableState

    fun start() {
        job = scope.launch {
            while (true) {
                delay(15_000)
                api.updatePlaybackPosition("p", 1.0)
            }
        }
    }
}

/** 収集の開始から 59 秒後（期限の内）に 1 回書く `early` と、61 秒後（期限の後）に書く `late`。 */
class DelayedFlowOwner(private val api: PodcastApi) {
    fun early(): Flow<Int> = flowOf(1).onStart {
        delay(59_000)
        api.updatePlaybackPosition("p", 1.0)
    }

    fun late(): Flow<Int> = flowOf(1).onStart {
        delay(61_000)
        api.updatePlaybackPosition("p", 1.0)
    }
}

/** 期限（60 秒）までに完了しない suspend の query。 */
class SlowSuspendQuery {
    suspend fun fetch(): Int {
        delay(61_000)
        return 1
    }
}
