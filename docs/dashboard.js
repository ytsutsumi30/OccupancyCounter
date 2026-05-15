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
let lastDashboardData = null;
let minutesJobs = [];
let minutesByRoom = {};
let selectedMinutesRoomId = null;
const API_KEY_STORAGE_KEY = "testdashboard_api_key";

initializeApiKey();

function initializeApiKey() {
  const params = new URLSearchParams(location.search);
  const apiKey = params.get("api_key") || params.get("apiKey");
  if (apiKey) {
    localStorage.setItem(API_KEY_STORAGE_KEY, apiKey);
    params.delete("api_key");
    params.delete("apiKey");
    history.replaceState({}, "", `${location.pathname}${params.toString() ? `?${params}` : ""}${location.hash}`);
  }
}

function getApiKey() {
  return localStorage.getItem(API_KEY_STORAGE_KEY) || "";
}

function fetchWithApiKey(url, options = {}) {
  const headers = new Headers(options.headers || {});
  const apiKey = getApiKey();
  if (apiKey) headers.set("X-API-Key", apiKey);
  return fetch(url, { ...options, headers });
}

function urlWithApiKey(url) {
  const apiKey = getApiKey();
  if (!apiKey) return url;
  const u = new URL(url, location.href);
  u.searchParams.set("api_key", apiKey);
  return u.toString();
}

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
    const res = await fetchWithApiKey(apiBase + "/api/state", { cache: "no-store" });
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

async function pollMinutesJobs() {
  if (demoMode) {
    updateMinutesJobs(createDemoMinutesJobs());
    return;
  }

  try {
    const res = await fetchWithApiKey(getApiBase() + "/api/jobs", { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const data = await res.json();
    updateMinutesJobs(Array.isArray(data.jobs) ? data.jobs : []);
  } catch (e) {
    console.warn("minutes jobs poll failed", e.message);
    updateMinutesJobs([]);
  }
}

function updateMinutesJobs(jobs) {
  minutesJobs = jobs
    .map(normalizeMinutesJob)
    .sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
  minutesByRoom = groupMinutesByRoom(minutesJobs);

  if (lastDashboardData) render(lastDashboardData);
  if (selectedMinutesRoomId) renderMinutesModal(selectedMinutesRoomId);
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
  lastDashboardData = data;
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

  // デバイスマッピング表示 — 各会議室の現在値も併記
  const map = data.deviceMap || {};
  // 会議室順 (large → medium → small → booth) でソート
  const roomOrder = ["large", "medium", "small", "booth"];
  const sortedEntries = Object.entries(map).sort((a, b) => {
    return roomOrder.indexOf(a[1]) - roomOrder.indexOf(b[1]);
  });

  document.getElementById("deviceMapView").innerHTML =
    '<div style="margin-bottom:6px">📌 <b>デバイスマッピング</b> (各会議室1台ずつ):</div>' +
    sortedEntries.map(([d, r]) => {
      const room = data.rooms.find(x => x.id === r);
      if (!room) return `<div class="map-row"><code>${d}</code> → <b>${r}</b> (未定義)</div>`;

      // ステータス: 受信あり / 待機中
      const live = room.lastUpdate ? "live" : "idle";
      const dot = live === "live"
        ? '<span style="color:#36e08c">●</span>'
        : '<span style="color:#475569">○</span>';

      // 現在値表示 (受信ありの場合)
      let valueLabel = '<span style="color:#475569">待機中</span>';
      if (room.lastUpdate) {
        const confColor = room.confidence === "confirmed" ? "#36e08c" :
                          room.confidence === "tentative" ? "#b58cff" : "#94a3b8";
        const t = new Date(room.lastUpdate);
        const ts = `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`;
        valueLabel = `<b style="color:#fff">${room.headcount}/${room.capacity}</b>` +
                     ` <span style="color:${confColor}">[${room.confidence}]</span>` +
                     ` <span style="color:#64748b">@ ${ts}</span>`;
      }

      return `<div class="map-row">${dot} <code>${d}</code> → <b style="color:#${live==="live"?"e2e8f0":"94a3b8"}">${room.name}</b> &nbsp; ${valueLabel}</div>`;
    }).join("") || '<div>未登録</div>';

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
  const roomMinutes = minutesByRoom[r.id] || [];
  const completedCount = roomMinutes.filter(job => job.status === "completed").length;
  const inProgressCount = roomMinutes.filter(job => isProcessingStatus(job.status)).length;
  const failedCount = roomMinutes.filter(job => job.status === "failed").length;
  const totalCount = roomMinutes.length;
  const minutesClass = totalCount > 0 ? "room-minutes" : "room-minutes disabled";
  const minutesText = totalCount > 0
    ? `議事録 ${completedCount}/${totalCount}件`
    : "議事録 0件";
  const minutesSub = inProgressCount > 0
    ? `生成中 ${inProgressCount}`
    : failedCount > 0 ? `失敗 ${failedCount}` : "一覧";
  const minutesClick = totalCount > 0 ? ` onclick="openMinutesModal('${escapeAttr(r.id)}')"` : "";

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
      <div class="${minutesClass}"${minutesClick}>
        <span><span class="badge-icon">📄</span> <span class="badge-count">${minutesText}</span></span>
        <span class="badge-arrow">${minutesSub} ›</span>
      </div>
    </div>`;
}

function normalizeMinutesJob(job) {
  const roomId = job.roomId || job.room_id || job.meta?.room_id || "unknown";
  const createdAt = job.createdAt || job.completedAt || "";
  return {
    jobId: job.jobId || job.job_id,
    status: String(job.status || "unknown").toLowerCase(),
    createdAt,
    title: job.title || job.meta?.title || "無題の会議",
    roomId,
    deviceId: job.deviceId || job.device_id || job.meta?.device_id || "",
    speakerCount: job.speakerCount ?? job.transcript?.speakerCount ?? null,
    mocked: !!job.mocked,
    error: job.error || "",
    onedriveUrl: job.onedriveUrl || job.minutes?.onedrive?.shareUrl || job.minutes?.onedrive?.webUrl || "",
  };
}

function groupMinutesByRoom(jobs) {
  return jobs.reduce((acc, job) => {
    const roomId = job.roomId || "unknown";
    if (!acc[roomId]) acc[roomId] = [];
    acc[roomId].push(job);
    return acc;
  }, {});
}

function createDemoMinutesJobs() {
  const now = Date.now();
  return [
    {
      jobId: "demo-minutes-medium-001",
      status: "completed",
      roomId: "medium",
      title: "PSUユニット会議",
      createdAt: new Date(now - 18 * 60 * 1000).toISOString(),
      speakerCount: 3,
      mocked: true
    },
    {
      jobId: "demo-minutes-large-001",
      status: "summarizing",
      roomId: "large",
      title: "人事MT",
      createdAt: new Date(now - 8 * 60 * 1000).toISOString(),
      speakerCount: 2,
      mocked: true
    },
    {
      jobId: "demo-minutes-small-001",
      status: "completed",
      roomId: "small",
      title: "作業報告",
      createdAt: new Date(now - 55 * 60 * 1000).toISOString(),
      speakerCount: 1,
      mocked: true
    }
  ];
}

function openMinutesModal(roomId) {
  selectedMinutesRoomId = roomId;
  renderMinutesModal(roomId);
  document.getElementById("minutesModal").classList.remove("hidden");
}

function closeMinutesModal() {
  selectedMinutesRoomId = null;
  document.getElementById("minutesModal").classList.add("hidden");
}

function onMinutesModalBgClick(event) {
  if (event.target && event.target.id === "minutesModal") closeMinutesModal();
}

function renderMinutesModal(roomId) {
  const room = ROOM_META.find(r => r.id === roomId);
  const jobs = minutesByRoom[roomId] || [];
  document.getElementById("minutesModalTitle").textContent =
    `${room ? room.name : roomId} - 議事録一覧`;

  const list = document.getElementById("minutesList");
  if (!jobs.length) {
    list.innerHTML = '<div class="minutes-empty">この会議室の議事録はまだありません。</div>';
    return;
  }

  list.innerHTML = jobs.map(minutesItemHtml).join("");
}

function minutesItemHtml(job) {
  const statusClass = statusClassName(job.status);
  const statusLabel = statusLabelText(job.status);
  const created = formatDateTime(job.createdAt);
  const apiBase = getApiBase();
  const docxUrl = demoMode ? "#" : urlWithApiKey(`${apiBase}/api/minutes/${encodeURIComponent(job.jobId)}/download`);
  const markdownUrl = demoMode ? "#" : urlWithApiKey(`${apiBase}/api/minutes/${encodeURIComponent(job.jobId)}/markdown`);
  const disabledClick = demoMode ? ' onclick="return false;"' : "";
  const actionLinks = job.status === "completed"
    ? `
      <a class="btn-action-docx" href="${escapeAttr(docxUrl)}"${disabledClick}>DOCX</a>
      <a class="btn-action-markdown" href="${escapeAttr(markdownUrl)}" target="_blank" rel="noopener"${disabledClick}>Markdown</a>
      ${job.onedriveUrl ? `<a class="btn-action-onedrive" href="${escapeAttr(job.onedriveUrl)}" target="_blank" rel="noopener">OneDrive</a>` : ""}
    `
    : `<span class="minutes-action-muted">${escapeHtml(job.error || "生成完了後にダウンロードできます")}</span>`;

  return `
    <div class="minutes-item">
      <div class="minutes-item-head">
        <div class="minutes-item-title">${escapeHtml(job.title)}</div>
        <span class="minutes-item-status ${statusClass}">${statusLabel}</span>
      </div>
      <div class="minutes-item-meta">
        <span>${escapeHtml(created)}</span>
        <span>Job: <code>${escapeHtml(job.jobId || "-")}</code></span>
        ${job.speakerCount != null ? `<span>話者 ${escapeHtml(job.speakerCount)}名</span>` : ""}
        ${job.mocked ? '<span class="mocked-tag">mock</span>' : ""}
      </div>
      <div class="minutes-item-actions">${actionLinks}</div>
    </div>`;
}

function isProcessingStatus(status) {
  return ["queued", "publishing", "transcribing", "identifying_speakers", "summarizing", "building_docx", "uploading_onedrive"].includes(status);
}

function statusClassName(status) {
  if (status === "completed") return "status-completed";
  if (status === "failed") return "status-failed";
  return "status-processing";
}

function statusLabelText(status) {
  const labels = {
    queued: "待機中",
    publishing: "公開中",
    transcribing: "文字起こし中",
    identifying_speakers: "話者識別中",
    summarizing: "要約中",
    building_docx: "DOCX生成中",
    uploading_onedrive: "OneDrive保存中",
    completed: "完了",
    failed: "失敗"
  };
  return labels[status] || status || "不明";
}

function formatDateTime(value) {
  if (!value) return "日時不明";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function escapeAttr(value) {
  return escapeHtml(value).replace(/`/g, "&#96;");
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
  const apiKeyInput = document.getElementById("apiKeyInput");
  if (apiKeyInput) apiKeyInput.value = getApiKey();
  document.getElementById("backendDialog").classList.remove("hidden");
}
function closeBackendDialog() {
  document.getElementById("backendDialog").classList.add("hidden");
}
function applyBackend() {
  const v = document.getElementById("apiBaseInput").value.trim().replace(/\/$/, "");
  if (!v) return alert("URLを入力してください");
  setApiBase(v);
  const apiKeyInput = document.getElementById("apiKeyInput");
  if (apiKeyInput) {
    const apiKey = apiKeyInput.value.trim();
    if (apiKey) localStorage.setItem(API_KEY_STORAGE_KEY, apiKey);
    else localStorage.removeItem(API_KEY_STORAGE_KEY);
  }
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
    await fetchWithApiKey(getApiBase() + "/api/state", { method: "DELETE" });
  } catch (e) { /* ignore */ }
  poll();
}

// ─── Init ────────────────────────────────────────────────
updateApiBaseLabel();
poll();
pollMinutesJobs();
setInterval(poll, window.DASHBOARD_CONFIG.POLL_INTERVAL_MS);
setInterval(pollMinutesJobs, window.DASHBOARD_CONFIG.MINUTES_POLL_INTERVAL_MS || window.DASHBOARD_CONFIG.POLL_INTERVAL_MS);
