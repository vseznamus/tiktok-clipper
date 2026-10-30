// UI-прогон в мобильном окне 390x844: путь пользователя из INSTRUCTION.md (часть C).
//   node tests/ui/ui_test.cjs <корень проекта> <демо-папка> <папка для скриншотов>
// Проверяет на каждом экране: нет горизонтальной прокрутки, кнопки не меньше 44 px и не выходят за экран.
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const path = require("path");
const fs = require("fs");

const [root, demo, shots] = process.argv.slice(2).map((p) => path.resolve(p));
fs.mkdirSync(shots, { recursive: true });
const PORT = 8765;
const BASE = `http://127.0.0.1:${PORT}`;
const report = { screens: {}, errors: [], steps: [] };
const py = fs.existsSync(path.join(root, ".venv/bin/python")) ? path.join(root, ".venv/bin/python") : "python3";

function step(msg) { report.steps.push(msg); console.log("•", msg); }
function fail(msg) { throw new Error(msg); }

async function waitServer() {
  for (let i = 0; i < 100; i++) {
    try { const r = await fetch(BASE + "/api/status"); if (r.ok) return; } catch (e) { /* ещё не поднялся */ }
    await new Promise((r) => setTimeout(r, 200));
  }
  fail("сервер не запустился");
}

async function check(page, name, { full = false } = {}) {
  await page.waitForTimeout(400);
  const res = await page.evaluate(() => {
    const vw = window.innerWidth, small = [], outside = [];
    const sel = "button, a.btn, a.item, .item, label.btn, label.toggle, input:not([type=checkbox]):not([type=file]), select, textarea, #nav a";
    for (const el of document.querySelectorAll(sel)) {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height || el.closest("[hidden]")) continue;
      const label = (el.innerText || el.value || el.id || el.tagName).trim().slice(0, 40);
      const minH = el.type === "range" ? 40 : 44;
      if (r.height < minH - 0.5 || r.width < 44) small.push(`${label} (${Math.round(r.width)}x${Math.round(r.height)})`);
      const fs = parseFloat(getComputedStyle(el).fontSize);
      if (el.matches("button, a.btn, label.btn") && fs < 15) small.push(`${label}: шрифт ${fs}px`);
      if (r.left < -1 || r.right > vw + 1) outside.push(`${label} [${Math.round(r.left)}..${Math.round(r.right)}]`);
    }
    return { vw, scrollWidth: document.documentElement.scrollWidth, small, outside,
             title: (document.querySelector("h1") || {}).innerText };
  });
  const file = path.join(shots, `${name}.png`);
  await page.screenshot({ path: file, fullPage: full });
  report.screens[name] = res;
  if (res.scrollWidth > res.vw) fail(`${name}: горизонтальная прокрутка ${res.scrollWidth} > ${res.vw}`);
  if (res.small.length) fail(`${name}: мелкие элементы: ${res.small.join("; ")}`);
  if (res.outside.length) fail(`${name}: за краем экрана: ${res.outside.join("; ")}`);
  step(`экран «${res.title}» → ${path.basename(file)}: ок`);
}

(async () => {
  const env = { ...process.env, CLIPPER_DATA: path.join(demo, "data"), CLIPPER_MEDIA: path.join(demo, "media"),
                CLIPPER_OUTPUT: path.join(demo, "Movies/TikTokClips") };
  delete env.CLIPPER_TOKEN;
  const srv = spawn(py, [path.join(root, "clipper.py"), "serve", "--port", String(PORT)], { env, stdio: ["ignore", "pipe", "pipe"] });
  srv.stderr.on("data", (d) => process.stderr.write("[server] " + d));
  let browser;
  try {
    await waitServer();
    browser = await chromium.launch();
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true,
                                           hasTouch: true, locale: "ru-RU" });
    const page = await ctx.newPage();
    page.on("pageerror", (e) => report.errors.push("pageerror: " + e.message));
    page.on("console", (m) => { if (m.type() === "error") report.errors.push("console: " + m.text()); });
    page.on("dialog", (d) => d.accept());

    // 1. «Сегодня»
    await page.goto(BASE + "/#/today");
    await page.waitForSelector("#curCard");
    const todayTxt = await page.innerText("#curCard");
    if (!todayTxt.includes("Стример Вася") || !todayTxt.includes("Пост 1 из 3")) fail("на «Сегодня» не тот пост: " + todayTxt);
    await check(page, "01_segodnya", { full: true });

    // 2. «Выбрать файл» → список файлов
    await page.click("text=Выбрать файл");
    await page.waitForSelector(".item[data-path]");
    await check(page, "02_vybor_fayla");
    await page.click(".item[data-path]");

    // 3. Выбор момента: видео, ползунки, «Предложить моменты»
    await page.waitForSelector("#pv");
    await page.waitForFunction(() => document.querySelector("#pv").readyState >= 1, null, { timeout: 15000 });
    await page.click("#sP"); await page.click("#eP"); await page.click("#eP");
    let st = await page.inputValue("#tStart"), en = await page.inputValue("#tEnd");
    if (st !== "0:01.0" || en !== "0:12.0") fail(`кнопки ±1 с работают неверно: ${st} – ${en}`);
    await page.fill("#tStart", "0:02.5"); await page.press("#tStart", "Enter"); await page.click("h1");
    st = await page.inputValue("#tStart");
    if (st !== "0:02.5") fail("ввод времени не сработал: " + st);
    await page.click("text=Предложить моменты");
    await page.waitForSelector(".cand", { timeout: 60000 });
    await page.click(".cand >> nth=0");
    st = await page.inputValue("#tStart");
    step(`«Предложить моменты»: первый кандидат ставит начало на ${st}`);
    if (!/^0:(1\d|2\d)/.test(st)) fail("кандидат не на громком месте (ожидалось 0:1x–0:2x): " + st);
    if (await page.isVisible("#pimg")) fail("скрытая картинка кадра видна при работающем видео");
    await check(page, "03_vybor_momenta", { full: true });

    // 4. «Проверить рамку вебкамеры» → тянем рамку пальцем (pointer events) → «Сохранить рамку»
    await page.click("text=Проверить рамку вебкамеры");
    await page.waitForSelector("#fimg");
    await page.waitForFunction(() => document.querySelector("#fimg").naturalWidth > 0);
    const box = await page.locator("#cw").boundingBox();
    // рамка по умолчанию [0.68, 0.62, 0.30, 0.35]; ставим её на «вебкамеру» в кадре: x=940/1280, y=460/720
    // сначала уменьшаем за правый нижний угол до 0.25 x 0.333, потом двигаем на место
    const hbr = await page.locator("#rCam .h.br").boundingBox();
    await page.mouse.move(hbr.x + hbr.width / 2, hbr.y + hbr.height / 2);
    await page.mouse.down();
    await page.mouse.move(hbr.x + hbr.width / 2 - 0.05 * box.width, hbr.y + hbr.height / 2 - 0.017 * box.height, { steps: 8 });
    await page.mouse.up();
    const r0 = await page.locator("#rCam").boundingBox();
    await page.mouse.move(r0.x + r0.width / 2, r0.y + r0.height / 2);
    await page.mouse.down();
    await page.mouse.move(r0.x + r0.width / 2 + 0.054 * box.width, r0.y + r0.height / 2 + 0.019 * box.height, { steps: 8 });
    await page.mouse.up();
    await check(page, "04_ramka_vebkamery", { full: true });
    await page.click("text=Сохранить рамку");
    await page.waitForSelector("#bMake");
    const cfg = await (await fetch(BASE + "/api/config")).json();
    const cam = cfg.sources.find((s) => s.id === "streamer").profile.cam_rect;
    step(`рамка сохранена в профиль источника: [${cam.map((v) => v.toFixed(3)).join(", ")}]`);
    const want = [0.734, 0.639, 0.25, 0.333];
    if (cam.some((v, i) => Math.abs(v - want[i]) > 0.03)) fail("рамка сохранилась не там: " + cam);

    // 5. Настройки клипа: субтитры из .srt, «Сделать клип» → «Очередь»
    await page.check("#oSrt");
    await page.click("#bMake");
    await page.waitForSelector("#jobs .card");
    await page.waitForTimeout(700);
    await check(page, "05_ochered");
    await page.waitForSelector('a.btn.primary[href^="#/clipview/"]', { timeout: 180000 });
    await check(page, "05b_ochered_gotovo");

    // 6. «Готовый клип»: превью, правка субтитров, «Пересобрать»
    await page.click("text=Открыть клип");
    await page.waitForSelector("#cv");
    await page.waitForFunction(() => document.querySelector("#cv").readyState >= 2, null, { timeout: 20000 });
    const vw = await page.evaluate(() => [document.querySelector("#cv").videoWidth, document.querySelector("#cv").videoHeight]);
    if (vw[0] !== 1080 || vw[1] !== 1920) fail("превью клипа не 1080x1920: " + vw);
    const nLines = await page.locator(".subline").count();
    if (nLines < 2) fail("в редакторе субтитров мало строк: " + nLines);
    await check(page, "06_gotovyi_klip", { full: true });
    const ta = page.locator(".subline textarea").first();
    await ta.fill("Привет, это ИСПРАВЛЕННЫЙ текст");
    await ta.dispatchEvent("change");
    await page.click("#bRebuild");
    await page.waitForSelector("#toast:not([hidden])", { timeout: 120000 });
    const toastTxt = await page.innerText("#toast");
    if (!toastTxt.includes("пересобран")) fail("после «Пересобрать»: " + toastTxt);
    await page.waitForSelector("#cv");
    const clipId = (await page.evaluate(() => location.hash)).split("/").pop();
    const meta = await (await fetch(`${BASE}/api/clips/${clipId}`)).json();
    if (meta.version !== 1 || !meta.lines.some((l) => l.text.includes("ИСПРАВЛЕННЫЙ"))) fail("пересборка не применила правку");
    step(`«Пересобрать»: версия ${meta.version}, строки: ${meta.lines.map((l) => l.text).join(" | ")}`);
    fs.copyFileSync(meta.gallery_path, path.join(shots, "klip_primer.mp4"));
    await check(page, "07_posle_peresborki");

    // 7. «Опубликовано» → «Сегодня» показывает пост 2/3
    await page.click("#bPub");
    await page.waitForSelector("#curCard");
    const after = await page.innerText("#curCard");
    if (!after.includes("Пост 2 из 3") || !after.includes("Стример Вася")) fail("после «Опубликовано» не 2/3: " + after);
    step("«Опубликовано» → на «Сегодня» пост 2 из 3 того же источника");
    await check(page, "08_segodnya_posle_publikacii", { full: true });
    await page.click("text=Выбрать файл");
    await page.waitForSelector(".item[data-path]", { timeout: 10000 });
    step("повторное «Выбрать файл» с «Сегодня» показывает список файлов, а не старый выбор");

    // 8. «Клипы», «Настройки», «Источник»
    await page.click('#nav a[data-tab="clips"]');
    await page.waitForSelector("h1:has-text('Клипы')");
    await check(page, "09_klipy");
    await page.click('#nav a[data-tab="settings"]');
    await page.waitForSelector("#bSched");
    await check(page, "10_nastroyki", { full: true });
    await page.click("text=Добавить источник");
    await page.waitForSelector("#sT");
    await page.fill("#sT", "Новый блогер");
    await page.click('#tSeg button[data-t="blogger"]');
    await check(page, "11_novyi_istochnik", { full: true });
    await page.click("#bSave");
    await page.waitForSelector("h1:has-text('Рамка вебкамеры')");
    step("новый блогер сохранён → открылся экран «Рамка вебкамеры»");
    await check(page, "12_ramka_pustoy_istochnik");

    // 9. Файл, который браузер не проигрывает → показываются кадры, ползунок «Перемотка» их меняет
    await page.goto(BASE + "/#/clip?source=matrix&file=" + encodeURIComponent("matrix/old_rip.avi"));
    await page.waitForSelector("#pimg:not([hidden])", { timeout: 20000 });
    await page.waitForFunction(() => document.querySelector("#pimg").naturalWidth > 0, null, { timeout: 20000 });
    const src1 = await page.getAttribute("#pimg", "src");
    await page.locator("#seek").fill("7");
    await page.waitForFunction((s) => document.querySelector("#pimg").getAttribute("src") !== s, src1, { timeout: 10000 });
    if (!(await page.isVisible("#pvNote"))) fail("нет подсказки про режим кадров");
    step("AVI не проигрывается браузером → включился режим кадров, ползунок меняет кадр");
    await check(page, "13_rezhim_kadrov");

    if (report.errors.length) fail("ошибки в консоли: " + report.errors.join(" | "));
    report.ok = true;
  } catch (e) {
    report.ok = false; report.failure = e.message; console.error("ПРОВАЛ:", e.message);
  } finally {
    if (browser) await browser.close();
    srv.kill();
    fs.writeFileSync(path.join(shots, "ui_report.json"), JSON.stringify(report, null, 2));
    process.exit(report.ok ? 0 : 1);
  }
})();
