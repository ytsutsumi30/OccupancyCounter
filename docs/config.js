// ============================================================
// Dashboard Backend 設定
// ============================================================
//
// 既存の Cloudflare Tunnel が再起動するたびに URL が変わるため、
// 以下のいずれかで切替可能。
//   1. 下の DEFAULT_API_BASE を直接書き換える（恒久切替）
//   2. URLパラメータ ?api=https://new-url.trycloudflare.com で一時切替
//   3. ダッシュボード上の「⚙ Backend」メニューから入力（localStorage保存）
// ============================================================

window.DASHBOARD_CONFIG = {
  // 既存の Cloudflare Tunnel URL (再起動で変わる場合は書き換え)
  DEFAULT_API_BASE: "https://supported-eligibility-rogers-warranty.trycloudflare.com",

  // ポーリング間隔(ms)
  POLL_INTERVAL_MS: 3000,

  // 接続失敗が連続するとデモモードに自動切替
  DEMO_MODE_AFTER_FAILURES: 5,
};
