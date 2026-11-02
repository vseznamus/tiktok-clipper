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
    // картинка для баннера (PNG 4×2); запись эфира с вебкамерой справа снизу — «выбрана на телефоне»
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAYAAAB/qH1jAAAAFklEQVR42mP8z8Dwn4GBgYGJAQoYGQAkpAMBGPyO5AAAAABJRU5ErkJggg==", "base64");
    const pick = path.join(demo, "media/matrix");
    fs.writeFileSync(path.join(pick, "banner.png"), png);
    fs.copyFileSync(path.join(demo, "media/streamer/stream_2026-10-28.mp4"), path.join(pick, "stream_2026-10-28.mp4"));
    const chatPath = path.join(pick, "stream_2026-10-28.json");
    if (fs.existsSync(chatPath)) fs.unlinkSync(chatPath);
    await ctx.addInitScript(() => {
      window.__calls = [];
      const vids = ["_picked/stream_2026-10-28.mp4", "_picked/matrix.mp4"];
      window.AndroidApp = {
        pickVideo: () => { window.__calls.push("pickVideo"); const n = window.__calls.filter((c) => c === "pickVideo").length;
          setTimeout(() => window.onVideoPicked(vids[Math.min(n, vids.length) - 1]), 50); },
        pickChat: (id) => { window.__calls.push("pickChat:" + id); setTimeout(() => window.onChatPicked("stream_2026-10-28.json"), 50); },
        dropChat: (id) => window.__calls.push("dropChat:" + id),
        pickImage: () => { window.__calls.push("pickImage"); setTimeout(() => window.onImagePicked("_img/banner.png"), 50); },
        pickFolder: () => window.__calls.push("pickFolder"),
        copyText: (t) => window.__calls.push("copy:" + t),
        shareClip: (id) => window.__calls.push("share:" + id),
        openClip: (id) => window.__calls.push("open:" + id),
        version: () => "test",
      };
    });
    const page = await ctx.newPage();
    page.on("pageerror", (e) => report.errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") report.errors.push("console: " + m.text()); });
    page.on("dialog", (d) => d.accept());
    const noJunk = async (where) => {
      const t = (await page.innerText("body")).toLowerCase();
      const hit = ["фильм", "сериал", "tmdb", "imdb", "youtube", "подборка", "расписан", "папк", "стримеры", "ночная нарезка", "ключи twitch"].find((w) => t.includes(w));
      if (hit) fail(`${where}: на экране есть «${hit}»`);
    };
    const toastHas = (txt, timeout = 15000) => page.waitForFunction((x) => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes(x); }, txt, { timeout });
    const draft = () => page.evaluate(() => JSON.parse(localStorage.getItem("draft") || "{}"));

    // первый запуск: две вкладки и одна кнопка «Загрузить видео»
    await page.goto(BASE + "/auth?k=testtoken");
    await page.waitForSelector("#pickV");
    const tabs = await page.$$eval("#nav a", (a) => a.filter((x) => !x.hidden && x.offsetParent).map((x) => x.innerText.trim()));
    if (tabs.join(",") !== "Клип,Настройки") fail("вкладки: " + tabs);
    if ((await page.$$("select, .item[data-path]")).length) fail("на пустом «Клипе» есть списки");
    if (!(await page.innerText("#pickV")).includes("Загрузить видео")) fail("нет кнопки «Загрузить видео»");
    await noJunk("первый запуск");
    await check(page, "a01_klip_pusto");
    step("в приложении две вкладки: Клип, Настройки; на «Клипе» — одна кнопка «📂 Загрузить видео», без списков и папок");

    // подпись к клипам — в «Настройках»; там нет ночной нарезки и ключей
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#cpName");
    await page.fill("#cpName", "Стример Вася");
    await page.fill("#cpLogin", "https://www.twitch.tv/Vasya_Live");
    await page.click("#bCap");
    await toastHas("Подпись сохранена");
    await page.waitForFunction(() => document.querySelector("#cpLogin") && document.querySelector("#cpLogin").value === "vasya_live");
    await noJunk("«Настройки»");
    await check(page, "a02_nastroyki", true);
    step("«Настройки» → «Подпись к клипам»: имя и Twitch-логин (из ссылки — «vasya_live»); ночной нарезки и ключей нет");

    // загрузить видео → рамки вебкамеры и экрана находятся сами
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#pickV");
    await page.click("#pickV");
    await page.waitForSelector("#frameMsg", { timeout: 60000 });
    const fm = await page.innerText("#frameMsg");
    if (!fm.includes("найдена автоматически")) fail("вебкамера не найдена: " + fm);
    const d1 = await draft();
    const cam = d1.rects && d1.rects.cam;
    if (d1.layout !== "split" || !cam || cam[0] < 0.6 || cam[1] < 0.55 || cam[0] + cam[2] < 0.95) fail("рамка вебкамеры: " + JSON.stringify(d1.rects));
    const scr = d1.rects.screen;
    const ov = Math.max(0, Math.min(scr[0] + scr[2], cam[0] + cam[2]) - Math.max(scr[0], cam[0])) * Math.max(0, Math.min(scr[1] + scr[3], cam[1] + cam[3]) - Math.max(scr[1], cam[1]));
    if (ov / (cam[2] * cam[3]) > 0.15) fail("экран захватывает вебкамеру: " + JSON.stringify(d1.rects));
    await page.waitForFunction(() => { const c = document.querySelector("#fr916"); if (!c) return false;
      const p = c.getContext("2d").getImageData(135, 400, 1, 1).data; return p[0] + p[1] + p[2] > 0 && !(p[0] === 34 && p[1] === 34 && p[2] === 34); });
    if (!(await page.innerText("#fileBox")).includes("stream_2026-10-28.mp4")) fail("имя видео не показано");
    await noJunk("«Клип» с видео");
    await check(page, "a03_video_ramki", true);
    step("«Загрузить видео» → вебкамера справа снизу найдена сама, экран подрезан без неё, схема 9:16 нарисована");

    // файл чата → моменты по реакции зрителей
    const comments = [];
    for (let t = 0; t < 40; t += 3) comments.push({ content_offset_seconds: t, commenter: { display_name: "z" }, message: { body: "привет" } });
    for (let i = 0; i < 25; i++) comments.push({ content_offset_seconds: 25 + i * 0.1, commenter: { display_name: "z" + i }, message: { body: "KEKW ахаха" } });
    fs.writeFileSync(chatPath, JSON.stringify({ video: { title: "Эфир" }, comments }));
    await page.click("#chatAdd");
    await toastHas("Чат добавлен");
    await page.waitForFunction(() => document.querySelector("#fileBox").innerText.includes("Чат: stream_2026-10-28.json"));
    await page.click("#bMom");
    await page.waitForSelector(".cand", { timeout: 60000 });
    const mr = await page.innerText("#momRes");
    if (!mr.includes("По активности чата") || !mr.includes("KEKW")) fail("моменты по чату: " + mr.slice(0, 300));
    await page.click(".cand >> nth=0");
    if ((await page.inputValue("#tStart")) !== "0:10.0") fail("момент по чату не с той секунды: " + (await page.inputValue("#tStart")));
    step("«Добавить» файл чата → «Автоподбор моментов»: всплеск «KEKW», начало 0:10");

    // ручная настройка рамок
    await page.click("#bFrame");
    await page.waitForSelector("h1:has-text('Рамки: экран и вебкамера')");
    await page.waitForFunction(() => document.querySelector("#fimg").naturalWidth > 0);
    const box = await page.$eval("#rCam", (e) => { const r = e.getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; });
    await page.mouse.move(box.x, box.y); await page.mouse.down(); await page.mouse.move(box.x - 12, box.y - 6, { steps: 4 }); await page.mouse.up();
    await check(page, "a04_ramki_redaktor", true);
    await page.click("#bAuto");
    await toastHas("Нашёл");
    await page.click("#bSave");
    await page.waitForSelector("#frameMsg", { timeout: 30000 });
    if (!(await page.innerText("#frameMsg")).includes("заданы вручную")) fail("ручные рамки не сохранились");
    step("«Настроить рамки» → редактор (тянуть пальцем, «Найти автоматически») → «Готово» → «Рамки заданы вручную»");

    // баннер, шаблон, клип: «экран + вебкамера» с рамками этого видео, подпись из «Настроек»
    await page.click("#bnPick");
    await page.waitForSelector("#bnImg");
    await page.click('#bnPos button[data-v="top"]');
    await page.click("#bTpl");
    await toastHas("Шаблон сохранён");
    await check(page, "a05_klip_nastroen", true);
    await page.click("#bMake");
    await page.waitForSelector("#jobsBox .progress", { timeout: 15000 });
    const jobs = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const cj = jobs.find((j) => j.type === "clip");
    if (!cj || cj.params.layout !== "split" || !Array.isArray(cj.params.cam_rect) || !Array.isArray(cj.params.screen_rect)
      || cj.params.banner_pos !== "top" || cj.params.source_id) fail("параметры клипа: " + JSON.stringify(cj && cj.params));
    await page.waitForSelector("#bShare", { timeout: 120000 });
    const cap = await page.innerText("#app");
    if (!cap.includes("Стример Вася · эфир 28.10") || !cap.includes("#vasya_live") || !cap.includes("#твич")) fail("подпись: " + cap.slice(0, 400));
    await page.click("#bShare");
    const calls = await page.evaluate(() => window.__calls);
    if (!calls.some((c) => c.startsWith("share:"))) fail("«Поделиться» не вызвано: " + calls);
    await check(page, "a06_gotovyi_klip", true);
    step("«Сделать клип» → «экран + вебкамера» по рамкам видео, баннер сверху → подпись «Стример Вася · эфир 28.10 #vasya_live #твич» → «Поделиться»");

    // «Быстрый клип» — с теми же рамками
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#bQuick");
    await page.click("#bQuick");
    await page.waitForSelector("#bShare", { timeout: 120000 });
    const jobs2 = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const auto = jobs2.find((j) => j.type === "auto" && j.status === "done");
    const qc = auto && jobs2.find((j) => j.id === auto.result.clip_jobs[0]);
    if (!qc || qc.params.layout !== "split" || !Array.isArray(qc.params.cam_rect) || qc.params.banner_pos !== "top") fail("быстрый клип: " + JSON.stringify(qc && qc.params));
    step("«⚡ Быстрый клип»: лучший момент → клип по шаблону с баннером и рамками вебкамеры этого видео");

    // другое видео без вебкамеры → один кадр; готовые клипы внизу «Клипа»
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#chgFile");
    await page.click("#chgFile");
    await page.waitForFunction(() => document.querySelector("#fileBox").innerText.includes("matrix.mp4"));
    await page.waitForSelector("#frameMsg", { timeout: 60000 });
    const fm2 = await page.innerText("#frameMsg");
    if (!fm2.includes("Вебкамеры не видно") || (await draft()).layout !== "single") fail("видео без вебкамеры: " + fm2);
    await page.waitForFunction(() => document.querySelector("#readyBox") && document.querySelector("#readyBox").innerText.includes("Готовые клипы"));
    await check(page, "a07_bez_kamery", true);
    step("«Другое видео» без вебкамеры → «Вебкамеры не видно — клип будет одним кадром»; ниже — «Готовые клипы»");

    // старые адреса (стримеры, папки) ведут в «Клип»
    await page.goto(BASE + "/#/streamers");
    await page.waitForSelector("#fileBox");
    if (!page.url().endsWith("#/clip")) fail("старый адрес не перенаправлен: " + page.url());
    step("старые экраны «Стримеры»/папки недоступны — открывается «Клип»");

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
