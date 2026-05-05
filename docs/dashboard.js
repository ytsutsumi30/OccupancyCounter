// ============================================================
// Dashboard - GitHub Pages 版
//   - Cloudflare Tunnel 等のバックエンドへ fetch
//   - 接続失敗時はデモモードに自動切替
//   - URLパラメータ ?api= or localStorage で URL 切替
// ============================================================

const ROOM_COLOR = {
  large:  "#4d8cff",
  medium: "#36e08c",
  small:  "#f5a623",
  booth:  "#b58cff",
};

const ROOM_META = [
  { id: "large",  name: "大会議室",   floor: "3F", capacity: 10, nextLabel: "10:00 【社内会議】人事MT" },
  { id: "medium", name: "中会議室",   floor: "2F", capacity: 6,  nextLabel: "17:30 【社内会議】CDA役職者会議" },
  { id: "small",  name: "小会議室",   floor: "2F", capacity: 2,  nextLabel: "16:00 安田 鍾哲 作業報告" },
  { id: "booth",  name: "個別ブース", floor: "1F", capacity: 1,  nextLabel: "" },
];

let lastEntryCount = 0;
let connFailures = 0;
let demoMode = false;
let demoState = createInitialDemoState();

// ─── API ベースURL の決定 ───────────────────────────────────
function getApiBase() {
  // 1. URLパラメータ ?api=
  const param = new URLSearchParams(location.search).get("api");
  if (param) {
    localStorage.setItem("api_base", param);
    // パラメータ自体はクリーンアップ
    history.replaceState({}, "", location.pathname);
    return param;
  }
  // 2. localStorage
  const saved = localStorage.getItem("api_base");
  if (saved) return saved;
  // 3. config.js のデフォルト
  return window.DASHBOARD_CONFIG.DEFAULT_API_BASE;
}

function setApiBase(url) {
  localStorage.setItem("api_base", url);
  updateApiBaseLabel();
}

function updateApiBaseLabel() {
  const url = getApiBase();
  document.getElementById("apiBaseLabel").textContent =
    demoMode ? "🧪 demo mode" : url.replace(/^https?:\/\//, "");
  document.getElementById("ingestUrl").textContent = demoMode ? "(demo)" : (url + "/ingest/headcount");
}

// ─── Banner ──────────────────────────────────────────────
function showBanner(text, kind = "") {
  const b = document.getElementById("connBanner");
  document.getElementById("connBannerText").textContent = text;
  b.className = "conn-banner " + kind;
}
function hideBanner() {
  document.getElementById("connBanner").className = "conn-banner hidden";
}

// ─── Polling ─────────────────────────────────────────────
async function poll() {
  if (demoMode) {
    advanceDemoState();
    render(demoState);
    return;
  }

  const apiBase = getApiBase();
  try {
    const res = await fetch(apiBase + "/api/state", { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    connFailures = 0;
    hideBanner();
    render(data);
  } catch (e) {
    connFailures++;
    console.warn(`poll failed (${connFailures})`, e.message);
    if (connFailures >= window.DASHBOARD_CONFIG.DEMO_MODE_AFTER_FAILURES) {
      activateDemoMode("バックエンドへ接続できないためデモモードに切替");
    } else {
      showBanner(`⚠️ バックエンドに接続できません (${connFailures}回失敗) - ${apiBase}`, "error");
    }
  }
}

// ─── Demo Mode ───────────────────────────────────────────
function createInitialDemoState() {
  const rooms = ROOM_META.map(m => ({
    ...m,
    headcount: 0, confidence: "—", lastUpdate: null, deviceId: null,
  }));
  const history = Object.fromEntries(ROOM_META.map(m => [m.id, []]));
  return {
    serverTime: new Date().toISOString(),
    rooms, deviceMap: { "3F:A8:91:0C:7B:E2": "medium" }, history
  };
}

function advanceDemoState() {
  demoState.serverTime = new Date().toISOString();
  // 中会議室をランダムに変動 (1分に1回程度)
  if (Math.random() < 0.15) {
    const m = demoState.rooms.find(r => r.id === "medium");
    m.headcount = Math.floor(Math.random() * (m.capacity + 1));
    m.confidence = Math.random() < 0.7 ? "confirmed" : "tentative";
    m.lastUpdate = new Date().toISOString();
    m.deviceId = "3F:A8:91:0C:7B:E2";
    demoState.history.medium.push({ t: m.lastUpdate, n: m.headcount, c: m.confidence });
    if (demoState.history.medium.length > 30) demoState.history.medium.shift();
  }
}

function activateDemoMode(reason) {
  demoMode = true;
  showBanner(`🧪 ${reason}`, "demo");
  updateApiBaseLabel();
}

function useDemoMode() {
  activateDemoMode("デモモード - ランダムな値を表示します");
  closeBackendDialog();
}

// ─── Render ──────────────────────────────────────────────
function render(data) {
  const vacant = data.rooms.filter(r => r.headcount === 0).length;
  document.getElementById("availableNum").textContent = vacant;
  document.getElementById("vacancyText").textContent = vacant + "室空き";

  document.getElementById("roomsGrid").innerHTML = data.rooms.map(roomCardHtml).join("");

  const t = new Date(data.serverTime);
  document.getElementById("updatedTag").textContent =
    `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())} 更新`;
  document.getElementById("todayLabel").textContent =
    `${t.getFullYear()}/${pad(t.getMonth() + 1)}/${pad(t.getDate())} (${["日","月","火","水","木","金","土"][t.getDay()]})`;

  if (!document.getElementById("hourAxis").innerHTML) {
    document.getElementById("hourAxis").innerHTML = "<div></div>" +
      Array.from({length:13}, (_,i)=>`<div>${pad(8+i)}</div>`).join("");
  }

  const map = data.deviceMap || {};
  document.getElementById("deviceMapView").innerHTML =
    '<div style="margin-bottom:4px">📌 デバイスマッピング:</div>' +
    Object.entries(map).map(([d, r]) => {
      const room = data.rooms.find(x => x.id === r);
      return `<div class="map-row"><code>${d}</code> → <b>${room ? room.name : r}</b></div>`;
    }).join("");

  drawChart(data);
  drawLog(data);
}

function pad(n) { return String(n).padStart(2, "0"); }

function roomCardHtml(r) {
  const filled = r.headcount > 0;
  const full = r.headcount >= r.capacity;
  const cls = full ? "full" : (filled ? "busy" : "");
  const live = r.lastUpdate ? "live" : "";
  const badge = full ? '<span class="badge full">満席</span>'
    : filled ? '<span class="badge busy">使用中</span>'
    : '<span class="badge vacant">空き</span>';
  const conf = r.confidence === "tentative"
    ? '<span class="badge tentative" style="margin-left:6px">tentative</span>' : "";
  const next = r.nextLabel ? `<div class="next-line"><span class="arrow">▸</span>次▸ ${r.nextLabel}</div>` : "";

  return `
    <div class="room ${cls} ${live}">
      <div class="room-head">
        <div>
          <div class="room-name">${r.name}</div>
          <div class="room-meta">${r.floor}・定員${r.capacity}名</div>
        </div>
        <div>${badge}${conf}</div>
      </div>
      <div class="headcount">
        <span class="n">${r.headcount}</span><span class="of">/ ${r.capacity}</span>
      </div>
      ${next}
    </div>`;
}

function drawChart(data) {
  const c = document.getElementById("trendChart");
  const ctx = c.getContext("2d");
  c.width = c.clientWidth;
  c.height = 180;
  ctx.clearRect(0, 0, c.width, c.height);

  ctx.strokeStyle = "#1f2d4d";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(36, 8); ctx.lineTo(36, c.height - 24);
  ctx.lineTo(c.width - 8, c.height - 24);
  ctx.stroke();

  ctx.fillStyle = "#8794b3";
  ctx.font = "10px Consolas";
  const maxY = Math.max(2, ...data.rooms.map(r => r.capacity));
  for (let i = 0; i <= maxY; i += Math.max(1, Math.ceil(maxY / 5))) {
    const y = c.height - 24 - (i / maxY) * (c.height - 40);
    ctx.fillText(i, 8, y + 3);
    ctx.strokeStyle = "rgba(255,255,255,0.04)";
    ctx.beginPath(); ctx.moveTo(36, y); ctx.lineTo(c.width - 8, y); ctx.stroke();
  }

  data.rooms.forEach(r => {
    const hist = (data.history && data.history[r.id]) || [];
    if (hist.length < 1) return;
    ctx.strokeStyle = ROOM_COLOR[r.id] || "#fff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    hist.forEach((p, i) => {
      const x = 36 + (i / Math.max(1, hist.length - 1)) * (c.width - 44);
      const y = c.height - 24 - (p.n / maxY) * (c.height - 40);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
    ctx.fillStyle = ROOM_COLOR[r.id];
    hist.forEach((p, i) => {
      const x = 36 + (i / Math.max(1, hist.length - 1)) * (c.width - 44);
      const y = c.height - 24 - (p.n / maxY) * (c.height - 40);
      ctx.beginPath(); ctx.arc(x, y, 2.5, 0, Math.PI * 2); ctx.fill();
    });
  });
}

function drawLog(data) {
  const all = [];
  data.rooms.forEach(r => {
    ((data.history && data.history[r.id]) || []).forEach(p => {
      all.push({ ...p, room: r.name });
    });
  });
  all.sort((a, b) => b.t.localeCompare(a.t));
  const slice = all.slice(0, 50);
  if (slice.length === lastEntryCount) return;
  lastEntryCount = slice.length;

  const log = document.getElementById("ingestLog");
  log.innerHTML = slice.map(p => {
    const t = new Date(p.t);
    const ts = `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`;
    return `<div class="entry ${p.c}"><span class="t">[${ts}]</span> → <span class="room">${p.room}</span> headcount=<b>${p.n}</b> confidence=<span class="c">${p.c}</span></div>`;
  }).join("") || '<div class="entry"><span class="t">受信待ち...</span></div>';
}

// ─── Dialog ──────────────────────────────────────────────
function openBackendDialog() {
  document.getElementById("apiBaseInput").value = getApiBase();
  document.getElementById("backendDialog").classList.remove("hidden");
}
function closeBackendDialog() {
  document.getElementById("backendDialog").classList.add("hidden");
}
function applyBackend() {
  const v = document.getElementById("apiBaseInput").value.trim().replace(/\/$/, "");
  if (!v) return alert("URLを入力してください");
  setApiBase(v);
  demoMode = false;
  connFailures = 0;
  closeBackendDialog();
  poll();
}
function resetToDefault() {
  localStorage.removeItem("api_base");
  demoMode = false;
  connFailures = 0;
  closeBackendDialog();
  updateApiBaseLabel();
  poll();
}

async function resetState() {
  if (demoMode) {
    demoState = createInitialDemoState();
    render(demoState);
    return;
  }
  if (!confirm("すべての会議室の人数をリセットしますか？")) return;
  try {
    await fetch(getApiBase() + "/api/state", { method: "DELETE" });
  } catch (e) { /* ignore */ }
  poll();
}

// ─── Init ────────────────────────────────────────────────
updateApiBaseLabel();
poll();
setInterval(poll, window.DASHBOARD_CONFIG.POLL_INTERVAL_MS);
