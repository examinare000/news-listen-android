package com.rioikeda.newslisten.architecture.fixture.container

import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

// 型引数に効果を持ちうる型を含む容器の field（中身が記録 fake でも配線は失敗する）と、その正の対照。

class StoresOwner(private val stores: List<SessionStore>) {
    fun ping(): Int = stores.size
}

class LazyApiOwner(private val lazyApi: Lazy<ApiClient>) {
    fun ping(): Int = 1
}

class CallbacksOwner(private val callbacks: MutableStateFlow<() -> Unit>) {
    fun ping(): Int = 1
}

/** 型引数が `Job`（INFRA）と `ApiException`（Throwable の派生で VALUE）だけなので、配線は通る。 */
class JobsOwner(
    private val jobs: MutableMap<String, Job>,
    private val failure: MutableStateFlow<ApiException?>,
) {
    fun ping(): Int = jobs.size
}
