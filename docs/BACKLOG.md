# 技術的負債バックログ

全ソース（app/src/main 9ファイル＋Manifest）の精読レビュー結果。**修正は含まない**（修正時はこの表を1件ずつ潰し、`docs/TESTABILITY_PLAN.md` のテスト整備と並行する）。

重大度: **P1**=製品の目的（正確な在室人数・確実な録音）を直接損なう / **P2**=データ損失・回復不能につながる / **P3**=品質・保守性

## P1 — 製品の信頼性を直接損なう

| ID | 場所 | 問題 | 修正方針 |
|---|---|---|---|
| B-01 | `FaceAnalyzer.kt:49-51` | ML Kit の検出**失敗時に「0人」を通知**している。一時的な検出エラーがスムージングを経て「空室」としてサーバーへ送られ、予約システム側の自動キャンセル等の誤動作を誘発しうる | 失敗フレームはコールバックを呼ばずスキップ（欠測扱い）。連続失敗時のみ UI にエラー表示 |
| B-02 | `MainActivity.kt:150-152` | 送信の成否に関わらず `lastCount`/`lastSentTime` を先に更新するため、**送信失敗が永久に再送されない**（次に人数が変化するまでサーバーは古い値のまま） | 成功コールバックで確定させるか、失敗時に `lastCount` を巻き戻す。`SendPolicy` 抽出（TESTABILITY_PLAN 参照）とセットで |
| B-03 | `MeetingActivity` / Manifest | **録音がフォアグラウンドサービス化されていない**。Manifest には `FOREGROUND_SERVICE_MICROPHONE` を宣言済みだが実装が無く、録音中にアプリがバックグラウンドへ回ると OS のマイク制限（Android 9+）で**無音が録音される** | `MeetingRecorder` を FGS（`microphone` タイプ）でホストする。宣言済み権限を実装に接続する |

## P2 — データ損失・回復不能

| ID | 場所 | 問題 | 修正方針 |
|---|---|---|---|
| B-04 | `JobStore.kt:267,273,276,278` | Android の `org.json` は値が `JSONObject.NULL` のとき `optString()` が**文字列 `"null"` を返す**。`roomId`/`audioPath`/`errorMessage`/`serverJobId` が `"null"` という文字列になり、`.ifEmpty { null }` をすり抜ける。再送時に `room_id="null"` がサーバーへ送られ、`File("null")` 判定で再送が誤って FAILED 化する | `optString` を `if (j.isNull(key)) null else j.optString(key)` 形に統一。**回帰テスト必須**（TESTABILITY_PLAN の T-02） |
| B-05 | `MeetingActivity.kt:105-107` + `MeetingRecorder.cancel()` | `onDestroy` で録音中なら `cancel()`＝**録音ファイル削除**。回転は Manifest で抑止済みだが、**ダークモード切替（uiMode）・言語変更・プロセスkill では発動**し、会議音声が無警告で消える。さらに `cancel()` は JobStore を更新しないため **RECORDING のまま孤児レコードが永久に残る** | 破棄時はファイルを残して `markFailed`（"interrupted"）へ倒す。B-03 の FGS 化が根本対策 |
| B-06 | `MeetingActivity.kt:309-313` | 再送対象の抽出が `PENDING/FAILED` のみ。アップロード中にプロセスが死ぬと **UPLOADING で固まったジョブは UI に出ず再送不能**（音声ファイルは残るのに手段がない） | 起動時に `UPLOADING` かつ `updatedAtMs` が古いものを `PENDING` へ戻す回復処理を追加 |
| B-07 | `MeetingRecorder.kt:151` | 録音ファイルの置き場が **`cacheDir`**。ストレージ逼迫時に OS がキャッシュを削除でき、未アップロードの会議音声が消えうる | `filesDir/recordings/` へ移動（アップロード成功時に削除する運用は維持） |

## P3 — 品質・保守性・セキュリティ

| ID | 場所 | 問題 | 修正方針 |
|---|---|---|---|
| B-08 | Manifest:37 | `usesCleartextTraffic="true"` かつエンドポイントは自由入力のため、**API キーが平文 HTTP で送られうる** | cleartext を無効化し https を強制（開発用に network_security_config で例外を切る） |
| B-09 | `MeetingActivity.kt:228` | `uploader.upload()` は既に非同期（`enqueue`）なのに `thread {}` で二重に包んでいる。無意味なスレッド生成 | `thread {}` を除去 |
| B-10 | `AppPrefs.kt:26-32` | `serverEndpoint` の **getter が SharedPreferences へ書き込む**副作用を持つ（レガシー移行）。読み取りのたびに走り、テストも書きにくい | 移行処理をアプリ起動時の1回に分離 |
| B-11 | `JobStore` 全体 | UPLOADED ジョブを削除する導線がなく **JSON ファイルが無限に蓄積** | 起動時に「UPLOADED かつ N 日経過」を削除 |
| B-12 | `MeetingActivity.kt:92` | タイトル入力にプレースホルダー文字列を `setText` している。ユーザーが消さない限り**全会議が同名**になる | `hint` に変更し、空なら送信時に日時から自動生成 |
| B-13 | `ServerClient` / `RecordingUploader` | それぞれが自前の `OkHttpClient` を生成（コネクションプール二重化）。`AppPrefs` もクラスごとに生成 | クライアントを共有シングルトンに。DI 導入（TESTABILITY_PLAN）とセットで |
| B-14 | `RecordingUploader.kt:139-149` | `uploadSync` は `wait(30min)` がタイムアウトしても `while` で再待機するため**永久待ちになりうる**（テスト用途とはいえ危険） | 期限つきループに修正、または CountDownLatch 化 |
| B-15 | `MainActivity.kt:99-101,171-179` | ネットワークコールバックから `runOnUiThread` で **破棄後の View を更新しうる**（クラッシュはしないがリーク気味）。`FaceAnalyzer.close()` と解析スレッドの競合も未同期 | `isDestroyed` ガード、または lifecycleScope へ移行 |
| B-16 | Manifest:31 | `allowBackup="true"` で API キー・device_id が端末バックアップに含まれる | 運用上問題なければ現状維持で可。キーの重要度が上がるなら false に |

## 推奨着手順

1. **B-04**（1行級の修正で回帰テストの題材として最適）→ 2. **B-01 / B-02**（在室人数の信頼性）→ 3. **B-06 / B-07**（録音データ保全の低コスト対策）→ 4. **B-03 / B-05**（FGS 化。最大工数）→ 5. P3 群

実装は Opus（builder）、テストは Sonnet（tester）で十分。**このバックログの読解と設計判断が必要な B-03/B-05 のみ、着手時にメインセッションで設計を固めること。**
