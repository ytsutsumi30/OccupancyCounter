# OccupancyCounter — 会議室 滞在人数カウントアプリ

余ったAndroid端末をIoTデバイスとして再利用し、内蔵カメラで会議室の **現在の滞在人数（瞬間値・スナップショット型）** をカウントします。
カウント結果は画面に常時表示され、設定でONにするとCloudflareエンドポイント（会議室予約システム）へJSON POSTで送信できます。

---

## 仕様サマリ

| 項目 | 内容 |
|------|------|
| 開発環境 | Android Studio (Hedgehog 以降を推奨) / Kotlin |
| 最小SDK | 24 (Android 7.0) |
| ターゲットSDK | 36 (Android 16) |
| カメラ取得 | CameraX 1.4.2 |
| 検出方式 | **ML Kit Face Detection (オフライン動作)** |
| カウント方式 | スナップショット型（カメラに映っている顔の数 = 現在の滞在人数） |
| サーバー連携 | OkHttp による JSON POST（設定でON/OFF切替） |
| 主要依存 | ML Kit Face Detection 16.1.7 / OkHttp 4.12.0 |
| 出力 | `.apk`（debug / release） |

---

## ディレクトリ構成

```
OccupancyCounter/
├── build.gradle.kts                 ← Top-level Gradle
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties    ← Gradle 8.5
└── app/
    ├── build.gradle.kts             ← 依存関係 (CameraX / ML Kit / OkHttp)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/occupancycounter/
        │   ├── MainActivity.kt      ← カメラ + UI + スムージング
        │   ├── FaceAnalyzer.kt      ← ML Kit Face Detection ラッパー
        │   ├── ServerClient.kt      ← OkHttp で JSON POST
        │   ├── SettingsActivity.kt  ← 設定画面 (PreferenceFragment)
        │   └── AppPrefs.kt          ← SharedPreferences ラッパー
        └── res/
            ├── layout/activity_main.xml
            ├── layout/activity_settings.xml
            ├── xml/preferences.xml
            ├── values/strings.xml (英語)
            ├── values-ja/strings.xml (日本語)
            ├── values/colors.xml
            ├── values/themes.xml
            ├── drawable/ic_launcher_*.xml
            └── mipmap-*/ic_launcher*.png
```

---

## 1. ビルド手順（Android Studio）

1. Android Studio を起動。
2. **File → Open** で本フォルダ（`OccupancyCounter`）を選択。
3. 初回はGradle Sync が走るので待機（依存ライブラリを自動DL）。
4. 上部のデバイスドロップダウンで **実機（旧Android端末）** を選択。
   - ※ML Kit Face Detection はエミュレータでも動作するが、実機の方が高精度。
5. **Run（▶）** ボタンでデバッグ実行 → 端末にインストール＆起動。

### APK の出力（リリースビルドではなくデバッグビルド版）

* **Build → Build Bundle(s) / APK(s) → Build APK(s)**
* 出力先:
  ```
  app/build/outputs/apk/debug/app-debug.apk
  ```

### リリース版APK（署名あり）を作る場合

* **Build → Generate Signed Bundle / APK** → APK を選択 → 署名キーを作成/指定 → release ビルド。

---

## 2. アプリの動作

### メイン画面 (MainActivity)

* 全画面でカメラプレビューを表示。
* 上部オーバーレイに **「現在の滞在人数」** を大きく表示（緑色）。
  - 検出のチラつきを防ぐため、直近 5 フレームの **最頻値** を採用（スムージング）。
  - `raw:` の値はその瞬間の生の検出数。
* 下部オーバーレイに、ステータス・サーバー送信結果・現在時刻・設定ボタン。
* `FLAG_KEEP_SCREEN_ON` により画面が消えないため、**IoT 常時表示** の用途に向く。

### 設定画面 (SettingsActivity)

| 項目 | 説明 |
|------|------|
| フロントカメラを使用 | OFF にすると背面カメラを使用。デフォルト ON |
| サーバーへ送信する | ON でカウント値をサーバーへ POST。デフォルト OFF |
| エンドポイントURL | 送信先URL。デフォルト: `https://supported-eligibility-rogers-warranty.trycloudflare.com/ingest/headcount` |
| デバイスID | 初回起動時に自動生成。会議室名などに変更可能 |
| 送信間隔(秒) | 連続送信を抑制する最小間隔。デフォルト 10 秒 |

---

## 3. サーバー仕様（送信フォーマット）

カウント値が **変化したとき** かつ **送信間隔が経過したとき** にのみ送信されます。

```http
POST https://supported-eligibility-rogers-warranty.trycloudflare.com/ingest/headcount
Content-Type: application/json

{
  "device_id":  "AA:BB:CC:DD:EE:FF",
  "headcount":  5,
  "confidence": "confirmed"
}
```

| フィールド | 型 | 説明 |
|---|---|---|
| `device_id`  | string | 端末識別子。MAC アドレス形式 (`AA:BB:CC:DD:EE:FF`)。初回起動時にUUIDから擬似的に生成され、設定画面で会議室固有の値に書き換え可能 |
| `headcount`  | int    | スムージング後の現在の滞在人数（直近5フレームの最頻値） |
| `confidence` | string | `confirmed`: スムージングウィンドウ内で全フレーム同値 → 安定<br>`tentative`: ウォームアップ中 / 検出値変動中 → 参考値 |

サーバー側では `device_id` で会議室を識別し、`headcount == 0` を「無人」、`headcount >= 1` を「使用中」として会議室予約状態に反映できます。`confidence == "tentative"` の場合は参考値として無視するか、複数回連続を待つ運用も可能です。

### 会議室予約システム側「テストモード」での確認

予約システムには「テストモード」画面が用意されており、画像ファイルから人数検出をテストしてDBに反映できます。Androidアプリと同じエンドポイント (`/ingest/headcount`) にPOSTされるため、APKインストール前の動作確認や、`device_id` と会議室名のマッピング登録に利用できます。

> Cloudflare Tunnel の URL (`*.trycloudflare.com`) は再起動で変わるため、URLを変えた場合は **設定画面のエンドポイントURL** を書き換えるだけで対応可能です。

---

## 4. 主要クラスの役割

### FaceAnalyzer.kt

`ImageAnalysis.Analyzer` を実装し、各フレームを ML Kit の `FaceDetector` に渡します。
* `PERFORMANCE_MODE_FAST` で速度優先（10〜30 fps 相当）。
* `setMinFaceSize(0.08f)` で小さすぎる検出をフィルタ（誤検出防止）。
* 結果の顔数をコールバックで返す。

### MainActivity.kt

* `ProcessCameraProvider` を使い、Preview と ImageAnalysis をライフサイクルにバインド。
* 検出値をスムージングし、UI と `ServerClient` に流す。
* カメラ権限の動的要求は `ActivityResultContracts.RequestPermission` を使用。

### ServerClient.kt

* OkHttp で 5 秒タイムアウトの非同期 POST。
* JSON ボディは `org.json.JSONObject` で生成（追加ライブラリ不要）。

---

## 5. 注意事項・チューニング

* **Face Detection** は「顔（=正面〜やや横顔）」しか検出しません。後ろ向きの人や下を向いた人はカウントされません。
  もし会議室の参加者が下を向いていることが多い場合は、`Object Detection` モデルへの差し替えを検討してください（`FaceAnalyzer.kt` を `ObjectDetectorClient` に置き換える形でリプレース可能）。
* スマホは **三脚 / 壁掛けスタンド** で会議室全体を見渡せる位置に固定するのが推奨。
* 横置きにする場合は `AndroidManifest.xml` の `screenOrientation` を `landscape` に変更可能。
* 連続稼働させる場合は端末設定で「電池の最適化を無視」「画面ロック解除しない」「自動アップデート停止」などを行うと安定します。

---

## 6. 動作確認チェックリスト

- [ ] アプリ起動後、カメラ権限ダイアログが表示される。
- [ ] 権限を許可するとプレビューが表示される。
- [ ] カメラに自分が映ると `1` と表示される。
- [ ] 顔を画面外に外すと `0` に戻る。
- [ ] 設定画面 → 「サーバーへ送信する」を ON にし、`adb logcat -s ServerClient` で送信ログを確認。
- [ ] サーバー側 (`/ingest/headcount`) でPOSTを受信できることを確認。

---

## 7. 既知の制限

* ML Kit Face Detection は端末にモデルをDLするため、初回起動時にネット接続が必要 (`uses-feature meta-data DEPENDENCIES=face` で自動DL)。
* `usesCleartextTraffic="true"` を有効にしているため、HTTPSではなくHTTPでの社内サーバー指定も可能ですが、本番ではHTTPS推奨。
* 1端末で1会議室の運用を想定。複数会議室を1台で監視する場合はカメラ切替/座標分割の実装拡張が必要。

---

## 8. トラブルシューティング

### Gradle Sync が始まらない / フリーズする

1. **`File → Invalidate Caches / Restart…` → Invalidate and Restart** を実行。
2. それでも動かない場合は、プロジェクトルートで以下を実行（PowerShell）:
   ```powershell
   cd C:\PRJ2\ANDROIDのIOTデバイス化と会議室予約アプリ\OccupancyCounter
   .\gradlew.bat --version
   ```
   初回はGradle 8.5本体（約120MB）が `~/.gradle/wrapper/dists/` に自動DLされるため、社内プロキシ環境では `gradle.properties` に以下を追記:
   ```properties
   systemProp.https.proxyHost=your.proxy.host
   systemProp.https.proxyPort=8080
   ```

### 「Cannot resolve symbol R / databinding」

1. Android Studio で **Build → Clean Project** → **Rebuild Project**
2. それでもダメなら一度プロジェクトを閉じて、`.idea/` と `app/build/` を削除し再オープン

### `gradle-wrapper.jar` が見つからない

本プロジェクトには配置済み(`gradle/wrapper/gradle-wrapper.jar`, 43KB)。誤って削除した場合は、Android Studio Terminalで以下:
```bash
gradle wrapper --gradle-version 8.5
```

### Android SDK パスが認識されない

`local.properties` に正しい SDK パスを記述:
```properties
sdk.dir=C\:\\Users\\<ユーザー名>\\AppData\\Local\\Android\\Sdk
```

### `Your project path contains non-ASCII characters` エラー

プロジェクトパスに **日本語などの非ASCII文字** が含まれていると、Android Gradle Plugin がWindowsでのビルド失敗を懸念して停止します。

**対処1（即効性・本プロジェクトに適用済み）**: `gradle.properties` に以下を追加（チェックを無効化）。
```properties
android.overridePathCheck=true
```

**対処2（恒久対策・推奨）**: プロジェクトをASCII文字のみのパスに移動。例:
```
C:\PRJ2\ANDROIDのIOTデバイス化と会議室予約アプリ\OccupancyCounter
   ↓
C:\dev\OccupancyCounter
```
移動後は Android Studio で **File → Open** で再オープンしてください。ネイティブビルド（NDK）を使うライブラリが将来増えた場合、ASCIIパスでないと一部のC/C++コンパイラがコケる可能性があるため、本格運用前にはパス移動を推奨します。
