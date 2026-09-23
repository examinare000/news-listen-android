## android リファクタ A-S3: CI の 3 ステップ分割と JDK toolchain 固定（RF11・SG-R11）

## 概要
CI を `testDebugUnitTest` / `lintDebug` / `assembleDebug` の独立ステップに分け、JDK 要件を Gradle toolchain で固定する。正本は user 承認済みの Implementation Spec `docs/design/2026-09-16-implementation-spec-playback-auth.md`（§4 CI-T18・§6 S3 行）。SG-R11 は 2026-09-16 に user 承認済み（レビュー §8.2）。本タスクは**承認済み指示書に従う実装**であり、analyze_order は検証モード（新規設計をしない）。generate_spec の spec.md は CI-T18 の抜粋で足りる。

着手順（A-S2c の PR が main に merge 済みであること。A-S2a〜c の 3 段の後に CI の粒度を上げる）。起点となった運用知見: ローカルの JDK 26 では Kotlin コンパイラが起動不能で、Android Studio 同梱 JBR を `JAVA_HOME` にしないと `testDebugUnitTest` が走らない（`docs/research-reports/2026-09-16-code-design-review/verification-run.md` §1〜§2）。toolchain 固定で環境差を吸収する。

## 前提・着手条件
- 依存 slice: A-S2c の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。A-S4 とは対象ファイルが重ならないが、同一 submodule のため直列で投入する。
- 2026-09-23 実測の現状: `.github/workflows/ci.yml` は `Build and test`（`./gradlew build --stacktrace`）の 1 ステップ＋ gitleaks。`app/build.gradle.kts:76` に `jvmTarget = "17"` はあるが `jvmToolchain` は無い（toolchain 追加後は `jvmTarget` と重複するため toolchain に寄せてよい）。
- Selection Gate 依存なし。
- 入れないもの（SG-R11 で決定・再提案しない）: detekt / ktlint / JaCoCo / Dependabot（学習サイクルで再判断）。
- `docs/trial-log/` を最初に読み、棄却済み案を再試行しない。

## 対象（android サブモジュールのみ）
1. **`.github/workflows/ci.yml`**: 現行の `./gradlew build --stacktrace`（unit test 含む 1 ステップ）を、`./gradlew testDebugUnitTest` → `./gradlew lintDebug` → `./gradlew assembleDebug` の**独立 3 ステップ**に分ける（各ステップの失敗が個別に見えること）。gitleaks ステップは維持。
2. **`app/build.gradle.kts`**: `kotlin { jvmToolchain(17) }` で JDK 要件を固定する（CI の temurin 17 と一致）。ローカルで `JAVA_HOME=<JBR>` を指定せずに `./gradlew testDebugUnitTest` が通ることを確認する。
3. `lintDebug` は本 review で未実行（verification-run V4 unexecuted）。初回実行で既存の lint 失敗が出た場合は、本 slice の差分に起因しない項目として記録し、CI では `lintDebug` を**警告扱いにしない**が、既存失敗の修正は別タスク（abort 条件参照）。

## 契約（RED テストの対応）
| CI | 内容 | 検証 |
|---|---|---|
| CI-T18 | `testDebugUnitTest` / `lintDebug` / `assembleDebug` が独立ステップで、JDK は toolchain 17 | T-T18: `ci.yml` / `build.gradle.kts` の差分レビュー（テスト不可）。ローカルで `./gradlew clean testDebugUnitTest` が `JAVA_HOME` 未指定で exit 0 |

## 特性テスト（baseline）
`JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` が exit 0（A-S2c 完了時点の全 unit テスト）。

## 手順
1. baseline: 上記コマンドで green を記録。`./gradlew lintDebug` を初めて実行し結果を記録する。
2. `jvmToolchain(17)` を追加し、`JAVA_HOME` 未指定で `./gradlew clean testDebugUnitTest` が通ることを確認する。
3. `ci.yml` を 3 ステップへ分割する。
4. 1 slice = 1 PR。temporary path なし。

## abort_conditions / rollback
- abort: `lintDebug` の初回実行で本 slice の差分に起因しない失敗が出て、CI が green にできない場合は、失敗項目を記録して中止し、lint 失敗の解消を別タスクとして起票する（本 slice で lint 対象コードを修正しない）。
- rollback: `ci.yml` と `build.gradle.kts` の revert。

## 完了条件
- CI が 3 ステップとも green（`gh run list` で確認）。
- `build.gradle.kts` に `jvmToolchain(17)` があり、ローカルの `./gradlew clean testDebugUnitTest` が `JAVA_HOME` 未指定で exit 0。
- detekt / ktlint / JaCoCo / Dependabot の設定が追加されていない。

## 禁止事項 / scope ��外
- lint 失敗の解消のためのソース修正はしない（別タスク）。
- 静的解析・カバレッジ・依存更新 bot の追加はしない。
- 仕様にない業務条件を足さない。

## 参照
- Spec: `docs/design/2026-09-16-implementation-spec-playback-auth.md` §4 CI-T18・§6 S3（親 plan の ID は A-S3）
- レビュー: `docs/research-reports/2026-09-16-code-design-review.md` §8.2（SG-R11）・§8.3（RF11・着手順 4）
- 検証: `docs/research-reports/2026-09-16-code-design-review/verification-run.md` §1〜§2（JDK 26 の起動不能・JBR 指定）・V4（lintDebug 未実行）
