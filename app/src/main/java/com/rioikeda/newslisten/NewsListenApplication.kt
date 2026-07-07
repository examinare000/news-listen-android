package com.rioikeda.newslisten

import android.app.Application
import com.rioikeda.newslisten.di.AppContainer

/**
 * アプリケーション エントリポイント。依存グラフ（AppContainer）をアプリスコープで保持する。
 *
 * 正本: iOS は AppDelegate（@main）で AppState を @StateObject で初期化するが、Android では
 * Application インスタンスが単一かつプロセスライフサイクルで存在するため、Application.onCreate
 * で AppContainer を初期化し、MainActivity から companion object で参照する設計。
 */
class NewsListenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        private lateinit var container: AppContainer

        fun getAppContainer(): AppContainer {
            return container
        }
    }
}
