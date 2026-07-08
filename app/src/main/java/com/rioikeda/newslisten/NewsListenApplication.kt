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

        // フェーズ12（issue #140）: 未捕捉例外の捕捉を起動直後に設定し、前回起動時に
        // 永続化されたクラッシュレポートがあれば送信する。install() はプロセスライフサイクルで
        // 一度だけ呼べばよく、Application.onCreate 以外に適切な呼び出し場所がない。
        // flush の起動スコープは AppContainer の crashReporterScope（SupervisorJob 付き
        // 長寿命スコープ）に集約する。preferencesScope 等と同じ管理方針に揃えるため、
        // ここで都度 CoroutineScope を生成しない。
        container.getCrashReporter().install()
        container.flushCrashReporter()
    }

    companion object {
        private lateinit var container: AppContainer

        fun getAppContainer(): AppContainer {
            return container
        }
    }
}
