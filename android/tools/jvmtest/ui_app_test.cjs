// UI-тест режима приложения: Java-сервер приложения на JVM + интерфейс в мобильном окне 390x844,
// мост window.AndroidApp подменён имитацией (системные окна выбора папки/видео и «Поделиться»).
//   node ui_app_test.cjs <classes-dir> <classpath> <demo-dir> <shots-dir>
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const path = require("path");
const fs = require("fs");

const [classes, cp, demo, shots] = process.argv.slice(2);
fs.mkdirSync(shots, { recursive: true });
const PORT = 18666, BASE = `http://127.0.0.1:${PORT}`;
const report = { steps: [], errors: [], screens: {} };
const step = (m) => { report.steps.push(m); console.log("•", m); };
const fail = (m) => { throw new Error(m); };

async function check(page, name, full = false) {
  await page.waitForTimeout(350);
  const res = await page.evaluate(() => {
    const vw = innerWidth, bad = [];
    for (const el of document.querySelectorAll("button, a.btn, .item, label.toggle, input:not([type=checkbox]):not([type=file]), select, textarea, #nav a")) {
      const r = el.getBoundingClientRect();
      if (!r.width || el.closest("[hidden]")) continue;
      const fsz = parseFloat(getComputedStyle(el).fontSize);
      if (r.height < (el.type === "range" ? 40 : 44) - 0.5 || r.width < 44) bad.push(`${(el.innerText || el.id).slice(0, 30)} ${Math.round(r.width)}x${Math.round(r.height)}`);
      if (el.matches("button, a.btn") && fsz < 15) bad.push(`${(el.innerText || el.id).slice(0, 30)}: шрифт ${fsz}`);
      if (r.left < -1 || r.right > vw + 1) bad.push(`${(el.innerText || el.id).slice(0, 30)} за краем`);
    }
    return { sw: document.documentElement.scrollWidth, vw, bad, title: (document.querySelector("h1") || {}).innerText };
  });
  await page.screenshot({ path: path.join(shots, name + ".png"), fullPage: full });
  report.screens[name] = res;
  if (res.sw > res.vw) fail(`${name}: горизонтальная прокрутка`);
  if (res.bad.length) fail(`${name}: ${res.bad.join("; ")}`);
  step(`экран «${res.title}» → ${name}.png: ок`);
}

(async () => {
  const srv = spawn("java", ["-Dstdout.encoding=UTF-8", "-cp", `${classes}:${cp}`, "DesktopServer", path.join(demo, "appdata"),
    path.join(demo, "media/matrix"), String(PORT)], { cwd: path.resolve(__dirname, "../.."), stdio: ["ignore", "pipe", "pipe"] });
  srv.stderr.on("data", (d) => { if (!/JAVA_TOOL_OPTIONS/.test(d)) process.stderr.write("[java] " + d); });
  let browser;
  try {
    for (let i = 0; i < 100; i++) { try { await fetch(BASE + "/"); break; } catch (e) { await new Promise((r) => setTimeout(r, 200)); } }
    // без ключа сессии ничего не отдаётся
    if ((await fetch(BASE + "/api/config")).status !== 401) fail("API доступно без ключа");
    step("без ключа сессии API отвечает 401");
    browser = await chromium.launch();
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
    // картинка для баннера (PNG 4×2) и чат записи рядом с эфиром (формат TwitchDownloader): всплеск «KEKW» на 25-й секунде
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAYAAAB/qH1jAAAAFklEQVR42mP8z8Dwn4GBgYGJAQoYGQAkpAMBGPyO5AAAAABJRU5ErkJggg==", "base64");
    fs.writeFileSync(path.join(demo, "media/matrix/banner.png"), png);
    const comments = [];
    for (let t = 0; t < 40; t += 3) comments.push({ content_offset_seconds: t, commenter: { display_name: "z" }, message: { body: "привет" } });
    for (let i = 0; i < 25; i++) comments.push({ content_offset_seconds: 25 + i * 0.1, commenter: { display_name: "z" + i }, message: { body: "KEKW ахаха" } });
    fs.writeFileSync(path.join(demo, "media/streamer/stream_2026-10-28.json"), JSON.stringify({ video: { title: "Эфир" }, comments }));
    await ctx.addInitScript((d) => {
      window.__calls = [];
      window.AndroidApp = {
        pickFolder: () => { window.__calls.push("pickFolder"); setTimeout(() => window.onFolderPicked({ uri: d, name: "streamer" }), 50); },
        pickVideo: () => { window.__calls.push("pickVideo"); setTimeout(() => window.onVideoPicked("_picked/old_rip.mp4"), 50); },
        pickImage: () => { window.__calls.push("pickImage"); setTimeout(() => window.onImagePicked("_img/banner.png"), 50); },
        copyText: (t) => window.__calls.push("copy:" + t),
        shareClip: (id) => window.__calls.push("share:" + id),
        openClip: (id) => window.__calls.push("open:" + id),
        version: () => "test",
      };
    }, path.join(demo, "media/streamer"));
    const page = await ctx.newPage();
    page.on("pageerror", (e) => report.errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") report.errors.push("console: " + m.text()); });
    page.on("dialog", (d) => d.accept());
    const noJunk = async (where) => {
      const t = (await page.innerText("body")).toLowerCase();
      const hit = ["фильм", "сериал", "tmdb", "imdb", "youtube", "подборка", "расписан"].find((w) => t.includes(w));
      if (hit) fail(`${where}: на экране есть «${hit}»`);
    };
    const toastHas = (txt, timeout = 15000) => page.waitForFunction((x) => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes(x); }, txt, { timeout });

    // первый запуск: три вкладки, открыт «Клип»
    await page.goto(BASE + "/auth?k=testtoken");
    await page.waitForSelector("#srcSel");
    const tabs = await page.$$eval("#nav a", (a) => a.filter((x) => !x.hidden && x.offsetParent).map((x) => x.innerText.trim()));
    if (tabs.join(",") !== "Стримеры,Клип,Настройки") fail("вкладки: " + tabs);
    await noJunk("первый запуск");
    await check(page, "a01_klip_pusto");
    step("в приложении три вкладки: Стримеры, Клип, Настройки; нигде нет фильмов, сериалов, TMDB");

    // стример: имя, Twitch-логин, папка записей, рамка вебкамеры
    await page.click('#nav a[data-tab="streamers"]');
    await page.waitForSelector("text=Добавить стримера");
    await page.click("text=Добавить стримера");
    await page.waitForSelector("#bFolder");
    await page.fill("#sT", "Стример Вася");
    await page.fill("#sTw", "vasya_live");
    await page.click("#bFolder");
    await page.waitForFunction(() => document.querySelector("#sFname").textContent === "streamer");
    await noJunk("новый стример");
    await check(page, "a02_novyi_strimer", true);
    await page.click("#bSave");
    await page.waitForSelector("h1:has-text('Рамка вебкамеры')");
    await page.waitForFunction(() => document.querySelector("#fimg").naturalWidth > 0);
    await check(page, "a03_ramka", true);
    await page.click("text=Сохранить рамку");
    await page.waitForSelector("text=twitch.tv/vasya_live");
    if (!(await page.innerText("#app")).includes("рамка задана")) fail("рамка вебкамеры не сохранилась");
    await check(page, "a04_strimery", true);
    step("«Стримеры»: имя, Twitch-логин, папка записей → «Рамка вебкамеры» → карточка «рамка задана», ссылка на Twitch");

    // клип из записи: моменты по чату, «экран + вебкамера», баннер, шаблон
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#srcSel");
    await page.selectOption("#srcSel", { label: "Стример Вася" });
    await page.waitForSelector(".item[data-path]");
    if (!(await page.innerText("#fileBox")).includes("есть чат")) fail("запись с чатом не отмечена");
    await page.click(".item[data-path] >> nth=0");
    await page.waitForSelector("#bMom");
    if ((await page.inputValue("#oLayout")) !== "split") fail("раскладка стримера должна быть «экран + вебкамера»");
    await page.click("#bMom");
    await page.waitForSelector(".cand", { timeout: 60000 });
    const mr = await page.innerText("#momRes");
    if (!mr.includes("По активности чата") || !mr.includes("KEKW")) fail("моменты по чату: " + mr.slice(0, 300));
    await page.click(".cand >> nth=0");
    if ((await page.inputValue("#tStart")) !== "0:10.0") fail("момент по чату не с той секунды: " + (await page.inputValue("#tStart")));
    step("«Клип» → Стример Вася → запись «есть чат» → «Автоподбор»: всплеск «KEKW», начало 0:10; раскладка «экран + вебкамера»");
    await page.click("#bnPick");
    await page.waitForSelector("#bnImg");
    await page.click('#bnPos button[data-v="top"]');
    await noJunk("«Клип» с записью");
    await check(page, "a05_klip_zapis", true);
    await page.click("#bTpl");
    await toastHas("Шаблон сохранён");
    await page.click("#bMake");
    await page.waitForSelector("#jobsBox .progress", { timeout: 15000 });
    const jobs = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const cj = jobs.find((j) => j.type === "clip");
    if (!cj || cj.params.layout !== "split" || cj.params.banner_pos !== "top" || cj.params.source_id !== "strimer_vasya") fail("параметры клипа: " + JSON.stringify(cj && cj.params));
    await page.waitForSelector("#bShare", { timeout: 120000 });
    const cap = await page.innerText("#app");
    if (!cap.includes("Стример Вася · эфир 28.10") || !cap.includes("#vasya_live") || !cap.includes("#твич")) fail("подпись: " + cap.slice(0, 400));
    await page.click("#bShare");
    const calls = await page.evaluate(() => window.__calls);
    if (!calls.some((c) => c.startsWith("share:"))) fail("«Поделиться» не вызвано: " + calls);
    await check(page, "a06_gotovyi_klip", true);
    step("«Сделать клип» → готовый клип с подписью «Стример Вася · эфир 28.10 #vasya_live #твич …» → «Поделиться»");

    // «Быстрый клип» с рамкой стримера
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#bQuick");
    await page.click("#bQuick");
    await page.waitForSelector("#bShare", { timeout: 120000 });
    const jobs2 = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const auto = jobs2.find((j) => j.type === "auto" && j.status === "done");
    const qc = auto && jobs2.find((j) => j.id === auto.result.clip_jobs[0]);
    if (!qc || qc.params.source_id !== "strimer_vasya" || qc.params.banner_pos !== "top") fail("быстрый клип: " + JSON.stringify(qc && qc.params));
    step("«⚡ Быстрый клип»: лучший момент → клип по шаблону с баннером и вебкамерой стримера");

    // ночная нарезка по папкам стримеров
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#ngNow");
    await noJunk("«Настройки»");
    await page.check("#ngOn");
    await page.fill("#ngN", "2");
    await page.click("#ngSave");
    await toastHas("включена");
    await check(page, "a07_nastroyki", true);
    await page.click("#ngNow");
    await page.waitForSelector("#readyBox");
    await page.waitForFunction(() => document.querySelector("#readyBox") && document.querySelector("#readyBox").innerText.includes("нарезан ночью")
      && !document.querySelector("#jobsBox .progress"), null, { timeout: 180000 });
    await check(page, "a08_gotovye_klipy", true);
    step("ночная нарезка: самая свежая запись стримера → клипы с пометкой 🌙 в «Готовых клипах»");

    // видео с телефона
    await page.selectOption("#srcSel", "");
    await page.waitForSelector("#pickV");
    await page.click("#pickV");
    await page.waitForSelector("#pv");
    if (!(await page.innerText("#fileBox")).includes("old_rip.mp4")) fail("выбранное видео не показано");
    step("«Видео с телефона» → «Выбрать видео на телефоне» → видео открыто");

    if (report.errors.length) fail("ошибки в консоли: " + report.errors.join(" | "));
    report.ok = true;
  } catch (e) {
    report.ok = false; report.failure = e.message; console.error("ПРОВАЛ:", e.message);
  } finally {
    if (browser) await browser.close();
    srv.kill();
    fs.writeFileSync(path.join(shots, "ui_app_report.json"), JSON.stringify(report, null, 2));
    process.exit(report.ok ? 0 : 1);
  }
})();
