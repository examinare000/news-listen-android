# android リファクタ計画（2026-09-16 設計レビュー反映）— takt 委譲用の指示書

2026-09-16 の android 設計レビュー（`android/docs/research-reports/2026-09-16-code-design-review.md`）と user 承認済みの Implementation Spec（`android/docs/design/2026-09-16-implementation-spec-playback-auth.md`）、2026-09-23 の主体離脱の設計決定（親 docs [ADR-104](../../../../docs/adr/104-subject-departure-and-subject-scoped-assets.md)）を、takt の `sdd-governed` ワークフローへ slice 単位で委譲するための指示書（order）一式。正本は親 docs の設計書（`design/android-design.md` §7・`design/shared-playback-spec.md` §4・§6）→ ADR → Spec の順で、本フォルダの各 order はその該当 slice を takt の 1 タスクに切り出したもの。slice ID は親 plan `docs/plan/2026-09-16-design-review-refactor.md` の接頭辞付き ID（A-*）を正本とする（2026-09-23 に旧 S2 の一括切替を 3 段へ再スライス。同日夜の点検で A-S2b を A-S2b1 / A-S2b2 の 2 境界に分けた。親 plan への ID 反映は router 側。旧 S0〜S4 の番号は使わない。完了済みの `S0-auth.md` はファイル名のまま残す）。実装完了後、本フォルダは削除し、確定内容は親 docs `design/android-design.md`（§7 target 節を現状記述へ書き換え）へ移す（`agent-rules/30` の plan ライフサイクル）。

> **2026-10-01 目標アーキテクチャ（ADR-110）による改訂**: 正本に Implementation Spec `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「目標 Spec」）が加わった。module 全体の構造（層と path・依存の規則・context ごとのモデル・command と query・検査）と slice の全体は目標 Spec が正、再生・認証の状態遷移と契約の詳細は既存の Spec（2026-09-16）が正（目標 Spec §1.2）。補完 slice **A-T1〜A-T9** の order を足し、未着手の A-S2b1・A-S2b2・A-S2c・A-S4 を目標 Spec §8.3 の全項目で補正した（各 order の冒頭の「2026-10-01 … による補正」節に差分）。A-S3 は補正なし。**投入の順は下の「slice と投入順」の表**（目標 Spec §8.1）。
>
> **投入前の前提点検が必須**: どの order も、投入の直前に、その order の「着手前の前提点検」の手順で実測値（件数・行番号・型名・grep の結果・許可リストの件数）を数え直す。値が違えば order を直してから投入する（後の wave の order は、書いた時点から実コードが変わっている）。あわせて、依存する slice が main に入り親ポインタが進んでいること、対で結ばれた order（前の slice が固定した許可リストの件数・作った型名）を突き合わせる（親 plan「再開手順」3）。
>
> **全 slice 共通の構造検査の規則**（A-T1 の後）: main に新しいファイルを作る slice は、そのファイルを `test/…/architecture/LayerMap.kt` に層つきで登録する（対応表に無いファイルがあると `ArchitectureStructureTest` が落ちる）。違反を消した slice は、その組を `Allowlist.kt` から消す（残すと「許可リストにあるのに違反が無い組」で落ちる）。許可リストを増やしてよいのは A-S4a の `OfflineLibrary` → `AudioCacheManager` の 1 件だけ（削除は A-T3a1）。
>
> **実装は止めてある**（親 plan「実装の停止と再開ゲート」）。再開ゲートが満たされ user が再開を指示するまで、どの order も投入しない。

共有仕様の先行 PR（`shared-playback-spec.md` §6.4・§6.5）は news-listen-docs #133 で **完了**。§6.7 の Selection Gate は 2026-09-16 に全て確定し、SG-X3 は 2026-09-23 に ADR-104 で「待たない＋主体識別」へ改訂済み。2026-10-01 時点で残る判断は 1 件: **A-T8a の記事の文面**（`feed/` の `= e.message` 3 箇所。決定 SG-D6 は再生の文面だけを決めた）。A-T8a は決まるまで投入しない。ほかの order は確定値を実装対象に含む（再生の文面は SG-D6 で確定し、A-T3b は ready）。

## slice と投入順

目標 Spec §8.1 の表の順が投入の順（同じ submodule は 1 本ずつ）。状態: 完了 / ready（前の slice が main に入れば投入できる）/ B-S5b 待ち / 判断待ち。

| 順 | ID | order | 内容 | 依存 | 状態 | 種類 |
|---|---|---|---|---|---|---|
| — | A-S0 | [S0-auth.md](S0-auth.md) | `ApiException.Unauthorized`・`refreshAuth` の分岐・`AuthInterceptor.onUnauthorized`・`onSubjectLeave` rename・`SessionStore.save` の失敗返却 | なし | 完了（android PR #28・2026-09-23） | 小ステップ |
| — | A-S1 | [A-S1-test-foundation.md](A-S1-test-foundation.md) | `BaseFakeApiClient` と 9 Fake の継承化・throwing default 9 箇所削除・`PodcastApi` 切り出し・`FakePodcastApi`・`FakePlayerController` の `state` 注入経路 | A-S0 | 完了（android PR #33・2026-09-28） | 挙動不変 |
| — | A-S2a | [A-S2a-playback-domain.md](A-S2a-playback-domain.md) | `PlaybackState` union・`PlaybackSession`（11 遷移）・`core/ResumeRule` の新設 | A-S1 | 完了（android PR #36・2026-09-30） | ① domain |
| 1 | A-T1 | [A-T1-layer-skeleton.md](A-T1-layer-skeleton.md) | 層の対応表と構造の検査（TA-V1〜V5・V8・V9、`PlaybackQueue` の TA-V6）。今の違反を許可リストに固定。test だけ | なし | ready | 適用 |
| 2 | A-S2b1 | [A-S2b1-queue-speed-contracts.md](A-S2b1-queue-speed-contracts.md) | Queue の不変条件・速度 8 段と判定関数 1 つ（TA-R-PF3）・値域外の拒否・`invalidate` | A-T1 | ready（補正済み） | ②-1 契約 |
| 3 | A-T2a | [A-T2a-episode-decode.md](A-T2a-episode-decode.md) | `Episode` と判別（PS-07・07b）、`EpisodeDecoder`・`PodcastApiAdapter`（新規だけ） | A-S2b1 | ready | 適用 |
| 4 | A-T2b | [A-T2b-episode-replacement.md](A-T2b-episode-replacement.md) | セッション・キュー・`PodcastApi`・画面の読みを `Episode` へ。`PodcastStatusBadge` 削除 | A-T2a | ready | 適用（変わる挙動 PS-07・07b） |
| 5 | A-S2b2a | [A-S2b2a-playback-entry-session.md](A-S2b2a-playback-entry-session.md) | 再生の入口を `PlaybackSession` へ差し替える前半（`session`・`nowPlaying`・`startEpisode`・`retry`・既定速度・`invalidate` 接続。PS-01〜04・08〜11） | A-T2b | ready（補正済み） | ②-2 entry |
| 5b | A-S2b2b | [A-S2b2b-playback-sync-and-leave.md](A-S2b2b-playback-sync-and-leave.md) | 同じく後半（完聴の順序・位置同期の送信条件・`stopForSubjectLeave`。PS-05・05b・06、SL-01 の再生停止） | A-S2b2a | ready（補正済み） | ②-2 entry |
| 6 | A-S2c | [A-S2c-playback-cleanup.md](A-S2c-playback-cleanup.md) | 旧実装の削除・`play` の非公開化・`onPlaybackCompleted` 削除（TA-V5 3 → 2） | A-S2b2b | ready（補正済み） | ③ cleanup |
| 7 | A-S3 | [A-S3-ci.md](A-S3-ci.md) | `ci.yml` を 3 ステップに、`jvmToolchain(17)` | A-S2c | ready（補正なし） | — |
| 8 | A-S4a | [A-S4a-subject-scoped-cache.md](A-S4a-subject-scoped-cache.md) | 音声キャッシュの主体化・`OfflineLibrary`・起動時回収（SL-06・08・09・10） | A-T2b・B-S5a（完了）。A-S2b2a〜A-S3 のどの位置にも挟める（同時には走らせない） | ready（補正済み） | 適用 |
| 9 | A-S4b | [A-S4b-subject-departure.md](A-S4b-subject-departure.md) | 主体離脱の導出と順序・後始末・FCM の解除を連鎖削除に任せる・`PreferenceItem` | A-S4a・**B-S5b** | **B-S5b 待ち**（遅れるときは A-T3a1〜A-T4 を先に。A-T5a より前に入れる） | 適用 |
| 10 | A-T3a1 | [A-T3a1-playback-coordinator.md](A-T3a1-playback-coordinator.md) | `PlaybackCoordinator` を取り出して委譲・`AudioStore`・Media3 の分類を adapter へ | A-S2c・A-S4a | ready | 適用 |
| 10b | A-T3a2 | [A-T3a2-playback-read-models.md](A-T3a2-playback-read-models.md) | `QueueView`・`EpisodeRow`・id の command。画面がリードモデルだけを読む | A-T3a1 | ready | 適用 |
| 11 | A-T3b | [A-T3b-playback-notice.md](A-T3b-playback-notice.md) | `PlaybackNotice` と固定文言（**SG-D6**）。生成の失敗の識別子を文言へ | A-T3a2 | ready（SG-D6 で判断済み） | 適用（変わる挙動 SG-D6） |
| 12 | A-T4 | [A-T4-failure-meaning.md](A-T4-failure-meaning.md) | `ApiException` を `core/` へ、意味の variant。code の比較 6 → 0 | A-T3a2 | ready | 適用 |
| 13 | A-T5a | [A-T5a-preferences-types-and-sync.md](A-T5a-preferences-types-and-sync.md) | `PlaybackSpeed`・`WeeklyGoal`・型つきの `PreferencesStore`・`PreferencesSync`・`PreferencesView`（型と port） | A-T4・**A-S4b** | ready（A-S4b の後） | 適用 |
| 13b | A-T5b | [A-T5b-preferences-screens.md](A-T5b-preferences-screens.md) | 画面の切り替え（選択肢の表と port の直接の呼出を無くす） | A-T5a | ready | 適用 |
| 14 | A-T6a | [A-T6a-account-domain-and-auth-port.md](A-T6a-account-domain-and-auth-port.md) | `AccountUser`・`Role`・`SubjectId`・`AuthApi` | A-T5b | ready | 適用 |
| 14b | A-T6b | [A-T6b-account-read-models.md](A-T6b-account-read-models.md) | `AccountApi`・セッションと passkey のリードモデルと画面 | A-T6a | ready | 適用 |
| 15 | A-T7a | [A-T7a-learning-dashboard.md](A-T7a-learning-dashboard.md) | ダッシュボード・ストリーク・実績（§6 の 1・3） | A-T6b | ready | 適用 |
| 16 | A-T7b1 | [A-T7b1-learning-quiz-vocabulary.md](A-T7b1-learning-quiz-vocabulary.md) | クイズ・語彙の登録。学習の中継 3 操作を `PodcastViewModel` から出す | A-T7a | ready | 適用 |
| 16b | A-T7b2 | [A-T7b2-learning-vocabulary-test.md](A-T7b2-learning-vocabulary-test.md) | 単語テスト（`VocabularyTestSession`） | A-T7b1 | ready | 適用 |
| 17 | A-T8a | [A-T8a-catalog-articles.md](A-T8a-catalog-articles.md) | `Article`・`PendingCuration`・`ArticleRow`・`FeedApi`（§6 の 2） | A-T7b2 | **判断待ち**（記事の文面 1 件。order 冒頭） | 適用 |
| 18 | A-T8b | [A-T8b-sources-notifications.md](A-T8b-sources-notifications.md) | Sources・Onboarding・Notifications の port とモデル。TA-D2 の許可リストが空 | A-T8a | ready（投入は A-T8a の後） | 適用 |
| 19 | A-T9 | [A-T9-package-layout.md](A-T9-package-layout.md) | 目標の package へ移し、許可リストを空にし、対応表を package の規則へ | A-T8b・A-T3b | A-T8b（D-A8a-1）の後（判断待ちの A-T8a に推移的に依存する） | 適用（機械的） |
| 未起票 | 位置同期（クライアント） | — | ADR-109 の決定 7〜13 | A-S2c・backend B-S7（A-T3a2 の後に置くと入れやすい） | 未起票（SG-C79） | — |

目標 Spec の「保留」だった RF6 全面・RF8・RF9・RF10・RF2・PS-07 は、A-T2a〜A-T8b に入った（目標 Spec §10.2）。baseline の報告の仮の名前との対応は目標 Spec §8.1 の末尾。

- **1 つの order ファイル = 1 つの PR = 1 つの slice ID**（2026-10-01）。投入の道具（`.takt/enqueue-orders.mjs`）は order ファイル単位で投入し、ID を「ファイル名が `<ID>-` で始まる」で解決するため。2 PR を持っていた order（A-S2b2・A-S4・A-T3a・A-T5・A-T6・A-T7b）は ID 付きの 2 ファイルに分けた。各 order・Spec の本文に残る「A-S2b2」「A-S4」「A-T3a」「A-T5」「A-T6」「A-T7b」は、分けた 2 つ（a / b、または 1 / 2）を合わせた呼び名として読む。
- 直列の依存（2026-10-01 改訂。目標 Spec §8.1）: A-T1 → A-S2b1 → A-T2a → A-T2b → A-S2b2a → A-S2b2b → A-S2c → A-S3。A-T2b → A-S4a → A-S4b（A-S4b は B-S5b 待ち）。A-S2c・A-S4a → A-T3a1 → A-T3a2 → A-T3b・A-T4 → A-T5a（A-S4b の後）→ A-T5b → A-T6a → A-T6b → A-T7a → A-T7b1 → A-T7b2 → A-T8a → A-T8b → A-T9。以下は 2026-09-30 以前の記述（A-S4 の位置の根拠として残す）: A-S4 は backend 契約 ＋ A-S1 ＋ **A-S2b1** の後で（2026-09-30 SG-C49。A-S4 は A-S2b1 の PR が main に入ってから投入する）、A-S2a・A-S2b2・A-S2c・A-S3 と独立に投入できる（A-S4 の後始末の手順集合は着手時点の `onSubjectLeave` を引き継ぐ。A-S2b2 が先なら `CleanupStep("playback_stop")` を含む）。SL-01 の「再生停止」部分は A-S2b2 と A-S4 の両方が merge されて初めて green。
- **A-S2b2 ↔ A-S4 の順序不定**（2026-09-24。2026-10-01 の分割後は A-S2b2a ↔ A-S4a（`currentSubject` の捕捉地点と `invalidate` の 2 引数化）と A-S2b2b ↔ A-S4b（`CleanupStep("playback_stop")`）の 2 組）: 両 order は `podcast/PodcastViewModel.kt`・`di/AppContainer.kt` が重なるため**並行不可**（同時に 2 つ走らせない）。どちらが先に merge されても他方が追随できるよう、両 order に「先に merge されている場合の追随」節を対称に置き、共有する値を固定した: `CleanupStep.name` の 5 値と順序、`invalidate(subject, id)` の主体はセッション開始時に固定した値（`currentSubject()` を都度呼ばない）、`retry()` は `Errored` 以外で no-op、`NowPlaying` は `podcast/PlaybackSession.kt` と同ファイル、`invalidate(` の grep 到達点は A-S2b2 単独 2 箇所 → A-S4 で 3 箇所（更新許可済み）。
- A-S2b を 2 つに分けた理由（2026-09-23 夜）: 旧 A-S2b は「Coordinator に触らない契約（Queue の不変条件・速度値域・`invalidate` 操作）」と「Coordinator の差し替え（現在再生中の正本・再生状態と失敗・完聴順序・送信条件）」の 2 境界を抱え、1 PR の見込みが `PodcastViewModel.kt` 560 行＋`PodcastViewModelTest.kt` 1,218 行の書き換えを含めて 1,000 行を超えていた。前者は `PlaybackSession` に依存せず単独で契約テスト green にでき、失敗時の巻き戻しを Coordinator 側に閉じられる。
- 3 段分割の判定基準（親 plan）: ① は新規ファイルの一覧と契約テスト green、② は「§2・Q-* 挙動不変（特性テスト）」＋「変更行の列挙（準拠テスト）」、③ は削除対象の列挙と参照 0 件の grep。
- **2026-09-30 の前提点検（wave 3）**: A-S2b1 と前後の A-S2a・A-S2b2 を現行 main と照合した。A-S2a（点検時は投入済み。その後 android PR #36 で完了）に問題は無い。user が確定した決定（親 docs 監査レポート §5）: 順序を A-S2b1 → A-S4 に固定（SG-C49）、`setQueue` の重複除去と開始位置（SG-C50。共有仕様 §2.4・Q-33）、`stopPlayback()` の終状態は `NothingPlaying`（SG-C51）、取得前・開始前の失敗は Coordinator が `Errored` の値を代入して表す（SG-C52）、総時間の正本（SG-C54）、完聴時の順序は送信を始める順（SG-C61）、手動で選んだものが開始前に再生不可と分かる場合は状態を変えない（SG-C62）。A-S2b1 は反映済みで未決は無い。A-S2b2 は Blocker（`stopPlayback()` の終状態）があったが、同日に order を直した（S2b2-1〜S2b2-8 と SG-C51・C52・C54・C61・C62 を反映。order を書く側の導出は A-1〜A-4 = 手動の開始は判定と取得の後でキューと Session を変える／「何も再生していない」は `NothingPlaying`／`Active` の速度は開始時の値／完聴の送信は別の coroutine の直列で、次の開始は待たない）。**A-S2b2 は A-S2a の成果（遷移関数の名前・総時間の型・`FakePlayerController.setState` の連動範囲）に依存するので、A-S2a の merge 後に実物と照合してから投入する**。点検の記録は親 docs `research-reports/2026-09-30-wave3-order-premise-check.md`。
- **2026-09-30 の前提点検（wave 1 完了後）**: A-S1 は完了。A-S2a の前提は実測と一致した（`error("` 0 件、`code == 401` は `AuthInterceptor.kt:48`・`OkHttpApiClient.kt:459` の 2 箇所、`currentPodcast` は main 30・test 27）。A-S1 が `FakePlayerController` に置いた状態注入経路は `setPlaying(Boolean)` で、A-S2a が `setState(PlaybackState)` に接続した（実装済み）。**A-S2a の order は変更しない**。再生セッションの停止は遷移表の外のリセットで、Android は Coordinator が `NothingPlaying` を代入して表す（遷移関数は通らない。分母 11 は不変。親 docs 監査レポート §5 の SG-C24・SG-C25、Spec 冒頭の 2026-09-30 追記）。代入の実装は A-S2b2。
- Android に効く確定済み Selection Gate: **SG-X1**（完聴時に `duration` を 1 回送る。A-S2b2）、**SG-X3**（待たない＋主体識別。ADR-104。A-S4）、**SG-X4**（一時停止中は周期送信しない。A-S2b2）、**SG-A6**（主体依存 4 key を消す。A-S4）、**SG-B3**（`Unknown` 中は回収しない。A-S4）、**SG-B4**（FCM 解除はサーバ連鎖。A-S4）、**SG-B6**（`user_id` 任意は 3 platform 共通。A-S4）、**SG-C16**（2026-09-24。主体離脱の後始末から位置同期を外す。共有仕様 §6.4 の送信契機「状態変化」に離脱を含めない。A-S2b2 の `stopForSubjectLeave`・A-S4 の手順集合）、**SG-C17**（2026-09-24。`download` / `removeDownload` / `cancelDownloadsAndClearCache` を `podcast/OfflineLibrary.kt` capsule へ移し `PodcastViewModel` は中継のみ。学習の中継 3 操作は §7.2 の RF8 保留どおり学習サイクルまで残す。A-S4）。SG-X2 / SG-X5 は Android に無関係。
- 正本間の差（order 側で採用先を明記済み。docs 反映は完了時）: CI-T15 の拒否時挙動は Spec「既定へ正規化」ではなく親 docs §7.2「直前の妥当値を維持」を採る（A-S2b1）。主体依存 key は §7.2・親 plan の「3 key」ではなく共有仕様 §6.5 分類表の「4 key」、FCM は §7.2 の「トークン解除」ではなく §6.5 Android 行の「連鎖削除に任せ client 呼出を削除」を採る（A-S4）。§7.2「`PodcastViewModel` に残る責務」の操作 15 のうち `cancelDownloadsAndClearCache` は SG-C17 で `OfflineLibrary` へ移り、`download` / `removeDownload` は中継になる（A-S4）。Spec §3.2 の「`CleanupIncomplete` を `AuthViewModel` の StateFlow に」は `auth/SubjectCleanup` の StateFlow と読み替える（`AuthViewModel` への注入は `onSubjectLeave: (Subject) -> Unit`。A-S4）。**2026-09-30**: Spec の本文は現行値へ更新済み（CI-T15・主体依存 4 key・FCM の連鎖削除・ダウンロード 3 操作の移設。Spec「9. 改訂履歴」）。`CleanupIncomplete` の置き場は Spec では保留のまま。
- grep oracle の件数判定: `code == 401` は「2 箇所から増えない」を **`grep -rn 'code == 401' app/src/main | wc -l` = 2** の件数判定で書く（2026-09-24 実測 2）。
- grep oracle `code == 401` の到達点は 0 件ではなく **2 箇所**（`AuthInterceptor.kt:48` の発火条件・`OkHttpApiClient.kt:459` の写像。A-S0 の帰結）。各 order は「2 箇所から増えない」で書く。
- 他モジュールとの契約: パスワード規則は backend 正本（[ADR-101](../../../../docs/adr/101-password-policy-cross-client-unification.md)）。Android は現状の文言のまま変更なしのため order を作らない。
- 準拠テストは行 ID（`PS-*` / `SL-*` / `RS-*`）をテスト名に含める（共有仕様 §5）。

## 着手条件（全 order 共通）

- 依存 slice の **android PR が main に merge 済み** かつ **親リポ `news-listen` の submodule ポインタが進んでいる**（親で `git submodule status` を実行し `android` 行に `+` が無い）。takt の worktree は親 main から fresh clone され、bootstrap が submodule と親の記録の一致を検査するため（不一致は `tree_incomplete`）。
- 他モジュールに依存する slice は PR 番号ではなく「契約が親 main にあること」で判定する（A-S4 の「依存契約」）。2026-10-01（目標 Spec §8.3 A-S4 補正 1・導出 A-19）: **A-S4a は B-S5a（`user_id` の契約。完了）だけ、A-S4b は B-S5b（セッション削除の FCM 連鎖）**を待つ。

## 投入順と release トリガ（2026-10-01 改訂）

release トリガはどの slice も同じ: **依存する slice の android PR が main に入り、親ポインタ PR が merge されたら**、その order の「着手前の前提点検」を通して release する。同じ submodule は 1 本ずつ（並行不可）。順と依存は上の「slice と投入順」の表。他モジュールの契約に依存するのは A-S4a（B-S5a。完了）と A-S4b（B-S5b の backend PR ＋ 親ポインタ PR）だけ。

## takt への投入手順（親リポ `news-listen` の作業ツリーで）

order はサブモジュール内の docs にあるが、takt のタスク単位は親リポ（`.takt/config.yaml` の `submodules: all`）。order 本文をタスク内容として渡す（投入時にコピーされるため、投入後の order 修正は反映されない）。

```bash
# 例: A-S2a（takt 0.66 は非対話の add を受けないため、実際は .takt/enqueue-orders.mjs の ORDERS 配列で投入する。親 plan「投入の型」）
node .takt/enqueue-orders.mjs
node .takt/hold-tasks.mjs release takt/refactor/android-a-s2a-playback-domain
takt run
```

- ブランチ名は `takt/refactor/android-<slice>`。1 slice = 1 タスク = 1 PR（サブモジュール PR → 親 PR。draft は作らない。`.takt/facets/knowledge/project-context.md`）。
- 前 slice の android PR が main に merge され、親ポインタ PR も merge されてから次を release する。
- **投入前に、指示書を `analyze_order` の受入検査（全称命題の対象集合の数え上げ／条項どうしの矛盾／未決の選択）へ自分で通す。** 未決が 1 件でも残っていれば投入しない（親 `docs/trial-log/order-acceptance-inspection-finds-design-defects.md`）。
- analyze_order は order を「承認済み指示書」として**検証モード**で受ける（新規設計をしない）。generate_spec の `spec.md` / `plan.md` は Spec の該当契約（CI-T*）の抜粋で足り、新しい契約 ID を作らない。
- 各 order の「特性テスト（baseline）」が green でなければ着手しない。
- 検証コマンド（android の実行形式は Gradle）: `JAVA_HOME=<Android Studio 同梱 JBR> ./gradlew clean testDebugUnitTest --console=plain`（A-S3 の merge 後は `jvmToolchain(17)` により `./gradlew clean testDebugUnitTest` のみで良い）。構造テスト `ArchitectureStructureTest`（A-T1）は `testDebugUnitTest` に入る。**takt の実行中は、takt の worktree の外で Gradle を走らせない**（同じ Gradle デーモンとビルドキャッシュを取り合う）。JDK 26 では Kotlin コンパイラが起動不能なため JBR を明示する。JDK 不適合の実行の直後は増分キャッシュが汚れるため `clean` を挟む（`android/docs/research-reports/2026-09-16-code-design-review/verification-run.md`）。

## 完了後
- 各 slice の merge 後、`docs/trial-log/` に棄却・方針転換があれば追記（takt の record_trial_log が行う）。
- A-T2b 完了で共有仕様 §4.4 の PS-07・PS-07b、A-S2a〜c 完了で §4.3（RS）・§4.4（PS-01〜06・08〜11）、A-S4a 完了で SL-06・SL-08〜SL-10、A-S4b 完了で SL-01 / SL-02 / SL-04 / SL-07 の android 保留を解除する（§5 の解除条件）。
- 全 slice 完了で本フォルダを削除し、親 docs `design/android-design.md` §7 を「target 設計（未着手）」から現状記述へ書き換える（§7.2 の「3 key」「FCM トークン解除」「残る責務の操作 15」・Spec CI-T15 の「既定へ正規化」・Spec §3.2 の `CleanupIncomplete` の置き場・共有仕様 §6.4 の送信契機（SG-C16「離脱は状態変化に含めない」）もこのとき揃える）。
