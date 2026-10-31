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
    // картинка для баннера (PNG 4×2) — кладём в папку «выбранных» файлов
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAYAAAB/qH1jAAAAFklEQVR42mP8z8Dwn4GBgYGJAQoYGQAkpAMBGPyO5AAAAABJRU5ErkJggg==", "base64");
    fs.writeFileSync(path.join(demo, "media/matrix/banner.png"), png);
    await ctx.addInitScript(() => {
      window.__calls = [];
      window.AndroidApp = {
        pickFolder: () => window.__calls.push("pickFolder"),
        pickVideo: () => { window.__calls.push("pickVideo"); setTimeout(() => window.onVideoPicked("_picked/old_rip.mp4"), 50); },
        pickImage: () => { window.__calls.push("pickImage"); setTimeout(() => window.onImagePicked("_img/banner.png"), 50); },
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
      const hit = ["стрим", "вебкам", "youtube", "twitch", "блогер", "расписан", "источник"].find((w) => t.includes(w));
      if (hit) fail(`${where}: на экране есть «${hit}»`);
    };

    // первый запуск: три вкладки, открыта «Подборка», без ключа — подсказка
    await page.goto(BASE + "/auth?k=testtoken");
    await page.waitForSelector("text=Нужен ключ TMDB");
    const tabs = await page.$$eval("#nav a", (a) => a.filter((x) => !x.hidden && x.offsetParent).map((x) => x.innerText.trim()));
    if (tabs.join(",") !== "Подборка,Клип,Настройки") fail("вкладки: " + tabs);
    await noJunk("«Подборка» без ключа");
    await check(page, "a01_podborka_bez_klyucha");
    step("в приложении три вкладки: Подборка, Клип, Настройки; без ключа — подсказка");

    // настройки: только нужное
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#bKeys");
    await noJunk("«Настройки»");
    await check(page, "a02_nastroyki", true);
    await page.click('[data-model="whisper-small"]');
    await page.waitForSelector('[data-mdel="whisper-small"]', { timeout: 30000 });
    step("«Настройки»: без источников и расписания; Whisper small скачивается");
    await page.fill("#kTm", "tmdbFakeKey42");
    await page.click("#bKeys");
    await page.waitForFunction(() => (document.querySelector("#kTm") || {}).placeholder?.includes("…ey42"));
    step("ключ TMDB сохранён, виден только конец");

    // «Подборка»: мир → Россия, популярное → лучшее по IMDb
    await page.click('#nav a[data-tab="pick"]');
    await page.waitForSelector(".ruitem");
    await page.waitForFunction(() => document.querySelector("#pkBox").innerText.includes("IMDb ★"), null, { timeout: 30000 });
    const w = await page.innerText("#pkBox");
    if (!w.includes("Во все тяжкие") || !w.includes("IMDb ★ 9,5")) fail("мир: " + w.slice(0, 300));
    await noJunk("«Подборка»");
    await check(page, "a03_podborka_mir", true);
    step("«Подборка» → Мир → Сериалы → Популярное: рейтинги IMDb загрузились в фоне и показаны");
    await page.click('#pkRegion button[data-v="ru"]');
    await page.waitForFunction(() => document.querySelector("#pkBox") && document.querySelector("#pkBox").innerText.includes("Ликвидация"));
    await page.click('#pkKind button[data-v="best"]');
    await page.waitForSelector(".ruitem");
    const ru = await page.innerText("#pkBox");
    if (ru.indexOf("Ликвидация") > ru.indexOf("Кухня") || !ru.includes("IMDb ★ 8,9")) fail("Россия, лучшее по IMDb: " + ru.slice(0, 300));
    await check(page, "a04_podborka_rossiya", true);
    step("«Подборка» → Россия → Лучшее по IMDb: «Ликвидация» (IMDb 8,9) выше «Кухни» (7,8)");
    await page.click('#pkType button[data-v="movie"]');
    await page.waitForFunction(() => document.querySelector("#pkBox") && document.querySelector("#pkBox").innerText.includes("Брат"));
    step("«Подборка» → Россия → Фильмы");

    // «Клип»: видео с телефона → автоподбор → баннер → сделать клип
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#pickV");
    await noJunk("«Клип»");
    await check(page, "a05_klip_pusto");
    await page.click("#pickV");
    await page.waitForSelector("#pv");
    if (!(await page.innerText("#fileBox")).includes("old_rip.mp4")) fail("выбранное видео не показано");
    await page.fill("#tEnd", "0:08"); await page.press("#tEnd", "Enter"); await page.click("h1");
    await page.click("#bMom");
    await page.waitForSelector(".cand", { timeout: 60000 });
    await page.click(".cand >> nth=0");
    step(`«Выбрать видео на телефоне» → «Автоподбор моментов»: начало ${await page.inputValue("#tStart")}`);
    await page.click("#bnPick");
    await page.waitForSelector("#bnImg");
    await page.click('#bnPos button[data-v="bottom"]');
    await page.fill("#bnSize", "50"); await page.dispatchEvent("#bnSize", "input");
    await page.waitForFunction(() => { const i = document.querySelector("#bnImg"); return i && i.style.width === "50%"; });
    const top = await page.$eval("#bnImg", (i) => parseFloat(i.style.top));
    if (Math.abs(top - (84 - 50 * 1080 * 0.5 / 1920)) > 0.5) fail("положение баннера в предпросмотре: " + top);
    await noJunk("«Клип» с видео");
    await check(page, "a06_klip_banner", true);
    step("баннер: картинка из файлов, «Снизу», ширина 50% — предпросмотр совпадает с расчётом сборки");
    await page.click("#bMake");
    await page.waitForSelector("#jobsBox .progress", { timeout: 15000 });
    const jobs = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const cj = jobs.find((j) => j.type === "clip");
    if (!cj || cj.params.banner !== "_img/banner.png" || cj.params.banner_pos !== "bottom" || cj.params.banner_size !== 0.5) fail("параметры баннера: " + JSON.stringify(cj && cj.params));
    await check(page, "a07_klip_sobiraetsya");
    await page.waitForSelector("#bShare", { timeout: 120000 });
    step("«Сделать клип»: прогресс прямо на вкладке «Клип», баннер передан в сборку, по готовности открылся клип");
    await page.click("#bShare");
    await page.click("#bOpen");
    const calls = await page.evaluate(() => window.__calls);
    if (!calls.some((c) => c.startsWith("share:")) || !calls.some((c) => c.startsWith("open:"))) fail("мост не вызван: " + calls);
    await check(page, "a08_gotovyi_klip", true);
    step("«Поделиться» и «Открыть в галерее» вызывают системные окна Android");
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#readyBox .item");
    await check(page, "a09_klip_gotovye", true);
    step("«Клип» → «Готовые клипы»: собранный клип в списке");

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
