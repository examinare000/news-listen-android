## android リファクタ S0: 認証失効（`ApiException.Unauthorized`・`AuthInterceptor`・`onSubjectLeave`）

## 概要
失効（保存トークンでの 401）とログインの資格情報誤りを型で区別し、失効経路にも主体離脱の cleanup（音声＋FCM）を適用する。正本は user 承認済みの Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.2 Account・§4 CI-T10〜T14・T21・§6 S0 行）。本タスクは**承認済み指示書に従う実装**であり、analyze_order は検証モード（新規設計をしない）。generate_spec の spec.md は Spec の該当契約（CI-T10〜T14・T21）の抜粋で足り、契約 ID は Spec のものを再利用する。

着手順 1（S1〜S3 に依存しない。最初に実施）。

## 前提・着手条件
- Selection Gate 依存なし。
- **再生停止（`stopForSubjectLeave`）は S2 で追加する**。S0 では主体離脱の事後条件は音声キャッシュ削除＋DL cancel（既存 `cancelDownloadsAndClearCache`）と FCM トークン解除（既存）のみを `onSubjectLeave` から呼ぶ。
- `SessionStore.save` の失敗返却（RF16）は §8.3 の「記録のみ」から本 slice へ繰り上げる（SG-R16、user 承認済み。逸脱として扱う）。
- `docs/trial-log/` を最初に読み、棄却済み案を再試行しない。

## 対象（android サブモジュールのみ。ファイル単位）
1. **`ApiException`（`network/`）**: `Unauthorized` を追加。`validateResponse` で `code == 401` を `Unauthorized` に写像する。
2. **`AuthViewModel.refreshAuth`**: `Unauthorized` のみ `sessionStore.clear()` して `Unauthenticated` へ。`NetworkError` / `HttpError(5xx)` / `DecodingError` は `Unknown` を維持し、トークンは保持したまま `lastFailure`（別 StateFlow）を設定する（UI は再試行導線）。
3. **`AuthViewModel.login`**: `Unauthorized` を catch したときの文言は現行の「ユーザーIDまたはパスワードが正しくありません」を維持し、`onUnauthorized` は発火させない（`AuthViewModel.kt:151-156` は失効ではなく login の資格情報誤りの文言分岐。CI-T21 で pin）。
4. **`network/AuthInterceptor`**: `onUnauthorized: () -> Unit`（関数注入、`tokenProvider` と同型）を追加。**発火条件は「三点一致かつ `Authorization` ヘッダを付与した（`tokenProvider` が非 null だった）リクエストの 401」のみ**。トークン無しの 401（login / passkey login の資格情報誤り）では呼ばない。`auth/` を import しない。
5. **`di/AppContainer`**: `AuthInterceptor.onUnauthorized` を `AuthViewModel` の失効通知経路へ配線する。
6. **`AppContainer.onLogoutCleanup` → `onSubjectLeave` rename**（SG-R18、挙動不変・**独立コミット**）。logout 経路に加え、失効通知（`onUnauthorized`）からも呼ぶ。
7. **`AuthViewModel` の `CleanupIncomplete(parts)`**: 主体離脱の cleanup 手順（音声・FCM）は各手順を独立 try/catch にし、失敗した手順名を `CleanupIncomplete(parts)` として StateFlow に残す（OB-C10）。認証状態の遷移は cleanup の成否で止めない。
8. **`SessionStore.save`**: 失敗を呼出元へ返す（`Boolean` か `Result`）。`AuthViewModel.login` は保存失敗時に `Authenticated` へ遷移せずエラー表示する（`InMemorySessionStoreTest` に失敗注入で確認）。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T10 | `refreshAuth`: `Unauthorized` のみ `clear` + `Unauthenticated`。`NetworkError` / `HttpError(5xx)` / `DecodingError` は `Unknown` 維持＋トークン保持＋`lastFailure` | T-T10（既存 `AuthViewModelTest.kt:95` を反転） |
| CI-T11 | 三点一致かつ `Authorization` 付与リクエストの 401 のときのみ `onUnauthorized` を 1 回呼ぶ。トークン無し（login / passkey）・非一致 host の 401 では呼ばない。ヘッダ付与契約（CI-A13/A14）は不変 | T-T11（既存 `AuthInterceptorTest` の `FakeChain` に応答を持たせる） |
| CI-T12 | 失効通知後: `Unauthenticated`、`sessionStore.load()==null`、cleanup 2 手順（音声・FCM）が呼ばれる。logout も同じ事後条件（**再生停止は S2 で追加**、本 slice の T-T12 では検証しない） | T-T12: Fake の呼出観測（再生停止部分を除く） |
| CI-T13 | cleanup の一部が失敗しても残りは実行され、`CleanupIncomplete(parts)` が観測可能。再実行で同じ事後条件 | T-T13 |
| CI-T14 | `save` 失敗は呼出元へ返り、`login` は `Authenticated` へ遷移しない | T-T14（`InMemorySessionStoreTest` に失敗注入） |
| CI-T21 | `login` が `Unauthorized` を受けたとき文言は「ユーザーIDまたはパスワードが正しくありません」、`onUnauthorized` は発火しない | T-T21（既存 `AuthViewModelTest` の login 401 ケースを `Unauthorized` へ置換） |

## 特性テスト（baseline。着手前に green を確認）
`auth/AuthViewModelTest.kt`（24 件。`:95` は本 slice で反転する）、`network/AuthInterceptorTest.kt`（6 件）、`network/OkHttpApiClientTest.kt`（46 件）、`network/InMemorySessionStoreTest.kt`（4 件）。

## 手順
1. baseline: `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` green を記録。
2. `onLogoutCleanup → onSubjectLeave` の rename を**独立コミット**として先に行う（挙動不変）。
3. T-T14（`SessionStore.save` の失敗返却）→ RED → 実装 → GREEN。
4. `ApiException.Unauthorized` の追加と `validateResponse` の写像 → T-T21（login の文言 pin）→ RED → 実装 → GREEN。
5. T-T10（`refreshAuth` の分岐）→ RED → 実装 → GREEN（既存 `AuthViewModelTest.kt:95` の反転を含む）。
6. T-T11（`AuthInterceptor.onUnauthorized`）→ RED → 実装 → `AppContainer` で配線 → GREEN。
7. T-T12（cleanup 2 手順。再生停止を除く）・T-T13（`CleanupIncomplete`）→ RED → 実装 → GREEN。
8. `grep 'code == 401'` = 0 を確認（他 ViewModel は 401 を比較していないため互換層は不要）。
9. 1 slice = 1 PR。rename は独立コミットのまま残す。temporary path なし。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green（既存 528 件＋追加分）。
- grep oracle: `error("` の grep 件数は本 slice で変えない（S1 で削除）、`currentPodcast` は本 slice で変えない（S2 で削除）、`code == 401` = 0。
- T-T10・T-T11・T-T12（再生停止を除く）・T-T13・T-T14・T-T21 が `verifies: CI-T10〜T14/T21` をテスト名またはコメントに持つ。
- `onLogoutCleanup` という名の関数・呼出が残っていない（rename が全箇所に反映されている）。
- レビュー観点: `AuthInterceptor` が `auth/` を import していない。`onUnauthorized` はトークン無しの 401 では呼ばれない（login / passkey login のテストで確認）。

## 禁止事項 / scope 外
- `stopForSubjectLeave`（再生停止）の追加は S2 で行う。本 slice では呼ばない。
- `BaseFakeApiClient` / `PodcastApi` 切り出し（S1）、`PlaybackSession` / `nowPlaying`（S2）、CI 分割（S3）は行わない。
- `AuthState` の 4 状態化はしない（棄却済み。`Unknown` + `lastFailure` で表す）。
- 意味型（`HttpError` の 401 以外の細分化）の全面導入はしない（RF6 は保留 slice）。
- preferences 消去契約は追加しない（SG-R5 で不採用）。
- 仕様にない業務条件を足さない。

## 参照
- Spec: `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §3.2（Account）・§4（CI-T10〜T14, T21）・§5（CP7・CP8・naming_decisions）・§6（S0 行）
- レビュー: `android/docs/research-reports/2026-09-16-code-design-review.md` §8.2（SG-R5・SG-R6）・§8.3（順 1）
- 親 docs: `docs/design/shared-playback-spec.md` §6.5（主体離脱の事後条件・Android 行）
