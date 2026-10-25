// End-to-end test of RunaSearchView and RunaChat.orderPlaced (1.1.0) on an emulator: the sample's search screen
// with Ask Quicklly in search above its own results, driven inside the view's WebView (debug builds enable WebView
// debugging), native screens checked through adb, the callbacks in logcat (tags RunaSample / RunaChat).
// The pages can come from the developer's laptop before they are live:
//   (cd runa_web/build-widget && python3 -m http.server 5521) ; adb reverse tcp:5521 tcp:5521
//   PAGE_BASE=http://quicklly.localhost:5521/ node tests/e2e-search.mjs      (default: the live pages)
import { _android as android } from "playwright";
import { execSync } from "child_process";
import fs from "fs";

const PKG = process.env.PKG || "ai.askruna.chat.sample";
const PAGE_BASE = process.env.PAGE_BASE || "";
const SHOTS = process.env.SHOTS || "/tmp";
const adb = (c) => execSync(`adb ${c}`, { maxBuffer: 50e6 }).toString();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (ok, label) => { ok ? pass++ : fail++; console.log(`${ok ? "PASS" : "FAIL"}  ${label}`); };
const log = () => adb(`logcat -d -s RunaSample:I RunaChat:D`);
async function waitLog(re, ms = 15000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { const m = log().split("\n").reverse().find((l) => re.test(l)); if (m) return m; await sleep(300); }
  return null;
}
function ui() {
  for (let i = 0; i < 8; i++) {
    try { const out = adb("shell uiautomator dump /sdcard/ui.xml"); if (/dumped to/.test(out)) return adb("shell cat /sdcard/ui.xml"); } catch (e) {}
    execSync("sleep 1");
  }
  return "";
}
const top = () => (adb("shell dumpsys activity activities").match(/topResumedActivity=\S+ \S+ \S+\/(\S+)/) || [])[1] || "";
async function waitTop(re, ms = 20000) { const end = Date.now() + ms; while (Date.now() < end) { if (re.test(top())) return true; await sleep(400); } return false; }
function boundsAll(xml, re) {
  return xml.split("<node").filter((n) => re.test(n)).map((m) => { const b = m.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/); return b ? { l: +b[1], t: +b[2], r: +b[3], b: +b[4] } : null; }).filter(Boolean);
}
function tapText(xml, text) {
  const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, "i"));
  if (!m) return false;
  adb(`shell input tap ${(+m[1] + +m[3]) >> 1} ${(+m[2] + +m[4]) >> 1}`);
  return true;
}
let device = null;
async function pageWith(part) {
  for (let i = 0; i < 60; i++) {
    try { if (device) await device.close(); } catch (e) {}
    device = (await android.devices())[0];
    for (const wv of device.webViews()) {
      if (wv.pkg() !== PKG) continue;
      try {
        // the search view and the chat screen are two pages in the same app process
        const first = await wv.page();
        for (const p of first.context().pages()) {
          if (!p.isClosed() && (await p.evaluate(() => location.href)).includes(part)) return p;
        }
      } catch (e) {}
    }
    await sleep(1000);
  }
  throw new Error("no WebView with " + part);
}

// pages from the laptop: the emulator reaches it through adb reverse (lost whenever the adb server restarts)
const localPort = (PAGE_BASE.match(/^http:\/\/[^/]*localhost:(\d+)/) || [])[1];
if (localPort) adb(`reverse tcp:${localPort} tcp:${localPort}`);
adb("logcat -c");
adb(`shell am force-stop ${PKG}`);
adb(`shell pm clear ${PKG}`);
await sleep(3000);
const extras = `--es search "cow\\ milk"` + (PAGE_BASE ? ` --es page_base ${PAGE_BASE}` : "");
let opened = false;
for (let attempt = 1; attempt <= 4 && !opened; attempt++) {
  adb(`shell am start -n ${PKG}/.MainActivity ${extras}`);
  opened = await waitTop(/SearchActivity/, 60000);
  if (!opened) { console.log(`      (start attempt ${attempt} did not reach the search screen, retrying)`); adb(`shell am force-stop ${PKG}`); await sleep(3000); }
}
check(opened, "the app's search screen opened with the search 'cow milk'");
let page = await pageWith("search-app.html");
const q = await page.evaluate(() => Object.fromEntries(new URLSearchParams(location.search)));
check(q.q === "cow milk" && q.results === "4" && (q.titles || "").startsWith("Amul Water Buffalo") && q.zip === "60610" && q.userId === "sample-user-1" && q.platform === "android",
  `page URL from search(): q=${q.q} results=${q.results} titles=${(q.titles || "").slice(0, 30)}… zip=${q.zip} platform=${q.platform}`);
check(!!(await waitLog(/search page→app ready/)), "search page said ready");
await page.waitForSelector(".runa-sb .runa-c__pcard", { timeout: 90000 });
await sleep(1500);
const first = (await page.locator(".runa-sb .runa-c__pcard-title").first().textContent()).trim();
const n = await page.locator(".runa-sb .runa-c__pcard").count();
check(n >= 3 && /milk/i.test(first) && !/butter/i.test(first), `answer: ${n} products, first "${first}" (the app's own first result is butter)`);
const hLog = await waitLog(/search view height (\d+)/);
const reported = hLog ? +hLog.match(/height (\d+)/)[1] : 0;
const css = await page.evaluate(() => document.getElementById("root_quicklly_chat").getBoundingClientRect().height);
const dpr = await page.evaluate(() => window.devicePixelRatio);
check(reported > 200 && Math.abs(reported - Math.ceil(css * dpr)) <= dpr * 2, `native view sized to the answer: ${reported}px (page ${Math.ceil(css)} css px × ${dpr})`);
let xml = ui();
const webs = boundsAll(xml, /class="android.webkit.WebView"/);
const foundRow = boundsAll(xml, /text="\d+ products found for item[^"]*"/)[0];
check(webs.length === 1 && !!foundRow && Math.abs(webs[0].b - foundRow.t) <= 4 && Math.abs((webs[0].b - webs[0].t) - reported) <= 2, `the view sits right above the app's "N products found" (view ${webs[0] && webs[0].t}–${webs[0] && webs[0].b}px, row at ${foundRow && foundRow.t}px)`);
fs.writeFileSync(SHOTS + "/runasearch-android-answer.png", execSync("adb exec-out screencap -p", { maxBuffer: 50e6 }));

// ADD → setQuantity → the cart → stepper
await page.locator(".runa-sb .runa-c__pcard-add").first().click();
const sq = await waitLog(/setQuantity pid=\d+ sid=\d+ qty=1/);
check(!!sq, "ADD → Callbacks.setQuantity(product, 1): " + (sq || "").replace(/.*setQuantity /, "").slice(0, 100));
await sleep(700);
check((await page.locator(".runa-sb .runa-c__pcard-qty-n").first().textContent()).trim() === "1", "stepper shows 1 (from getCart)");
adb(`shell am broadcast -a ${PKG}.BUMP --ei delta 1 -p ${PKG}`);
await sleep(1200);
check((await page.locator(".runa-sb .runa-c__pcard-qty-n").first().textContent()).trim() === "2", "app changed the cart + notifyCartChanged() → the search view's stepper shows 2");

// a product tap → openProduct → the app's product screen; back
await page.locator(".runa-sb .runa-c__pcard-title").first().click({ noWaitAfter: true });
check(!!(await waitLog(/openProduct pid=\d+/)), "product tap → Callbacks.openProduct");
check(await waitTop(/ProductActivity/), "the app's product screen opened");
await sleep(1000);                       // a back press while the screen is still opening can be lost on a slow emulator
adb("shell input keyevent 4");
let backOk = await waitTop(/SearchActivity/, 8000);
if (!backOk && /ProductActivity/.test(top())) { adb("shell input keyevent 4"); backOk = await waitTop(/SearchActivity/, 8000); }
check(backOk, "back → search screen");
page = await pageWith("search-app.html");

// a chip → the chat screen with the question, credited to Ask Quicklly in search
const chip = page.locator(".runa-sb .runa-pdp__chip:not(.runa-sb__chip--ask)").first();
const ask = await chip.getAttribute("data-ask");
await chip.click({ noWaitAfter: true });
check(await waitTop(/RunaChatActivity/, 20000), `chip → the chat screen opened ("${ask}")`);
const chat = await pageWith("chat-app.html");
const cq = await chat.evaluate(() => Object.fromEntries(new URLSearchParams(location.search)));
check(cq.q === ask && cq.entry === "search_answers" && cq.zip === "60610", `chat opened with q="${cq.q}", entry=${cq.entry}`);
await chat.waitForSelector(".runa-c__window--app", { timeout: 30000 });
let sent = false;
for (let i = 0; i < 40 && !sent; i++) { sent = (await chat.evaluate(() => document.body.innerText)).includes(ask); if (!sent) await sleep(500); }
check(sent, "the question was sent as the first message");
check(!/content-desc="Loading Ask Quicklly"/.test(ui()), "the chat's loading screen is gone");
adb("shell input keyevent 4");      // nothing open in the chat → the chat closes
check(await waitTop(/SearchActivity/, 15000), "back → search screen");

// "Ask me anything" → the chat screen opens with no question, still credited to Ask Quicklly in search
page = await pageWith("search-app.html");
await page.locator(".runa-sb .runa-sb__chip--ask").first().click({ noWaitAfter: true });
check(await waitTop(/RunaChatActivity/, 20000), '"Ask me anything" → the chat screen opened');
const chat2 = await pageWith("chat-app.html");
const cq2 = await chat2.evaluate(() => Object.fromEntries(new URLSearchParams(location.search)));
check(!cq2.q && cq2.entry === "search_answers" && cq2.zip === "60610", `chat opened without a question (q=${cq2.q || "none"}), entry=${cq2.entry}`);
await chat2.waitForSelector(".runa-c__window--app .runa-c__composer-text", { timeout: 30000 });
// the shopper's own messages: the earlier conversation is restored (its answer may still be arriving), nothing new is sent
const msgs = async () => chat2.evaluate(() => document.querySelectorAll(".runa-c__msg-block--user").length);
await sleep(1500);
const m1 = await msgs();
await sleep(4000);
const m2 = await msgs();
const boxes = await chat2.evaluate(() => [...document.querySelectorAll(".runa-c__composer-text")].map((i) => ({ v: i.value, shown: i.offsetParent !== null })));
const box = boxes.filter((b) => b.shown).map((b) => (b.v || "").trim()).join("");
check(m1 === m2 && box === "", `nothing sent by itself: shopper messages ${m1} → ${m2} over 4 s, the message box is empty and ready (${JSON.stringify(boxes)})`);
fs.writeFileSync(SHOTS + "/runasearch-android-ask.png", execSync("adb exec-out screencap -p", { maxBuffer: 50e6 }));
adb("shell input keyevent 4");
check(await waitTop(/SearchActivity/, 15000), "back → search screen");

// a new search typed in the app's field → the same view answers it (no reload)
adb("logcat -c");
xml = ui();
const field = boundsAll(xml, /content-desc="search field"/)[0];
adb(`shell input tap ${(field.l + field.r) >> 1} ${(field.t + field.b) >> 1}`);
await sleep(500);
adb("shell input keyevent KEYCODE_MOVE_END");
for (let i = 0; i < 12; i++) adb("shell input keyevent KEYCODE_DEL");
adb(`shell input text "basmati%srice"`);
adb("shell input keyevent KEYCODE_ENTER");
check(!!(await waitLog(/search app→page search .*basmati rice/, 10000)), "the view got the new search over the bridge");
page = await pageWith("search-app.html");
await page.waitForSelector('.runa-sb[data-query="basmati rice"] .runa-c__pcard', { timeout: 90000 });
const t2 = (await page.locator(".runa-sb .runa-c__pcard-title").first().textContent()).trim();
check(/rice/i.test(t2), `new search → new answer, first "${t2}"`);
check(!!(await waitLog(/search view height \d+/, 10000)), "the view resized for the new answer");
fs.writeFileSync(SHOTS + "/runasearch-android-second.png", execSync("adb exec-out screencap -p", { maxBuffer: 50e6 }));

// checkout → RunaChat.orderPlaced → the order page reports it with Ask Quicklly's sales
adb("shell input keyevent 4");
adb("shell input keyevent 4");
check(await waitTop(/MainActivity/, 15000), "back on the app's home");
adb("logcat -c");
await sleep(800);
check(tapText(ui(), "Place order"), "tap Place order");
const done = await waitLog(/order page→app done/, 25000);
const sales = done && done.match(/"sales":([\d.]+)/);
check(!!done && /"ok":true/.test(done) && !!sales && +sales[1] > 0, `orderPlaced → reported with Ask Quicklly sales $${sales ? sales[1] : "?"}: ${(done || "").replace(/.*done /, "").slice(0, 120)}`);
check(!!(await waitLog(/order reported=true/, 5000)), "OrderListener told reported=true");

console.log(`\n${pass} passed, ${fail} failed`);
try { await device.close(); } catch (e) {}
process.exit(fail ? 1 : 0);
