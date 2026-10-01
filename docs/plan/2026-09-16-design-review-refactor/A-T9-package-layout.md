## android リファクタ A-T9: ファイルを目標の package へ移し、許可リストを空にし、対応表を package の規則に置き換える

## 1. 目的と、応える要求・設計・契約の ID

Spec §3.2 の「目標の置き場」へファイルを移す（package の宣言と import だけを変える機械的な変更）。main に居る test double 2 つを test へ移す。`ArchitectureStructureTest` の「ファイル → 層」の対応表を package の規則（`**/domain/**` など）に置き換え、許可リストを空にする。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (5) | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-5・AQ-7 | `docs/design/architecture.md` §2・§8 |
| 決定 | ADR-110 決定 10（最後の slice で一覧を空にする）、ADR-066（Gradle の module は 1 つのまま） | `docs/adr/` |
| Spec | §3.1（層の単位と目標の形）、§3.2（現状 → 目標の対応の全行）、TA-V1〜TA-V5、§8.4「A-T9」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T8b と **A-T3b** の android PR が main に merge 済み、かつ親ポインタが進んでいる（Spec §8.1）。
- 許可リスト（`test/…/architecture/Allowlist.kt`）に残る組が、本 slice が消すもの（ファイルの置き場に由来する組と、test double）だけであること。それ以外が残っていれば、その持ち主の slice が済んでいないので投入しない。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 期待 |
|---|---|---|
| 許可リストの残り | `Allowlist.kt` の全行 | 置き場に由来する組だけ（列挙して PR に貼る） |
| 移すファイルの集合 | Spec §3.2 の「移す slice」が A-T9 の行の全ファイル、と前の slice が新規に作ったファイル（`podcast/OfflineLibrary.kt`・`auth/SubjectCleanup.kt` など）を、`find app/src/main/java/com/rioikeda/newslisten -name '*.kt'` と突き合わせる | 移す前 → 移した後の path の表を作る（ファイル名が変わっているものは前の slice の名前に合わせる） |
| test double | `grep -rn 'InMemoryPreferencesStore\|InMemorySessionStore' app/src/main --include='*.kt' \| grep -v 'class InMemory'` | main からのコードの参照 0（KDoc の相互参照だけ） |
| `package` と path の不一致 | `grep -rn '^package' app/src/main --include='*.kt'` と path の比較 | 移した後に 0 |
| 外から見える名前 | `grep -n 'android:name' app/src/main/AndroidManifest.xml` | `MainActivity`・`NewsListenApplication`・`PlaybackService`・`FcmTokenService` の完全修飾名（Spec §3.2 で「そのまま」。移さない） |

## 4. 対象の path と対象外

**変える（main）**: Spec §3.2 で「移す slice」が A-T9 の全ファイル（`podcast/domain/`・`podcast/app/`・`podcast/infra/`・`podcast/ui/`・`learning/ui/`・`auth/domain/`・`auth/app/`・`auth/ui/`・`account/app/`・`passkey/app/`・`passkey/infra/`・`feed/app/`・`feed/domain/`・`feed/ui/`・`learning/app/`・`settings/app/`・`settings/ui/`・`onboarding/app/`・`onboarding/ui/`・`preferences/domain/`・`preferences/app/`・`preferences/infra/`・`notification/app/`、`network/` の port 3 つ（`PodcastApi`・`LearningApi`・`VocabularyTestApi`。前の slice で移していなければ）と `SessionStore`・`NetworkMonitoring`）。package の宣言と import だけを変える。
**移す（main → test）**: `preferences/InMemoryPreferencesStore.kt`・`network/InMemorySessionStore.kt` を `app/src/test/` へ。
**変える（test）**: import の書き換え、`architecture/LayerMap.kt`（package の規則へ置き換え）・`Allowlist.kt`（空、またはファイルごと削除）・`ArchitectureStructureTest.kt`。

**対象外**: 関数・型の名前と本文（package の宣言と import 以外の差分を作らない）。AndroidManifest に名前が載る入口 4 つ（`MainActivity`・`NewsListenApplication`・`playbackservice/PlaybackService`・`notification/FcmTokenService`）、`core/`・`model/`・`network/` の adapter・`observability/`・`designsystem/`・`di/`（Spec §3.2 で「そのまま」）。`res/`。

## 5. 変更の責務

| 置くもの | 内容 |
|---|---|
| ファイルの置き場 | Spec §3.2 の「目標の置き場」の列のとおり |
| 対応表 | 「ファイル → 層」の表を、package の規則（`core/**`・`**/domain/**` = domain、`**/app/**` = application、`**/infra/**`・`network/**`・`observability/**`・`model/**` = adapter、`**/ui/**`・`designsystem/**`・`AppScaffold.kt`・`MainActivity.kt` = presentation、`di/**` = composition root、入口 3 つ）に置き換える。規則に当たらないファイルが 0 |
| 許可リスト | 空。TA-V1〜V5 が許可リストなしで通る |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| 許可リスト | A-T1 | A-T1 | 空になる | A-T9（本 slice） |
| 「ファイル → 層」の対応表 | A-T1 | A-T1 | package の規則に置き換わる | A-T9（本 slice） |
| test double が main に居る | 既存 | 既存 | test へ移す | A-T9（本 slice） |

本 slice の後に残る一時経路は無い（ADR-110 決定 10）。

## 7. 変わる挙動

無い（package の移動だけ）。

## 8. 契約と検査

- TA-V1〜TA-V5 が許可リストなしで green。規則に当たらないファイルが 0。
- 全テストの期待値が不変。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `./gradlew assembleDebug` → exit 0（AndroidManifest の名前が変わっていないことの確認。A-S3 の CI のステップと同じ）。
- `git diff -M main -- app/src/main | grep '^[-+]' | grep -vE '^[-+](package|import) |^(\+\+\+|---) '` が空（package と import 以外の差分が無い。rename は `-M` で数える）。
- `grep -rn 'InMemoryPreferencesStore\|InMemorySessionStore' app/src/main` = 0。
- `Allowlist.kt` が空、または削除されている。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest`（許可リストなし）が green。`assembleDebug` が通る。§9 の grep が期待値。
- 移したファイルの「前 → 後」の path の表が PR にある（集合 = Spec §3.2 の A-T9 の行の全ファイル）。
- TA-V10（PR の説明）: Spec §7 の問い 7 つすべてに、変わるファイルの一覧を答え、「収まる範囲」に入っていることを示す。

相互矛盾の突き合わせ: 「package と import 以外を変えない」と「test double を test へ移す」— 移動も package と path の変更だけなので両立する。

## 11. 決定 slice か適用 slice か

適用 slice（機械的）。

## 12. 規模の目安と返却事項

- 規模は機械的（main のほぼ全ファイルの 1〜数行。Spec §8.4）。差分が大きいときは context ごとに PR を分けてよい（各 PR で `ArchitectureStructureTest` を green に保つ）。
- 返却事項: android-design §2 の package の表と依存の向きを現状記述へ書き換える旨。本フォルダを削除して確定内容を親 docs へ移す時期（README「完了後」）。共有仕様 §6.8 の Android 欄の名前。
