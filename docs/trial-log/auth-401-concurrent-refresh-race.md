# trial-log: 401/認証失効まわりの並行 refresh 競合修正
範囲: android S0（`ApiException.Unauthorized` 導入・`AuthViewModel` の失効/一時障害区別・`AuthInterceptor` の失効通知）における third_vote の REJECT と、それに伴う実装方針変更。

## 目的
CI-T10（`lastFailure != null ⇒ authState is Unknown` の不変条件）を、並行する `refreshAuth` 呼び出しの下でも成立させる。

## 現在地
third_vote_completion（adversarial-verdict.md）で CONDITIONAL PASS。CI-T10 の競合はコード上で閉じたと判定されたが、全体テスト557件のgreen証跡（結果XML）は再実行時に上書き消失しており未確認、rename の独立コミット化は git_compose 待ち。いずれも completion_gate/verify_script 側へ持ち越し済み（観測: supervisor-validation.md、adversarial-verdict.md 記載）。

## 棄却した案（t3: third_vote の REJECT を受けた方針変更）
- **開始時の認証状態を変数（`wasAuthenticated` 相当）に保存し、失敗反映時にその保存値で分岐する実装** → third_vote（third-vote.md）で REJECT。
  - 反証: 同一トークンで認証確認 A・B を並行実行し、A 成功後に B が一時障害で完了すると、B は開始時に保存した状態（未認証）に基づいて動くため、`Authenticated` かつ `lastFailure != null` という不変条件違反が発生する（観測: third-vote.md L11-17、`AuthViewModel.kt:195,215-217` 該当箇所への言及）。
  - 動的再現は未実施、静的反証のみ（観測: third-vote.md L17）。
- **採用した代替**: 失敗反映時に「開始時の状態」ではなく「`sessionLock` 内で確認する現在の状態」を見て分岐する実装に変更。`wasAuthenticated` 相当の変数は削除。
  - 検証: adversarial-verdict.md（third_vote_completion）で、成功→一時障害・一時障害→成功の両順序を反証試行し、いずれも崩れず（観測: adversarial-verdict.md L32-39）。回帰テスト `AuthViewModelTest.kt:861` 相当（`concurrent_success_then_transient_failure_...`）で `delay(1)`/`delay(2)` により完了順序を固定して担保。

## 観測と推測の区別
- 観測: REJECT の理由・反証内容・修正後の再反証結果は上記の各レポートファイルに記載された内容そのもの。
- 推測: なし（本エントリは各レポートの記述をそのまま要約したもの）。

## 参照
- third-vote.md（third_vote, step 5, REJECT 判定）
- adversarial-verdict.md（third_vote_completion, step 11, CONDITIONAL PASS 判定）
- supervisor-validation.md（completion_gate, 対象2の充足根拠）
