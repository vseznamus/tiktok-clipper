"use strict";
// TikTok-нарезка: мобильный интерфейс без фреймворков и без интернета.

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
const app = $("#app");
// Внутри Android-приложения есть мост window.AndroidApp: системные окна выбора папки/видео и «Поделиться».
const APP = !!window.AndroidApp;
const S = { cfg: null, status: null, timers: [], draft: loadDraft(), moments: {} };

// ------------------------------------------------------------------ утилиты
function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function fmt(t, frac = true) {
  t = Math.max(0, Number(t) || 0);
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  const ss = frac ? s.toFixed(1).padStart(4, "0") : String(Math.floor(s)).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}
function parseTime(str) {
  str = String(str).trim().replace(",", ".");
  if (!str) return NaN;
  const parts = str.split(":").map(Number);
  if (parts.some((x) => isNaN(x))) return NaN;
  return parts.reduce((a, x) => a * 60 + x, 0);
}
function fmtSize(b) { return b > 1e9 ? (b / 1e9).toFixed(1) + " ГБ" : (b / 1e6).toFixed(0) + " МБ"; }
const WD = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"];
const MON = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
function fmtDate(iso) { const d = new Date(iso + "T12:00:00"); return `${WD[d.getDay()]}, ${d.getDate()} ${MON[d.getMonth()]}`; }
function mediaUrl(path) { return "/media/" + path.split("/").map(encodeURIComponent).join("/"); }
function store(key, val) { try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) { /* приватный режим */ } }
function loadDraft() { try { return JSON.parse(localStorage.getItem("draft") || "{}") || {}; } catch (e) { return {}; } }
function saveDraft() { store("draft", S.draft); }
function typeName(t) { return (S.cfg && S.cfg.type_names[t]) || t; }
function every(fn, ms) { const id = setInterval(fn, ms); S.timers.push(id); return id; }

let toastTimer = null;
function toast(msg, isErr = false) {
  const t = $("#toast");
  t.textContent = msg; t.className = isErr ? "error" : ""; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), isErr ? 6000 : 3500);
}

async function api(path, opts = {}) {
  const o = { method: opts.method || "GET", headers: {} };
  if (opts.body !== undefined) { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(opts.body); }
  let r;
  try { r = await fetch(path, o); } catch (e) { toast("Сервер не отвечает. Он запущен?", true); throw e; }
  if (r.status === 401) {
    if (APP) toast("Нет доступа к данным. Закройте приложение и откройте снова.", true); else location.href = "/login";
    throw new Error("auth");
  }
  const data = await r.json().catch(() => ({}));
  if (!r.ok) { toast(data.error || `Ошибка ${r.status}`, true); throw new Error(data.error || r.status); }
  return data;
}
async function loadCfg(force = false) { if (!S.cfg || force) S.cfg = await api("/api/config"); return S.cfg; }
async function loadStatus(force = false) { if (!S.status || force) S.status = await api("/api/status"); return S.status; }

async function pollJob(id, onUpdate) {
  for (;;) {
    const j = await api("/api/jobs/" + id);
    onUpdate && onUpdate(j);
    if (["done", "error", "canceled"].includes(j.status)) return j;
    await new Promise((r) => setTimeout(r, 1200));
  }
}
function bar(frac) { return `<div class="progress"><i style="width:${Math.round((frac || 0) * 100)}%"></i></div>`; }

// ------------------------------------------------------------------ роутер
const routes = { today: viewToday, discover: viewDiscover, ru: viewRu, clip: viewClip, cam: viewCam, queue: viewQueue, clips: viewClips,
  clipview: viewClipView, settings: viewSettings, source: viewSource };

function parseHash() {
  const h = location.hash.replace(/^#\/?/, "") || "today";
  const [path, qs] = h.split("?");
  const [name, ...rest] = path.split("/");
  return { name, arg: rest.join("/") ? decodeURIComponent(rest.join("/")) : "", q: new URLSearchParams(qs || "") };
}
async function route() {
  S.timers.forEach(clearInterval); S.timers = [];
  S.seq = (S.seq || 0) + 1;
  const r = parseHash();
  const fn = routes[r.name] || viewToday;
  const tab = { cam: "clip", clipview: "clips", source: "settings" }[r.name] || r.name;
  $$("#nav a").forEach((a) => a.classList.toggle("on", a.dataset.tab === tab));
  try { await fn(r.arg, r.q); } catch (e) { if (e.message !== "auth") app.innerHTML += `<p class="err pad">${esc(e.message)}</p>`; }
  window.scrollTo(0, 0);
}
window.addEventListener("hashchange", route);
function go(h) { if (location.hash === h) route(); else location.hash = h; }

async function refreshBadge() {
  try {
    const jobs = await fetch("/api/jobs").then((r) => (r.ok ? r.json() : []));
    const n = jobs.filter((j) => j.status === "queued" || j.status === "running").length;
    const b = $("#qbadge"); b.hidden = !n; b.textContent = n;
  } catch (e) { /* сервер недоступен */ }
}
setInterval(refreshBadge, 4000);

// ------------------------------------------------------------------ «Сегодня»
/** Экран сменился, пока грузились данные (ответ пришёл поздно) — рисовать уже нельзя. */
function staleSince(seq) { return seq !== S.seq; }

async function viewToday() {
  const seq = S.seq;
  await loadCfg(true);
  const d = await api("/api/today");
  if (staleSince(seq)) return;
  const planCard = (d.plan_today || []).length || (d.plan_next || []).length ? `<div class="card hl stack"><h2>По плану на сегодня</h2>
    ${(d.plan_today || []).length ? d.plan_today.map((it, i) => planItemHtml(it, i, false) + (it.clip_status === "done"
      ? `<button class="btn ok" data-ppub="${i}">Опубликовано</button>` : "")).join("") : `<p class="muted">На сегодня в плане ничего нет.</p>`}
    ${(d.plan_next || []).length ? `<p class="muted small">Дальше: ${d.plan_next.map((x) => `${fmtDate(x.date)} — ${esc(x.source_title)}`).join("; ")}</p>` : ""}
    <a class="btn ghost sm" href="#/discover/kino">Весь план</a></div>` : "";
  const bindToday = () => {
    bindPlan(d.plan_today || [], () => route());
    $$("[data-ppub]").forEach((b) => (b.onclick = async () => {
      const r = await api(`/api/plan/${d.plan_today[+b.dataset.ppub].id}/published`, { method: "POST" }); toast(r.message); route();
    }));
    if ((d.plan_today || []).some((x) => x.clip_status === "running" || x.clip_status === "queued")) every(async () => {
      const t = await api("/api/today").catch(() => null);
      if (t && !(t.plan_today || []).some((x) => x.clip_status === "running" || x.clip_status === "queued") && parseHash().name === "today") route();
    }, 5000);
  };
  if (d.empty) {
    app.innerHTML = `<h1>Сегодня</h1>${planCard}<div class="card stack"><h2>Источников пока нет</h2>
      <p class="muted">Добавьте ${APP ? "сериал или фильм" : "сериал, фильм, стрим или блогера"} — и здесь появится план публикаций.</p>
      <a class="btn primary" href="#/source/new">Добавить источник</a></div>`;
    bindToday();
    return;
  }
  const c = d.current;
  const sug = d.suggest || {};
  const fileHtml = d.files_count
    ? `${sug.next ? `<p>Следующий файл: <b class="wrap">${esc(sug.next.name)}</b></p>` : `<p class="muted">Все файлы уже использованы.</p>`}
       ${sug.continue ? `<p class="muted small wrap">Последний использованный: ${esc(sug.continue.name)}</p>` : ""}`
    : d.folder ? `<p class="warn">В папке «${esc(d.folder)}» пока нет видео. Положите туда файлы.</p>`
      : `<p class="warn">Для этого источника не выбрана папка с видео.</p>
         <a class="btn" href="#/source/${encodeURIComponent(c.source_id)}">Выбрать папку</a>`;
  app.innerHTML = `<h1>Сегодня</h1>${planCard}
  <div class="card hl stack" id="curCard">
    <div class="row"><span class="badge ${c.type}">${esc(c.type_name)}</span>
      <span class="grow"></span><span class="${d.overdue ? "due-over" : d.is_due ? "due-ok" : "muted"}">${esc(d.due_status)}</span></div>
    <div class="big wrap">${esc(c.source_title)}</div>
    <p>Пост <b>${c.pos} из ${c.posts_per_block}</b> в блоке · ${fmtDate(c.date)}</p>
    ${fileHtml}
    <div id="feed"></div>
    ${d.clip ? `<p class="ok">Готовый клип: <span class="wrap">${esc(d.clip.filename)}</span></p>
      <a class="btn" href="#/clipview/${d.clip.id}">Открыть готовый клип</a>` : ""}
    <a class="btn primary" href="#/clip?source=${encodeURIComponent(c.source_id)}">Выбрать файл</a>
    <button class="btn ok" id="bPub">Опубликовано</button>
    <div class="grid2"><button class="btn sm" id="bPost">Перенести на завтра</button><button class="btn sm" id="bSkip">Пропустить</button></div>
  </div>
  <div class="card"><h2>Дальше по плану</h2><ul class="list">
    ${d.upcoming.map((p) => `<li><div class="item"><span class="badge ${p.type}">${p.pos}/${p.posts_per_block}</span>
      <span class="t">${esc(p.source_title)}<br><span class="muted small">${esc(p.type_name)} · ${fmtDate(p.date)}</span></span></div></li>`).join("")}
  </ul></div>
  ${d.history.length ? `<button class="btn ghost sm" id="bUndo">Отменить последнее действие</button>` : ""}`;

  bindToday();
  $("#bPub").onclick = async () => {
    const msg = d.clip ? `Отметить, что клип «${d.clip.filename}» опубликован?` : "Отметить пост как опубликованный?";
    if (!confirm(msg)) return;
    const r = await api("/api/schedule/publish", { method: "POST", body: { clip_id: d.clip ? d.clip.id : null } });
    toast(r.message); route();
  };
  $("#bPost").onclick = async () => { const r = await api("/api/schedule/postpone", { method: "POST", body: { days: 1 } }); toast(r.message); route(); };
  $("#bSkip").onclick = async () => {
    if (!confirm("Пропустить этот пост? Следующий пост встанет на эту же дату.")) return;
    const r = await api("/api/schedule/skip", { method: "POST" }); toast(r.message); route();
  };
  const u = $("#bUndo"); if (u) u.onclick = async () => { const r = await api("/api/schedule/undo", { method: "POST" }); toast(r.message); route(); };
  if (d.has_youtube || d.has_twitch) {
    $("#feed").innerHTML = `<p class="muted small">Проверяю свежие видео…</p>`;
    api("/api/feed/" + encodeURIComponent(c.source_id)).then((f) => {
      const parts = [];
      for (const [k, name] of [["youtube", "YouTube"], ["twitch", "Twitch"]]) {
        const x = f[k]; if (!x) continue;
        if (!x.ok) { parts.push(`<p class="muted small">${name}: ${esc(x.error)}</p>`); continue; }
        const it = x.items[0];
        if (it) parts.push(`<p>Свежее на ${name}: <a href="${esc(it.url)}" target="_blank" rel="noopener">${esc(it.title)}</a>
          <span class="muted small">· ${esc((it.published || "").slice(0, 10))}</span></p>`);
      }
      const el = $("#feed"); if (el) el.innerHTML = parts.join("");
    }).catch(() => {});
  }
}

// ------------------------------------------------------------------ «Клип»: выбор файла и момента
async function viewClip(_arg, q) {
  const seq = S.seq;
  await loadCfg();
  const st = await loadStatus();
  if (staleSince(seq)) return;
  const dr = S.draft;
  if (q.get("source") !== null) { dr.source_id = q.get("source"); if (!q.get("file")) dr.file = null; }
  if (q.get("file")) dr.file = q.get("file");
  if (q.get("auto") === "mom") { S.autoMom = true; dr.start = 0; dr.end = null; S.moments = {}; history.replaceState(null, "", "#/clip"); }
  if (q.get("start") !== null) {
    dr.start = Number(q.get("start")) || 0; dr.end = Number(q.get("end")) || null; S.moments = {};
    history.replaceState(null, "", "#/clip");
  }
  if (dr.source_id === undefined) dr.source_id = S.cfg.sources[0] ? S.cfg.sources[0].id : "";
  saveDraft();
  const opts = S.cfg.sources.map((s) => `<option value="${esc(s.id)}" ${s.id === dr.source_id ? "selected" : ""}>${esc(s.title)} (${esc(typeName(s.type))})</option>`).join("");
  app.innerHTML = `<h1>Выбор момента</h1>
  <div class="card"><label class="f" for="srcSel">Источник</label>
    <select id="srcSel">${opts}<option value="" ${dr.source_id ? "" : "selected"}>${APP ? "Выбранные видео" : "Загруженные с телефона"}</option></select>
    <div id="fileBox"></div></div>
  <div id="momentBox"></div>`;
  $("#srcSel").onchange = (e) => { dr.source_id = e.target.value; dr.file = null; saveDraft(); viewClip("", new URLSearchParams()); };
  await renderFiles();
  if (dr.file) await renderMoment();

  async function renderFiles() {
    const box = $("#fileBox");
    const data = await api("/api/files?source=" + encodeURIComponent(dr.source_id || ""));
    let suggested = null;
    if (dr.source_id) {
      try { const t = await api("/api/today"); if (t.current && t.current.source_id === dr.source_id && t.suggest.next) suggested = t.suggest.next.path; } catch (e) { /* нет плана */ }
    }
    const picked = dr.file && dr.file.startsWith("_picked/") ? { name: dr.file.slice(8), path: dr.file } : null;
    if (dr.file && !picked && !data.files.some((f) => f.path === dr.file)) dr.file = null;
    const chosen = data.files.find((f) => f.path === dr.file) || picked;
    if (chosen) {
      box.innerHTML = `<label class="f">Файл</label><div class="row"><b class="grow wrap">${esc(chosen.name)}</b>
        <button class="btn sm ghost" id="chgFile" style="width:auto">Другой файл</button></div>`;
      $("#chgFile").onclick = () => { dr.file = null; saveDraft(); $("#momentBox").innerHTML = ""; renderFiles(); };
      return;
    }
    box.innerHTML = `<label class="f">Файл</label>
      ${data.files.length ? `<ul class="list">${data.files.map((f) => `<li><button class="item ${f.path === suggested ? "sel" : ""}" data-path="${esc(f.path)}">
        <span class="t"><b>${esc(f.name)}</b><br><span class="muted small">${fmtSize(f.size)}${f.used ? " · уже использован" : ""}${f.path === suggested ? " · ★ рекомендуем" : ""}</span></span>
        <span class="chev">›</span></button></li>`).join("")}</ul>`
        : data.has_folder === false ? `<p class="warn">Для источника не выбрана папка с видео.</p>
            <a class="btn" href="#/source/${encodeURIComponent(dr.source_id)}">Выбрать папку</a>`
        : `<p class="warn">В папке нет видео.</p>`}
      ${APP ? `${data.folder && dr.source_id ? `<p class="muted small wrap">Папка: ${esc(data.folder)}</p>` : ""}
        <button class="btn ghost" id="pickV">Выбрать видео на телефоне</button>`
      : `<p class="muted small wrap">Папка на устройстве с сервером: ${esc(data.abs)}</p>
        <label class="btn ghost" for="up">Загрузить короткое видео с телефона</label>
        <input type="file" id="up" accept="video/*" hidden>
        <div id="upProg"></div>`}`;
    $$(".item[data-path]", box).forEach((b) => (b.onclick = async () => {
      dr.file = b.dataset.path; dr.start = 0; dr.end = null; dr.info = null; S.moments = {}; saveDraft();
      history.replaceState(null, "", "#/clip"); // чтобы «назад» с других экранов не сбрасывал выбор
      await renderFiles(); await renderMoment();
    }));
    if (APP) {
      window.onVideoPicked = async (path) => {
        dr.file = path; dr.start = 0; dr.end = null; S.moments = {}; saveDraft();
        history.replaceState(null, "", "#/clip");
        await renderFiles(); await renderMoment();
      };
      $("#pickV").onclick = () => window.AndroidApp.pickVideo();
    } else {
      $("#up").onchange = (e) => upload(e.target.files[0]);
    }
  }

  function upload(file) {
    if (!file) return;
    const fd = new FormData(); fd.append("file", file); fd.append("source_id", dr.source_id || "");
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/upload");
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) $("#upProg").innerHTML = bar(e.loaded / e.total) + `<p class="muted small">Загрузка ${Math.round((100 * e.loaded) / e.total)}%</p>`; };
    xhr.onload = async () => {
      let d = {}; try { d = JSON.parse(xhr.responseText); } catch (e) { /* не JSON */ }
      if (xhr.status !== 200) { $("#upProg").innerHTML = ""; toast(d.error || "Ошибка загрузки", true); return; }
      toast("Видео загружено"); history.replaceState(null, "", "#/clip"); dr.file = d.path; dr.start = 0; dr.end = null; saveDraft(); await renderFiles(); await renderMoment();
    };
    xhr.onerror = () => toast("Ошибка сети при загрузке", true);
    xhr.send(fd);
  }

  async function renderMoment() {
    const box = $("#momentBox");
    box.innerHTML = `<p class="muted pad">Читаю видео…</p>`;
    const info = await api("/api/probe?file=" + encodeURIComponent(dr.file));
    const src = S.cfg.sources.find((s) => s.id === dr.source_id);
    const prof = src ? src.full_profile : { layout: "single", mirror: true, subtitles: true, sub_style: "classic", language: "auto", cam_rect: null };
    const dur = info.duration || 0;
    const mlen = Number(S.cfg.settings.moment_len) || 30;
    if (dr.start == null || dr.start > dur) dr.start = 0;
    if (dr.end == null || dr.end > dur || dr.end <= dr.start) dr.end = Math.min(dur, dr.start + mlen);
    for (const k of ["mirror", "subtitles", "sub_style", "language", "layout"]) if (dr[k] == null || dr.file !== dr.optsFor) dr[k] = prof[k];
    if (dr.file !== dr.optsFor) dr.use_srt = false;
    dr.optsFor = dr.file; saveDraft();
    const langs = [["auto", "Определить сам"], ["ru", "Русский"], ["en", "English"], ["uk", "Українська"], ["de", "Deutsch"], ["es", "Español"], ["fr", "Français"], ["it", "Italiano"], ["pt", "Português"], ["tr", "Türkçe"], ["ja", "日本語"], ["ko", "한국어"]];
    const styles = Object.entries(S.cfg.sub_styles);
    const camOk = !!prof.cam_rect;
    box.innerHTML = `<div class="card stack">
      <div class="player"><video id="pv" playsinline controls preload="metadata" src="${mediaUrl(dr.file)}"></video>
        <img id="pimg" hidden alt="Кадр"></div>
      <p id="pvNote" class="muted small" hidden>Это видео браузер не проигрывает — показываю кадры. Двигайте ползунок «Перемотка».</p>
      <label class="f" for="seek">Перемотка: <span id="seekT">0:00.0</span> из ${fmt(dur, false)}</label>
      <input type="range" id="seek" min="0" max="${dur}" step="0.1" value="${dr.start}">

      <label class="f" for="tStart">Начало</label>
      <div class="timebox"><input id="tStart" inputmode="decimal" value="${fmt(dr.start)}"><button class="btn sm" id="sM">−1 с</button><button class="btn sm" id="sP">+1 с</button></div>
      <input type="range" id="rStart" min="0" max="${dur}" step="0.1" value="${dr.start}" aria-label="Ползунок начала">
      <label class="f" for="tEnd">Конец</label>
      <div class="timebox"><input id="tEnd" inputmode="decimal" value="${fmt(dr.end)}"><button class="btn sm" id="eM">−1 с</button><button class="btn sm" id="eP">+1 с</button></div>
      <input type="range" id="rEnd" step="0.1" aria-label="Ползунок конца">
      <div class="grid2"><button class="btn sm" id="setS">Начало = позиция</button><button class="btn sm" id="setE">Конец = позиция</button></div>
      <p class="big" id="durTxt"></p>
      <button class="btn" id="playSeg">▶ Проиграть отрезок</button>
    </div>
    <div class="card stack"><button class="btn" id="bMom">Предложить моменты</button>
      <div id="momRes"></div></div>
    <div class="card">
      <h2>Настройки клипа</h2>
      <label class="toggle"><span>Зеркало (отразить картинку)</span><input type="checkbox" id="oMirror" ${dr.mirror ? "checked" : ""}></label>
      <label class="toggle"><span>Субтитры</span><input type="checkbox" id="oSubs" ${dr.subtitles ? "checked" : ""}></label>
      <div id="subOpts">
        ${info.has_srt ? `<label class="toggle"><span>Взять текст из файла .srt рядом с видео</span><input type="checkbox" id="oSrt" ${dr.use_srt ? "checked" : ""}></label>` : ""}
        <label class="f" for="oLang">Язык речи</label><select id="oLang">${langs.map(([v, n]) => `<option value="${v}" ${dr.language === v ? "selected" : ""}>${n}</option>`).join("")}</select>
        <label class="f" for="oStyle">Стиль субтитров</label><select id="oStyle">${styles.map(([v, n]) => `<option value="${v}" ${dr.sub_style === v ? "selected" : ""}>${esc(n)}</option>`).join("")}</select>
      </div>
      ${APP ? "" : `<label class="f" for="oLayout">Раскладка</label>
      <select id="oLayout"><option value="single" ${dr.layout === "single" ? "selected" : ""}>Один кадр по центру (фильм, сериал)</option>
        <option value="split" ${dr.layout === "split" ? "selected" : ""}>Экран сверху + вебкамера снизу</option></select>
      <div id="camInfo" class="stack" style="margin-top:10px"></div>`}
    </div>
    <button class="btn primary" id="bMake">Сделать клип</button>`;

    const v = $("#pv"), img = $("#pimg");
    let frameMode = false, frameTimer = null, stopAt = null;
    const showFrame = (t) => { clearTimeout(frameTimer); frameTimer = setTimeout(() => { img.src = `/api/frame?file=${encodeURIComponent(dr.file)}&t=${t.toFixed(1)}&w=720`; }, 200); };
    v.addEventListener("error", () => { frameMode = true; v.hidden = true; img.hidden = false; $("#pvNote").hidden = false; showFrame(dr.start); });
    v.addEventListener("loadedmetadata", () => { v.currentTime = dr.start; });
    v.addEventListener("timeupdate", () => {
      if (!v.isConnected) return; // экран уже сменился, а видео досылает событие
      $("#seek").value = v.currentTime; $("#seekT").textContent = fmt(v.currentTime);
      if (stopAt != null && v.currentTime >= stopAt) { v.pause(); stopAt = null; }
    });
    const pos = () => (frameMode ? Number($("#seek").value) : v.currentTime);
    const seekTo = (t) => { t = Math.max(0, Math.min(dur, t)); $("#seek").value = t; $("#seekT").textContent = fmt(t); if (frameMode) showFrame(t); else v.currentTime = t; };
    $("#seek").oninput = (e) => seekTo(Number(e.target.value));

    function sync(changed) {
      dr.start = Math.max(0, Math.min(dr.start, Math.max(0, dur - 1)));
      dr.end = Math.max(dr.start + 1, Math.min(dr.end, dur));
      $("#tStart").value = fmt(dr.start); $("#tEnd").value = fmt(dr.end);
      $("#rStart").value = dr.start;
      const re = $("#rEnd"); re.min = Math.min(dr.start + 1, dur); re.max = Math.min(dur, dr.start + 180); re.value = dr.end;
      const len = dr.end - dr.start;
      $("#durTxt").innerHTML = `Длительность: ${len.toFixed(1)} с ${len > 180 ? '<span class="warn small">(длинновато для TikTok)</span>' : ""}`;
      saveDraft();
      if (changed === "start") seekTo(dr.start); else if (changed === "end") seekTo(Math.max(dr.start, dr.end - 2));
    }
    const setStart = (t, keepLen = true) => { const len = dr.end - dr.start; dr.start = t; if (keepLen) dr.end = Math.min(dur, t + len); sync("start"); };
    $("#rStart").oninput = (e) => setStart(Number(e.target.value));
    $("#rEnd").oninput = (e) => { dr.end = Number(e.target.value); sync("end"); };
    $("#tStart").onchange = (e) => { const t = parseTime(e.target.value); if (isNaN(t)) { toast("Время пишите так: 12:34.5", true); sync(); } else setStart(t); };
    $("#tEnd").onchange = (e) => { const t = parseTime(e.target.value); if (isNaN(t)) { toast("Время пишите так: 12:34.5", true); sync(); } else { dr.end = t; sync("end"); } };
    $("#sM").onclick = () => setStart(dr.start - 1, false);
    $("#sP").onclick = () => setStart(dr.start + 1, false);
    $("#eM").onclick = () => { dr.end -= 1; sync("end"); };
    $("#eP").onclick = () => { dr.end += 1; sync("end"); };
    $("#setS").onclick = () => setStart(pos(), false);
    $("#setE").onclick = () => { dr.end = pos(); sync("end"); };
    $("#playSeg").onclick = () => {
      if (frameMode) { toast("В режиме кадров проиграть нельзя — смотрите кадры на ползунке", true); return; }
      v.currentTime = dr.start; stopAt = dr.end; v.play().catch(() => {});
    };
    sync();

    // моменты
    const renderCands = (res) => {
      const el = $("#momRes");
      if (!res.candidates.length) { el.innerHTML = `<p class="muted">Ничего не нашлось.</p>`; return; }
      const how = res.source === "chat" ? ` По активности чата (${res.chat_messages} сообщений): где зрители писали в разы чаще обычного. Начало — за 15 с до всплеска.`
        : res.speech ? "" : " (Учитывалась только громкость: рядом с видео нет .srt.)";
      el.innerHTML = `<p class="muted small">Нажмите на момент — ползунки встанут на него.${how}</p>` +
        res.candidates.map((c, i) => `<button class="cand" data-i="${i}"><b>${i + 1}</b><span class="grow">${fmt(c.start, false)}–${fmt(c.end, false)}<br>
          <span class="muted small">${esc(c.reasons.join(", "))}</span></span><span class="bar"><i style="width:${Math.round(c.score * 100)}%"></i></span></button>`).join("");
      $$(".cand", el).forEach((b) => (b.onclick = () => {
        const c = res.candidates[Number(b.dataset.i)]; dr.start = c.start; dr.end = Math.min(dur, c.end); sync("start");
        $$(".cand", el).forEach((x) => (x.style.borderColor = "")); b.style.borderColor = "var(--accent)";
        $("#tStart").scrollIntoView({ block: "center", behavior: "smooth" });
      }));
    };
    if (S.moments[dr.file]) renderCands(S.moments[dr.file]);
    $("#bMom").onclick = async () => {
      const btn = $("#bMom"); btn.disabled = true;
      const job = await api("/api/jobs", { method: "POST", body: { type: "moments", params: { file: dr.file, length: Math.round(dr.end - dr.start) || mlen } } });
      const fin = await pollJob(job.id, (j) => { const el = $("#momRes"); if (el) el.innerHTML = bar(j.progress) + `<p class="muted small">${esc(j.stage)}</p>`; });
      btn.disabled = false;
      if (fin.status !== "done") { $("#momRes").innerHTML = `<p class="err">${esc(fin.error || "Не получилось")}</p>`; return; }
      S.moments[dr.file] = fin.result; renderCands(fin.result);
    };
    if (S.autoMom) { S.autoMom = false; $("#bMom").click(); }

    // опции
    const subVis = () => ($("#subOpts").hidden = !dr.subtitles);
    $("#oMirror").onchange = (e) => { dr.mirror = e.target.checked; saveDraft(); };
    $("#oSubs").onchange = (e) => { dr.subtitles = e.target.checked; saveDraft(); subVis(); };
    if ($("#oSrt")) $("#oSrt").onchange = (e) => { dr.use_srt = e.target.checked; saveDraft(); };
    $("#oLang").onchange = (e) => { dr.language = e.target.value; saveDraft(); };
    $("#oStyle").onchange = (e) => { dr.sub_style = e.target.value; saveDraft(); };
    const camVis = () => {
      const el = $("#camInfo");
      if (!el) return;
      if (dr.layout !== "split") { el.innerHTML = ""; return; }
      if (!src) { el.innerHTML = `<p class="warn">Для раскладки с вебкамерой выберите источник: рамка хранится в его настройках.</p>`; return; }
      el.innerHTML = `<p class="${camOk ? "ok" : "warn"}">${camOk ? "Рамка вебкамеры задана." : "Рамка вебкамеры не задана — клип получится в режиме «один кадр»."}</p>
        <a class="btn" href="#/cam/${encodeURIComponent(src.id)}?file=${encodeURIComponent(dr.file)}&t=${dr.start}">Проверить рамку вебкамеры</a>`;
    };
    if (APP) dr.layout = "single"; // в приложении — только сериалы и фильмы, один кадр
    else $("#oLayout").onchange = (e) => { dr.layout = e.target.value; saveDraft(); camVis(); };
    subVis(); camVis();

    $("#bMake").onclick = async () => {
      const params = { source_id: dr.source_id || null, file: dr.file, start: dr.start, end: dr.end, mirror: dr.mirror,
        subtitles: dr.subtitles, language: dr.language, sub_style: dr.sub_style, layout: dr.layout, sub_source: dr.use_srt ? "srt" : "asr" };
      await api("/api/jobs", { method: "POST", body: { type: "clip", params } });
      toast("Клип добавлен в очередь"); refreshBadge(); go("#/queue");
    };
  }
}

// ------------------------------------------------------------------ «Рамка вебкамеры»
async function viewCam(sid, q) {
  const seq = S.seq;
  await loadCfg(true);
  if (staleSince(seq)) return;
  const src = S.cfg.sources.find((s) => s.id === sid);
  if (!src) { app.innerHTML = `<h1>Рамка вебкамеры</h1><p class="err">Источник не найден.</p>`; return; }
  let file = q.get("file") || (S.draft.source_id === sid ? S.draft.file : null);
  if (!file) { const fl = await api("/api/files?source=" + encodeURIComponent(sid)); file = fl.files.length ? fl.files[fl.files.length - 1].path : null; }
  if (!file) {
    app.innerHTML = `<h1>Рамка вебкамеры</h1><div class="card"><p class="warn">В папке источника нет видео — рамку не на чем показать. Сначала положите файл в папку «${esc(src.folder)}».</p></div>`;
    return;
  }
  const info = await api("/api/probe?file=" + encodeURIComponent(file));
  const st = await loadStatus();
  const prof = src.full_profile;
  const rects = { cam: prof.cam_rect ? [...prof.cam_rect] : [0.68, 0.62, 0.3, 0.35], screen: prof.screen_rect ? [...prof.screen_rect] : [0, 0, 1, 1] };
  let mode = "cam";
  let t = Math.min(Number(q.get("t")) || info.duration * 0.3, Math.max(0, info.duration - 0.5));
  app.innerHTML = `<h1>Рамка вебкамеры</h1>
  <div class="card stack"><p class="muted small wrap">${esc(src.title)} · ${esc(file.split("/").pop())}</p>
    <div class="seg"><button id="mCam" class="on">Вебкамера</button><button id="mScr">Экран (необязательно)</button></div>
    <div class="camwrap" id="cw"><img id="fimg" alt="Кадр из видео">
      <div class="rect screen" id="rScr"><span class="lbl">ЭКРАН</span></div>
      <div class="rect" id="rCam"><span class="lbl">КАМЕРА</span><i class="h tl"></i><i class="h tr"></i><i class="h bl"></i><i class="h br"></i></div></div>
    <p class="muted small">Потяните рамку пальцем, чтобы передвинуть. Тяните за белые кружки в углах, чтобы изменить размер.</p>
    <label class="f" for="ft">Кадр: <span id="ftT">${fmt(t, false)}</span></label>
    <input type="range" id="ft" min="0" max="${Math.max(0, info.duration - 0.5)}" step="1" value="${t}">
    ${st.opencv ? `<button class="btn" id="bFace">Найти лицо автоматически</button>` : `<p class="muted small">Автопоиск лица недоступен (на этом устройстве нет OpenCV) — задайте рамку вручную.</p>`}
    <button class="btn sm ghost" id="bFull" hidden>Экран: весь кадр</button>
  </div>
  <div class="card"><h2>Как будет в клипе</h2><canvas id="pv916" class="preview916" width="270" height="480"></canvas></div>
  <button class="btn primary" id="bSave">Сохранить рамку</button>
  <div style="height:10px"></div>
  <button class="btn danger" id="bNone">Без вебкамеры</button>`;

  const img = $("#fimg"), cw = $("#cw");
  const els = { cam: $("#rCam"), screen: $("#rScr") };
  const loadFrame = () => { img.src = `/api/frame?file=${encodeURIComponent(file)}&t=${t}&w=960`; };
  img.onload = () => { place(); preview(); };
  loadFrame();
  $("#ft").oninput = (e) => { t = Number(e.target.value); $("#ftT").textContent = fmt(t, false); };
  $("#ft").onchange = loadFrame;

  function place() {
    for (const k of ["cam", "screen"]) {
      const [x, y, w, h] = rects[k], el = els[k];
      Object.assign(el.style, { left: x * 100 + "%", top: y * 100 + "%", width: w * 100 + "%", height: h * 100 + "%" });
    }
    els.screen.hidden = mode !== "screen" && rects.screen.join() === "0,0,1,1";
    // активная рамка сверху и с ручками
    els.cam.style.zIndex = mode === "cam" ? 2 : 1; els.screen.style.zIndex = mode === "screen" ? 2 : 1;
    $$(".h", els.cam).forEach((h) => (h.hidden = mode !== "cam"));
    if (mode === "screen" && !$(".h", els.screen)) els.screen.insertAdjacentHTML("beforeend", '<i class="h tl"></i><i class="h tr"></i><i class="h bl"></i><i class="h br"></i>');
    $$(".h", els.screen).forEach((h) => (h.hidden = mode !== "screen"));
    $("#bFull").hidden = mode !== "screen";
  }
  function setMode(m) { mode = m; $("#mCam").classList.toggle("on", m === "cam"); $("#mScr").classList.toggle("on", m === "screen"); place(); }
  $("#mCam").onclick = () => setMode("cam");
  $("#mScr").onclick = () => setMode("screen");
  $("#bFull").onclick = () => { rects.screen = [0, 0, 1, 1]; place(); preview(); };

  // перетаскивание пальцем (pointer events работают и для мыши, и для касаний)
  let drag = null;
  cw.addEventListener("pointerdown", (e) => {
    const el = els[mode];
    if (!el.contains(e.target)) return;
    e.preventDefault();
    const handle = e.target.classList.contains("h") ? [...e.target.classList].find((c) => c.length === 2) : "move";
    drag = { handle, x0: e.clientX, y0: e.clientY, r0: [...rects[mode]], box: cw.getBoundingClientRect() };
    cw.setPointerCapture(e.pointerId);
  });
  cw.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const dx = (e.clientX - drag.x0) / drag.box.width, dy = (e.clientY - drag.y0) / drag.box.height;
    let [x, y, w, h] = drag.r0; const MIN = 0.05;
    if (drag.handle === "move") { x = Math.min(Math.max(0, x + dx), 1 - w); y = Math.min(Math.max(0, y + dy), 1 - h); }
    else {
      let x2 = x + w, y2 = y + h;
      if (drag.handle.includes("l")) x = Math.min(Math.max(0, x + dx), x2 - MIN);
      if (drag.handle.includes("r")) x2 = Math.max(Math.min(1, x2 + dx), x + MIN);
      if (drag.handle.includes("t")) y = Math.min(Math.max(0, y + dy), y2 - MIN);
      if (drag.handle.includes("b")) y2 = Math.max(Math.min(1, y2 + dy), y + MIN);
      w = x2 - x; h = y2 - y;
    }
    rects[mode] = [x, y, w, h].map((v) => Math.round(v * 10000) / 10000);
    place();
  });
  const end = () => { if (drag) { drag = null; preview(); } };
  cw.addEventListener("pointerup", end); cw.addEventListener("pointercancel", end);

  // схематичный предпросмотр вертикального кадра
  function preview() {
    const c = $("#pv916"), g = c.getContext("2d"), W = c.width, H = c.height;
    if (!img.naturalWidth) return;
    const iw = img.naturalWidth, ih = img.naturalHeight;
    const hs = prof.split_screen_h, hg = prof.split_gap_h, hc = prof.split_cam_h;
    g.fillStyle = "#222"; g.fillRect(0, 0, W, H);
    const drawFill = (r, dy, dh) => {
      let [x, y, w, h] = r.map((v, i) => v * (i % 2 ? ih : iw));
      const a = W / dh;
      if (w / h > a) { const nw = h * a; x += (w - nw) / 2; w = nw; } else { const nh = w / a; y += (h - nh) / 2; h = nh; }
      g.drawImage(img, x, y, w, h, 0, dy, W, dh);
    };
    drawFill(rects.screen, 0, H * hs);
    drawFill(rects.cam, H * (hs + hg), H * hc);
    g.fillStyle = "#ffd400"; g.font = "bold 22px sans-serif"; g.textAlign = "center";
    g.fillText("СУБТИТРЫ", W * 0.45, H * (hs + hg / 2) + 8);
  }

  if ($("#bFace")) $("#bFace").onclick = async () => {
    const b = $("#bFace"); b.disabled = true; b.textContent = "Ищу лицо…";
    try {
      const r = await api("/api/facedetect", { method: "POST", body: { file, t } });
      if (r.ok) { rects.cam = r.rect; setMode("cam"); preview(); toast("Лицо найдено — проверьте рамку и нажмите «Сохранить рамку»"); }
      else toast(r.error, true);
    } finally { b.disabled = false; b.textContent = "Найти лицо автоматически"; }
  };
  $("#bSave").onclick = async () => {
    const scr = rects.screen.join() === "0,0,1,1" ? null : rects.screen;
    await api(`/api/sources/${encodeURIComponent(sid)}/profile`, { method: "PUT", body: { cam_rect: rects.cam, screen_rect: scr, layout: "split" } });
    await loadCfg(true); S.draft.layout = "split"; saveDraft();
    toast("Рамка сохранена"); history.back();
  };
  $("#bNone").onclick = async () => {
    if (!confirm("Убрать вебкамеру? Клипы этого источника будут в режиме «один кадр».")) return;
    await api(`/api/sources/${encodeURIComponent(sid)}/profile`, { method: "PUT", body: { cam_rect: null, layout: "single" } });
    await loadCfg(true); S.draft.layout = "single"; saveDraft(); toast("Вебкамера убрана"); history.back();
  };
  setMode("cam");
}

// ------------------------------------------------------------------ «Очередь»
const STATUS = { queued: "В очереди", running: "Идёт", done: "Готово", error: "Ошибка", canceled: "Отменено" };
async function viewQueue() {
  app.innerHTML = `<h1>Очередь</h1><p class="muted small">Задачи выполняются на сервере. Можно закрыть вкладку или выключить экран — работа не прервётся.</p><div id="jobs"></div>`;
  const draw = async () => {
    const jobs = await api("/api/jobs");
    const el = $("#jobs"); if (!el) return;
    if (!jobs.length) { el.innerHTML = `<div class="card"><p class="muted">Очередь пуста. Сделайте клип на вкладке «Клип».</p></div>`; return; }
    el.innerHTML = jobs.map((j) => `<div class="card stack" data-id="${j.id}">
      <div class="row"><b class="grow wrap">${esc(j.title)}</b><span class="badge">${STATUS[j.status]}</span></div>
      ${j.status === "running" || j.status === "queued" ? bar(j.progress) + `<p class="muted small">${esc(j.stage)} · ${Math.round(j.progress * 100)}%</p>` : ""}
      ${(j.warnings || []).map((w) => `<p class="warn">⚠ ${esc(w)}</p>`).join("")}
      ${j.error ? `<p class="err">${esc(j.error)}</p>` : ""}
      ${j.status === "done" && j.result && j.result.clip_id ? `<a class="btn primary" href="#/clipview/${j.result.clip_id}">Открыть клип</a>` : ""}
      ${j.status === "done" && j.type === "moments" ? `<button class="btn" data-mom="${j.id}">Показать моменты</button>` : ""}
      ${j.status === "queued" || j.status === "running" ? `<button class="btn sm danger" data-cancel="${j.id}">Отменить</button>` : ""}
    </div>`).join("");
    $$("[data-cancel]", el).forEach((b) => (b.onclick = async () => { await api(`/api/jobs/${b.dataset.cancel}/cancel`, { method: "POST" }); draw(); }));
    $$("[data-mom]", el).forEach((b) => (b.onclick = () => {
      const j = jobs.find((x) => x.id === b.dataset.mom); S.moments[j.result.file] = j.result;
      S.draft.file = j.result.file; saveDraft(); go("#/clip");
    }));
  };
  await draw();
  every(() => { if (!document.hidden) draw().catch(() => {}); refreshBadge(); }, 1500);
}

// ------------------------------------------------------------------ «Клипы»
async function viewClips() {
  const seq = S.seq;
  const clips = await api("/api/clips");
  if (staleSince(seq)) return;
  app.innerHTML = `<h1>Клипы</h1>${clips.length ? `<div class="card"><ul class="list">${clips.map((c) => `<li><a class="item" href="#/clipview/${c.id}">
    <span class="t"><b>${esc(c.source_title)}</b> ${c.published ? '<span class="badge" style="background:#1d5a33">опубликован</span>' : ""}<br>
    <span class="muted small wrap">${esc(c.filename)} · ${fmt(c.start, false)}–${fmt(c.end, false)}${c.exists ? "" : " · файл удалён"}</span></span><span class="chev">›</span></a></li>`).join("")}</ul></div>`
    : `<div class="card"><p class="muted">Клипов пока нет.</p><a class="btn primary" href="#/clip">Сделать клип</a></div>`}`;
}

// ------------------------------------------------------------------ «Готовый клип»
async function viewClipView(id) {
  const seq = S.seq;
  await loadCfg();
  const m = await api("/api/clips/" + encodeURIComponent(id));
  if (staleSince(seq)) return;
  let lines = (m.lines || []).map((l) => ({ s: l.s, e: l.e, text: l.text }));
  const styles = Object.entries(S.cfg.sub_styles);
  const galleryName = m.gallery_path.split("/").slice(-3).join("/");
  app.innerHTML = `<h1>Готовый клип</h1>
  <div class="card stack">
    ${m.exists ? `<video class="clipvideo" id="cv" controls playsinline preload="metadata" src="${m.url}"></video>` : `<p class="err">Файл клипа удалён из галереи.</p>`}
    <p class="muted small wrap">Сохранён: ${esc(galleryName)}</p>
    ${(m.warnings || []).map((w) => `<p class="warn">⚠ ${esc(w)}</p>`).join("")}
    <button class="btn primary" id="bShare" ${m.exists ? "" : "disabled"}>Поделиться</button>
    <p class="muted small" id="shareNote"></p>
    <div class="grid2">${APP ? `<button class="btn" id="bOpen">Открыть в галерее</button>`
      : `<a class="btn" id="bDl" href="${m.url}&download=1" download="${esc(m.filename)}">Скачать</a>`}
      <button class="btn ok" id="bPub">${m.published ? "Уже опубликован" : "Опубликовано"}</button></div>
  </div>
  <div class="card"><h2>Субтитры</h2>
    <p class="muted small">Исправьте текст или время и нажмите «Пересобрать». Речь заново не распознаётся.</p>
    <div id="lines"></div>
    <button class="btn sm ghost" id="bAdd">+ Добавить строку</button>
    <label class="toggle"><span>Зеркало</span><input type="checkbox" id="rMirror" ${m.mirror ? "checked" : ""}></label>
    <label class="toggle"><span>Показывать субтитры</span><input type="checkbox" id="rSubs" ${m.subtitles ? "checked" : ""}></label>
    <label class="f" for="rStyle">Стиль субтитров</label>
    <select id="rStyle">${styles.map(([v, n]) => `<option value="${v}" ${m.sub_style === v ? "selected" : ""}>${esc(n)}</option>`).join("")}</select>
    <div style="height:12px"></div>
    <button class="btn primary" id="bRebuild">Пересобрать</button>
    <div id="rbProg"></div>
  </div>
  <button class="btn danger" id="bDel">Удалить клип</button>`;

  const drawLines = () => {
    const el = $("#lines");
    if (!lines.length) { el.innerHTML = `<p class="muted">Строк нет${m.subtitles ? " — речь не найдена" : ""}. Можно добавить вручную.</p>`; return; }
    el.innerHTML = lines.map((l, i) => `<div class="subline" data-i="${i}"><div class="times">
      <input data-k="s" inputmode="decimal" value="${fmt(l.s)}" aria-label="Начало строки ${i + 1}"><input data-k="e" inputmode="decimal" value="${fmt(l.e)}" aria-label="Конец строки ${i + 1}">
      <button class="x" data-del="${i}" aria-label="Удалить строку">×</button></div>
      <textarea data-k="text" rows="2" aria-label="Текст строки ${i + 1}">${esc(l.text)}</textarea></div>`).join("");
    $$(".subline", el).forEach((row) => {
      const i = Number(row.dataset.i);
      $$("[data-k]", row).forEach((inp) => (inp.onchange = () => {
        const k = inp.dataset.k;
        if (k === "text") lines[i].text = inp.value;
        else { const t = parseTime(inp.value); if (isNaN(t)) { toast("Время пишите так: 0:03.5", true); inp.value = fmt(lines[i][k]); } else lines[i][k] = t; }
      }));
      $("[data-del]", row).onclick = () => { lines.splice(i, 1); drawLines(); };
    });
  };
  drawLines();
  $("#bAdd").onclick = () => {
    const last = lines[lines.length - 1]; const s = last ? last.e : 0; const dur = m.end - m.start;
    lines.push({ s: Math.min(s, dur - 0.5), e: Math.min(s + 1.5, dur), text: "" }); drawLines();
    $$("#lines textarea").pop().focus();
  };

  // «Поделиться»: файл заранее скачивается в память, чтобы окно отправки открылось сразу по нажатию
  let blobFile = null;
  const note = $("#shareNote");
  const canShareFiles = !!(navigator.canShare && window.File);
  if (APP) {
    note.textContent = m.exists ? "Нажмите «Поделиться» и выберите TikTok." : "";
    $("#bOpen").onclick = () => window.AndroidApp.openClip(m.id);
  } else if (m.exists) {
    if (!window.isSecureContext) note.textContent = "Кнопка «Поделиться» работает только по адресу 127.0.0.1 или через https. Используйте «Скачать».";
    else if (!canShareFiles) note.textContent = "Этот браузер не умеет отправлять файлы. Используйте «Скачать».";
    else {
      note.textContent = "Готовлю файл для отправки…";
      fetch(m.url).then((r) => r.blob()).then((b) => {
        blobFile = new File([b], m.filename, { type: "video/mp4" });
        note.textContent = navigator.canShare({ files: [blobFile] }) ? "Нажмите «Поделиться» и выберите TikTok." : "Браузер не разрешает отправить этот файл. Используйте «Скачать».";
      }).catch(() => (note.textContent = "Не удалось подготовить файл. Используйте «Скачать»."));
    }
  }
  $("#bShare").onclick = async () => {
    if (APP) { window.AndroidApp.shareClip(m.id); return; }
    if (blobFile && navigator.canShare && navigator.canShare({ files: [blobFile] })) {
      try { await navigator.share({ files: [blobFile], title: m.filename }); return; }
      catch (e) { if (e.name === "AbortError") return; }
    }
    toast("Отправка недоступна — скачиваю файл"); $("#bDl").click();
  };
  $("#bPub").onclick = async () => {
    if (m.published) return;
    const t = await api("/api/today");
    let msg = "Отметить пост как опубликованный?";
    if (!t.empty && t.current.source_id !== m.source_id) msg = `Сейчас по плану «${t.current.source_title}», а этот клип из «${m.source_title}». Всё равно отметить текущий пост опубликованным?`;
    if (!confirm(msg)) return;
    const r = await api("/api/schedule/publish", { method: "POST", body: { clip_id: m.id } });
    toast(r.message); go("#/today");
  };
  $("#bRebuild").onclick = async () => {
    const b = $("#bRebuild"); b.disabled = true;
    const body = { lines: lines.filter((l) => l.text.trim()), mirror: $("#rMirror").checked, subtitles: $("#rSubs").checked, sub_style: $("#rStyle").value };
    const job = await api(`/api/clips/${m.id}/rebuild`, { method: "POST", body });
    const fin = await pollJob(job.id, (j) => { const el = $("#rbProg"); if (el) el.innerHTML = bar(j.progress) + `<p class="muted small">${esc(j.stage)}</p>`; });
    if (parseHash().name !== "clipview") return;
    b.disabled = false;
    if (fin.status === "done") { toast("Клип пересобран"); viewClipView(id); } else $("#rbProg").innerHTML = `<p class="err">${esc(fin.error || "Не получилось")}</p>`;
  };
  $("#bDel").onclick = async () => {
    if (!confirm("Удалить клип из галереи? Это нельзя отменить.")) return;
    await api("/api/clips/" + m.id, { method: "DELETE" }); toast("Клип удалён"); go("#/clips");
  };
}

// ------------------------------------------------------------------ «Настройки»
async function viewSettings() {
  const seq = S.seq;
  await loadCfg(true);
  const st = await loadStatus(true);
  if (staleSince(seq)) return;
  const sc = S.cfg.schedule, se = S.cfg.settings;
  let order = [...sc.type_order];
  const asr = st.asr;
  const ff = st.ffmpeg;
  app.innerHTML = `<h1>Настройки</h1>
  <div class="card"><h2>Источники</h2>
    ${S.cfg.sources.length ? `<ul class="list">${S.cfg.sources.map((s) => `<li><a class="item" href="#/source/${encodeURIComponent(s.id)}">
      <span class="badge ${s.type}">${esc(typeName(s.type))}</span><span class="t"><b>${esc(s.title)}</b>${s.enabled === false ? " (выключен)" : ""}<br>
      <span class="muted small">папка: ${esc(s.folder)}${APP ? "" : ` · ${s.full_profile.layout === "split" ? "экран + вебкамера" : "один кадр"}`}</span></span><span class="chev">›</span></a></li>`).join("")}</ul>`
      : `<p class="muted">Пока пусто.</p>`}
    <a class="btn primary" href="#/source/new">Добавить источник</a></div>

  <div class="card"><h2>Расписание</h2>
    <label class="f" for="sDate">Дата старта</label><input type="date" id="sDate" value="${esc(sc.start_date || "")}">
    <div class="grid2"><div><label class="f" for="sInt">Интервал, дней</label><input type="number" id="sInt" min="1" max="30" value="${sc.interval_days}"></div>
      <div><label class="f" for="sPpb">Постов в блоке</label><input type="number" id="sPpb" min="1" max="20" value="${sc.posts_per_block}"></div></div>
    <label class="f">Порядок типов</label><ul class="list" id="order"></ul>
    <button class="btn primary" id="bSched">Сохранить расписание</button></div>

  ${APP ? keysCard(se) : ""}
  ${APP ? appAsrCard(st, se) : `  <div class="card"><h2>Распознавание речи</h2>
    <p class="small">faster-whisper: ${asr["faster-whisper"].ok ? '<span class="ok">установлен</span>' : `<span class="muted">${esc(asr["faster-whisper"].message)}</span>`}</p>
    <p class="small">whisper.cpp: ${asr["whisper.cpp"].ok ? `<span class="ok">установлен</span> · модели: ${esc(asr["whisper.cpp"].models.join(", ") || "нет")}` : `<span class="muted">${esc(asr["whisper.cpp"].message)}</span>`}</p>
    <label class="f" for="aB">Движок</label><select id="aB">
      ${[["auto", "Автоматически"], ["faster-whisper", "faster-whisper (ПК, сервер)"], ["whisper.cpp", "whisper.cpp (телефон)"], ["off", "Выключено"]].map(([v, n]) => `<option value="${v}" ${se.asr_backend === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="aM">Модель</label><select id="aM">
      ${S.cfg.models.map((m) => `<option value="${m}" ${(se.asr_model || st.default_model) === m ? "selected" : ""}>${m}${{ tiny: " — самая быстрая", base: " — быстрая", small: " — точнее (ПК)", medium: " — точная, медленная (ПК)" }[m]}</option>`).join("")}</select>
    <p class="muted small">На телефоне выбирайте tiny или base, на компьютере — small или medium.</p>
    <label class="f" for="aL">Язык по умолчанию</label><select id="aL">
      ${[["auto", "Определять автоматически"], ["ru", "Русский"], ["en", "English"], ["uk", "Українська"]].map(([v, n]) => `<option value="${v}" ${se.asr_language === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="vP">Скорость сборки видео</label><select id="vP">
      ${[["ultrafast", "Максимально быстро (файл больше)"], ["veryfast", "Быстро (для телефона)"], ["faster", "Средне (для ПК)"], ["medium", "Медленно, файл меньше"]].map(([v, n]) => `<option value="${v}" ${(se.x264_preset || st.default_preset) === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="mL">Длина подсказанных моментов, секунд</label><input type="number" id="mL" min="5" max="180" value="${se.moment_len}">
    <div style="height:12px"></div><button class="btn primary" id="bAsr">Сохранить</button></div>`}
  <div class="card"><h2>О системе</h2>
    ${st.checks ? st.checks.map((c) => `<p class="small">${esc(c.name)}: ${c.ok ? '<span class="ok">есть</span>' : '<span class="err">нет</span>'}</p>`).join("")
      : `<p class="small">ffmpeg: ${ff.ffmpeg ? '<span class="ok">есть</span>' : '<span class="err">нет</span>'} · libass: ${ff.libass ? '<span class="ok">есть</span>' : '<span class="err">нет</span>'} · H.264: ${ff.libx264 ? '<span class="ok">есть</span>' : '<span class="err">нет</span>'}</p>
    ${ff.problems.map((p) => `<p class="err">${esc(p)}</p>`).join("")}`}
    <p class="small wrap">Папка с видео: ${esc(st.media_root)}</p>
    <p class="small wrap">Готовые клипы: ${esc(st.output_dir)}</p>
    <p class="small">Автопоиск лица (OpenCV): ${st.opencv ? "есть" : "нет"} · ${st.termux ? "Android/Termux" : "компьютер/сервер"} · версия ${esc(st.version)}</p>
    ${st.auth ? `<button class="btn ghost" id="bOut">Выйти</button>` : ""}</div>`;

  const drawOrder = () => {
    $("#order").innerHTML = order.map((t, i) => `<li><div class="item"><span class="t">${i + 1}. ${esc(typeName(t))}</span>
      <button class="btn sm" style="width:56px" data-up="${i}" ${i ? "" : "disabled"} aria-label="Выше">↑</button>
      <button class="btn sm" style="width:56px" data-down="${i}" ${i < order.length - 1 ? "" : "disabled"} aria-label="Ниже">↓</button></div></li>`).join("");
    $$("[data-up]").forEach((b) => (b.onclick = () => { const i = +b.dataset.up; [order[i - 1], order[i]] = [order[i], order[i - 1]]; drawOrder(); }));
    $$("[data-down]").forEach((b) => (b.onclick = () => { const i = +b.dataset.down; [order[i + 1], order[i]] = [order[i], order[i + 1]]; drawOrder(); }));
  };
  drawOrder();
  $("#bSched").onclick = async () => {
    await api("/api/schedule", { method: "PUT", body: { start_date: $("#sDate").value || null, interval_days: +$("#sInt").value, posts_per_block: +$("#sPpb").value, type_order: order } });
    toast("Расписание сохранено");
  };
  $$("[data-model]").forEach((b) => (b.onclick = async () => {
    b.disabled = true;
    const job = await api("/api/jobs", { method: "POST", body: { type: "model", params: { model: b.dataset.model } } });
    const box = $("#mProg");
    const fin = await pollJob(job.id, (j) => { if (box) box.innerHTML = bar(j.progress) + `<p class="muted small">${esc(j.stage)}</p>`; });
    if (parseHash().name !== "settings") return;
    if (fin.status === "done") { toast("Модель скачана"); viewSettings(); } else { b.disabled = false; box.innerHTML = `<p class="err">${esc(fin.error || "Не получилось")}</p>`; }
  }));
  $$("[data-mdel]").forEach((b) => (b.onclick = async () => {
    if (!confirm("Удалить модель? Её можно будет скачать снова.")) return;
    b.disabled = true;
    const job = await api("/api/jobs", { method: "POST", body: { type: "model", params: { model: b.dataset.mdel, action: "delete" } } });
    const fin = await pollJob(job.id, () => {});
    if (parseHash().name !== "settings") return;
    if (fin.status === "done") { toast("Модель удалена"); viewSettings(); } else { b.disabled = false; toast(fin.error || "Не получилось"); }
  }));
  const bKeys = $("#bKeys");
  if (bKeys) {
    const send = async (body, msg) => { await api("/api/settings", { method: "PUT", body }); await loadCfg(true); toast(msg); viewSettings(); };
    bKeys.onclick = async () => {
      const body = {};
      for (const [id, k] of KEY_FIELDS) { const v = $("#" + id).value.trim(); if (v) body[k] = v; }
      if (!Object.keys(body).length) { toast("Вставьте ключ в поле", true); return; }
      await send(body, "Ключ сохранён");
    };
    $("#bKeysDel").onclick = async () => {
      if (!confirm("Стереть ключ TMDB с телефона?")) return;
      await send(Object.fromEntries(KEY_FIELDS.map(([, k]) => [k, ""])), "Ключ стёрт");
    };
  }
  $("#bAsr").onclick = async () => {
    const body = { asr_backend: $("#aB").value, asr_model: $("#aM").value, asr_language: $("#aL").value, moment_len: +$("#mL").value };
    if ($("#vP")) body.x264_preset = $("#vP").value;
    if ($("#vB")) body.bitrate_mbps = +$("#vB").value;
    await api("/api/settings", { method: "PUT", body });
    await loadCfg(true); toast("Настройки сохранены");
  };
  const out = $("#bOut"); if (out) out.onclick = async () => { await fetch("/logout", { method: "POST" }); location.href = "/login"; };
}

const KEY_FIELDS = [["kTm", "tmdb_key"]];
function keysCard(se) {
  const f = (id, k, label) => `<label class="f" for="${id}">${label}</label>
    <input id="${id}" autocomplete="off" autocapitalize="off" spellcheck="false"
      placeholder="${se[k + "_set"] ? `сохранён (${esc(se[k + "_hint"])}) — вставьте новый, чтобы заменить` : "не задан"}">`;
  return `<div class="card"><h2>Ключ TMDB</h2>
    <p class="muted small">Бесплатный. Нужен для рейтинга серий и подборок во вкладке «Моменты». Хранится только на этом телефоне.
      Как получить — в инструкции, раздел «Моменты».</p>
    ${f("kTm", "tmdb_key", "Ключ API или токен доступа")}
    <p class="muted small"><a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">themoviedb.org/settings/api</a></p>
    <div style="height:12px"></div><button class="btn primary" id="bKeys">Сохранить ключ</button>
    <button class="btn ghost sm" id="bKeysDel">Стереть ключ</button></div>`;
}

// ------------------------------------------------------------------ «Моменты»: автоплан и подборки TMDB (сериалы и фильмы)
function fmtRating(r) { return (Number(r) || 0).toFixed(1).replace(".", ","); }

async function viewDiscover(_sec, q) {
  const seq = S.seq;
  await loadCfg();
  if (staleSince(seq)) return;
  S.discSec = "kino";
  app.innerHTML = `<h1>Моменты</h1>
  <p class="muted small" style="margin:10px 2px">Автоплан моментов на несколько дней, лучшие серии по рейтингу TMDB, что популярно сейчас.</p>
  <div id="dBox"><p class="muted pad">Загружаю…</p></div>
  <button class="btn ghost sm" id="dRef">Обновить</button>`;
  $("#dRef").onclick = () => renderKino($("#dBox"), true);
  renderKino($("#dBox"), !!(q && q.get("refresh") === "1"));
}

// ------------------------------------------------------------------ «Фильмы и сериалы»: автоплан и TMDB
const PLAN_ST = { queued: "клип в очереди", running: "клип собирается", done: "клип готов", error: "ошибка сборки", canceled: "сборка отменена", none: "клип не сделан" };
function planItemHtml(it, i, full = true) {
  const st = it.clip_status;
  return `<div class="mom"><div class="row"><span class="tm">${fmtDate(it.date)}</span><span class="grow"></span>
      <span class="small ${st === "done" ? "ok" : st === "error" ? "err" : "muted"}">${it.published ? "опубликовано" : PLAN_ST[st] || st}</span></div>
    <b class="wrap">${esc(it.source_title)} · ${esc(it.file_name)}</b>
    <span class="small">${fmt(it.start, false)}–${fmt(it.end, false)} · ${esc(it.how || "")}${(it.reasons || []).length ? " · " + esc(it.reasons.join(", ")) : ""}</span>
    ${it.why ? `<span class="muted small wrap">${esc(it.why)}</span>` : ""}
    ${st === "running" || st === "queued" ? bar(it.progress) : ""}
    ${st === "error" && it.error ? `<span class="err small">${esc(it.error)}</span>` : ""}
    <div class="row">${st === "done" ? `<a class="btn sm primary" href="#/clipview/${encodeURIComponent(it.clip_id)}">Открыть клип</a>`
      : st === "queued" || st === "running" ? "" : `<button class="btn sm" data-pclip="${i}">Сделать клип</button>`}
      <button class="btn sm ghost" data-pmom="${i}">Момент</button>
      ${full ? `<button class="btn sm ghost" data-pdel="${i}" aria-label="Убрать из плана">Убрать</button>` : ""}</div></div>`;
}
function bindPlan(items, after) {
  $$("[data-pclip]").forEach((b) => (b.onclick = async () => {
    b.disabled = true; await api(`/api/plan/${items[+b.dataset.pclip].id}/clip`, { method: "POST" }); toast("Клип добавлен в очередь"); refreshBadge(); after();
  }));
  $$("[data-pmom]").forEach((b) => (b.onclick = () => {
    const it = items[+b.dataset.pmom];
    Object.assign(S.draft, { source_id: it.source_id, file: it.file }); saveDraft();
    go(`#/clip?source=${encodeURIComponent(it.source_id)}&file=${encodeURIComponent(it.file)}&start=${it.start}&end=${it.end}`);
  }));
  $$("[data-pdel]").forEach((b) => (b.onclick = async () => {
    if (!confirm("Убрать момент из плана? Готовый клип останется в «Клипах».")) return;
    await api(`/api/plan/${items[+b.dataset.pdel].id}`, { method: "DELETE" }); after();
  }));
}

async function renderKino(box, refresh) {
  box.innerHTML = `<p class="muted pad">Загружаю…</p>`;
  const [plan, lists] = await Promise.all([api("/api/plan"), api("/api/tmdb/lists" + (refresh ? "?refresh=1" : "")).catch((e) => ({ error: e.message }))]);
  if (parseHash().name !== "discover" || S.discSec !== "kino") return;
  const srcs = S.cfg.sources.filter((x) => x.enabled !== false);
  const today = new Date(), p2 = (n) => String(n).padStart(2, "0");
  const iso = `${today.getFullYear()}-${p2(today.getMonth() + 1)}-${p2(today.getDate())}`;
  const items = plan.items.filter((x) => !x.published);
  const kinoSrcs = srcs.filter((x) => x.type === "series" || x.type === "movie");
  box.innerHTML = `<div class="card stack"><h2>Автоплан</h2>
      <p class="muted small">Приложение само выберет моменты и разложит по дням — один в день.
        Сериал: лучшие по рейтингу серии (TMDB), фильм: лучшие сцены. Оценка сцен — офлайн, без интернета.</p>
      ${srcs.length ? `<label class="f" for="plSrc">Источник</label><select id="plSrc">${srcs.map((x) => `<option value="${esc(x.id)}">${esc(x.title)} (${esc(typeName(x.type))})</option>`).join("")}</select>
      <div class="grid2"><div><label class="f" for="plN">Сколько дней</label><input type="number" id="plN" min="1" max="7" value="3"></div>
        <div><label class="f" for="plD">С какого дня</label><input type="date" id="plD" value="${iso}"></div></div>
      <label class="toggle"><span>Сразу сделать клипы</span><input type="checkbox" id="plClips" checked></label>
      <button class="btn primary" id="plGo">Составить план</button><div id="plProg"></div>`
      : `<p class="warn">Сначала добавьте источник в «Настройках».</p>`}</div>
    <div class="card"><h2>План</h2>${items.length ? items.map((it, i) => planItemHtml(it, i)).join("") + `<div style="height:10px"></div><button class="btn ghost sm" id="plClear">Очистить план</button>`
      : `<p class="muted">Пока пусто. Составьте план выше.</p>`}</div>
    ${lists.error ? `<div class="card"><p class="err">${esc(lists.error)}</p></div>`
      : !lists.configured ? `<div class="card stack"><h2>Рейтинги TMDB</h2>
        <p>С ключом TMDB автоплан берёт серии с самым высоким рейтингом, а здесь видно, что популярно сейчас.
          Ключ бесплатный: themoviedb.org → «Настройки» → «API» (пошагово — в инструкции, раздел «Моменты»).</p>
        <a class="btn" href="#/settings">Ввести ключ в «Настройках»</a></div>`
      : `${kinoSrcs.map((x) => `<div class="card" data-tsrc="${esc(x.id)}"><h2>${esc(x.title)}</h2><p class="muted small">Ищу в TMDB…</p></div>`).join("")}
        ${[["trending", "В тренде за неделю"], ["top_tv", "Лучшие сериалы"], ["top_movies", "Лучшие фильмы"]].map(([k, n]) => `<details class="card"><summary><b>${n}</b></summary>
          <ul class="list">${(lists[k] || []).map((t) => `<li><div class="item"><span class="t"><b>${esc(t.title)}</b>${t.year ? ` (${esc(t.year)})` : ""}
            <br><span class="small muted">${t.type === "tv" ? "сериал" : "фильм"} · ★ ${fmtRating(t.rating)}</span>
            ${t.source_id ? ` <span class="ok small">· есть у вас</span>` : ""}</span></div></li>`).join("")}</ul></details>`).join("")}`}`;
  bindPlan(items, () => renderKino(box, false));
  const cl = $("#plClear"); if (cl) cl.onclick = async () => { if (!confirm("Очистить весь план? Готовые клипы останутся.")) return; await api("/api/plan/clear", { method: "POST" }); renderKino(box, false); };
  const go2 = $("#plGo");
  if (go2) go2.onclick = async () => {
    go2.disabled = true;
    const params = { source_id: $("#plSrc").value, count: Math.max(1, Math.min(7, +$("#plN").value || 3)), start: $("#plD").value, make_clips: $("#plClips").checked };
    const job = await api("/api/jobs", { method: "POST", body: { type: "plan", params } }).catch(() => null);
    if (!job) { go2.disabled = false; return; }
    const fin = await pollJob(job.id, (j) => { const el = $("#plProg"); if (el) el.innerHTML = bar(j.progress) + `<p class="muted small">${esc(j.stage)}</p>`; });
    if (parseHash().name !== "discover") return;
    if (fin.status !== "done") { go2.disabled = false; $("#plProg").innerHTML = `<p class="err">${esc(fin.error || "Не получилось")}</p>`; return; }
    toast(`План готов: ${fin.result.items.length} дн.${params.make_clips ? ", клипы собираются" : ""}`); refreshBadge();
    if ((fin.warnings || []).length) toast(fin.warnings[0], true);
    renderKino(box, false);
  };
  if (lists.configured) for (const x of kinoSrcs) tmdbSource(x.id, refresh);
}

async function tmdbSource(id, refresh) {
  const card = $(`[data-tsrc="${CSS.escape(id)}"]`);
  if (!card) return;
  let d;
  try { d = await api(`/api/tmdb/source/${encodeURIComponent(id)}` + (refresh ? "?refresh=1" : "")); }
  catch (e) { card.innerHTML += `<p class="err small">${esc(e.message)}</p>`; return; }
  if (!card.isConnected) return;
  const head = `<h2>${esc(d.title)}</h2>`;
  if (!d.match) {
    card.innerHTML = head + `<p class="small">Какое это название в TMDB?</p>${(d.candidates || []).length ? `<ul class="list">${d.candidates.map((c, i) => `<li><button class="item" data-tl="${i}">
      <span class="t"><b>${esc(c.title)}</b>${c.year ? ` (${esc(c.year)})` : ""}<br><span class="muted small">${c.type === "tv" ? "сериал" : "фильм"} · ★ ${fmtRating(c.rating)}</span></span><span class="chev">›</span></button></li>`).join("")}</ul>`
      : `<p class="muted small">Ничего не нашлось — проверьте название источника.</p>`}`;
    $$("[data-tl]", card).forEach((b) => (b.onclick = async () => {
      const c = d.candidates[+b.dataset.tl];
      await api("/api/tmdb/link", { method: "POST", body: { source_id: id, tmdb_id: c.id, tmdb_type: c.type } }); tmdbSource(id, false);
    }));
    return;
  }
  const m = d.match;
  const eps = (d.episodes || []).filter((e) => e.file).slice(0, 6);
  card.innerHTML = head + `<p class="small">${esc(m.title)}${m.year ? ` (${esc(m.year)})` : ""} · ★ ${fmtRating(m.rating)} <a href="#" data-tun="1" class="muted">не то</a></p>
    ${m.type === "tv" ? (eps.length ? `<p class="muted small">Лучшие серии из вашей папки:</p>${eps.map((e, i) => `<div class="mom"><div class="row">
        <span class="tm">S${String(e.season).padStart(2, "0")}E${String(e.episode).padStart(2, "0")}</span><span class="grow"></span>
        <span class="small">★ ${fmtRating(e.rating)} <span class="muted">(${e.votes})</span></span></div>
        <span class="small wrap">«${esc(e.name)}»${e.used ? ' <span class="muted">· уже была</span>' : ""}</span>
        <button class="btn sm primary" data-tep="${i}">Найти моменты</button></div>`).join("")}`
      : `<p class="muted small">Не нашёл в папке файлов с номером серии в имени (S01E05, 1x05, «2 сезон 3 серия»).</p>`) : ""}`;
  $("[data-tun]", card).onclick = async (ev) => {
    ev.preventDefault(); await api("/api/tmdb/link", { method: "POST", body: { source_id: id, tmdb_id: 0 } }); tmdbSource(id, false);
  };
  $$("[data-tep]", card).forEach((b) => (b.onclick = () => {
    const e = eps[+b.dataset.tep];
    Object.assign(S.draft, { source_id: id, file: e.file }); saveDraft();
    go(`#/clip?source=${encodeURIComponent(id)}&file=${encodeURIComponent(e.file)}&auto=mom`);
  }));
}

// ------------------------------------------------------------------ «Русское»: русские и советские сериалы и фильмы (TMDB)
const RU_KINDS = [["popular", "Популярные сейчас"], ["top", "Лучшие по рейтингу"], ["new", "Новинки за год"]];
async function viewRu(_arg, q) {
  const seq = S.seq;
  await loadCfg();
  if (staleSince(seq)) return;
  S.ru = S.ru || { type: "tv", kind: "popular" };
  const st = S.ru;
  app.innerHTML = `<h1>Русское</h1>
  <div class="seg" id="ruType"><button data-t="tv" class="${st.type === "tv" ? "on" : ""}">Сериалы</button><button data-t="movie" class="${st.type === "movie" ? "on" : ""}">Фильмы</button></div>
  <div class="seg seg3" id="ruKind" style="margin-top:8px">${RU_KINDS.map(([k, n]) => `<button data-k="${k}" class="${st.kind === k ? "on" : ""}">${n}</button>`).join("")}</div>
  <p class="muted small" style="margin:10px 2px">Русские и советские ${st.type === "tv" ? "сериалы" : "фильмы"} по данным TMDB. «Добавить в источники» —
    и дальше как обычно: выберите папку, куда положили видео, и автоплан найдёт моменты.</p>
  <div id="ruBox"><p class="muted pad">Загружаю…</p></div>`;
  $$("#ruType button").forEach((b) => (b.onclick = () => { st.type = b.dataset.t; viewRu(); }));
  $$("#ruKind button").forEach((b) => (b.onclick = () => { st.kind = b.dataset.k; viewRu(); }));
  let page = 1, items = [];
  const box = $("#ruBox");
  const load = async (refresh) => {
    let d;
    try { d = await api(`/api/tmdb/ru?type=${st.type}&kind=${st.kind}&page=${page}` + (refresh ? "&refresh=1" : "")); }
    catch (e) { box.innerHTML = `<p class="err pad">${esc(e.message)}</p>`; return; }
    if (staleSince(seq) || !box.isConnected) return;
    if (!d.configured) {
      box.innerHTML = `<div class="card stack"><h2>Нужен ключ TMDB</h2>
        <p>Списки берутся из TMDB. Ключ бесплатный: themoviedb.org → «Настройки» → «API» (пошагово — в инструкции, раздел 4.2).</p>
        <a class="btn primary" href="#/settings">Ввести ключ в «Настройках»</a></div>`;
      return;
    }
    items = items.concat(d.items || []);
    box.innerHTML = `<div class="card">${items.length ? items.map((t, i) => `<div class="ruitem">
        <b class="wrap">${i + 1}. ${esc(t.title)}${t.year ? ` (${esc(t.year)})` : ""}</b>
        <span class="small">★ ${fmtRating(t.rating)} <span class="muted">· ${t.votes} голосов</span>${t.source_id ? ' · <span class="ok">есть у вас</span>' : ""}</span>
        ${t.overview ? `<span class="muted small wrap">${esc(t.overview)}</span>` : ""}
        <div class="row">${t.source_id ? `<a class="btn sm" href="#/source/${encodeURIComponent(t.source_id)}">Открыть источник</a>`
          : `<button class="btn sm primary" data-add="${i}">Добавить в источники</button>`}
          <a class="btn sm ghost" target="_blank" rel="noopener" href="https://www.themoviedb.org/${t.type}/${t.id}?language=ru-RU">TMDB ↗</a></div></div>`).join("")
        : `<p class="muted">Ничего не нашлось.</p>`}
      ${d.page < d.total_pages && page < 20 ? `<div style="height:10px"></div><button class="btn ghost" id="ruMore">Показать ещё</button>` : ""}</div>`;
    $$("[data-add]", box).forEach((b) => (b.onclick = async () => {
      const t = items[+b.dataset.add];
      b.disabled = true;
      const saved = await api("/api/sources", { method: "POST", body: { type: t.type === "tv" ? "series" : "movie", title: t.title,
        tmdb_id: t.id, tmdb_type: t.type, enabled: true } }).catch(() => null);
      if (!saved) { b.disabled = false; return; }
      await loadCfg(true);
      toast("Добавлено. Выберите папку с видео и сохраните");
      go(`#/source/${encodeURIComponent(saved.id)}`);
    }));
    const more = $("#ruMore", box);
    if (more) more.onclick = () => { more.disabled = true; page++; load(false); };
  };
  load(!!(q && q.get && q.get("refresh") === "1"));
}

function appAsrCard(st, se) {
  const models = st.asr_models || [];
  const langs = [["auto", "Определять автоматически"], ["ru", "Русский"], ["en", "English"], ["uk", "Українська"]];
  const usable = models.filter((m) => m.supported !== false);
  return `<div class="card"><h2>Распознавание речи</h2>
    <p class="muted small">Работает без интернета. Модель скачивается один раз (лучше по Wi-Fi).</p>
    <ul class="list">${models.map((m) => `<li><div class="item"><span class="t">${esc(m.name)}${m.recommended ? ' <span class="ok small">★ рекомендуем</span>' : ""}<br>
      <span class="muted small">${esc(m.note || "")}</span><br>
      <span class="small ${m.installed ? "ok" : "muted"}">${m.supported === false ? "не работает на этом телефоне" : m.installed ? "скачана" : "не скачана"}</span></span>
      ${m.supported === false ? "" : m.installed ? `<button class="btn sm ghost" style="width:auto" data-mdel="${esc(m.id)}">Удалить</button>`
        : `<button class="btn sm" style="width:auto" data-model="${esc(m.id)}">Скачать</button>`}</div></li>`).join("")}</ul>
    <div id="mProg"></div>
    <label class="f" for="aB">Распознавание</label><select id="aB">
      ${[["auto", "Включено"], ["off", "Выключено"]].map(([v, n]) => `<option value="${v}" ${(se.asr_backend === "off" ? "off" : "auto") === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="aM">Модель по умолчанию</label><select id="aM">
      ${usable.map((m) => `<option value="${esc(m.id)}" ${(se.asr_model || st.default_model) === m.id ? "selected" : ""}>${esc(m.name)}${m.installed ? "" : " (не скачана)"}</option>`).join("")}</select>
    <p class="muted small">Если выбранная модель не скачана, возьму любую скачанную: сначала Whisper, потом Vosk.</p>
    <label class="f" for="aL">Язык по умолчанию</label><select id="aL">
      ${langs.map(([v, n]) => `<option value="${v}" ${se.asr_language === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="vB">Качество видео</label><select id="vB">
      ${[[6, "Экономное (6 Мбит/с)"], [10, "Обычное (10 Мбит/с)"], [16, "Высокое (16 Мбит/с)"]].map(([v, n]) => `<option value="${v}" ${(se.bitrate_mbps || 10) === v ? "selected" : ""}>${n}</option>`).join("")}</select>
    <label class="f" for="mL">Длина подсказанных моментов, секунд</label><input type="number" id="mL" min="5" max="180" value="${se.moment_len}">
    <div style="height:12px"></div><button class="btn primary" id="bAsr">Сохранить</button></div>`;
}

// ------------------------------------------------------------------ «Источник»
async function viewSource(id) {
  const seq = S.seq;
  await loadCfg(true);
  if (staleSince(seq)) return;
  const isNew = id === "new" || !id;
  const s = isNew ? { type: "series", title: "", folder: "", enabled: true, profile: {} } : S.cfg.sources.find((x) => x.id === id);
  if (!s) { app.innerHTML = `<h1>Источник</h1><p class="err">Источник не найден.</p>`; return; }
  const folders = (await api("/api/folders")).folders;
  const p = Object.assign({ layout: "single", mirror: true, subtitles: true, sub_style: "classic", language: "auto", single_zoom: 1,
    split_screen_h: 0.45, split_gap_h: 0.1, split_cam_h: 0.45, screen_fit: "crop" }, s.full_profile || {});
  let type = s.type, layout = APP || isNew ? "single" : p.layout;
  const typeBtns = () => S.cfg.types.map((t) => `<button data-t="${t}" class="${t === type ? "on" : ""}">${esc(typeName(t))}</button>`).join("");
  app.innerHTML = `<h1>${isNew ? "Новый источник" : "Источник"}</h1>
  <div class="card">
    <label class="f" for="sT">Название</label><input id="sT" value="${esc(s.title)}" placeholder="Например: Во все тяжкие">
    <label class="f">Тип</label><div class="seg" id="tSeg">${typeBtns()}</div>
    <label class="f" for="sF">Папка с видео</label>
    ${APP ? `<p class="wrap"><b id="sFname">${esc(s.folder || "не выбрана")}</b></p>
      <button class="btn" id="bFolder">Выбрать папку на телефоне</button>
      <p class="muted small">Программа увидит только эту папку. Положите туда серии или фильм.</p>`
    : `<input id="sF" list="fl" value="${esc(s.folder)}" placeholder="Например: breaking_bad">
    <datalist id="fl">${folders.map((f) => `<option value="${esc(f)}">`).join("")}</datalist>
    <p class="muted small wrap">Это подпапка в общей папке ${esc((await loadStatus()).media_root)}. Если её нет — создам.</p>`}
    <div id="ytBox" ${APP ? "hidden" : ""}><label class="f" for="sY">YouTube: ID канала (необязательно)</label><input id="sY" value="${esc(s.youtube_channel_id || "")}" placeholder="UCxxxxxxxxxxxxxxxxxxxxxx">
      <p class="muted small">Нужен, чтобы на экране «Сегодня» видеть свежее видео. ID начинается с UC.</p>
      <label class="f" for="sTw">Twitch: логин (необязательно)</label><input id="sTw" value="${esc(s.twitch_login || "")}"></div>
    <label class="toggle"><span>Источник включён</span><input type="checkbox" id="sEn" ${s.enabled !== false ? "checked" : ""}></label>
  </div>
  <div class="card"><h2>Профиль нарезки</h2>
    <div ${APP ? "hidden" : ""}><label class="f">Раскладка</label>
    <div class="seg" id="lSeg"><button data-l="single">Один кадр</button><button data-l="split">Экран + вебкамера</button></div></div>
    <div id="singleOpts"><label class="f" for="pZ">Увеличение кадра: <span id="pZv">${Number(p.single_zoom).toFixed(2)}</span>×</label>
      <input type="range" id="pZ" min="1" max="2" step="0.05" value="${p.single_zoom}">
      <p class="muted small">1× — весь кадр по ширине, больше — крупнее, края обрезаются.</p></div>
    <div id="splitOpts">
      <label class="f" for="pS">Экран сверху: <span id="pSv"></span>%</label><input type="range" id="pS" min="20" max="70" step="1" value="${Math.round(p.split_screen_h * 100)}">
      <label class="f" for="pG">Зона субтитров: <span id="pGv"></span>%</label><input type="range" id="pG" min="0" max="25" step="1" value="${Math.round(p.split_gap_h * 100)}">
      <p class="muted small">Вебкамера снизу: <b id="pCv"></b>%</p>
      <label class="f" for="pFit">Экран</label><select id="pFit"><option value="crop" ${p.screen_fit === "crop" ? "selected" : ""}>Заполнить зону (края обрезаются)</option>
        <option value="fit" ${p.screen_fit === "fit" ? "selected" : ""}>Показать целиком</option></select>
      ${isNew ? `<p class="muted small">Рамку вебкамеры можно задать после сохранения.</p>` : `<a class="btn" style="margin-top:12px" href="#/cam/${encodeURIComponent(s.id)}">Рамка вебкамеры ${p.cam_rect ? "(задана)" : "(не задана)"}</a>`}
    </div>
    <label class="toggle"><span>Зеркало по умолчанию</span><input type="checkbox" id="pM" ${p.mirror ? "checked" : ""}></label>
    <label class="toggle"><span>Субтитры по умолчанию</span><input type="checkbox" id="pSu" ${p.subtitles ? "checked" : ""}></label>
    <label class="f" for="pSt">Профиль субтитров</label><select id="pSt">${Object.entries(S.cfg.sub_styles).map(([v, n]) => `<option value="${v}" ${p.sub_style === v ? "selected" : ""}>${esc(n)}</option>`).join("")}</select>
    <label class="f" for="pL">Язык речи</label><select id="pL">${[["auto", "Определять автоматически"], ["ru", "Русский"], ["en", "English"], ["uk", "Українська"]].map(([v, n]) => `<option value="${v}" ${p.language === v ? "selected" : ""}>${n}</option>`).join("")}</select>
  </div>
  <button class="btn primary" id="bSave">Сохранить источник</button>
  ${isNew ? "" : `<div style="height:10px"></div><button class="btn danger" id="bDel">Удалить источник</button>`}`;

  const syncType = () => {
    $$("#tSeg button").forEach((b) => b.classList.toggle("on", b.dataset.t === type));
    $("#ytBox").hidden = APP || !(type === "stream" || type === "blogger");
  };
  const syncLayout = () => {
    $$("#lSeg button").forEach((b) => b.classList.toggle("on", b.dataset.l === layout));
    $("#singleOpts").hidden = layout !== "single"; $("#splitOpts").hidden = APP || layout !== "split";
  };
  const syncSplit = () => {
    let sh = +$("#pS").value, g = +$("#pG").value;
    if (sh + g > 90) { g = 90 - sh; $("#pG").value = g; }
    $("#pSv").textContent = sh; $("#pGv").textContent = g; $("#pCv").textContent = 100 - sh - g;
  };
  $$("#tSeg button").forEach((b) => (b.onclick = () => {
    type = b.dataset.t;
    if (isNew) layout = type === "stream" || type === "blogger" ? "split" : "single";
    syncType(); syncLayout();
  }));
  $$("#lSeg button").forEach((b) => (b.onclick = () => { layout = b.dataset.l; syncLayout(); }));
  $("#pS").oninput = syncSplit; $("#pG").oninput = syncSplit;
  $("#pZ").oninput = (e) => ($("#pZv").textContent = Number(e.target.value).toFixed(2));
  syncType(); syncLayout(); syncSplit();
  const folder = { uri: s.folder_uri || "", name: s.folder || "" };
  if (APP) {
    window.onFolderPicked = (o) => { folder.uri = o.uri; folder.name = o.name; $("#sFname").textContent = o.name; };
    $("#bFolder").onclick = () => window.AndroidApp.pickFolder();
  }

  $("#bSave").onclick = async () => {
    const sh = +$("#pS").value / 100, g = +$("#pG").value / 100;
    const body = {
      id: isNew ? undefined : s.id, title: $("#sT").value.trim(), type, folder: APP ? folder.name : $("#sF").value.trim(),
      youtube_channel_id: $("#sY").value.trim(), twitch_login: $("#sTw") ? $("#sTw").value.trim() : "", enabled: $("#sEn").checked,
      profile: Object.assign({}, s.profile || {}, { layout, single_zoom: +$("#pZ").value, split_screen_h: sh, split_gap_h: g,
        split_cam_h: Math.round((1 - sh - g) * 100) / 100, screen_fit: $("#pFit").value, mirror: $("#pM").checked,
        subtitles: $("#pSu").checked, sub_style: $("#pSt").value, language: $("#pL").value }),
    };
    if (!body.title) { toast("Введите название", true); return; }
    if (APP) {
      if (!folder.uri) { toast("Выберите папку с видео", true); return; }
      body.folder_uri = folder.uri;
    }
    if (!body.folder) body.folder = undefined;
    const saved = await api("/api/sources", { method: "POST", body });
    await loadCfg(true); toast("Источник сохранён");
    if (isNew && layout === "split") go(`#/cam/${encodeURIComponent(saved.id)}`); else go("#/settings");
  };
  const d = $("#bDel"); if (d) d.onclick = async () => {
    if (!confirm(`Удалить источник «${s.title}»? Видео в папке останутся.`)) return;
    await api("/api/sources/" + encodeURIComponent(s.id), { method: "DELETE" }); await loadCfg(true); toast("Источник удалён"); go("#/settings");
  };
}

// ------------------------------------------------------------------ старт
if (!APP && "serviceWorker" in navigator && window.isSecureContext) navigator.serviceWorker.register("/sw.js").catch(() => {});
if (APP) {
  for (const k of ["discover", "ru"]) $(`#nav a[data-tab="${k}"]`).hidden = false;
  $("#nav").classList.add("n7");
}
route(); refreshBadge();
