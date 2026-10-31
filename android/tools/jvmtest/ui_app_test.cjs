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
    await ctx.addInitScript(({ bbDir }) => {
      window.__calls = [];
      window.AndroidApp = {
        pickFolder: () => { window.__calls.push("pickFolder"); setTimeout(() => window.onFolderPicked(window.__folder || { uri: bbDir, name: "breaking_bad" }), 50); },
        pickVideo: () => { window.__calls.push("pickVideo"); setTimeout(() => window.onVideoPicked("_picked/old_rip.mp4"), 50); },
        shareClip: (id) => window.__calls.push("share:" + id),
        openClip: (id) => window.__calls.push("open:" + id),
        version: () => "test",
      };
    }, { bbDir: path.join(demo, "media/breaking_bad") });
    const page = await ctx.newPage();
    page.on("pageerror", (e) => report.errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") report.errors.push("console: " + m.text()); });
    page.on("dialog", (d) => d.accept());
    // в приложении остались только сериалы и фильмы — никаких стримов, вебкамер, YouTube и Twitch
    const noStreams = async (where) => {
      const t = (await page.innerText("body")).toLowerCase();
      const hit = ["стрим", "вебкам", "youtube", "twitch", "блогер"].find((w) => t.includes(w));
      if (hit) fail(`${where}: на экране есть «${hit}»`);
    };
    // субтитры рядом с первой серией — для проверки «Взять текст из .srt»
    fs.writeFileSync(path.join(demo, "media/breaking_bad/S01E01.srt"),
      "1\n00:00:00,500 --> 00:00:03,000\nПроверка субтитров, первая реплика.\n\n2\n00:00:03,200 --> 00:00:06,000\nВторая реплика — тоже на месте!\n\n"
      + "3\n00:00:06,500 --> 00:00:09,500\nНет! Стой! Ха-ха!\n\n", "utf-8");

    await page.goto(BASE + "/auth?k=testtoken");
    await page.waitForSelector("text=Источников пока нет");
    await noStreams("первый запуск");
    await check(page, "a01_pervyi_zapusk");

    // сериал с папкой через системное окно
    await page.click("text=Добавить источник");
    await page.waitForSelector("#bFolder");
    const types = await page.$$eval("#tSeg button", (b) => b.map((x) => x.innerText.trim()));
    if (types.join(",") !== "Сериал,Фильм") fail("типы источника: " + types);
    await noStreams("новый источник");
    await page.fill("#sT", "Во все тяжкие");
    await page.click('#tSeg button[data-t="series"]');
    await page.click("#bSave");
    await page.waitForSelector("#toast:not([hidden])");
    if (!(await page.innerText("#toast")).includes("Выберите папку")) fail("сохранение без папки не остановлено");
    await page.click("#bFolder");
    await page.waitForFunction(() => document.querySelector("#sFname").textContent === "breaking_bad");
    await check(page, "a02_istochnik_papka", true);
    await page.click("#bSave");
    await page.waitForSelector("h1:has-text('Настройки')");
    step("типы источника — только «Сериал» и «Фильм»; сериал сохранён с папкой из системного окна");
    await noStreams("настройки");

    // «Сегодня» → «Выбрать файл» → момент → клип
    await page.goto(BASE + "/#/today");
    await page.waitForSelector("#curCard");
    await check(page, "a04_segodnya", true);
    await page.click("text=Выбрать файл");
    await page.waitForSelector(".item[data-path]");
    await check(page, "a05_fayly");
    await page.click(".item[data-path] >> nth=0");
    await page.waitForSelector("#bMom");
    await page.fill("#tEnd", "0:10"); await page.press("#tEnd", "Enter"); await page.click("h1");
    await page.click("#bMom");
    await page.waitForSelector(".cand", { timeout: 60000 });
    await page.click(".cand >> nth=0");
    step(`«Предложить моменты»: начало ${await page.inputValue("#tStart")}`);
    if (await page.$("#oLayout")) fail("в приложении не должно быть выбора раскладки с вебкамерой");
    await page.check("#oSrt");
    await noStreams("выбор момента");
    await check(page, "a06_moment", true);
    await page.click("#bMake");
    await page.waitForSelector('a.btn.primary[href^="#/clipview/"]', { timeout: 120000 });
    await check(page, "a07_ochered");
    await page.click("text=Открыть клип");
    await page.waitForSelector("#bOpen");
    if (await page.$("#bDl")) fail("в приложении не должно быть кнопки «Скачать»");
    const nLines = await page.locator(".subline").count();
    if (nLines < 2) fail("строк субтитров " + nLines);
    await page.click("#bShare");
    await page.click("#bOpen");
    const calls = await page.evaluate(() => window.__calls);
    if (!calls.some((c) => c.startsWith("share:")) || !calls.some((c) => c.startsWith("open:"))) fail("мост не вызван: " + calls);
    step("«Поделиться» и «Открыть в галерее» вызывают системные окна Android (мост): " + calls.join(", "));
    await check(page, "a08_klip", true);
    const ta = page.locator(".subline textarea").first();
    await ta.fill("ИСПРАВЛЕНО в приложении");
    await ta.dispatchEvent("change");
    await page.click("#bRebuild");
    await page.waitForFunction(() => { const t = document.querySelector("#toast"); return t && !t.hidden && t.innerText.includes("пересобран"); },
      null, { timeout: 60000 }).catch(() => fail("пересборка"));
    step("«Пересобрать»: правка субтитров принята Java-API");

    // настройки: модель распознавания
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("[data-model]");
    await check(page, "a09_nastroyki", true);
    if (!(await page.innerText("#app")).includes("рекомендуем")) fail("нет пометки рекомендуемой модели");
    await page.click('[data-model="whisper-small"]');
    await page.waitForSelector("#toast:not([hidden])", { timeout: 30000 });
    await page.waitForSelector('[data-mdel="whisper-small"]');
    step("«Скачать» Whisper small → задача в очереди с прогрессом → модель отмечена «скачана», есть «Удалить»");
    await page.click('[data-mdel="whisper-small"]');
    await page.waitForSelector('[data-model="whisper-small"]', { timeout: 30000 });
    step("«Удалить» модель → снова «Скачать»");

    // «Моменты» без ключа TMDB — автоплан есть, подсказка про ключ
    await page.click('#nav a[data-tab="discover"]');
    await page.waitForSelector("#plGo");
    if (!(await page.innerText("#dBox")).includes("Ввести ключ")) fail("нет подсказки про ключ TMDB");
    await noStreams("«Моменты» без ключа");
    await check(page, "a11_momenty_bez_klyucha", true);
    // ключ TMDB
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#bKeys");
    if (await page.$("#kTwId") || await page.$("#kYt")) fail("в настройках остались ключи Twitch/YouTube");
    await page.fill("#kTm", "tmdbFakeKey42");
    await page.click("#bKeys");
    await page.waitForFunction(() => (document.querySelector("#kTm") || {}).placeholder?.includes("…ey42"));
    if ((await page.content()).includes("tmdbFakeKey42")) fail("ключ показан целиком");
    step("ключ TMDB сохранён, виден только конец");

    // TMDB: рейтинг серий → автоплан на 3 дня → «Сегодня»
    await page.click('#nav a[data-tab="discover"]');
    await page.waitForSelector("[data-tep]");
    const tm = await page.$eval("#dBox", (e) => e.textContent); // списки TMDB свёрнуты — innerText их не видит
    if (!tm.includes("S01E02") || tm.indexOf("S01E02") > tm.indexOf("S01E03") || !tm.includes("есть у вас")) fail("рейтинг серий TMDB: " + tm.slice(0, 400));
    await noStreams("«Моменты»");
    await check(page, "a15_filmy_serialy", true);
    step("TMDB: сериал привязан сам, лучшие серии из папки по рейтингу (S01E02 ★9,0 первой), в тренде отмечено «есть у вас»");
    await page.selectOption("#plSrc", { label: "Во все тяжкие (Сериал)" });
    await page.click("#plGo");
    await page.waitForFunction(() => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes("План готов"); }, null, { timeout: 120000 });
    await page.waitForFunction(() => document.querySelectorAll("[data-pdel]").length === 3);
    const pl = await page.innerText("#dBox");
    const i2 = pl.indexOf("S01E02.mp4"), i3 = pl.indexOf("S01E03.mp4"), i1 = pl.indexOf("S01E01.mp4");
    if (!(i2 >= 0 && i2 < i3 && i3 < i1) || !pl.includes("рейтинг 9,0")) fail("порядок плана: " + pl.slice(0, 600));
    await check(page, "a16_avtoplan", true);
    step("автоплан: 3 серии по рейтингу (E02 → E03 → E01) на 3 дня, клипы поставлены в очередь");
    await page.click('#nav a[data-tab="today"]');
    await page.waitForSelector("text=По плану на сегодня");
    await page.waitForSelector("[data-ppub]", { timeout: 180000 });
    await noStreams("«Сегодня» с планом");
    await check(page, "a17_segodnya_plan", true);
    await page.click("[data-ppub]");
    await page.waitForFunction(() => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes("опубликовано"); }, null, { timeout: 15000 });
    step("«Сегодня»: момент по плану, клип собран → «Опубликовано»");

    // отдельное видео через системное окно
    await page.goto(BASE + "/#/clip");
    await page.waitForSelector("#srcSel");
    await page.selectOption("#srcSel", "");
    await page.waitForSelector("#pickV");
    await page.click("#pickV");
    await page.waitForSelector("#pv");
    if (!(await page.innerText("#fileBox")).includes("old_rip.mp4")) fail("выбранное видео не показано");
    step("«Выбрать видео на телефоне» → видео открыто на экране выбора момента");
    await check(page, "a10_vybrannoe_video");

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
