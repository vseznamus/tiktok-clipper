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
        pickFolder: () => { window.__calls.push("pickFolder"); setTimeout(() => window.onFolderPicked({ uri: window.__nightDir, name: "breaking_bad" }), 50); },
        copyText: (t) => window.__calls.push("copy:" + t),
        pickVideo: () => { window.__calls.push("pickVideo"); setTimeout(() => window.onVideoPicked("_picked/old_rip.mp4"), 50); },
        pickImage: () => { window.__calls.push("pickImage"); setTimeout(() => window.onImagePicked("_img/banner.png"), 50); },
        shareClip: (id) => window.__calls.push("share:" + id),
        openClip: (id) => window.__calls.push("open:" + id),
        version: () => "test",
      };
    });
    await ctx.addInitScript((d) => { window.__nightDir = d; }, path.join(demo, "media/breaking_bad"));
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
    // ширина 50% безопасной зоны = 420 px из 1080; картинка 4×2 → высота 210; низ — на границе зоны (1440)
    await page.waitForFunction(() => { const i = document.querySelector("#bnImg"); return i && Math.abs(parseFloat(i.style.width) - 38.89) < 0.1; });
    const geo = await page.evaluate(() => ({ top: parseFloat(document.querySelector("#bnImg").style.top), sub: parseFloat(document.querySelector("#bnSub").style.top),
      plate: document.querySelector("#bnPlate").checked }));
    if (Math.abs(geo.top - 64.06) > 0.2) fail("положение баннера в предпросмотре: " + geo.top);
    if (!(geo.sub < geo.top - 3)) fail("субтитры должны быть над баннером: " + JSON.stringify(geo));
    if (!geo.plate) fail("подложка по умолчанию должна быть включена");
    await noJunk("«Клип» с видео");
    await check(page, "a06_klip_banner", true);
    step("баннер «Снизу», 50%: в безопасной зоне TikTok, субтитры поднялись над баннером, тёмная подложка включена");
    await page.click("#bTpl");
    await page.waitForFunction(() => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes("Шаблон сохранён"); });
    step("«Сохранить как шаблон»: настройки и баннер запомнены для «Быстрого клипа» и ночной нарезки");
    await page.click("#bMake");
    await page.waitForSelector("#jobsBox .progress", { timeout: 15000 });
    const jobs = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const cj = jobs.find((j) => j.type === "clip");
    if (!cj || cj.params.banner !== "_img/banner.png" || cj.params.banner_pos !== "bottom" || cj.params.banner_size !== 0.5 || cj.params.banner_plate !== true)
      fail("параметры баннера: " + JSON.stringify(cj && cj.params));
    await check(page, "a07_klip_sobiraetsya");
    await page.waitForSelector("#bShare", { timeout: 120000 });
    step("«Сделать клип»: прогресс прямо на вкладке «Клип», баннер передан в сборку, по готовности открылся клип");
    const cap = await page.innerText("#app");
    if (!cap.includes("Подпись для TikTok") || !cap.includes("#фильм")) fail("подпись: " + cap.slice(0, 300));
    await page.click("#bCap");
    await page.click("#bShare");
    await page.click("#bOpen");
    const calls = await page.evaluate(() => window.__calls);
    if (!calls.some((c) => c.startsWith("share:")) || !calls.some((c) => c.startsWith("open:")) || !calls.some((c) => c.startsWith("copy:") && c.includes("#кино")))
      fail("мост не вызван: " + calls);
    await check(page, "a08_gotovyi_klip", true);
    step("готовый клип: подпись с хэштегами («Скопировать подпись»), «Поделиться», «Открыть в галерее»");

    // «Быстрый клип»: лучший момент + шаблон (с баннером) одной кнопкой
    await page.click('#nav a[data-tab="clip"]');
    await page.waitForSelector("#bQuick");
    await page.click("#bQuick");
    await page.waitForSelector("#bShare", { timeout: 120000 });
    const jobs2 = await page.evaluate(() => fetch("/api/jobs").then((r) => r.json()));
    const auto = jobs2.find((j) => j.type === "auto" && j.status === "done");
    const qc = auto && jobs2.find((j) => j.id === auto.result.clip_jobs[0]);
    if (!qc || qc.params.banner_pos !== "bottom" || qc.params.banner_plate !== true) fail("быстрый клип не по шаблону: " + JSON.stringify(qc && qc.params));
    step("«⚡ Быстрый клип»: одна кнопка → лучший момент → клип по шаблону (с баннером) → открылся готовый клип");

    // ночная нарезка: папка, 2 клипа, «Нарезать сейчас»
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#ngPick");
    await page.click("#ngPick");
    await page.waitForFunction(() => document.querySelector("#ngFolder").textContent === "breaking_bad");
    await page.check("#ngOn");
    await page.fill("#ngN", "2");
    await page.click("#ngSave");
    await page.waitForFunction(() => { const t = document.querySelector("#toast"); return !t.hidden && t.innerText.includes("включена"); });
    await check(page, "a10_nochnaya_narezka", true);
    await page.click("#ngNow");
    await page.waitForSelector("#jobsBox .progress", { timeout: 15000 });
    await page.waitForFunction(() => document.querySelectorAll("#readyBox .item").length >= 4 && !document.querySelector("#jobsBox .progress"), null, { timeout: 180000 });
    const ready = await page.innerText("#readyBox");
    if ((ready.match(/нарезан ночью/g) || []).length !== 2) fail("ночные клипы: " + ready);
    step("ночная нарезка: папка «breaking_bad», 2 клипа за раз → «Нарезать сейчас» → 2 клипа с пометкой 🌙 в «Готовых клипах»");
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
