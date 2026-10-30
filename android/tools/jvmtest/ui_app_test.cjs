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
    await ctx.addInitScript(({ streamerDir }) => {
      window.__calls = [];
      window.AndroidApp = {
        pickFolder: () => { window.__calls.push("pickFolder"); setTimeout(() => window.onFolderPicked({ uri: streamerDir, name: "streamer" }), 50); },
        pickVideo: () => { window.__calls.push("pickVideo"); setTimeout(() => window.onVideoPicked("_picked/old_rip.mp4"), 50); },
        shareClip: (id) => window.__calls.push("share:" + id),
        openClip: (id) => window.__calls.push("open:" + id),
        version: () => "test",
      };
    }, { streamerDir: path.join(demo, "media/streamer") });
    const page = await ctx.newPage();
    page.on("pageerror", (e) => report.errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") report.errors.push("console: " + m.text()); });
    page.on("dialog", (d) => d.accept());

    await page.goto(BASE + "/auth?k=testtoken");
    await page.waitForSelector("text=Источников пока нет");
    await check(page, "a01_pervyi_zapusk");

    // источник с папкой через системное окно
    await page.click("text=Добавить источник");
    await page.waitForSelector("#bFolder");
    await page.fill("#sT", "Стример Вася");
    await page.click('#tSeg button[data-t="stream"]');
    await page.click("#bSave");
    await page.waitForSelector("#toast:not([hidden])");
    if (!(await page.innerText("#toast")).includes("Выберите папку")) fail("сохранение без папки не остановлено");
    await page.click("#bFolder");
    await page.waitForFunction(() => document.querySelector("#sFname").textContent === "streamer");
    await check(page, "a02_istochnik_papka", true);
    await page.click("#bSave");
    await page.waitForSelector("h1:has-text('Рамка вебкамеры')");
    await page.waitForFunction(() => document.querySelector("#fimg").naturalWidth > 0);
    step("источник сохранён с папкой из системного окна → открылась «Рамка вебкамеры» с кадром из папки");
    await check(page, "a03_ramka", true);
    await page.click("text=Сохранить рамку");
    await page.waitForTimeout(500);

    // «Сегодня» → «Выбрать файл» → момент → клип
    await page.goto(BASE + "/#/today");
    await page.waitForSelector("#curCard");
    await check(page, "a04_segodnya", true);
    await page.click("text=Выбрать файл");
    await page.waitForSelector(".item[data-path]");
    await check(page, "a05_fayly");
    await page.click(".item[data-path]");
    await page.waitForSelector("#bMom");
    await page.fill("#tEnd", "0:10"); await page.press("#tEnd", "Enter"); await page.click("h1");
    await page.click("#bMom");
    await page.waitForSelector(".cand", { timeout: 60000 });
    await page.click(".cand >> nth=0");
    const st = await page.inputValue("#tStart");
    if (!/^0:(1\d|2\d)/.test(st)) fail("момент не на громком месте: " + st);
    step(`«Предложить моменты» (ядро Java): начало ${st}`);
    await page.check("#oSrt");
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
    await page.waitForSelector("#toast:not([hidden])", { timeout: 60000 });
    if (!(await page.innerText("#toast")).includes("пересобран")) fail("пересборка");
    step("«Пересобрать»: правка субтитров принята Java-API");

    // настройки: скачивание модели распознавания
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("[data-model]");
    await check(page, "a09_nastroyki", true);
    await page.click('[data-model="ru-small"]');
    await page.waitForSelector("#toast:not([hidden])", { timeout: 30000 });
    await page.waitForFunction(() => !document.querySelector('[data-model="ru-small"]'));
    step("«Скачать» модель → задача в очереди с прогрессом → модель отмечена «скачана»");

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
