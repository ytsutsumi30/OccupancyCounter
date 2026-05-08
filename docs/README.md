# 会議室管理ダッシュボード - GitHub Pages 版

このフォルダは **GitHub Pages 公開用の静的ダッシュボード** です。
リポジトリ: https://github.com/ytsutsumi30/OccupancyCounter
公開URL  : **https://ytsutsumi30.github.io/OccupancyCounter/**

`main` ブランチに push すると、`docs/` 配下が自動的に GitHub Pages にデプロイされます。

---

## 構成

```
OccupancyCounter/                ← リポジトリ ルート
├── app/                         ← Android モジュール (.apk ビルド)
├── gradle/, build.gradle.kts    ← Android 開発関連
└── docs/                        ← このフォルダ ←★ GitHub Pages 公開対象
    ├── index.html               ダッシュボード本体
    ├── style.css                ダークテーマスタイル
    ├── dashboard.js             ポーリング・チャート・デモモード
    ├── config.js                バックエンドURL等の設定
    └── README.md                このファイル
```

---

## アーキテクチャ

```
┌─────────────────────────────────────┐
│  GitHub Pages (静的)                  │
│  https://ytsutsumi30.github.io/      │
│           OccupancyCounter/          │
│                                     │
│  3秒ごとに /api/state を fetch        │
└────────────┬────────────────────────┘
             │ CORS越しに HTTP
             ▼
┌─────────────────────────────────────┐
│  Cloudflare Tunnel URL              │
│  *.trycloudflare.com                │
│  ─ Express on 開発PC ─              │
│                                     │
│  POST /ingest/headcount             │ ← Androidアプリから
│  GET  /api/state                    │ ← GitHub Pagesから
└─────────────────────────────────────┘
```

GitHub Pages は静的配信のみで POST 受信ができないため、
バックエンド（Express + Cloudflare Tunnel）はそのまま稼働させ、
ダッシュボード**フロントエンドだけ**を公開します。

---

## デプロイ手順（ytsutsumi30/OccupancyCounter リポジトリ）

### 1. このフォルダをコミット & push

ローカル開発フォルダで以下を実行（`OccupancyCounter` がリポジトリルート）:

```powershell
cd C:\PRJ2\ANDROIDのIOTデバイス化と会議室予約アプリ\OccupancyCounter
git add docs/
git commit -m "feat: GitHub Pages dashboard"
git push origin main
```

### 2. GitHub の Pages 設定（初回のみ）

リポジトリページ → **Settings → Pages** で以下を選択:

| 項目 | 値 |
|---|---|
| Source | **Deploy from a branch** |
| Branch | **main** |
| Folder | **`/docs`** |

→ **Save** ボタン押下。1〜2分後に GitHub Actions のような Pages のビルドが走り、URLが有効になります。

### 3. 公開URLでアクセス

https://ytsutsumi30.github.io/OccupancyCounter/

ダッシュボードが表示され、自動的に `config.js` の `DEFAULT_API_BASE` (= Cloudflare Tunnel URL) へ接続します。

---

## バックエンドURL（Cloudflare Tunnel）の切替

Cloudflare Tunnel は再起動するたびにURLが変わります（`*.trycloudflare.com`）。
URLが変わった場合、以下のいずれかで切替:

### A. ダッシュボード上で一時切替（おすすめ）

ダッシュボード右上の **⚙ Backend設定** ボタン、または左サイドバーの **⚙ 設定** をクリック。
新しいURL（例: `https://newxxx.trycloudflare.com`）を入力 → 「適用」。
**URL は localStorage に保存される** ので、次回アクセス時も同じURLに自動接続されます。

### B. URLパラメータで一時切替

```
https://ytsutsumi30.github.io/OccupancyCounter/?api=https://new-tunnel.trycloudflare.com
```

### C. config.js を書き換えて push（恒久切替）

```js
// docs/config.js
window.DASHBOARD_CONFIG = {
  DEFAULT_API_BASE: "https://new-tunnel.trycloudflare.com",
  ...
};
```

push後、GitHub Pages が再デプロイ（数分後に反映）されます。

---

## バックエンド（Express）側の必要設定

`TestDashboard/server.js` には既に **CORS 対応** が組み込まれています。
GitHub Pages からの fetch を許可するため、以下のヘッダーが付与されます:

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type
```

セキュリティを強化したい場合、自分のGitHub Pages URLに限定可能:

```powershell
$env:CORS_ORIGIN = "https://ytsutsumi30.github.io"
npm start
```

---

## デモモード

GitHub Pages を開いたとき、バックエンドに接続できない場合（Tunnel未起動・URL変更・ファイアウォール等）、
**5回連続で接続失敗するとデモモードに自動切替** されます。

デモモードでは中会議室の人数が定期的にランダム変動し、UI動作確認のみが可能です。
バックエンド未準備でもダッシュボードのデザイン確認ができるよう設計されています。

手動でデモモードに入りたい場合は **Backend設定 → デモモード** ボタン。

---

## 動作確認手順（ローカル）

GitHub Pages にデプロイする前に、ローカルでも確認可能です:

```powershell
# 1. バックエンド起動（別フォルダ - TestDashboard）
cd C:\PRJ2\ANDROIDのIOTデバイス化と会議室予約アプリ\TestDashboard
npm install
npm start
```

```powershell
# 2. 別ターミナルで docs/ を簡易サーバー起動
cd C:\PRJ2\ANDROIDのIOTデバイス化と会議室予約アプリ\OccupancyCounter\docs
python -m http.server 8080
# または: npx serve -p 8080
```

ブラウザで `http://localhost:8080` を開き、Backend設定を `http://localhost:3000` に変更。

```powershell
# 3. テストデータ送信
curl.exe -X POST http://localhost:3000/ingest/headcount `
  -H "Content-Type: application/json" `
  -d "{\"device_id\":\"3F:A8:91:0C:7B:E2\",\"headcount\":5,\"confidence\":\"confirmed\"}"
```

→ ブラウザの中会議室カードが `5/6` に更新されれば成功です。

---

## トラブルシューティング

### CORS エラーが出る

ブラウザDevTools Console:
```
Access to fetch at 'https://...' from origin 'https://ytsutsumi30.github.io' has been blocked by CORS policy
```

→ バックエンド (`TestDashboard/server.js`) の CORS 設定を確認。`Access-Control-Allow-Origin: *` または `https://ytsutsumi30.github.io` が返っているか確認。

### GitHub Pages が `404` を返す

- Settings → Pages で Folder が `/docs` になっているか確認
- main ブランチに `docs/index.html` がコミット済みか確認 (`git ls-tree main:docs`)
- 反映まで最大数分かかる場合あり

### ダッシュボードは出るが値が更新されない

- バナーが「⚠️ バックエンドに接続できません」表示の場合、Tunnel URLが変わっている可能性
- ⚙ Backend設定 で最新URLを設定するか、5回失敗してデモモードに自動切替されるのを待つ
