# android — CLAUDE.md

> Submodule (`news-listen-android`). 親リポジトリ `news-listen` 配下で作業する場合、
> `../agent-rules/` のルールが正本。本ファイルはこのモジュール固有の補足のみ。

## スタック
- Kotlin / Jetpack Compose。Gradle プロジェクト: `build.gradle.kts` / `settings.gradle.kts`。
- テスト: `./gradlew test`。

## 作業規約
- TDD 必須（`agent-rules/11-testing-strategy.md`）。テストを実装前に書く。
- 認証・トークン管理は `agent-rules/12-security-guidelines.md` 準拠。環境変数を使い、ログに出さない。

## このモジュールで触らないこと
- `build/`・`*.apk` 等のビルド生成物は手動編集しない。

