## android リファクタ A-T2b: セッションの保持値・キューの要素・`PodcastApi` の戻り値・一覧と再生の画面の読みを `Episode` へ置き換える

## 1. 目的と、応える要求・設計・契約の ID

A-T2a の `Episode` を、再生の旧い実装の上で先に使い始める（A-S2b2 が新しく書くコードに DTO を持ち込まないため。Spec §8.2）。挙動は PS-07・PS-07b の 2 行だけが変わる。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-POD-01 | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-1・AQ-3 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2・8、ADR-103・ADR-108、SG-C64、導出 A-24・A-26、Spec §10.3 の 3（(a) A-T2b で適用。2026-10-01 確認済み） | Spec §10 |
| 共有仕様 | PS-07・PS-07b（§4.4）、§6.6、§6.8（逆変換はしない） | `docs/design/shared-playback-spec.md` |
| Spec | TA-D1（`PlaybackSession.kt`）・TA-D2、TA-M-PB・TA-M-CT、TA-R-CT1・CT2、§8.2 の一時経路、§8.4「A-T2b」、§10.2 の 1・2 行目 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T16（`PodcastApi` の 5 操作。A-T2b 以後、戻り値は `Episode`、`ApiClient` は継承しない） | `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §4 |

## 2. 前提（着手条件）

- A-T2a の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テスト（`PodcastViewModelTest` 52 件・`PodcastViewModelPortTest`・`PlaybackSessionTest`・`PlaybackMetadataTest`・`PodcastStatusBadgeTest`・`NoThrowingDefaultStructureTest` を含む）と `ArchitectureStructureTest` が green。
- 判断待ち: 無い（PS-07・PS-07b の適用は 2026-10-01 に確認済み）。

## 3. 着手前の前提点検（投入の直前に `android/` で数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| `PodcastResponse` を使う main のファイル | `grep -rln 'PodcastResponse' app/src/main` | 対象のファイル集合を記録（`podcast/` の 8・`network/` の `PodcastApi`・`ApiClient`・`OkHttpApiClient`・`model/` ほか） |
| セッションの保持値 | `grep -n 'PodcastResponse' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt` | `:3,14-17,21,27,33,39,84`（import と 4 状態・4 遷移関数・`Loaded`） |
| キューの要素 | `grep -n 'PlaybackQueue<PodcastResponse>' app/src/main -r` | `podcast/PodcastViewModel.kt:117,123` |
| DTO が `QueueItem` | `grep -n 'QueueItem' app/src/main/java/com/rioikeda/newslisten/model/PodcastResponse.kt` | `:3`（import）・`:37` |
| `ApiClient` の継承 | `grep -n 'interface ApiClient' app/src/main/java/com/rioikeda/newslisten/network/ApiClient.kt` | `:37` `ApiClient : LearningApi, VocabularyTestApi, PodcastApi` |
| `PodcastStatusBadge` の参照 | `grep -rn 'PodcastStatusBadge' app/src` | main（`PodcastStatusBadge.kt`・`PodcastViewModel.kt:551-556`・`PodcastRowView.kt:103,136` ほか）と test（`PodcastStatusBadgeTest` 5 件） |
| 画面 5 ファイルの DTO | `grep -n 'PodcastResponse\|QuizQuestion' app/src/main/java/com/rioikeda/newslisten/podcast/{PodcastScreen,PodcastRowView,AudioPlayerSection,QueueSheet,QuizSheet}.kt` | 記録する |
| テストの件数 | `grep -c '@Test'` を `PodcastViewModelTest`（52）・`PodcastViewModelPortTest`（1）・`PlaybackSessionTest`（19）・`PlaybackMetadataTest`（5）・`PodcastStatusBadgeTest`（5）・`NoThrowingDefaultStructureTest` に | 記録する |
| 許可リスト | `Allowlist.kt` の件数と、TA-D1 の `PlaybackSession.kt`・TA-D2 の 7 ファイル＋`model/PodcastResponse.kt` の組 | 記録する |

## 4. 対象の path と対象外

**変える（main）**: `podcast/PlaybackSession.kt`（保持値を `Episode.Playable`。`Loaded` も）、`network/PodcastApi.kt`（戻り値を `Episode`／`List<Episode>`。`fetchPodcasts(): List<Episode>`・`fetchPodcast(id): Episode`）、`network/ApiClient.kt`（`PodcastApi` を継承しない）、`network/PodcastApiAdapter.kt`（port の実装として配線できる形に）、`di/AppContainer.kt`（`PodcastViewModel` へ `PodcastApiAdapter` を渡す）、`podcast/PodcastViewModel.kt`（型と、開始前の判定を `Episode` の種類で）、`podcast/PlaybackMetadata.kt`（DTO の拡張関数を外し、`Episode` から作る）、`podcast/PodcastStatusBadge.kt`（**削除**）、`model/PodcastResponse.kt`（`QueueItem` の実装と `core` の import を外す）、画面 5 ファイル（`PodcastScreen`・`PodcastRowView`・`AudioPlayerSection`・`QueueSheet`・`QuizSheet` の設問の型）。
**変える（test）**: `PodcastViewModelTest`・`PodcastViewModelPortTest`（fixture `podcast(…)` が `Episode` を返す。Fake の型）、`PlaybackSessionTest`、`PlaybackMetadataTest`、`PodcastStatusBadgeTest`（`catalog/EpisodeTest` へ統合して削除）、`NoThrowingDefaultStructureTest` の 2 件目（`PodcastApi` の経路の検査を新しい形へ）、`FakePodcastApi`（戻り値の型）、`FakePodcastApiClient`（`ApiClient` と `PodcastApi` を兼ねていれば 2 つに分ける。Spec §11）、`architecture/Allowlist.kt`（消える組を消す）。

**対象外**: 再生の手順（A-S2b2）。学習の中継 3 操作（`loadVocabularyRegistrations`・`saveVocabulary`・`submitQuizAnswers`。`ApiClient` のまま。A-T7b）。リードモデル `NowPlaying`・`QueueView`・`EpisodeRow`（A-S2b2・A-T3a）。失敗の表示の文言（現行どおり識別子をそのまま出す。A-T3b）。ダウンロードの実体の移設（A-S4a）。`PodcastResponse` の field 名・`@SerialName`。

## 5. 変更の責務

| 層 | 変えること |
|---|---|
| domain | `PlaybackSession` の `Starting`・`Active`・`Completed`・`Stopped`・`SessionEpisodeRef.Loaded` が `Episode.Playable` を持つ（TA-D1 の `model` の import が消える）。遷移関数の名前・辺・例外は変えない（11 辺） |
| application（port） | `PodcastApi` の戻り値が `Episode`。`ApiClient` は port を継承しない（導出 A-24。`ApiClient` は adapter の中の通信 client として残る） |
| adapter | `PodcastApiAdapter` が `ApiClient` を包んで `PodcastApi` を実装し、`EpisodeDecoder` で写す。`model/PodcastResponse.kt` は `core` を import しない（TA-D2 の `model/` 側） |
| application（ViewModel） | `podcasts: StateFlow<List<Episode>>`、キュー `PlaybackQueue<Episode>`、`currentPodcast: StateFlow<Episode.Playable?>`、開始と保存の引数が `Episode`。開始前の判定は `Episode` の種類（`playabilityError` を `Episode` で書き直す。文言は現行のまま） |
| presentation | 画面 5 ファイルが `Episode`（domain の値）と `queue` を読む（一時経路。§6）。`PodcastRowView` の▶と保存ボタンは `Episode.Playable` にだけ付く |

## 6. 移行の中間状態（Spec §8.2 の一時経路）

| 経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| command が `Episode` を受ける（`playNow(episode)` など） | `PodcastViewModel` | A-T2b | id を受ける command に替わる | A-T3a2 |
| 画面が `Episode`（domain の値）と `queue` を直接読む | 同上 | A-T2b | `EpisodeRow`・`QueueView` に替わる | A-T3a2 |
| 失敗の表示に失敗の識別子をそのまま出す（現行どおり） | 同上 | 既存 | `PlaybackNotice` と識別子の文言写像（SG-D6） | A-T3b |
| 学習の中継 3 操作が `ApiClient` を受ける | 同上 | 既存 | `learning/app/` へ移す | A-T7b1 |

許可リスト: 減る（下の §8）。増やさない。

## 7. 変わる挙動

PS-07・PS-07b の 2 行だけ（Spec §8.4「A-T2b」・§10.3 の 3（(a) 採用・確認済み）・導出 A-26）: `completed` で失敗の識別子がある・音声 URL が空・未知の `status` のエピソードが、再生不可（▶と保存ボタンが出ず、失敗の表示）になる。失敗の表示の文言は現行のまま（識別子をそのまま出す）。

## 8. 契約と検査

- PS-07・PS-07b: 一覧の行（`PodcastRowView`）と開始前の判定で、該当の `Episode` に▶が付かず開始しない。`PodcastViewModelTest` に PS-07・PS-07b をテスト名に含むテストを足す（変えたテストは PS-07・PS-07b を理由に持つ）。
- 特性: `PodcastViewModelTest` 52 件・`PlaybackSessionTest` 19 件・`PlaybackMetadataTest` 5 件の本文と期待値が変わらない（fixture と Fake の型だけを直す）。
- CI-T16: `PodcastApi` の 5 操作が `PodcastApiAdapter` 経由。`NoThrowingDefaultStructureTest` の 2 件目を新しい形で green。
- 許可リストから消える組（Spec §8.4「A-T2b」）: TA-D1 の `podcast/PlaybackSession.kt`、TA-D2 の 7 ファイル（`PlaybackSession`・`PodcastStatusBadge`・`PlaybackMetadata`・`PodcastRowView`・`PodcastScreen`・`QueueSheet`・`AudioPlayerSection`）と `model/PodcastResponse.kt`（`core.QueueItem`）。TA-V4 の状態の文字列 3 件（`PodcastStatusBadge.kt` の削除で消える）。

## 9. 受入とテストのコマンド

- `JAVA_HOME=<Android Studio 同梱の JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0（`android/` で実行。takt の実行中は worktree の外で走らせない）。
- `grep -rn 'PodcastStatusBadge' app/src` = 0（集合 = main と test の全 Kotlin）。
- `grep -n 'PodcastResponse' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0。
- `grep -rn 'PodcastResponse' app/src/main/java/com/rioikeda/newslisten/podcast/{PodcastScreen,PodcastRowView,AudioPlayerSection,QueueSheet,PlaybackMetadata}.kt` = 0。
- `grep -n 'QueueItem\|import com.rioikeda' app/src/main/java/com/rioikeda/newslisten/model/PodcastResponse.kt` = 0。
- `grep -n 'PodcastApi' app/src/main/java/com/rioikeda/newslisten/network/ApiClient.kt` の `interface ApiClient` の行に `PodcastApi` が無い。
- 負の対照: PS-07 のテストの DTO を `error_message = null` に替えると▶が付く（判別が効いていることの確認。PR の説明に記録し、戻す）。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§8 の組が許可リストから消え、ほかは増えていない。
- §9 の grep がすべて期待値。
- 既存の特性テストの期待値を変えたのは PS-07・PS-07b を理由に持つものだけ（PR に「テスト名 → 行 ID」の表）。
- `QuizSheet` の `network` の import（TA-D4）と、学習の中継 3 操作は変わっていない（A-T7b）。

相互矛盾の突き合わせ: 「学習の中継 3 操作は対象外」と「`PodcastViewModel` の DTO が 0」— `submitQuizAnswers` の戻り値 `QuizAnswerResponse` は `PodcastResponse` ではないので、§9 の grep（`PodcastResponse` に限る）と両立する。`model.*` の import は `PodcastViewModel` に残り、許可リストの持ち主は A-T7b。

## 11. 決定 slice か適用 slice か

適用 slice（変わる挙動は決定済みの PS-07・PS-07b だけ）。

## 12. 規模の目安と返却事項

- 規模 ≈ 800 行（test が主。Spec §8.4）。1 PR（1,000 行を超えたら「型と port」「画面」で 2 PR に分ける）。
- 返却事項: 共有仕様 §4.4 PS-07・PS-07b の Android の保留を解除できる旨。既存の Spec §1.3「再生可能」・§5 rejected_overdesign「Episode decode」、android-design §7.3・§7.4 を ADR-110 決定 8 で改める旨（Spec §10.2）。A-S2b2a・A-S2b2b の「着手前の前提点検」の行番号。
