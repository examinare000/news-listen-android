package com.rioikeda.newslisten.architecture.fixture.timer

import com.rioikeda.newslisten.network.PodcastApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 自前の `SupervisorJob` の scope で 15 秒ごとに書く。command で開始し、止める command は持たない。 */
class PeriodicWriter(private val api: PodcastApi, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (true) {
                delay(15_000)
                api.updatePlaybackPosition("p", 1.0)
            }
        }
    }

    /** 何もしない query。 */
    fun idle(): Int = 0
}

/** 呼ばれるたびに、注入した dispatcher の上で「800ms 後に書込を 1 回」の処理を 1 つ起動して、すぐ返る query。 */
class DelayedWriter(private val api: PodcastApi, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun fire() {
        scope.launch {
            delay(800)
            api.updatePlaybackPosition("p", 1.0)
        }
    }
}
