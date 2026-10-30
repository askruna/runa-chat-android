// End-to-end test of RunaProductView (1.2.0) on an emulator: the sample's product screen with Ask Quicklly's bundle
// under the product details, driven inside the view's WebView (debug builds enable WebView debugging), native screens
// checked through adb, the callbacks in logcat (tags RunaSample / RunaChat). The sample is a tester build
// (Options.preview(true)): the view shows before bundles are live for shoppers.
// The pages can come from the developer's laptop before they are live:
//   (cd runa_web/build-widget && python3 -m http.server 5521) ; adb reverse tcp:5521 tcp:5521
//   PAGE_BASE=http://quicklly.localhost:5521/ node tests/e2e-product.mjs      (default: the live pages)
import { _android as android } from "playwright";
import { execSync } from "child_process";

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
const countLog = (re) => log().split("\n").filter((l) => re.test(l)).length;
const top = () => (adb("shell dumpsys activity activities").match(/topResumedActivity=\S+ \S+ \S+\/(\S+)/) || [])[1] || "";
async function waitTop(re, ms = 20000) { const end = Date.now() + ms; while (Date.now() < end) { if (re.test(top())) return true; await sleep(400); } return false; }
let device = null;
async function pageWith(part) {
  for (let i = 0; i < 60; i++) {
    try { if (device) await device.close(); } catch (e) {}
    device = (await android.devices())[0];
    for (const wv of device.webViews()) {
      if (wv.pkg() !== PKG) continue;
      try {
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
const screen = () => { const m = adb("shell wm size").match(/(\d+)x(\d+)/); return { w: +m[1], h: +m[2] }; };

const localPort = (PAGE_BASE.match(/^http:\/\/[^/]*localhost:(\d+)/) || [])[1];
if (localPort) adb(`reverse tcp:${localPort} tcp:${localPort}`);
adb("logcat -c");
adb(`shell am force-stop ${PKG}`);
adb(`shell pm clear ${PKG}`);
await sleep(3000);
const extras = `--es product "1013592\\|113189\\|Okra\\ \\(bhindi\\)\\ 1\\ Lbs"` + (PAGE_BASE ? ` --es page_base ${PAGE_BASE}` : "");
let opened = false;
for (let attempt = 1; attempt <= 4 && !opened; attempt++) {
  adb(`shell am start -n ${PKG}/.MainActivity ${extras}`);
  opened = await waitTop(/ProductActivity/, 60000);
  if (!opened) { console.log(`      (start attempt ${attempt} did not reach the product screen, retrying)`); adb(`shell am force-stop ${PKG}`); await sleep(3000); }
}
check(opened, "the app's product screen opened (okra, store 113189)");
let page = await pageWith("product-app.html");
const q = await page.evaluate(() => Object.fromEntries(new URLSearchParams(location.search)));
check(q.pid === "1013592" && q.sid === "113189" && q.title === "Okra (bhindi) 1 Lbs" && q.preview === "1" && q.zip === "60610" && q.platform === "android",
  `page URL from show(): pid=${q.pid} sid=${q.sid} title=${q.title} preview=${q.preview} zip=${q.zip} platform=${q.platform}`);
check(!!(await waitLog(/product page→app ready/)), "product page said ready");
await page.waitForSelector(".runa-pb .runa-c__pcard", { timeout: 60000 }).catch(() => {});
await sleep(1500);
const heading = ((await page.locator(".runa-pb .runa-pdp__title").textContent().catch(() => "")) || "").trim();
const n = await page.locator(".runa-pb .runa-c__pcard").count();
check(heading === "Recipes" && n >= 2, `bundle: "${heading}" with ${n} products`);
const hLog = await waitLog(/product view height (\d+)/);
const reported = hLog ? +hLog.match(/height (\d+)/)[1] : 0;
const css = await page.evaluate(() => document.getElementById("root_quicklly_chat").getBoundingClientRect().height);
const dpr = await page.evaluate(() => window.devicePixelRatio);
check(reported > 0 && Math.abs(reported - Math.ceil(css * dpr)) <= Math.ceil(dpr) + 1, `native view height ${reported} px = page ${Math.ceil(css)} css px × ${dpr}`);

// the Ask Quicklly line: below the fold at first (the product photo above it) — not typed; scrolled to the middle of
// the screen — typed once (the view sends `viewport`)
const before = await page.evaluate(() => ({ typing: document.querySelector(".runa-pb__line").dataset.typing, done: document.querySelector(".runa-pb__line").dataset.done, key: localStorage.getItem("runaAskLineTyped") }));
check(before.done === "0" && !before.key, "the line below the fold: nothing typed yet");
const s = screen();
for (let i = 0; i < 6; i++) {
  const r = await page.evaluate(() => { const b = document.querySelector(".runa-pb__line"); return b.dataset.typing === "1" || b.dataset.done === "1"; });
  if (r) break;
  adb(`shell input swipe ${s.w >> 1} ${Math.round(s.h * 0.7)} ${s.w >> 1} ${Math.round(s.h * 0.55)} 400`);
  await sleep(1500);
}
const typed = await page.waitForFunction(() => document.querySelector(".runa-pb__line").dataset.done === "1", null, { timeout: 30000 }).then(() => true).catch(() => false);
const key = await page.evaluate(() => localStorage.getItem("runaAskLineTyped"));
const line = await page.evaluate(() => document.querySelector(".runa-pb__line-q").textContent.replace(/​/g, ""));
check(typed && !!key && line === "Plan meals, build your cart", `scrolled into view → the line typed once and settled on "${line}"`);
await device.screenshot({ path: `${SHOTS}/android-product-bundle.png` }).catch(() => {});

// "Add all" → one setQuantity per card, the button from the cart snapshots
const sqBefore = countLog(/setQuantity pid=/);
await page.locator(".runa-pb__all").click();
await sleep(3000);
const sqLines = log().split("\n").filter((l) => /setQuantity pid=/.test(l)).slice(sqBefore);
check(sqLines.length === n && sqLines.every((l) => /sid=113189 qty=1/.test(l)), `"Add all" → ${sqLines.length} setQuantity calls (store 113189, qty 1)`);
const label = (await page.locator(".runa-pb__all").innerText()).trim();
check(/^✓ All \d+ in your cart$/.test(label), `button from the cart snapshots: "${label}"`);

// a card → openProduct (the app's product screen)
await page.locator(".runa-pb .runa-c__pcard-title").first().click();
check(!!(await waitLog(/openProduct pid=\d+ sid=113189/)), "card tap → openProduct with the store");
adb("shell input keyevent KEYCODE_BACK");
await waitTop(/ProductActivity/, 10000);

// the Ask Quicklly line → the chat screen with its question, credited to the product page
page = await pageWith("product-app.html");
await page.locator(".runa-pb__line").click({ position: { x: 30, y: 12 } });
const chatOpen = await waitTop(/RunaChatActivity/, 20000);
let chatQ = {};
if (chatOpen) { const chat = await pageWith("chat-app.html"); chatQ = await chat.evaluate(() => Object.fromEntries(new URLSearchParams(location.search))); }
check(chatOpen && chatQ.q === "Plan a meal with Okra (bhindi) 1 Lbs" && chatQ.entry === "product_page", `the line → chat screen q="${chatQ.q}" entry=${chatQ.entry}`);
adb("shell input keyevent KEYCODE_BACK");
await sleep(1500);

// notifyCartChanged reaches the product view (the product screen's own +1 button)
check(/ProductActivity/.test(top()), "back on the product screen");
console.log(`\n${pass} passed, ${fail} failed`);
try { await device.close(); } catch (e) {}
process.exit(fail ? 1 : 0);
