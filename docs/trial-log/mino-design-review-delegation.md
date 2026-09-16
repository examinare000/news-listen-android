# trial-log: mino 設計 Skill 群による設計レビューの委譲運用（android）
範囲: review mode で 4 Function（architecture / completeness / contract / boundary）を read-only サブエージェントへ委譲する際の運用上の試行と失敗を扱う。レビュー内容そのもの（finding・package）は `docs/research-reports/2026-09-16-code-design-review.md` が正本で、ここでは扱わない。web 側の同名 trial-log（`web/docs/trial-log/mino-design-review-delegation.md`）の続編。

## 2026-09-16 初回実行（`docs/research-reports/2026-09-16-code-design-review.md`）

### router 自身が「連結出力の行番号」を引用した
- 目的: 探索役の起動前に、中核ファイル（ci.yml・AuthState・ApiException・PlayerController）を 1 回の Bash で `cat` して確認する。
- 前提: web trial-log の失敗（Boundary 担当が連結 `sed -n` の行番号を引用）は**ワーカー側**の問題だと認識していた。未検証: router が同じ読み方をしても、自分の引用には現れないという思い込み。
- やったこと: 4 ファイルを `;` で連結して `cat` し、その persisted-output の行番号を共通ブリーフ §4 に 3 件（`AuthState.kt:62-71`、`ApiException.kt:80-92`、`PlayerController.kt:141`）転記した。
- 結果: Architecture 担当が `wc -l` 超過検査で発見・差戻し（内容は正しく行番号のみ誤り）。router がブリーフと report core を修正し、並走中の Completeness 担当へ SendMessage で正誤表を通知。→ **ランブック §3「router 側の検査」に「router 自身の引用も提出前に `wc -l` 検査する」を追加すべき**。連結 `cat` の出力から行番号を取らない規律は router にも適用される。
- 残課題: なし（ブリーフ §6 に正誤表として残置。他 package への波及は Architecture §14 / router の範囲検査で確認）。

### `./gradlew test` が JDK 26 で起動せず、別 JDK での再実行も増分キャッシュで落ちた
- 目的: P3 の検証実行（`test-execution` ロールに委譲）。
- 前提: `/usr/libexec/java_home -V` に JDK は Homebrew OpenJDK 26 の 1 件のみ。プロジェクトは jvmTarget 17、CI は temurin 17。未検証: ローカルで過去に unit test が通っていたか（`launcher-icon-rebrand.md` は 2026-08 に `BUILD SUCCESSFUL` を記録しているが JDK は不明）。
- やったこと: (1) ロールが JDK 26 で実行 → Kotlin Gradle plugin が `IllegalArgumentException: 26.0.2.1` で異常終了、テスト 0 件。ロールは 12 ターン上限で報告未達（SendMessage で「今あるものだけ報告」を送って回収）。(2) router が Android Studio 同梱 JBR を `JAVA_HOME` にして再実行 → `compileDebugUnitTestKotlin` が `PodcastDecodingTest.kt:58` で `Unresolved reference 'NewsListenJson'`（同一パッケージの top-level val。同コミットの CI は green）。(3) `./gradlew clean testDebugUnitTest` → BUILD SUCCESSFUL、528 tests / 0 failures。
- 結果: (2) は (1) の異常終了で汚れた増分コンパイル状態（untracked `.kotlin/`・`app/build/`）が原因と判断（`clean` で解消。厳密な切り分けは未実施）。→ JDK 不適合の実行の直後は必ず `clean` を挟む。検証ロールのブリーフに「JDK 版を先に確認し、17/21 が無ければ Android Studio の JBR を使う」を入れる。
- 残課題: README か Gradle toolchain（`kotlin { jvmToolchain(17) }`）で JDK 要件を固定する提案は finding RF11 の required_action に含めた（実施は user 判断）。

### ワーカーの turn 上限（20）に 3 体が到達
- 目的: 4 Function package を 2 波で委譲（`testability-architect`、maxTurns 20）。
- 前提: 共通ブリーフに web の教訓（「10 ターン以内に書き始める」「読みは 1〜2 回の Bash に束ねる」）を最初から明記した。未検証: 明記すれば守られる。
- やったこと／結果:
  - Architecture: 32 tool uses で完走（1,022 行、要約あり）。
  - Completeness: 24 tool uses で上限。**成果物は 1,419 行・§17 まで書けていた**が最終要約が未送信。router がファイルを直接検査して採用（再開不要）。
  - Boundary: **36 tool uses で成果物ゼロ**（読みで使い切り）。SendMessage で「これ以上読まず今ある観測で書け。未確認の行番号は unverified と付す」を送って再開。
  - test-execution（maxTurns 12）: JDK 失敗の切り分けに使い切り報告未達 → 同様に回収。
- 教訓: 「10 ターン以内に書き始める」の明記だけでは不十分で、**ブリーフの「読む対象」自体を絞る**（Boundary に 6 境界・十数ファイルを渡したのが過大）か、成果物を「§1 を 3 ターン目までに書く」のように段階締切にする。上限到達は完了でも失敗でもなく無印の中途状態なので、router はまずファイルの実体（`wc -l`・§見出し）を測ってから再開・引き取り・再分割を選ぶ（`95-orchestration-protocol.md`）。
- 残課題: ブリーフ雛形（agentDevTemplate 側）への「段階締切」「読む対象の上限（ファイル数）」の昇格候補。

### 棄却した案
- Explorer 報告 3 本を付録に保存する案: 内容は共通ブリーフ §4/§5 に router が再検証のうえ圧縮して取り込んだため、web と同じく付録には含めない（重複と未検証転記の混入を避ける）。

### 追記: router の連結行番号は 4 件目もあった
- `app/build.gradle.kts:320`（signingConfig）も連結 cat 由来で、実際は `:66`。レポート結合後の機械検査（V7 を本文にも適用）で検出・修正。**router 自身の成果物にも提出前の範囲検査を掛ける**を再確認。
