// End-to-end test of the Android library on an emulator/device: drives the chat inside the
// library's WebView (debug builds enable WebView debugging), taps native UI through adb, and
// checks the app's callbacks in logcat (tags RunaSample / RunaChat).
// Run: npm i playwright (any folder) → node tests/e2e.mjs   (sample app installed, adb on PATH)
import { _android as android } from "playwright";
import { execSync } from "child_process";
import fs from "fs";

const PKG = process.env.PKG || "ai.askruna.chat.sample";
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
function boundsOf(xml, re) {
  const m = xml.split("<node").find((n) => re.test(n));
  const b = m && m.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  return b ? { l: +b[1], t: +b[2], r: +b[3], b: +b[4] } : null;
}
function tapText(xml, text) {
  const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`, "i"));   // buttons render in capitals
  if (!m) return false;
  adb(`shell input tap ${(+m[1] + +m[3]) >> 1} ${(+m[2] + +m[4]) >> 1}`);
  return true;
}
// The chat's WebView, as a Playwright page. Reconnects to the device each time: a reopened chat
// lives in the same app process, and a cached connection would still point at the closed page.
let device = null;
async function chatPage() {
  for (let i = 0; i < 60; i++) {
    try { if (device) await device.close(); } catch (e) {}
    device = (await android.devices())[0];
    for (const wv of device.webViews()) {
      if (wv.pkg() !== PKG) continue;
      try {
        const p = await wv.page();
        if (!p.isClosed() && (await p.evaluate(() => location.href)).includes("chat-app.html")) return p;
      } catch (e) {}
    }
    await sleep(1000);
  }
  throw new Error("no WebView for " + PKG);
}

adb("logcat -c");
adb(`shell am force-stop ${PKG}`);
adb(`shell pm clear ${PKG}`);
await sleep(4000);
// A slow emulator sometimes loses the first start right after clearing data: start, wait, retry.
let opened = false;
for (let attempt = 1; attempt <= 4 && !opened; attempt++) {
  adb(`shell am start -n ${PKG}/.MainActivity --ez open_chat true`);
  opened = await waitTop(/RunaChatActivity/, 60000);
  if (!opened) { console.log(`      (start attempt ${attempt} did not reach the chat screen, retrying)`); adb(`shell am force-stop ${PKG}`); await sleep(3000); }
}
check(opened, "chat screen opened");
let page = await chatPage();

await page.waitForSelector(".runa-c__window--app", { timeout: 30000 });
check(true, "chat opened in app mode inside the library's WebView");
check(!!(await waitLog(/page→app ready/)), "page said ready");
check(!!(await waitLog(/app→page cart/)), "library answered with the cart");
const q = await page.evaluate(() => Object.fromEntries(new URLSearchParams(location.search)));
const appVersion = adb(`shell dumpsys package ${PKG}`).match(/versionName=(\S+)/)?.[1];   // the library reports the app's own version
check(q.zip === "60610" && q.userId === "sample-user-1" && q.platform === "android" && q.appVersion === appVersion, `URL built from options: zip=${q.zip} userId=${q.userId} platform=${q.platform} appVersion=${q.appVersion} (app: ${appVersion})`);
const head = await page.evaluate(() => { const h = document.querySelector(".runa-c__window-head").cloneNode(true); h.querySelectorAll("select,button").forEach((n) => n.remove()); return h.textContent.replace(/\s+/g, " ").trim(); });
check(head === "Ask Quicklly", `header: "${head}"`);
const opts = await page.locator(".runa-c__store-select option").allTextContents();
check(opts.length > 1, `store picker: ${opts.length - 1} stores`);
// Edge to edge: the WebView starts below the status bar and ends above the navigation bar
let xml = ui();
const web = boundsOf(xml, /class="android.webkit.WebView"/);
const statusBar = Math.max(...[...adb("shell dumpsys window").matchAll(/type=statusBars[^\n]*?top=(\d+)/g)].map((m) => +m[1]), 0);
const screenH = +adb("shell wm size").match(/(\d+)x(\d+)/)[2];
check(!!web && web.t >= statusBar && statusBar > 0 && web.b < screenH, `chat fits between the system bars (WebView ${web && web.t}–${web && web.b}px, status bar ${statusBar}px, screen ${screenH}px)`);

// Keyboard: a real finger tap on the input (adb), then the chat must shrink above the keyboard
const dpr = await page.evaluate(() => window.devicePixelRatio);
const r = await page.evaluate(() => { const b = document.querySelector(".runa-c__composer-text").getBoundingClientRect(); return { x: b.left + b.width / 2, y: b.top + b.height / 2 }; });
adb(`shell input tap ${Math.round(web.l + r.x * dpr)} ${Math.round(web.t + r.y * dpr)}`);
await sleep(2500);
const imeShown = /mInputShown=true/.test(adb("shell dumpsys input_method"));
const web2 = boundsOf(ui(), /class="android.webkit.WebView"/);
const kb = await page.evaluate(() => { const b = document.querySelector(".runa-c__composer-text").getBoundingClientRect(); return { bottom: b.bottom, vh: window.innerHeight, focused: document.activeElement === document.querySelector(".runa-c__composer-text") }; });
// The WebView can only lose 300+ px at the bottom to the keyboard inset, so the geometry is the proof.
check(kb.focused && web2 && web2.b < web.b - 300 && kb.bottom <= kb.vh, `keyboard open (ime reported: ${imeShown}); the chat shrank to ${web2 && web2.b}px and the input stays visible (input bottom ${Math.round(kb.bottom)} ≤ ${kb.vh} css px)`);
fs.writeFileSync((process.env.SHOTS || "/tmp") + "/runachat-android-keyboard.png", execSync("adb exec-out screencap -p", { maxBuffer: 50e6 }));

// Turn + ADD → setQuantity callback → cart → stepper
await page.fill(".runa-c__composer-text", "basmati rice");
await page.keyboard.press("Enter");
await page.waitForSelector(".runa-c__pcard-add", { timeout: 90000 });
adb("shell input keyevent 111");   // hide the keyboard (ESC)
await sleep(800);
await page.locator(".runa-c__pcard-add").first().click();
const sq = await waitLog(/setQuantity pid=\d+ sid=\d+ qty=1/);
check(!!sq, "ADD → Callbacks.setQuantity(product, 1): " + (sq || "").replace(/.*setQuantity /, ""));
await sleep(600);
check((await page.locator(".runa-c__pcard-qty-n").first().textContent()).trim() === "1", "stepper shows 1 (from getCart)");

// App-side change while the chat is open → notifyCartChanged → stepper
adb(`shell am broadcast -a ${PKG}.BUMP --ei delta 2 -p ${PKG}`);
await sleep(1200);
check((await page.locator(".runa-c__pcard-qty-n").first().textContent()).trim() === "3", "app changed the cart + notifyCartChanged() → stepper shows 3");

// Product tap → openProduct → app's product screen; +1 there; back → chat follows
await page.locator(".runa-c__pcard-title").first().click({ noWaitAfter: true });   // the app's screen covers the WebView
check(!!(await waitLog(/openProduct pid=\d+/)), "card tap → Callbacks.openProduct");
check(await waitTop(/ProductActivity/), "app's product screen opened");
xml = ui();
tapText(xml, "\\+1 in cart");
check(!!(await waitLog(/product screen \+1/)), "+1 on the app's product screen");
adb("shell input keyevent 4");
check(await waitTop(/RunaChatActivity/), "back → chat");
await sleep(1200);
page = await chatPage();
check((await page.locator(".runa-c__pcard-qty-n").first().textContent()).trim() === "4", "+1 on the product screen → back in the chat the stepper shows 4");

// Outside link → openLink
await page.evaluate(() => window.open("https://www.quicklly.com/", "_blank"));
check(!!(await waitLog(/openLink https:\/\/www\.quicklly\.com/)), "outside link → Callbacks.openLink");

// Back: menu open → the page closes it; nothing open → the screen closes
await page.locator('.runa-c__window-icon[aria-label="More options"]').click();
await sleep(600);
check(await page.locator(".runa-c__window-menu").count() === 1, "menu opened");
adb("shell input keyevent 4");
await sleep(800);
check(await page.locator(".runa-c__window-menu").count() === 0 && !(await waitLog(/chat closed/, 500)), "back with the menu open → menu closed, chat still open");
adb("shell input keyevent 4");
check(!!(await waitLog(/chat closed/, 8000)), "back with nothing open → chat closed (Callbacks.onClose)");
await waitTop(/MainActivity/);
await sleep(800);
xml = ui();
check(/Cart: 4 item/.test(xml), "back on the app's screen; its cart shows 4");

// Reopen: conversation restored, stepper from the app's cart; ✕ closes
adb("logcat -c");
check(tapText(xml, "Ask Quicklly"), "tap Ask Quicklly on the app's screen");
check(await waitTop(/RunaChatActivity/, 30000), "chat screen opened again");
page = await chatPage();
await page.waitForSelector(".runa-c__pcard", { timeout: 30000 });
await sleep(1500);
check((await page.locator(".runa-c__pcard-qty-n").first().textContent().catch(() => "")).trim() === "4", "reopened: conversation restored, stepper 4");
await page.locator(".runa-c__window-close").click({ noWaitAfter: true });
check(!!(await waitLog(/chat closed/, 8000)), "✕ → chat closed");

// Deep link question
adb("logcat -c");
await sleep(800);
check(tapText(ui(), "Ask about paneer \\(deep link\\)"), "tap the deep-link button");
check(await waitTop(/RunaChatActivity/, 30000), "chat screen opened from the deep link");
page = await chatPage();
await sleep(2500);
check((await page.evaluate(() => document.body.innerText)).includes("What can I cook tonight with paneer?"), "Options.question → sent as the first message");
adb("shell input keyevent 4");

console.log(`\n${pass} passed, ${fail} failed`);
try { await device.close(); } catch (e) {}
process.exit(fail ? 1 : 0);
