# NewsListen — Android クライアント

news-listen の Android ネイティブクライアント。Kotlin + Jetpack Compose で構築。

## 技術スタック

- **言語**: Kotlin 2.1.20
- **UI フレームワーク**: Jetpack Compose（Material Design 3）
- **ビルドシステム**: Gradle 8.13（AGP 8.9.2）
- **コンパイル / ターゲット**: compileSdk 35 / targetSdk 35 / minSdk 26
- **Java 互換性**: Java 17

## 要件

- JDK 17 以上
- Android Studio（最新推奨）または `ANDROID_HOME` 環境変数の設定

## ビルド・テスト

```bash
# ビルド（デバッグ）
./gradlew build

# テスト実行
./gradlew test

# デバッグ APK 生成
./gradlew assembleDebug
```

## プロジェクト構成

```
android/
├── app/                          # メインアプリモジュール
│   ├── src/main/
│   │   ├── java/com/rioikeda/newslisten/  # ソースコード
│   │   ├── res/                           # リソース（strings, colors, themes）
│   │   └── AndroidManifest.xml
│   ├── src/test/                 # ユニットテスト
│   └── build.gradle.kts
├── gradle/wrapper/               # Gradle ラッパー
├── settings.gradle.kts
├── build.gradle.kts
└── gradle.properties
```

## 関連ドキュメント

- **技術選択**: [ADR-064](../docs/adr/064-android-tech-selection.md)（親リポジトリ `news-listen` の ADR）
- **再生状態マシン**: [shared-playback-spec.md](../docs/design/shared-playback-spec.md)（親リポジトリの設計仕様）

設計ドキュメントは親リポジトリ `news-listen` の `docs/` 配下（Git submodule）に集約されています。

## 開発規約

- **テスト**: テストファースト（TDD）で実装（`agent-rules/11-testing-strategy.md` 参照）
- **セキュリティ**: 認証トークンはログに出さず、環境変数で管理（`agent-rules/12-security-guidelines.md` 参照）
- **生成物**: `build/` ディレクトリの手動編集は禁止

詳細は親リポジトリの `agent-rules/` を参照。
