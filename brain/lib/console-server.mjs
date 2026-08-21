// Lore Console: a tiny local (or LAN-bound) HTTP page for the operator to
// type free-text directives - "add a ruined tower somewhere in the eastern
// mountains with a hermit who knows about the old war" - that the DIRECTOR
// must fold into the world without anyone touching files by hand.
//
// node:http only, no new runtime dependency. Directives are appended as
// dated blocks to brain/lore/90-operator-directives.md (the 90- prefix
// sorts it LAST among lore/*.md, so operator directives land at the end of
// the director's system prompt - see lib/lore.mjs's loadLore, which
// concatenates lore/*.md sorted by filename). After every write, the same
// reload the lore watcher uses on its timer (lib/lore.mjs's watchLore
// `tick`) is invoked immediately, so the NEXT scene already carries it
// instead of waiting up to BRAIN_LORE_REFRESH_MS.
//
// Auth: a single shared token (BRAIN_CONSOLE_TOKEN, default
// MCALIVE2_TOKEN). Accepted as ?token=... on first visit, then set as an
// HttpOnly cookie so the page's own fetch() calls (same-origin, default
// credentials) carry it automatically afterward. Anything without a valid
// token gets 401 - this matters because the operator may choose to bind
// this to 0.0.0.0 to reach it from another machine on the LAN.

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { log } from "./logger.mjs";

const COOKIE_NAME = "brain_console_token";
const DIRECTIVES_HEADER =
  "# Operator Directives — instructions from the world's owner. These override taste but never the standing rules in 00-rules.md.\n";

// ---------------- directives file (lore/90-operator-directives.md) ----------------

function directivesPathFor(config) {
  return path.join(config.loreDir, "90-operator-directives.md");
}

function readRaw(filePath) {
  try {
    return fs.readFileSync(filePath, "utf8");
  } catch {
    return null;
  }
}

/** Parse "## <timestamp>\n<text>\n" blocks out of the directives file body
 * (everything after the header comment), newest-appended-last in the file. */
function parseBlocks(content) {
  if (!content) return [];
  const headerRe = /^## (.+)$/gm;
  const starts = [];
  let m;
  while ((m = headerRe.exec(content))) {
    starts.push({ timestamp: m[1].trim(), start: m.index, headerEnd: m.index + m[0].length });
  }
  const blocks = [];
  for (let i = 0; i < starts.length; i++) {
    const end = i + 1 < starts.length ? starts[i + 1].start : content.length;
    const text = content.slice(starts[i].headerEnd, end).trim();
    blocks.push({ timestamp: starts[i].timestamp, text });
  }
  return blocks;
}

function writeBlocks(filePath, blocks) {
  const body = blocks.map((b) => `## ${b.timestamp}\n${b.text}\n`).join("\n");
  fs.writeFileSync(filePath, DIRECTIVES_HEADER + "\n" + body);
}

/** Appends one directive block, creating the file (with its header comment)
 * on first use. Returns the ISO timestamp assigned to the new block. */
export function appendDirective(config, text) {
  const filePath = directivesPathFor(config);
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const existing = parseBlocks(readRaw(filePath));
  const timestamp = new Date().toISOString();
  existing.push({ timestamp, text: text.trim() });
  writeBlocks(filePath, existing);
  return timestamp;
}

/** Removes the block with this exact timestamp. Returns whether anything was removed. */
export function deleteDirective(config, timestamp) {
  const filePath = directivesPathFor(config);
  const existing = parseBlocks(readRaw(filePath));
  const remaining = existing.filter((b) => b.timestamp !== timestamp);
  if (remaining.length === existing.length) return false;
  writeBlocks(filePath, remaining);
  return true;
}

export function listDirectives(config) {
  return parseBlocks(readRaw(directivesPathFor(config))).reverse(); // newest first
}

// ---------------- orders file (state/orders.json) ----------------
//
// Orders are one-shot commands the director must EXECUTE now ("strike spawn
// with lightning 100 times then build a floating village"), as opposed to
// directives, which are permanent taste folded into lore. An order never
// touches lore/ - it becomes a single "operator_order" scene event (see
// index.mjs's submitOrder), and is only ever persisted here as an operator-
// facing log of what was sent, rolling at the last 50 entries.

const MAX_ORDERS = 50;

function ordersPathFor(config) {
  return path.join(config.stateDir, "orders.json");
}

function readOrders(config) {
  const raw = readRaw(ordersPathFor(config));
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeOrders(config, orders) {
  const filePath = ordersPathFor(config);
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(orders, null, 1));
}

/** Appends one order (status "queued"), rolling at MAX_ORDERS (oldest
 * dropped first). Returns the ISO timestamp assigned to the new entry. */
export function appendOrder(config, text) {
  const existing = readOrders(config);
  const timestamp = new Date().toISOString();
  existing.push({ timestamp, text: text.trim(), status: "queued" });
  const rolled = existing.slice(-MAX_ORDERS);
  writeOrders(config, rolled);
  return timestamp;
}

export function listOrders(config) {
  return readOrders(config).slice().reverse(); // newest first
}

/** Orders with status "queued", oldest first (file order) - used by
 * index.mjs on boot to re-push every order that was still pending when the
 * process last stopped. The order text/status survive a restart on disk;
 * the in-memory scheduler push that would have carried a "queued" one into
 * a scene does not, so without this a queued order silently dies with the
 * process that received it (DESIGN.md gap this closes). */
export function queuedOrders(config) {
  return readOrders(config).filter((o) => o && o.status === "queued");
}

/** Sets the status of the order matching this exact timestamp. No-op
 * (returns false) if no such order exists, e.g. it already rolled off
 * MAX_ORDERS. Used by index.mjs to mark an order "done" once its
 * operator_order scene completes, or revert it to "queued" if that scene
 * timed out (lib/timed-query.mjs), so the next restart retries it via
 * queuedOrders() above. */
export function setOrderStatus(config, timestamp, status) {
  const existing = readOrders(config);
  const order = existing.find((o) => o.timestamp === timestamp);
  if (!order) return false;
  order.status = status;
  writeOrders(config, existing);
  return true;
}

// ---------------- state/decisions.log tail ----------------

function tailLines(filePath, n) {
  const content = readRaw(filePath);
  if (!content) return "";
  const lines = content.split("\n");
  if (lines[lines.length - 1] === "") lines.pop(); // trailing newline
  return lines.slice(-n).join("\n");
}

// ---------------- auth ----------------

function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(";")) {
    const idx = part.indexOf("=");
    if (idx === -1) continue;
    const k = part.slice(0, idx).trim();
    if (k) out[k] = decodeURIComponent(part.slice(idx + 1).trim());
  }
  return out;
}

/** { ok, setCookie } - setCookie is true only the moment a query-param
 * token is what authenticated the request (i.e. "first visit"), so the
 * cookie only ever gets (re)set when it was actually needed. */
function checkAuth(req, url, token) {
  const cookies = parseCookies(req.headers.cookie);
  if (cookies[COOKIE_NAME] && cookies[COOKIE_NAME] === token) return { ok: true, setCookie: false };
  if (url.searchParams.get("token") === token) return { ok: true, setCookie: true };
  return { ok: false, setCookie: false };
}

function unauthorizedPage() {
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>Lore Console</title></head>
<body style="background:#14161a;color:#e6e6e6;font-family:system-ui,sans-serif;padding:3rem;">
<h1>Lore Console</h1>
<p>Missing or invalid token. Append <code>?token=YOUR-TOKEN</code> to the URL.</p>
</body></html>`;
}

// ---------------- HTML page ----------------

function escapeHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function renderPage(directives, orders) {
  const items = directives
    .map(
      (b) => `
      <li class="directive">
        <div class="directive-head">
          <span class="ts">${escapeHtml(b.timestamp)}</span>
          <button class="del" data-ts="${escapeHtml(b.timestamp)}">delete</button>
        </div>
        <pre class="directive-text">${escapeHtml(b.text)}</pre>
      </li>`
    )
    .join("\n");

  const orderItems = orders
    .map(
      (o) => `
      <li class="order">
        <div class="order-head">
          <span class="ts">${escapeHtml(o.timestamp)}</span>
          <span class="order-status">${escapeHtml(o.status)}</span>
        </div>
        <pre class="order-text">${escapeHtml(o.text)}</pre>
      </li>`
    )
    .join("\n");

  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lore Console</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    background: #14161a; color: #e6e6e6; margin: 0; padding: 2rem;
    font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  }
  h1 { font-size: 1.3rem; margin: 0 0 .25rem; }
  h1 .maplink { font-size: .75rem; font-weight: normal; color: #7fa8ff; text-decoration: none; margin-left: .75rem; vertical-align: middle; }
  h1 .maplink:hover { text-decoration: underline; }
  .sub { color: #9aa0a8; margin: 0 0 1.5rem; font-size: .9rem; }
  main { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 2rem; }
  textarea {
    width: 100%; min-height: 8rem; background: #1c1f24; color: #e6e6e6;
    border: 1px solid #33373e; border-radius: 6px; padding: .75rem;
    font: inherit; font-size: .95rem; resize: vertical;
  }
  button {
    background: #3a6ff7; color: white; border: none; border-radius: 6px;
    padding: .6rem 1.1rem; font-size: .9rem; cursor: pointer;
  }
  button:hover { background: #5081f8; }
  button.del {
    background: #2a2d33; color: #c98; padding: .25rem .6rem; font-size: .75rem;
  }
  button.del:hover { background: #3a2d33; }
  #status, #order-status-line { margin-left: .75rem; color: #9aa0a8; font-size: .85rem; }

  /* Status strip: always-visible budget/kill-switch summary polled from
     GET /status (see lib/console-server.mjs's getStatus dep) - the "is the
     world actually doing anything" answer an operator previously had to
     dig for in decisions.log. */
  .status-strip {
    display: flex; flex-wrap: wrap; align-items: center; gap: .75rem;
    font-size: .8rem; color: #9aa0a8; margin: .6rem 0 0;
  }
  .status-strip b { color: #e6e6e6; }
  #reset-budget-btn {
    display: none; background: #a83232; padding: .3rem .7rem; font-size: .75rem;
  }
  #reset-budget-btn:hover { background: #c23e3e; }
  .world-inert-banner {
    display: none; background: #3a1a1a; color: #ffb0b0; border: 1px solid #6a2a2a;
    border-radius: 6px; padding: .6rem .9rem; margin: .75rem 0 0; font-size: .85rem;
  }
  section h2 { font-size: .95rem; color: #9aa0a8; text-transform: uppercase; letter-spacing: .04em; margin: 0 0 .75rem; }
  .caption { color: #6b7078; margin: -.4rem 0 .6rem; font-size: .8rem; }
  ul.directives, ul.orders { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .75rem; }
  li.directive { background: #1c1f24; border: 1px solid #33373e; border-radius: 6px; padding: .6rem .8rem; }
  .directive-head { display: flex; justify-content: space-between; align-items: center; }
  .ts { color: #7fa8ff; font-size: .8rem; font-family: ui-monospace, monospace; }
  .directive-text { white-space: pre-wrap; margin: .4rem 0 0; font-family: inherit; }
  .empty { color: #6b7078; font-style: italic; }

  /* Orders: visually distinct from directives (one-shot command vs.
     permanent taste) - warm/amber accent instead of the directive's blue,
     and its own section styling so the two forms are never confused. */
  section.order-section {
    border: 1px solid #7a4a1a; border-radius: 8px; padding: 1rem; background: #201a12;
  }
  section.order-section h2 { color: #e0a458; }
  textarea#order-text {
    border-color: #7a4a1a; background: #241c14;
  }
  button#send-order {
    background: #d97a1f;
  }
  button#send-order:hover { background: #ea8b2c; }
  li.order { background: #1c1712; border: 1px solid #4a3218; border-radius: 6px; padding: .6rem .8rem; }
  .order-head { display: flex; justify-content: space-between; align-items: center; }
  .order-status { color: #e0a458; font-size: .75rem; text-transform: uppercase; letter-spacing: .03em; }
  .order-text { white-space: pre-wrap; margin: .4rem 0 0; font-family: inherit; }
  pre#decisions {
    background: #0f1114; border: 1px solid #33373e; border-radius: 6px;
    padding: .75rem; font-size: .8rem; line-height: 1.4; max-height: 24rem;
    overflow: auto; white-space: pre-wrap; font-family: ui-monospace, monospace;
  }
</style>
</head>
<body>
<main>
  <div>
    <h1>Lore Console <a class="maplink" href="/map">3D map &rarr;</a></h1>
    <p class="sub">Type an instruction for the director to fold into the world. It takes effect on the next scene.</p>
    <div class="status-strip" id="status-strip">
      <span id="status-budget">budget: loading&hellip;</span>
      <span id="status-killswitch"></span>
      <button id="reset-budget-btn">Reset budget</button>
    </div>
    <div class="world-inert-banner" id="status-banner"></div>
  </div>

  <section>
    <p class="caption">Directives are permanent taste - they land in lore and quietly shape every future scene.</p>
    <textarea id="text" placeholder="e.g. Add a ruined tower somewhere in the eastern mountains with a hermit who knows about the old war."></textarea>
    <div style="margin-top:.6rem;">
      <button id="send">Send to the world</button>
      <span id="status"></span>
    </div>
  </section>

  <section>
    <h2>Past directives</h2>
    <ul class="directives" id="directives">
      ${items || '<li class="empty">No directives yet.</li>'}
    </ul>
  </section>

  <section class="order-section">
    <h2>Order the world</h2>
    <p class="caption">Orders are one-shot commands the director must carry out right now - not lore, executed immediately as a scene.</p>
    <textarea id="order-text" placeholder="e.g. Strike spawn with lightning 100 times, then build a floating village."></textarea>
    <div style="margin-top:.6rem;">
      <button id="send-order">Order the world</button>
      <span id="order-status-line"></span>
    </div>
  </section>

  <section>
    <h2>Recent orders</h2>
    <ul class="orders" id="orders">
      ${orderItems || '<li class="empty">No orders yet.</li>'}
    </ul>
  </section>

  <section>
    <h2>Decisions log (live)</h2>
    <pre id="decisions">loading…</pre>
  </section>
</main>

<script>
async function sendDirective() {
  const el = document.getElementById('text');
  const status = document.getElementById('status');
  const text = el.value.trim();
  if (!text) return;
  status.textContent = 'sending…';
  const res = await fetch('/directive', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (res.ok) {
    el.value = '';
    status.textContent = 'sent';
    setTimeout(() => location.reload(), 400);
  } else {
    status.textContent = 'failed';
  }
}

async function deleteDirective(ts) {
  const res = await fetch('/directive/delete', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ timestamp: ts }),
  });
  if (res.ok) location.reload();
}

document.getElementById('send').addEventListener('click', sendDirective);
document.getElementById('directives').addEventListener('click', (e) => {
  const btn = e.target.closest('button.del');
  if (btn) deleteDirective(btn.dataset.ts);
});

async function sendOrder() {
  const el = document.getElementById('order-text');
  const status = document.getElementById('order-status-line');
  const text = el.value.trim();
  if (!text) return;
  status.textContent = 'sending…';
  const res = await fetch('/order', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (res.ok) {
    el.value = '';
    status.textContent = 'sent';
    setTimeout(() => location.reload(), 400);
  } else {
    status.textContent = 'failed';
  }
}

document.getElementById('send-order').addEventListener('click', sendOrder);

async function refreshDecisions() {
  try {
    const res = await fetch('/decisions');
    if (res.ok) document.getElementById('decisions').textContent = (await res.text()) || '(empty)';
  } catch {}
}

// Status strip: budget used/limit as a percentage, plus a prominent banner
// when the world is not currently able to act (budget exhausted or the kill
// switch is on) and why - polled on the same cadence as refreshDecisions()
// above so an operator watching this page always has a current answer.
function renderStatus(s) {
  var pct = s.budget.limit > 0 ? Math.round((s.budget.used / s.budget.limit) * 100) : 0;
  document.getElementById('status-budget').innerHTML =
    'budget: <b>' + s.budget.used + '</b> / ' + s.budget.limit + ' (' + pct + '%)';
  document.getElementById('status-killswitch').textContent = s.killSwitch ? 'kill switch: ON' : '';
  document.getElementById('reset-budget-btn').style.display = s.budget.exhausted ? 'inline-block' : 'none';

  var banner = document.getElementById('status-banner');
  if (s.budget.exhausted || s.killSwitch) {
    var reason = s.budget.exhausted
      ? 'the daily token budget is exhausted (' + s.budget.used + '/' + s.budget.limit + ' tokens). It resets at ' + s.budget.resetsAt + '.'
      : 'the kill switch is active.';
    banner.textContent = 'The world is NOT currently acting: ' + reason;
    banner.style.display = 'block';
  } else {
    banner.style.display = 'none';
  }
}

async function refreshStatus() {
  try {
    const res = await fetch('/status');
    if (res.ok) renderStatus(await res.json());
  } catch {}
}

document.getElementById('reset-budget-btn').addEventListener('click', async function () {
  if (!confirm("Reset today's token budget counter and let the world start acting again?")) return;
  await fetch('/budget/reset', { method: 'POST' });
  refreshStatus();
});

refreshDecisions();
refreshStatus();
setInterval(refreshDecisions, 5000);
setInterval(refreshStatus, 5000);
</script>
</body>
</html>`;
}

// ---------------- 3D world map page (GET /map) ----------------
//
// A second, independent page hitting GET /worldmodel (entities, places,
// diagnostics, heightmap fallback) and GET /voxels (true cubic-voxel
// terrain, tiled by cursor) - both same-origin, so the
// cookie set by checkAuth() above already authenticates its fetch() calls
// exactly like the / page's own fetch()es). Entirely self-contained: no
// external/CDN resources, no npm deps. Terrain renders through raw WebGL
// (depth-buffered voxel cubes, see the "WebGL terrain renderer" section);
// entities/labels/picking draw on a 2D overlay canvas sharing the same
// hand-rolled orthographic projection, in the same dark theme as /.

function renderMapPage() {
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lore Console — 3D Map</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  html, body {
    background: #14161a; color: #e6e6e6; margin: 0; padding: 0; height: 100%;
    font-family: system-ui, -apple-system, "Segoe UI", sans-serif; overflow: hidden;
  }
  header {
    display: flex; align-items: center; gap: 1rem; padding: .6rem 1rem;
    border-bottom: 1px solid #33373e; background: #1c1f24;
  }
  header h1 { font-size: 1.05rem; margin: 0; }
  header a { color: #7fa8ff; text-decoration: none; font-size: .85rem; }
  header a:hover { text-decoration: underline; }
  #counts { font-size: .8rem; color: #9aa0a8; display: flex; gap: 1rem; flex-wrap: wrap; }
  #counts b { color: #e6e6e6; }
  .spacer { flex: 1; }
  button { background: #3a6ff7; color: white; border: none; border-radius: 6px; padding: .4rem .9rem; font-size: .8rem; cursor: pointer; }
  button:hover { background: #5081f8; }
  label.toggle { font-size: .8rem; color: #9aa0a8; display: flex; align-items: center; gap: .35rem; cursor: pointer; }
  #layout { display: flex; height: calc(100% - 48px); }
  #canvas-wrap { position: relative; flex: 1; min-width: 0; background: #0f1114; }
  #canvas-wrap canvas { display: block; position: absolute; top: 0; left: 0; }
  #gl { z-index: 0; }
  #c { z-index: 1; background: transparent; cursor: grab; }
  #c.dragging { cursor: grabbing; }
  #banner {
    position: absolute; top: 0; left: 0; right: 0; padding: .6rem 1rem; font-size: .85rem;
    background: #3a1a1a; color: #ffb0b0; border-bottom: 1px solid #6a2a2a; display: none;
  }
  #banner a { color: #ffd0d0; }
  /* Status strip + world-inert banner: same GET /status polling as the main
     console page (/) - see its own comment for why this exists. */
  .status-strip { display: flex; align-items: center; gap: .6rem; font-size: .78rem; color: #9aa0a8; }
  .status-strip b { color: #e6e6e6; }
  #reset-budget-btn-map {
    display: none; background: #a83232; color: white; border: none; border-radius: 6px;
    padding: .3rem .7rem; font-size: .75rem; cursor: pointer;
  }
  #reset-budget-btn-map:hover { background: #c23e3e; }
  #world-inert-banner {
    display: none; background: #3a1a1a; color: #ffb0b0; border-bottom: 1px solid #6a2a2a;
    padding: .5rem 1rem; font-size: .82rem;
  }
  #hover-coord {
    position: absolute; left: .6rem; bottom: .5rem; font-size: .75rem; color: #7f858c;
    font-family: ui-monospace, monospace; pointer-events: none;
  }
  #empty-notes {
    position: absolute; top: .6rem; left: .6rem; font-size: .75rem; color: #6b7078;
    display: flex; flex-direction: column; gap: .15rem; pointer-events: none;
  }
  aside {
    width: 300px; flex-shrink: 0; border-left: 1px solid #33373e; background: #1c1f24;
    display: flex; flex-direction: column; overflow: hidden;
  }
  aside section { padding: .75rem .85rem; border-bottom: 1px solid #2a2d33; overflow: auto; }
  aside h2 { font-size: .75rem; color: #9aa0a8; text-transform: uppercase; letter-spacing: .04em; margin: 0 0 .5rem; }
  #info-panel { min-height: 4.5rem; font-size: .82rem; line-height: 1.4; }
  #info-panel .empty { color: #6b7078; font-style: italic; }
  #info-panel .flag { display: inline-block; background: #3a1a1a; color: #ffb0b0; border-radius: 4px; padding: 0 .35rem; margin: .1rem .2rem 0 0; font-size: .72rem; }
  #problems { flex: 1; min-height: 0; }
  ul#problem-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .4rem; }
  li.problem {
    border-left: 3px solid #7fa8ff; background: #16181c; border-radius: 4px; padding: .4rem .55rem;
    cursor: pointer; font-size: .78rem; line-height: 1.35;
  }
  li.problem:hover { background: #20242b; }
  li.problem.sev-error { border-left-color: #ff5555; }
  li.problem.sev-warn { border-left-color: #e0a458; }
  li.problem.sev-info { border-left-color: #7fa8ff; }
  li.problem .sev { text-transform: uppercase; font-size: .65rem; letter-spacing: .04em; color: #9aa0a8; }
  li.problem .kind { color: #c9cdd3; font-weight: 600; }
  li.problem .msg { color: #b7bbc2; margin-top: .15rem; }
  #legend { font-size: .72rem; color: #9aa0a8; display: flex; flex-direction: column; gap: .25rem; }
  #legend .row { display: flex; align-items: center; gap: .4rem; }
  #legend .swatch { width: .7rem; height: .7rem; border-radius: 2px; display: inline-block; }
</style>
</head>
<body>
<header>
  <h1>3D Map</h1>
  <a href="/">&larr; Lore Console</a>
  <div id="counts">loading&hellip;</div>
  <div class="status-strip" id="status-strip">
    <span id="status-budget">budget: loading&hellip;</span>
    <span id="status-killswitch"></span>
    <button id="reset-budget-btn-map">Reset budget</button>
  </div>
  <div class="spacer"></div>
  <label class="toggle"><input type="checkbox" id="auto-refresh"> auto-refresh (10s)</label>
  <button id="refresh">Refresh</button>
</header>
<div id="world-inert-banner"></div>
<div id="layout">
  <div id="canvas-wrap">
    <div id="banner"></div>
    <canvas id="gl"></canvas>
    <canvas id="c"></canvas>
    <div id="empty-notes"></div>
    <div id="hover-coord"></div>
  </div>
  <aside>
    <section>
      <h2>Selected</h2>
      <div id="info-panel"><span class="empty">Hover or click a place/NPC marker.</span></div>
    </section>
    <section>
      <h2>Legend</h2>
      <div id="legend">
        <div class="row"><span class="swatch" style="background:#7fa8ff"></span> place built by AI</div>
        <div class="row"><span class="swatch" style="background:#e0a458"></span> place built by player</div>
        <div class="row"><span class="swatch" style="background:#9aa0a8"></span> place built by world/other</div>
        <div class="row"><span class="swatch" style="background:#4caf6b"></span> NPC alive</div>
        <div class="row"><span class="swatch" style="background:#6b7078"></span> NPC dead</div>
        <div class="row"><span class="swatch" style="background:#ff6a3d"></span> NPC has a flag</div>
      <div class="row"><span class="swatch" style="background:#ffcf5c"></span> world spawn</div>
      <div class="row"><span class="swatch" style="background:#5cc8ff"></span> player online</div>
      <div class="row" style="color:#6b7078">terrain: true cubic voxels rendered on the GPU (WebGL, exact occlusion from every angle) — solid blocks within 3 of air; coloured by block type, grey/green by height otherwise. Falls back to coarse heightmap cubes while voxels load.</div>
      </div>
    </section>
    <section id="problems">
      <h2>Problems (worst first)</h2>
      <ul id="problem-list"></ul>
    </section>
  </aside>
</div>

<script>
// =========================================================================
// SECTION: state
// =========================================================================
var worldmodel = null;       // last GET /worldmodel response
var camera = {                // orbit camera - yaw/pitch in radians, scale = px per world unit
  yaw: 0.7, pitch: 0.55, scale: 4,
  target: { x: 0, y: 64, z: 0 }, // world point the camera orbits/pans around
};
var hitTargets = [];         // rebuilt every draw(): [{sx,sy,r,kind,obj}]
var lastMouse = null;        // {x,y} in canvas-local pixels, for the coord readout
var focusPoint = null;       // {x,y,z,until} - a transient highlight ring, see focusOn()
var autoRefreshTimer = null;
var hoveredKey = null;       // "place:<id>" / "npc:<id>" of the currently hovered marker

// TRUE cubic-voxel terrain, fetched from GET /voxels (gadget:world-scan's
// voxel mode: every solid block within Chebyshev distance 3 of air, so
// building interiors, overhangs, and near-surface caves render as real
// cubes). Tiled by cursor until nextCursor is null; static, so re-fetched
// at most every VOXEL_REFRESH_MS (entities keep their own live cadence).
// The raw voxel list is turned into a WebGL vertex buffer ONCE per fetch
// (see buildVoxelGeometry/uploadVoxelGeometry) and then discarded - only
// summary stats survive here: { mats:[name], count, faceCount, minY, maxY,
// partial }. null until the first successful fetch, in which case draw()
// falls back to the pre-existing heightmap terrain cubes on the overlay.
var voxels = null;
var voxelFetchedAt = 0;
var voxelFetching = false;
var VOXEL_REFRESH_MS = 10000;
var VOXEL_MAX_PAGES = 200;    // tiling safety cap (pages per refresh)
var VOXEL_MAX_TOTAL = 600000; // aggregate voxel cap (GPU-rendered, so generous)

var canvas = document.getElementById('c');
var ctx = canvas.getContext('2d');

// =========================================================================
// SECTION: camera / projection
// -------------------------------------------------------------------------
// Hand-rolled orthographic projection: rotate the world by -yaw around Y,
// then by -pitch around the resulting X axis, then drop Z (used only for
// painter's-algorithm depth sorting) and scale X/Y to screen pixels.
// unproject() inverts this assuming the point lies on the y = target.y
// plane, used only for the "coordinate under the cursor" readout.
// =========================================================================
function project(wx, wy, wz) {
  var x = wx - camera.target.x, y = wy - camera.target.y, z = wz - camera.target.z;
  var cosY = Math.cos(camera.yaw), sinY = Math.sin(camera.yaw);
  var x1 = x * cosY - z * sinY;
  var z1 = x * sinY + z * cosY;
  var cosP = Math.cos(camera.pitch), sinP = Math.sin(camera.pitch);
  var y2 = y * cosP - z1 * sinP;
  var z2 = y * sinP + z1 * cosP; // depth: larger = farther from the viewer
  return {
    sx: canvas.width / 2 + x1 * camera.scale,
    sy: canvas.height / 2 - y2 * camera.scale,
    depth: z2,
  };
}

// Inverse-projects a screen pixel onto the y = target.y plane. Returns null
// where the view is too edge-on (sin(pitch) ~ 0) for a stable answer.
function unproject(sx, sy) {
  var sinP = Math.sin(camera.pitch), cosP = Math.cos(camera.pitch);
  if (Math.abs(sinP) < 0.05) return null;
  var x1 = (sx - canvas.width / 2) / camera.scale;
  var y2 = (canvas.height / 2 - sy) / camera.scale;
  var z1 = -y2 / sinP; // solved from y2 = 0*cosP - z1*sinP (world y == target.y)
  var cosY = Math.cos(camera.yaw), sinY = Math.sin(camera.yaw);
  var x = x1 * cosY + z1 * sinY;
  var z = -x1 * sinY + z1 * cosY;
  return { x: x + camera.target.x, z: z + camera.target.z };
}

// Approximate screen-space right/up vectors at the current yaw/pitch, used
// only to turn a drag delta into a world-space pan.
function cameraRightUp() {
  var right = { x: Math.cos(camera.yaw), y: 0, z: -Math.sin(camera.yaw) };
  var up = {
    x: Math.sin(camera.yaw) * Math.sin(camera.pitch),
    y: Math.cos(camera.pitch),
    z: Math.cos(camera.yaw) * Math.sin(camera.pitch),
  };
  return { right: right, up: up };
}

// =========================================================================
// SECTION: meshes - turns worldmodel into a flat list of paintable
// primitives ({depth, draw}) plus hitTargets for picking. Rebuilt on every
// draw() call, which only happens on interaction/refresh, never a rAF loop.
// =========================================================================
function colorForBuiltBy(builtBy) {
  if (builtBy === 'ai') return '#7fa8ff';
  if (builtBy === 'player') return '#e0a458';
  return '#9aa0a8'; // world / other / unset
}

function hasErrorFlag(flags) {
  return Array.isArray(flags) && flags.some(function (f) { return /error/i.test(String(f)); });
}

// =========================================================================
// SECTION: voxel/cube rendering helpers
// -------------------------------------------------------------------------
// The world is Minecraft, so terrain (and small entities) are drawn as
// blocky cubes rather than a smooth shaded surface: flat, untextured faces,
// no lighting model - just enough shading (top brightest, one visible side
// mid, the other darkest) for the cube form to read. Only the top face plus
// the two side faces the camera can actually see are ever drawn, chosen
// once per draw() from the camera's yaw (see computeSideFacing()) - the
// other two sides and the bottom are always occluded/off-screen for an
// orbit camera that never dips below the horizon, so skipping them is free
// correctness, not just a perf trick.
// =========================================================================

// Small lookup of common Minecraft blocks -> plausible flat top-face
// colours (as [r,g,b] arrays - cheaper to shade per-frame than parsing hex
// strings repeatedly in the hot terrain loop). Unmatched/null materials
// fall back to heightRampColor() below (the pre-existing grey/green ramp).
var MATERIAL_COLORS = {
  grass_block: [92, 156, 70],
  dirt: [134, 96, 60],
  coarse_dirt: [122, 88, 56],
  mud: [74, 64, 56],
  podzol: [92, 69, 48],
  moss_block: [91, 122, 52],
  stone: [138, 138, 138],
  cobblestone: [122, 122, 118],
  deepslate: [69, 69, 72],
  cobbled_deepslate: [76, 76, 80],
  netherrack: [117, 54, 52],
  sand: [220, 205, 143],
  red_sand: [201, 111, 58],
  sandstone: [216, 201, 138],
  gravel: [141, 133, 121],
  clay: [154, 163, 172],
  ice: [168, 212, 240],
  packed_ice: [150, 196, 232],
  blue_ice: [130, 176, 232],
  snow: [242, 246, 250],
  snow_block: [242, 246, 250],
  water: [58, 111, 216],
  terracotta: [165, 87, 58],
  bedrock: [46, 46, 50],
  obsidian: [26, 21, 38],
  lava: [207, 92, 20],
  andesite: [130, 132, 130],
  diorite: [188, 188, 190],
  granite: [154, 106, 90],
  tuff: [108, 110, 102],
  calcite: [223, 224, 220],
  dripstone_block: [134, 107, 92],
  stone_bricks: [118, 118, 122],
  mossy_stone_bricks: [104, 118, 96],
  mossy_cobblestone: [104, 116, 96],
  bricks: [150, 97, 83],
  bookshelf: [162, 130, 78],
  crafting_table: [140, 106, 62],
  furnace: [110, 110, 110],
  glowstone: [252, 218, 120],
  sea_lantern: [200, 232, 224],
  hay_block: [198, 168, 44],
  pumpkin: [214, 126, 32],
  melon: [116, 168, 48],
  farmland: [110, 78, 48],
  dirt_path: [148, 120, 68],
  mycelium: [110, 96, 106],
  soul_sand: [82, 62, 50],
  soul_soil: [76, 58, 46],
  basalt: [74, 74, 78],
  blackstone: [42, 38, 44],
  end_stone: [220, 222, 158],
  prismarine: [96, 156, 140],
  magma_block: [142, 62, 30],
  sponge: [196, 192, 76],
  slime_block: [112, 192, 92],
  honey_block: [232, 156, 44],
  bone_block: [210, 206, 178],
  quartz_block: [234, 228, 220],
  smooth_quartz: [234, 228, 220],
  coal_ore: [96, 96, 96],
  iron_ore: [150, 132, 116],
  copper_ore: [122, 122, 106],
  gold_ore: [158, 140, 92],
};

function colorForMaterial(material) {
  if (!material) return null;
  var m = String(material).toLowerCase().replace(/^minecraft:/, '');
  if (MATERIAL_COLORS[m]) return MATERIAL_COLORS[m];
  if (/_log$/.test(m) || /_wood$/.test(m) || /stripped_/.test(m) || m === 'log') return [107, 74, 44];
  if (/_leaves$/.test(m)) return [63, 122, 52];
  if (/_planks$/.test(m)) return [162, 130, 78];
  if (/glass/.test(m)) return [186, 214, 228];
  if (/_wool$|_carpet$|_bed$/.test(m)) return [206, 206, 206];
  if (/_concrete/.test(m)) return [178, 178, 178];
  if (/copper/.test(m)) return [186, 108, 70];
  if (/deepslate/.test(m)) return [72, 72, 76];
  if (/sandstone/.test(m)) return [216, 201, 138];
  if (/_ore$/.test(m)) return [128, 124, 118];
  if (/_stairs$|_slab$|_wall$|_fence/.test(m)) return [148, 132, 106];
  if (/door$|trapdoor$/.test(m)) return [140, 104, 58];
  if (/_coral/.test(m)) return [214, 108, 158];
  if (/terracotta$/.test(m)) return [165, 87, 58]; // dyed terracotta variants
  return null; // unknown material -> caller falls back to heightRampColor()
}

// Pre-existing grey/green height ramp, kept as the fallback for cells with
// no known material (large scans, where terrain.materials is null, or an
// unrecognized block name).
function heightRampColor(h, bounds) {
  var lowCol = [72, 92, 62], highCol = [150, 158, 128];
  var t = clamp((h - bounds.minY) / Math.max(1, bounds.maxY - bounds.minY), 0, 1);
  return [0, 1, 2].map(function (k) { return Math.round(lowCol[k] + (highCol[k] - lowCol[k]) * t); });
}

function shadeColor(rgb, factor) {
  return [0, 1, 2].map(function (k) { return clamp(Math.round(rgb[k] * factor), 0, 255); });
}

function rgbCss(rgb) { return 'rgb(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ')'; }

function hexToRgbArr(hex) {
  return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
}

// Which vertical side faces (one along X, one along Z) are front-facing to
// the camera, derived from the orbit camera's yaw (see project()'s header
// comment for the rotation convention). Pitch is ignored: cos(pitch) stays
// positive across the whole allowed pitch range (-1.45..1.45 rad), so it
// never flips which horizontal side faces the camera. Computed once per
// draw() call, not per cube.
function computeSideFacing() {
  return {
    x: Math.sin(camera.yaw) < 0 ? 'x2' : 'x1', // 'x2' = +X (east) face visible
    z: Math.cos(camera.yaw) < 0 ? 'z2' : 'z1', // 'z2' = +Z (south) face visible
  };
}

// Pushes one paintable quad (4 world-space corners, already in face-winding
// order) onto the given list, projecting them once and capturing only what
// draw() needs (no extra per-vertex allocation beyond the projected points
// themselves, which draw() requires anyway).
function pushQuad(list, corners, fillCss, strokeCss, lineWidth) {
  var pr = [project(corners[0].x, corners[0].y, corners[0].z),
    project(corners[1].x, corners[1].y, corners[1].z),
    project(corners[2].x, corners[2].y, corners[2].z),
    project(corners[3].x, corners[3].y, corners[3].z)];
  var depth = (pr[0].depth + pr[1].depth + pr[2].depth + pr[3].depth) / 4;
  list.push({
    depth: depth,
    draw: function (pr, fillCss, strokeCss, lineWidth) {
      return function (ctx) {
        ctx.beginPath();
        ctx.moveTo(pr[0].sx, pr[0].sy);
        ctx.lineTo(pr[1].sx, pr[1].sy);
        ctx.lineTo(pr[2].sx, pr[2].sy);
        ctx.lineTo(pr[3].sx, pr[3].sy);
        ctx.closePath();
        ctx.fillStyle = fillCss;
        ctx.fill();
        if (strokeCss) {
          ctx.strokeStyle = strokeCss;
          ctx.lineWidth = lineWidth || 1;
          ctx.stroke();
        }
      };
    }(pr, fillCss, strokeCss, lineWidth),
  });
}

// Pushes a full cube's visible faces (top + the 2 camera-facing sides, see
// computeSideFacing()) between (x1..x2, yBottom..yTop, z1..z2). topRgb is
// shaded down for the two side faces so top stays brightest, one side mid,
// the other darkest - the minimal shading needed for the cube form to read
// without any real lighting model.
function pushCube(list, x1, x2, yBottom, yTop, z1, z2, topRgb, sideFacing, strokeCss, lineWidth) {
  var sideXRgb = shadeColor(topRgb, 0.72);
  var sideZRgb = shadeColor(topRgb, 0.5);
  var topCss = rgbCss(topRgb), sideXCss = rgbCss(sideXRgb), sideZCss = rgbCss(sideZRgb);

  pushQuad(list, [
    { x: x1, y: yTop, z: z1 }, { x: x2, y: yTop, z: z1 },
    { x: x2, y: yTop, z: z2 }, { x: x1, y: yTop, z: z2 },
  ], topCss, strokeCss, lineWidth);

  if (sideFacing.x === 'x2') {
    pushQuad(list, [
      { x: x2, y: yBottom, z: z1 }, { x: x2, y: yBottom, z: z2 },
      { x: x2, y: yTop, z: z2 }, { x: x2, y: yTop, z: z1 },
    ], sideXCss, strokeCss, lineWidth);
  } else {
    pushQuad(list, [
      { x: x1, y: yBottom, z: z1 }, { x: x1, y: yTop, z: z1 },
      { x: x1, y: yTop, z: z2 }, { x: x1, y: yBottom, z: z2 },
    ], sideXCss, strokeCss, lineWidth);
  }

  if (sideFacing.z === 'z2') {
    pushQuad(list, [
      { x: x1, y: yBottom, z: z2 }, { x: x2, y: yBottom, z: z2 },
      { x: x2, y: yTop, z: z2 }, { x: x1, y: yTop, z: z2 },
    ], sideZCss, strokeCss, lineWidth);
  } else {
    pushQuad(list, [
      { x: x1, y: yBottom, z: z1 }, { x: x2, y: yBottom, z: z1 },
      { x: x2, y: yTop, z: z1 }, { x: x1, y: yTop, z: z1 },
    ], sideZCss, strokeCss, lineWidth);
  }
}

// Coalesce bursts of input events (mousemove fires far faster than the
// display refreshes) into at most one draw() per animation frame.
var drawQueued = false;
function scheduleDraw() {
  if (drawQueued) return;
  drawQueued = true;
  requestAnimationFrame(function () { drawQueued = false; draw(); });
}

// =========================================================================
// SECTION: WebGL terrain renderer.
// -------------------------------------------------------------------------
// Terrain draws on its own <canvas id="gl"> UNDER the 2D overlay canvas,
// through raw WebGL (no libraries): the voxel list becomes one static
// vertex buffer at fetch time (buildVoxelGeometry - one quad per exposed
// face, per-face shading and colour baked into the vertices), and every
// frame after that is a single drawArrays call with the camera passed as
// uniforms. The GPU depth buffer makes occlusion exact from every angle -
// no painter's-algorithm sorting, no greedy meshing, no fast/exact modes,
// none of the artifacts those caused. The vertex shader reproduces
// project()'s exact orthographic math, so the 2D overlay (entities, labels,
// picking, hover coords) stays pixel-aligned with the terrain beneath it.
// =========================================================================
var glCanvas = document.getElementById('gl');
var GL = null;            // WebGL context, or null until initGL()/if unsupported
var glProgram = null;
var glBufPos = null, glBufCol = null;
var glAttrs = null, glUnis = null;
var glVertexCount = 0;
var GL_DEPTH_RANGE = 8192; // ortho depth normalization; world fits easily

function initGL() {
  if (GL) return true;
  var gl = glCanvas.getContext('webgl', { antialias: true, depth: true })
    || glCanvas.getContext('experimental-webgl', { antialias: true, depth: true });
  if (!gl) return false;
  var vsSrc = [
    'attribute vec3 aPos;',
    'attribute vec3 aCol;',
    'uniform vec3 uTarget;',
    'uniform vec4 uRot;',  // cos(yaw), sin(yaw), cos(pitch), sin(pitch)
    'uniform vec3 uView;', // scale, halfWidth, halfHeight (px)
    'uniform float uDepthRange;',
    'varying vec3 vCol;',
    'void main() {',
    '  vec3 p = aPos - uTarget;',
    '  float x1 = p.x * uRot.x - p.z * uRot.y;',
    '  float z1 = p.x * uRot.y + p.z * uRot.x;',
    '  float y2 = p.y * uRot.z - z1 * uRot.w;',
    '  float z2 = p.y * uRot.w + z1 * uRot.z;',
    '  gl_Position = vec4(x1 * uView.x / uView.y, y2 * uView.x / uView.z, z2 / uDepthRange, 1.0);',
    '  vCol = aCol;',
    '}',
  ].join('\n');
  var fsSrc = [
    'precision mediump float;',
    'varying vec3 vCol;',
    'void main() { gl_FragColor = vec4(vCol, 1.0); }',
  ].join('\n');
  function compile(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  try {
    var prog = gl.createProgram();
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, vsSrc));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fsSrc));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
    glProgram = prog;
    glAttrs = { pos: gl.getAttribLocation(prog, 'aPos'), col: gl.getAttribLocation(prog, 'aCol') };
    glUnis = {
      target: gl.getUniformLocation(prog, 'uTarget'),
      rot: gl.getUniformLocation(prog, 'uRot'),
      view: gl.getUniformLocation(prog, 'uView'),
      depthRange: gl.getUniformLocation(prog, 'uDepthRange'),
    };
    glBufPos = gl.createBuffer();
    glBufCol = gl.createBuffer();
    gl.enable(gl.DEPTH_TEST);
    GL = gl;
    return true;
  } catch (e) {
    return false; // shader failure -> heightmap fallback keeps the page useful
  }
}

// --- BEGIN pure voxel geometry builder (extracted verbatim by
// test/smoke.mjs for a headless sanity run; keep this block self-contained:
// no DOM access, no WebGL, no outer-scope variables) ---

// Turns the fetched voxel list into flat vertex arrays: for each voxel,
// each of its 6 faces is emitted (two triangles, 6 vertices) ONLY if no
// voxel occupies the neighboring cell - interior faces between shell voxels
// never reach the GPU. matColors[m] is a precomputed [r,g,b] per palette
// index or null; null falls back to the same grey/green height ramp the 2D
// renderer used (rampLo..rampHi are the voxel Y bounds). Per-face shading
// (top brightest, bottom darkest) plus a tiny per-voxel brightness hash is
// baked into the colour bytes so adjacent same-material cubes still read as
// distinct blocks without any stroke lines. Occupancy keys pack the
// bounds-normalized integer coords, so the neighbor test is Set-of-numbers
// fast even at hundreds of thousands of voxels.
function buildVoxelGeometry(list, matColors, rampLo, rampHi) {
  var n = list.length;
  var empty = { pos: new Float32Array(0), col: new Uint8Array(0), vertexCount: 0, faceCount: 0 };
  if (!n) return empty;
  var minX = Infinity, minY = Infinity, minZ = Infinity;
  var maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  var i, v;
  for (i = 0; i < n; i++) {
    v = list[i];
    if (v.x < minX) minX = v.x;
    if (v.x > maxX) maxX = v.x;
    if (v.y < minY) minY = v.y;
    if (v.y > maxY) maxY = v.y;
    if (v.z < minZ) minZ = v.z;
    if (v.z > maxZ) maxZ = v.z;
  }
  var spanY = maxY - minY + 3, spanZ = maxZ - minZ + 3;
  function okey(x, y, z) {
    return ((x - minX + 1) * spanZ + (z - minZ + 1)) * spanY + (y - minY + 1);
  }
  var occ = new Set();
  for (i = 0; i < n; i++) occ.add(okey(list[i].x, list[i].y, list[i].z));
  // 6 faces: unit-cube corner offsets (quad order) + neighbor direction +
  // shade factor. Top brightest, bottom darkest, X/Z sides two mid tones.
  var FACES = [
    { d: [0, 1, 0], s: 1.0, c: [[0, 1, 0], [1, 1, 0], [1, 1, 1], [0, 1, 1]] },
    { d: [0, -1, 0], s: 0.45, c: [[0, 0, 0], [0, 0, 1], [1, 0, 1], [1, 0, 0]] },
    { d: [1, 0, 0], s: 0.8, c: [[1, 0, 0], [1, 0, 1], [1, 1, 1], [1, 1, 0]] },
    { d: [-1, 0, 0], s: 0.66, c: [[0, 0, 0], [0, 1, 0], [0, 1, 1], [0, 0, 1]] },
    { d: [0, 0, 1], s: 0.58, c: [[0, 0, 1], [0, 1, 1], [1, 1, 1], [1, 0, 1]] },
    { d: [0, 0, -1], s: 0.52, c: [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0]] },
  ];
  // Pass 1: count exposed faces so both arrays are allocated exactly once.
  var faceCount = 0, f;
  for (i = 0; i < n; i++) {
    v = list[i];
    for (f = 0; f < 6; f++) {
      var d = FACES[f].d;
      if (!occ.has(okey(v.x + d[0], v.y + d[1], v.z + d[2]))) faceCount++;
    }
  }
  if (!faceCount) return empty;
  var pos = new Float32Array(faceCount * 6 * 3);
  var col = new Uint8Array(faceCount * 6 * 3);
  var pi = 0, ci = 0;
  var rampSpan = Math.max(1, rampHi - rampLo);
  var QUAD = [0, 1, 2, 0, 2, 3]; // two triangles per face
  for (i = 0; i < n; i++) {
    v = list[i];
    var base = (v.m >= 0 && matColors[v.m]) ? matColors[v.m] : null;
    if (!base) {
      var t = Math.max(0, Math.min(1, (v.y - rampLo) / rampSpan));
      base = [72 + (150 - 72) * t, 92 + (158 - 92) * t, 62 + (128 - 62) * t];
    }
    // Small deterministic per-voxel brightness variation (0.95..1.03) so a
    // flat same-material area still shows individual blocks.
    var h = ((v.x * 73856093) ^ (v.y * 19349663) ^ (v.z * 83492791)) & 7;
    var jitter = 0.95 + h * 0.0114;
    for (f = 0; f < 6; f++) {
      var face = FACES[f];
      if (occ.has(okey(v.x + face.d[0], v.y + face.d[1], v.z + face.d[2]))) continue;
      var shade = face.s * jitter;
      var r = Math.max(0, Math.min(255, Math.round(base[0] * shade)));
      var g = Math.max(0, Math.min(255, Math.round(base[1] * shade)));
      var b = Math.max(0, Math.min(255, Math.round(base[2] * shade)));
      for (var q = 0; q < 6; q++) {
        var corner = face.c[QUAD[q]];
        pos[pi++] = v.x + corner[0];
        pos[pi++] = v.y + corner[1];
        pos[pi++] = v.z + corner[2];
        col[ci++] = r;
        col[ci++] = g;
        col[ci++] = b;
      }
    }
  }
  return { pos: pos, col: col, vertexCount: faceCount * 6, faceCount: faceCount };
}
// --- END pure voxel geometry builder ---

// Uploads freshly built geometry into the two static GPU buffers. The
// source arrays are garbage afterwards - nothing per-voxel is retained on
// the JS side.
function uploadVoxelGeometry(geo) {
  if (!initGL()) return false;
  var gl = GL;
  gl.bindBuffer(gl.ARRAY_BUFFER, glBufPos);
  gl.bufferData(gl.ARRAY_BUFFER, geo.pos, gl.STATIC_DRAW);
  gl.bindBuffer(gl.ARRAY_BUFFER, glBufCol);
  gl.bufferData(gl.ARRAY_BUFFER, geo.col, gl.STATIC_DRAW);
  glVertexCount = geo.vertexCount;
  return true;
}

// One frame of terrain: clear (the page-background colour, so the GL canvas
// doubles as the map background), set the camera uniforms, one drawArrays.
function drawGL() {
  if (!GL && !initGL()) return;
  var gl = GL;
  if (glCanvas.width !== canvas.width || glCanvas.height !== canvas.height) {
    glCanvas.width = canvas.width;
    glCanvas.height = canvas.height;
  }
  gl.viewport(0, 0, glCanvas.width, glCanvas.height);
  gl.clearColor(0x0f / 255, 0x11 / 255, 0x14 / 255, 1);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  if (!glVertexCount || !glCanvas.width || !glCanvas.height) return;
  gl.useProgram(glProgram);
  gl.uniform3f(glUnis.target, camera.target.x, camera.target.y, camera.target.z);
  gl.uniform4f(glUnis.rot, Math.cos(camera.yaw), Math.sin(camera.yaw), Math.cos(camera.pitch), Math.sin(camera.pitch));
  gl.uniform3f(glUnis.view, camera.scale, glCanvas.width / 2, glCanvas.height / 2);
  gl.uniform1f(glUnis.depthRange, GL_DEPTH_RANGE);
  gl.bindBuffer(gl.ARRAY_BUFFER, glBufPos);
  gl.enableVertexAttribArray(glAttrs.pos);
  gl.vertexAttribPointer(glAttrs.pos, 3, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, glBufCol);
  gl.enableVertexAttribArray(glAttrs.col);
  gl.vertexAttribPointer(glAttrs.col, 3, gl.UNSIGNED_BYTE, true, 0, 0);
  gl.drawArrays(gl.TRIANGLES, 0, glVertexCount);
}

var TERRAIN_STROKE = 'rgba(0,0,0,.25)';

// Each terrain grid cell becomes one cube of world-size step, top face
// sitting at that cell's scanned height, extruded down to a shared floor a
// few blocks below the lowest known height in the whole grid (clamped so a
// single deep cell can't blow up the geometry) - this reads as solid ground
// with real depth and never leaves a gap under a neighboring cliff, since
// every column shares the same floor. Coloured by terrain.materials when
// available (see colorForMaterial()); unknown material or no materials data
// at all falls back to the pre-existing height-based grey/green ramp.
function buildTerrainPrimitives(list, sideFacing) {
  var terrain = worldmodel.terrain;
  if (!terrain || !terrain.heights || !terrain.heights.length) return;
  var heights = terrain.heights, materials = terrain.materials, step = terrain.step || 1;
  var ox = terrain.origin ? terrain.origin.x : 0, oz = terrain.origin ? terrain.origin.z : 0;
  var bounds = worldmodel.bounds || { minY: 0, maxY: 256 };

  var minH = Infinity;
  for (var r = 0; r < heights.length; r++) {
    var hrow = heights[r];
    if (!hrow) continue;
    for (var c = 0; c < hrow.length; c++) {
      if (hrow[c] != null && hrow[c] < minH) minH = hrow[c];
    }
  }
  if (!isFinite(minH)) return;
  var maxDepth = Math.max(step * 4, 16);

  for (var i = 0; i < heights.length; i++) {
    var row = heights[i];
    if (!row) continue;
    var matRow = materials ? materials[i] : null;
    for (var j = 0; j < row.length; j++) {
      var h = row[j];
      if (h == null) continue;
      var x1 = ox + i * step, x2 = x1 + step;
      var z1 = oz + j * step, z2 = z1 + step;
      var yTop = h;
      var yBottom = Math.max(minH - 3, h - maxDepth);
      if (yBottom >= yTop) yBottom = yTop - step;

      var mat = matRow ? matRow[j] : null;
      var topRgb = colorForMaterial(mat) || heightRampColor(h, bounds);
      pushCube(list, x1, x2, yBottom, yTop, z1, z2, topRgb, sideFacing, TERRAIN_STROKE, 1);
    }
  }
}

// Fetches the whole voxel shell, tiled by cursor (GET /voxels?cursor=N ->
// {palette, chunks:[{cx,cz,runs:[[lx,lz,yStart,len,pi],...]}], nextCursor}),
// merging each page's palette into one materials list. Gated to at most one
// fetch per VOXEL_REFRESH_MS (terrain is static; entities stay live via the
// /worldmodel poll), and never runs concurrently with itself.
async function fetchVoxels() {
  if (voxelFetching) return;
  if (Date.now() - voxelFetchedAt < VOXEL_REFRESH_MS) return;
  voxelFetching = true;
  try {
    var mats = [], matIdx = {}, list = [], set = new Set();
    var minY = Infinity, maxY = -Infinity;
    var cursor = 0, partial = false, sawAny = false;
    for (var page = 0; page < VOXEL_MAX_PAGES; page++) {
      var res = await fetch('/voxels?cursor=' + cursor);
      if (!res.ok) { partial = sawAny; break; }
      var data = await res.json();
      if (!data || data.ok === false || !Array.isArray(data.chunks)) { partial = sawAny; break; }
      sawAny = true;
      var remap = (data.palette || []).map(function (name) {
        if (matIdx[name] == null) { matIdx[name] = mats.length; mats.push(name); }
        return matIdx[name];
      });
      data.chunks.forEach(function (ch) {
        var bx = ch.cx * 16, bz = ch.cz * 16;
        (ch.runs || []).forEach(function (run) {
          var x = bx + run[0], z = bz + run[1], y0 = run[2], len = run[3];
          var m = remap[run[4]] != null ? remap[run[4]] : -1;
          for (var k = 0; k < len; k++) {
            var y = y0 + k;
            var key = x + ',' + y + ',' + z;
            if (set.has(key)) continue;
            set.add(key);
            list.push({ x: x, y: y, z: z, m: m });
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
          }
        });
      });
      if (data.nextCursor == null) break;
      cursor = data.nextCursor;
      if (list.length >= VOXEL_MAX_TOTAL) { partial = true; break; }
    }
    if (page >= VOXEL_MAX_PAGES) partial = true;
    if (list.length) {
      // Build the GPU geometry once, here at fetch time - draw() afterwards
      // is a single drawArrays call per frame. The per-voxel list and set
      // are discarded once the vertex buffers are uploaded.
      var matColors = mats.map(function (name) { return colorForMaterial(name); });
      var lo = isFinite(minY) ? minY : 0, hi = isFinite(maxY) ? maxY : 0;
      var geo = buildVoxelGeometry(list, matColors, lo, hi);
      var uploaded = uploadVoxelGeometry(geo);
      voxels = {
        mats: mats, count: list.length, faceCount: geo.faceCount,
        minY: lo, maxY: hi, partial: partial, gpu: uploaded,
      };
      voxelFetchedAt = Date.now();
      updateCounts();
      draw();
    }
  } catch (e) {
    // keep whatever voxel data (or heightmap fallback) we already have
  } finally {
    voxelFetching = false;
  }
}

function buildPlacePrimitives(list) {
  (worldmodel.places || []).forEach(function (place) {
    var color = colorForBuiltBy(place.builtBy);
    var errored = hasErrorFlag(place.flags);
    if (place.bounds) {
      var b = place.bounds;
      var x1 = Math.min(b.x1, b.x2), x2 = Math.max(b.x1, b.x2);
      var y1 = Math.min(b.y1, b.y2), y2 = Math.max(b.y1, b.y2);
      var z1 = Math.min(b.z1, b.z2), z2 = Math.max(b.z1, b.z2);
      var corners = [
        { x: x1, y: y1, z: z1 }, { x: x2, y: y1, z: z1 }, { x: x2, y: y1, z: z2 }, { x: x1, y: y1, z: z2 },
        { x: x1, y: y2, z: z1 }, { x: x2, y: y2, z: z1 }, { x: x2, y: y2, z: z2 }, { x: x1, y: y2, z: z2 },
      ];
      var pc = corners.map(function (c) { return project(c.x, c.y, c.z); });
      var faces = [
        [0, 1, 2, 3], [4, 5, 6, 7], [0, 1, 5, 4], [2, 3, 7, 6], [1, 2, 6, 5], [0, 3, 7, 4],
      ];
      faces.forEach(function (f) {
        var pts = f.map(function (idx) { return pc[idx]; });
        var depth = (pts[0].depth + pts[1].depth + pts[2].depth + pts[3].depth) / 4;
        list.push({
          depth: depth,
          draw: function (pts) {
            return function (ctx) {
              ctx.beginPath();
              ctx.moveTo(pts[0].sx, pts[0].sy);
              ctx.lineTo(pts[1].sx, pts[1].sy);
              ctx.lineTo(pts[2].sx, pts[2].sy);
              ctx.lineTo(pts[3].sx, pts[3].sy);
              ctx.closePath();
              ctx.fillStyle = hexToRgba(color, 0.22);
              ctx.fill();
              ctx.strokeStyle = errored ? '#ff5555' : hexToRgba(color, 0.9);
              ctx.lineWidth = errored ? 2 : 1;
              ctx.stroke();
            };
          }(pts),
        });
      });
      var topCenter = project((x1 + x2) / 2, y2, (z1 + z2) / 2);
      list.push({
        depth: topCenter.depth - 0.01,
        draw: function (ctx) { drawLabel(ctx, topCenter, place.name, color); },
      });
      registerHit(topCenter, 12, 'place', place);
    } else {
      var o = place.origin || { x: 0, y: 0, z: 0 };
      var base = project(o.x, o.y, o.z);
      var top = project(o.x, o.y + 2.2, o.z);
      list.push({
        depth: base.depth,
        draw: function (ctx) {
          ctx.beginPath();
          ctx.moveTo(base.sx, base.sy);
          ctx.lineTo(top.sx, top.sy);
          ctx.strokeStyle = errored ? '#ff5555' : color;
          ctx.lineWidth = errored ? 3 : 2;
          ctx.stroke();
          ctx.beginPath();
          ctx.moveTo(top.sx, top.sy - 6);
          ctx.lineTo(top.sx + 5, top.sy + 3);
          ctx.lineTo(top.sx - 5, top.sy + 3);
          ctx.closePath();
          ctx.fillStyle = errored ? '#ff5555' : color;
          ctx.fill();
          drawLabel(ctx, { sx: top.sx, sy: top.sy - 12 }, place.name, color);
        },
      });
      registerHit(top, 10, 'place', place);
    }
  });
}

// Builds a small 1-wide (width blocks square footprint), levels-tall
// stack of cubes centered on (x,z) starting at yBase - how NPCs, players,
// and spawn now read as blocky volumes rather than pillar/pin markers, same
// cube-face machinery (pushCube) as the terrain. Registers one hit target
// and one label at the stack's top, exactly like the old marker code did.
function buildEntityCubeStack(list, sideFacing, opts) {
  var hw = opts.width / 2;
  var x1 = opts.x - hw, x2 = opts.x + hw, z1 = opts.z - hw, z2 = opts.z + hw;
  var strokeCss = opts.hovered ? '#ffffff' : 'rgba(0,0,0,.4)';
  var lineWidth = opts.hovered ? 2 : 1;
  for (var lvl = 0; lvl < opts.levels; lvl++) {
    var yBottom = opts.yBase + lvl, yTop = yBottom + 1;
    pushCube(list, x1, x2, yBottom, yTop, z1, z2, opts.colorRgb, sideFacing, strokeCss, lineWidth);
  }
  var top = project(opts.x, opts.yBase + opts.levels, opts.z);
  if (opts.label) {
    list.push({
      depth: top.depth - 0.01,
      draw: function (ctx) { drawLabel(ctx, top, opts.label, opts.labelColor); },
    });
  }
  registerHit(top, 10, opts.hitKind, opts.hitObj);
}

function buildNpcPrimitives(list, sideFacing) {
  (worldmodel.npcs || []).forEach(function (npc) {
    if (!npc.pos) return; // skip gracefully - no marker without a position
    var color = '#4caf6b';
    if (hasErrorFlag(npc.flags) || (Array.isArray(npc.flags) && npc.flags.length)) color = '#ff6a3d';
    else if (!npc.alive) color = '#6b7078';
    buildEntityCubeStack(list, sideFacing, {
      x: npc.pos.x, z: npc.pos.z, yBase: npc.pos.y, width: 0.7, levels: 2,
      colorRgb: hexToRgbArr(color), hovered: hoveredKey === 'npc:' + npc.id,
      hitKind: 'npc', hitObj: npc, label: null, labelColor: color,
    });
  });
}

// Distinct cube marker for the world spawn point (gold) - drawn from
// worldmodel.spawn ({x,y,z}|null), the real spawn reported by the
// gadget:world-scan terrain source (falls back to nothing drawn if the
// gadget was unavailable and buildWorldModel() left spawn null).
function buildSpawnPrimitives(list, sideFacing) {
  var spawn = worldmodel.spawn;
  if (!spawn) return;
  buildEntityCubeStack(list, sideFacing, {
    x: spawn.x, z: spawn.z, yBase: spawn.y, width: 1.3, levels: 1,
    colorRgb: hexToRgbArr('#ffcf5c'), hovered: hoveredKey === 'spawn:spawn',
    hitKind: 'spawn', hitObj: { id: 'spawn', name: 'World spawn', flags: [] },
    label: 'SPAWN', labelColor: '#ffcf5c',
  });
}

// Player markers - distinct color from NPCs, labelled with the player's
// name, drawn from worldmodel.players ([{name,x,y,z}], empty when the
// gadget:world-scan terrain source is unavailable - see buildWorldModel()).
function buildPlayerPrimitives(list, sideFacing) {
  (worldmodel.players || []).forEach(function (p) {
    if (typeof p.x !== 'number' || typeof p.y !== 'number' || typeof p.z !== 'number') return;
    var color = '#5cc8ff';
    buildEntityCubeStack(list, sideFacing, {
      x: p.x, z: p.z, yBase: p.y, width: 0.7, levels: 2,
      colorRgb: hexToRgbArr(color), hovered: hoveredKey === 'player:' + p.name,
      hitKind: 'player', hitObj: { id: p.name, name: p.name, flags: [] },
      label: p.name, labelColor: color,
    });
  });
}

function registerHit(pt, r, kind, obj) {
  hitTargets.push({ sx: pt.sx, sy: pt.sy, r: r, kind: kind, obj: obj });
}

function drawLabel(ctx, pt, text, color) {
  ctx.font = '11px system-ui, sans-serif';
  ctx.fillStyle = 'rgba(15,17,20,.75)';
  var w = ctx.measureText(text).width;
  ctx.fillRect(pt.sx - w / 2 - 3, pt.sy - 13, w + 6, 15);
  ctx.fillStyle = color;
  ctx.textAlign = 'center';
  ctx.fillText(text, pt.sx, pt.sy - 2);
  ctx.textAlign = 'left';
}

// =========================================================================
// SECTION: small math helpers
// =========================================================================
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
function hexToRgba(hex, alpha) {
  var r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16);
  return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
}

// =========================================================================
// SECTION: draw - the painter's-algorithm redraw. Called on interaction and
// on data refresh only; there is no continuous requestAnimationFrame loop.
// =========================================================================
function draw() {
  // Terrain lives on the WebGL canvas beneath (also paints the background);
  // everything else - entities, places, labels, compass - draws on this
  // transparent 2D overlay. Entities are few and always-visible on top,
  // which beats strict occlusion on a monitoring map.
  drawGL();
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  if (!worldmodel) return;

  hitTargets = [];
  var sideFacing = computeSideFacing();
  var primitives = [];
  // Coarse heightmap cubes remain only as the fallback while /voxels hasn't
  // produced GPU geometry yet (or WebGL is unavailable).
  if (!(voxels && voxels.gpu && glVertexCount)) buildTerrainPrimitives(primitives, sideFacing);
  buildPlacePrimitives(primitives);
  buildNpcPrimitives(primitives, sideFacing);
  buildSpawnPrimitives(primitives, sideFacing);
  buildPlayerPrimitives(primitives, sideFacing);
  primitives.sort(function (a, b) { return b.depth - a.depth; }); // farthest first
  primitives.forEach(function (p) { p.draw(ctx); });

  if (focusPoint && Date.now() < focusPoint.until) {
    var fp = project(focusPoint.x, focusPoint.y, focusPoint.z);
    var pulse = 10 + 6 * Math.sin(Date.now() / 120);
    ctx.beginPath();
    ctx.arc(fp.sx, fp.sy, pulse, 0, Math.PI * 2);
    ctx.strokeStyle = '#ffcf5c';
    ctx.lineWidth = 2;
    ctx.stroke();
  }

  drawCompass();
  updateEmptyNotes();
}

// N/E/S/W + up indicator, fixed in the canvas's top-right corner - always
// visible regardless of camera state, so orientation never gets lost.
function drawCompass() {
  var cx = canvas.width - 60, cy = 60, r = 34;
  ctx.save();
  ctx.font = '11px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  // Compass directions in Minecraft: +X = east, -X = west, +Z = south, -Z = north.
  var dirs = [
    { label: 'N', x: 0, z: -1 }, { label: 'S', x: 0, z: 1 },
    { label: 'E', x: 1, z: 0 }, { label: 'W', x: -1, z: 0 },
  ];
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(28,31,36,.85)';
  ctx.fill();
  ctx.strokeStyle = '#33373e';
  ctx.stroke();
  dirs.forEach(function (d) {
    var cosY = Math.cos(camera.yaw), sinY = Math.sin(camera.yaw);
    var x1 = d.x * cosY - d.z * sinY;
    ctx.fillStyle = d.label === 'N' ? '#7fa8ff' : '#c9cdd3';
    ctx.fillText(d.label, cx + x1 * (r - 10), cy - (d.x * sinY + d.z * cosY) * (r - 10) * 0.4);
  });
  ctx.fillStyle = '#6b7078';
  ctx.fillText('up ↑', cx, cy + r + 12);
  ctx.restore();
}

function updateEmptyNotes() {
  var notes = [];
  if (!worldmodel.terrain && !(voxels && voxels.count)) notes.push('No terrain data available.');
  if (!(voxels && voxels.count) && worldmodel.terrain) notes.push('Voxel terrain not loaded yet — showing coarse heightmap.');
  if (!worldmodel.places || !worldmodel.places.length) notes.push('No places recorded yet.');
  if (!worldmodel.npcs || !worldmodel.npcs.length) notes.push('No NPCs recorded yet.');
  (worldmodel.notes || []).forEach(function (n) { notes.push(n); });
  document.getElementById('empty-notes').textContent = notes.join('  ·  ');
}

// =========================================================================
// SECTION: picking - hover shows the info panel, click pins it
// =========================================================================
function hitTest(px, py) {
  var best = null, bestDist = Infinity;
  hitTargets.forEach(function (t) {
    var dx = t.sx - px, dy = t.sy - py, d = Math.sqrt(dx * dx + dy * dy);
    if (d <= t.r + 4 && d < bestDist) { best = t; bestDist = d; }
  });
  return best;
}

function renderInfo(target) {
  var panel = document.getElementById('info-panel');
  if (!target) { panel.innerHTML = '<span class="empty">Hover or click a place/NPC marker.</span>'; return; }
  var obj = target.obj, html = '';
  if (target.kind === 'place') {
    html += '<b>' + escapeHtml(obj.name || obj.id) + '</b><br>';
    html += 'kind: ' + escapeHtml(obj.kind || '(unknown)') + '<br>';
    html += 'built by: ' + escapeHtml(obj.builtBy || '(unknown)') + '<br>';
  } else if (target.kind === 'spawn') {
    html += '<b>' + escapeHtml(obj.name) + '</b><br>';
    html += 'the world spawn point<br>';
  } else if (target.kind === 'player') {
    html += '<b>' + escapeHtml(obj.name) + '</b><br>';
    html += 'online player<br>';
  } else {
    html += '<b>' + escapeHtml(obj.name || obj.id) + '</b><br>';
    html += 'role: ' + escapeHtml(obj.role || '(unknown)') + '<br>';
    html += 'status: ' + (obj.alive ? 'alive' : 'dead') + '<br>';
  }
  var flags = obj.flags || [];
  html += flags.length
    ? flags.map(function (f) { return '<span class="flag">' + escapeHtml(f) + '</span>'; }).join('')
    : '<span class="empty">no flags</span>';
  panel.innerHTML = html;
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

// =========================================================================
// SECTION: mouse/wheel interaction - orbit (drag), pan (right-drag or
// shift-drag), zoom (wheel)
// =========================================================================
var dragState = null; // {mode: 'orbit'|'pan', lastX, lastY}

canvas.addEventListener('contextmenu', function (e) { e.preventDefault(); });

canvas.addEventListener('mousedown', function (e) {
  var mode = (e.button === 2 || e.shiftKey) ? 'pan' : 'orbit';
  dragState = { mode: mode, lastX: e.clientX, lastY: e.clientY };
  canvas.classList.add('dragging');
});

window.addEventListener('mouseup', function () {
  dragState = null;
  canvas.classList.remove('dragging');
});

window.addEventListener('mousemove', function (e) {
  if (dragState) {
    var dx = e.clientX - dragState.lastX, dy = e.clientY - dragState.lastY;
    dragState.lastX = e.clientX;
    dragState.lastY = e.clientY;
    if (dragState.mode === 'orbit') {
      camera.yaw += dx * 0.006;
      camera.pitch = clamp(camera.pitch + dy * 0.006, -1.45, 1.45);
    } else {
      var ru = cameraRightUp();
      var f = 1 / camera.scale;
      camera.target.x -= (ru.right.x * dx - ru.up.x * dy) * f;
      camera.target.y -= (ru.right.y * dx - ru.up.y * dy) * f;
      camera.target.z -= (ru.right.z * dx - ru.up.z * dy) * f;
    }
    scheduleDraw();
  }
});

canvas.addEventListener('mousemove', function (e) {
  if (dragState) return; // no hover hit-testing mid-drag - it fights the orbit redraws
  var rect = canvas.getBoundingClientRect();
  var px = e.clientX - rect.left, py = e.clientY - rect.top;
  lastMouse = { x: px, y: py };
  var hit = hitTest(px, py);
  var key = hit ? (hit.kind + ':' + hit.obj.id) : null;
  if (key !== hoveredKey) {
    hoveredKey = key;
    renderInfo(hit);
    draw();
  }
  var ground = unproject(px, py);
  document.getElementById('hover-coord').textContent = ground
    ? ('~ ground x:' + Math.round(ground.x) + ' z:' + Math.round(ground.z) + ' (y:' + Math.round(camera.target.y) + ')')
    : '';
});

canvas.addEventListener('click', function (e) {
  var rect = canvas.getBoundingClientRect();
  var hit = hitTest(e.clientX - rect.left, e.clientY - rect.top);
  if (hit) renderInfo(hit); // pin the panel to the clicked object
});

canvas.addEventListener('wheel', function (e) {
  e.preventDefault();
  var factor = e.deltaY > 0 ? 0.9 : 1.1;
  camera.scale = clamp(camera.scale * factor, 0.3, 60);
  scheduleDraw();
}, { passive: false });

// =========================================================================
// SECTION: problems panel - worst-first, click focuses the camera
// =========================================================================
var SEV_ORDER = { error: 0, warn: 1, info: 2 };

function renderProblems() {
  var list = document.getElementById('problem-list');
  var diagnostics = (worldmodel && worldmodel.diagnostics) || [];
  if (!diagnostics.length) {
    list.innerHTML = '<li class="problem" style="cursor:default"><span class="msg">No problems reported.</span></li>';
    return;
  }
  var sorted = diagnostics.slice().sort(function (a, b) {
    return (SEV_ORDER[a.severity] != null ? SEV_ORDER[a.severity] : 3) - (SEV_ORDER[b.severity] != null ? SEV_ORDER[b.severity] : 3);
  });
  list.innerHTML = '';
  sorted.forEach(function (d, idx) {
    var li = document.createElement('li');
    li.className = 'problem sev-' + (d.severity || 'info');
    li.innerHTML = '<div class="sev">' + escapeHtml(d.severity || 'info') + '</div>' +
      '<div class="kind">' + escapeHtml(d.kind || '') + (d.subject ? ' &mdash; ' + escapeHtml(d.subject) : '') + '</div>' +
      '<div class="msg">' + escapeHtml(d.message || '') + '</div>';
    li.addEventListener('click', function () { focusOn(d.at); });
    list.appendChild(li);
  });
}

// Centers the camera on a diagnostic's coordinate (a no-op, gracefully, if
// the diagnostic carries no "at") and drops a brief pulsing ring there.
function focusOn(at) {
  if (!at) return;
  camera.target = { x: at.x, y: at.y, z: at.z };
  focusPoint = { x: at.x, y: at.y, z: at.z, until: Date.now() + 2000 };
  draw();
  setTimeout(draw, 2050); // one extra redraw to clear the pulse ring
}

// =========================================================================
// SECTION: header counts + data loading
// =========================================================================
function updateCounts() {
  var el = document.getElementById('counts');
  if (!worldmodel) { el.textContent = 'loading…'; return; }
  var places = (worldmodel.places || []).length;
  var npcs = worldmodel.npcs || [];
  var alive = npcs.filter(function (n) { return n.alive; }).length;
  var dead = npcs.length - alive;
  var problems = (worldmodel.diagnostics || []).length;
  var players = (worldmodel.players || []).length;
  var html = 'Places: <b>' + places + '</b> &middot; ' +
    'NPCs: <b>' + alive + '</b> alive / <b>' + dead + '</b> dead &middot; ' +
    'Players online: <b>' + players + '</b> &middot; ' +
    'Problems: <b>' + problems + '</b>';
  var b = worldmodel.bounds;
  if (b) {
    html += ' &middot; extent x:' + Math.round(b.minX) + '..' + Math.round(b.maxX) +
      ' z:' + Math.round(b.minZ) + '..' + Math.round(b.maxZ);
  }
  if (typeof worldmodel.loadedChunks === 'number') {
    html += ' (' + worldmodel.loadedChunks + ' loaded chunks)';
  }
  if (voxels && voxels.count) {
    html += ' &middot; voxels: <b>' + voxels.count + '</b> (' + voxels.faceCount + ' faces)' + (voxels.partial ? ' (partial)' : '');
  }
  html += ' &middot; generated ' + escapeHtml(worldmodel.generatedAt || '?');
  el.innerHTML = html;
}

function showBanner(html) {
  var b = document.getElementById('banner');
  if (!html) { b.style.display = 'none'; b.innerHTML = ''; return; }
  b.innerHTML = html;
  b.style.display = 'block';
}

function resizeCanvas() {
  var wrap = document.getElementById('canvas-wrap');
  canvas.width = wrap.clientWidth;
  canvas.height = wrap.clientHeight;
  // The WebGL canvas mirrors these dimensions inside drawGL().
  draw();
}

async function fetchAndRender() {
  try {
    var res = await fetch('/worldmodel');
    if (res.status === 401) {
      showBanner('Not authorized. Add <code>?token=YOUR-TOKEN</code> to the console URL first, then reload.');
      return;
    }
    if (!res.ok) {
      showBanner('Failed to load /worldmodel (HTTP ' + res.status + '). Retrying on next refresh.');
      return;
    }
    showBanner(null);
    worldmodel = await res.json();
    updateCounts();
    renderProblems();
    draw();
  } catch (err) {
    showBanner('Failed to reach /worldmodel: ' + escapeHtml(String(err)));
  }
  // Voxel terrain rides the same refresh triggers but is internally gated
  // to one fetch per VOXEL_REFRESH_MS - static blocks don't need the live
  // cadence the entity markers get from /worldmodel.
  fetchVoxels();
}

document.getElementById('refresh').addEventListener('click', fetchAndRender);
document.getElementById('auto-refresh').addEventListener('change', function (e) {
  if (autoRefreshTimer) { clearInterval(autoRefreshTimer); autoRefreshTimer = null; }
  if (e.target.checked) autoRefreshTimer = setInterval(fetchAndRender, 10000);
});

window.addEventListener('resize', resizeCanvas);
resizeCanvas();
fetchAndRender();

// =========================================================================
// SECTION: status strip - same GET /status polling as the / page, on the
// same cadence as its own auto-refresh (10s) plus one call on load.
// =========================================================================
function renderStatus(s) {
  var pct = s.budget.limit > 0 ? Math.round((s.budget.used / s.budget.limit) * 100) : 0;
  document.getElementById('status-budget').innerHTML =
    'budget: <b>' + s.budget.used + '</b> / ' + s.budget.limit + ' (' + pct + '%)';
  document.getElementById('status-killswitch').textContent = s.killSwitch ? 'kill switch: ON' : '';
  document.getElementById('reset-budget-btn-map').style.display = s.budget.exhausted ? 'inline-block' : 'none';

  var worldBanner = document.getElementById('world-inert-banner');
  if (s.budget.exhausted || s.killSwitch) {
    var reason = s.budget.exhausted
      ? 'the daily token budget is exhausted (' + s.budget.used + '/' + s.budget.limit + ' tokens). It resets at ' + s.budget.resetsAt + '.'
      : 'the kill switch is active.';
    worldBanner.textContent = 'The world is NOT currently acting: ' + reason;
    worldBanner.style.display = 'block';
  } else {
    worldBanner.style.display = 'none';
  }
}

async function fetchStatus() {
  try {
    var res = await fetch('/status');
    if (res.ok) renderStatus(await res.json());
  } catch (e) {}
}

document.getElementById('reset-budget-btn-map').addEventListener('click', async function () {
  if (!confirm("Reset today's token budget counter and let the world start acting again?")) return;
  await fetch('/budget/reset', { method: 'POST' });
  fetchStatus();
});

fetchStatus();
setInterval(fetchStatus, 10000);
</script>
</body>
</html>`;
}

// ---------------- request body ----------------

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > 1_000_000) req.destroy(new Error("body too large"));
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

function sendJson(res, status, obj, extraHeaders = {}) {
  res.writeHead(status, { "content-type": "application/json", ...extraHeaders });
  res.end(JSON.stringify(obj));
}

// ---------------- server ----------------

/**
 * @param {object} config - loadConfig() result (consoleBind/consolePort/consoleToken/loreDir/stateDir)
 * @param {{ reloadLore: () => Promise<void> | void, submitOrder: (text: string, orderTimestamp: string) => void, getWorldModel: () => Promise<object>, getStatus: () => Promise<object>, resetBudget: () => Promise<void> }} deps
 *   - reloadLore is the exact reload lore.mjs's watcher uses on its own
 *   timer (index.mjs passes `loreWatch.tick`), invoked after every
 *   directive add/delete so the NEXT scene already sees the change.
 *   - submitOrder (index.mjs) pushes an "operator_order" scene event onto
 *   the director scheduler - see index.mjs's submitOrder for how an order
 *   becomes a director scene rather than lore. orderTimestamp is the exact
 *   timestamp appendOrder() below assigned this order, threaded onto the
 *   scene event so index.mjs's runScene can later mark this same orders.json
 *   entry "done" (or revert it to "queued" on a timeout, or a guardrail
 *   skip) once its scene completes.
 *   - getWorldModel (index.mjs) builds the raw world-model JSON (lib/
 *   worldmodel.mjs's buildWorldModel, called against the brain's own bridge
 *   connection) for GET /worldmodel below - the 3D map page's (/map) data
 *   source. Its result is cached for WORLDMODEL_CACHE_MS so rapid page
 *   refreshes/auto-refresh polling don't hammer the bridge with a fresh
 *   ledger_query + scan_area round trip every time.
 *   - getVoxels (index.mjs) fetches one page of gadget:world-scan's VOXEL
 *   mode ({voxels:true, cursor?, area?, maxBlocks?}) for GET /voxels below -
 *   the /map page's true cubic-voxel terrain source (solid blocks within
 *   Chebyshev distance 3 of air, palette + per-column vertical RLE). Human
 *   map only; never part of the director's world_overview path.
 *   - getStatus (index.mjs) builds the JSON snapshot for GET /status below -
 *   token budget, kill switch, rate limit, scene/bridge state - polled by
 *   both console pages' status strip so an operator can see AT A GLANCE
 *   whether the world is currently able to act, instead of having to notice
 *   its absence in decisions.log.
 *   - resetBudget (index.mjs) zeroes today's token usage counter for POST
 *   /budget/reset below - the console's "reset budget" button, for an
 *   operator who wants to keep the world running past an exhausted budget
 *   rather than wait for the UTC midnight roll.
 * @returns {Promise<{ server: import('node:http').Server, port: number, stop: () => Promise<void> }>}
 */
export function startConsoleServer(config, { reloadLore, submitOrder, getWorldModel, getVoxels, getStatus, resetBudget }) {
  const token = config.consoleToken;
  const decisionsPath = path.join(config.stateDir, "decisions.log");

  // ---- GET /worldmodel: short-lived cache (see deps.getWorldModel above) ----
  const WORLDMODEL_CACHE_MS = 5000;
  let worldModelCache = null; // { at: number (Date.now()), promise: Promise<object> }
  function cachedWorldModel() {
    const now = Date.now();
    if (worldModelCache && now - worldModelCache.at < WORLDMODEL_CACHE_MS) {
      return worldModelCache.promise;
    }
    const promise = Promise.resolve(getWorldModel()).catch((e) => {
      worldModelCache = null; // don't cache a failure - the next request should retry
      throw e;
    });
    worldModelCache = { at: now, promise };
    return promise;
  }

  // ---- GET /voxels: per-page cache (deps.getVoxels - index.mjs's wrapper
  // around gadget:world-scan's voxel mode). Static terrain changes slowly,
  // so each distinct page (cursor/area querystring) is cached for 10s: the
  // /map viewer's own 10s refresh gate plus this server-side cache means
  // repeated map loads never hammer the bridge with voxel rescans. Bounded
  // LRU-ish: oldest entry dropped past VOXEL_CACHE_MAX. ----
  const VOXEL_CACHE_MS = 10_000;
  const VOXEL_CACHE_MAX = 64;
  const voxelCache = new Map(); // key (querystring) -> { at, promise }
  function cachedVoxels(key, params) {
    const now = Date.now();
    const hit = voxelCache.get(key);
    if (hit && now - hit.at < VOXEL_CACHE_MS) return hit.promise;
    const promise = Promise.resolve(getVoxels(params)).catch((e) => {
      voxelCache.delete(key); // don't cache a failure - the next request should retry
      throw e;
    });
    voxelCache.set(key, { at: now, promise });
    if (voxelCache.size > VOXEL_CACHE_MAX) {
      let oldestKey = null, oldestAt = Infinity;
      for (const [k, v] of voxelCache) if (v.at < oldestAt) { oldestAt = v.at; oldestKey = k; }
      if (oldestKey !== null) voxelCache.delete(oldestKey);
    }
    return promise;
  }

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://localhost");
      const auth = checkAuth(req, url, token);
      if (!auth.ok) {
        res.writeHead(401, { "content-type": "text/html; charset=utf-8" });
        res.end(unauthorizedPage());
        return;
      }
      const extraHeaders = auth.setCookie
        ? { "set-cookie": `${COOKIE_NAME}=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax` }
        : {};

      if (req.method === "GET" && url.pathname === "/") {
        res.writeHead(200, { "content-type": "text/html; charset=utf-8", ...extraHeaders });
        res.end(renderPage(listDirectives(config), listOrders(config)));
        return;
      }

      if (req.method === "GET" && url.pathname === "/map") {
        res.writeHead(200, { "content-type": "text/html; charset=utf-8", ...extraHeaders });
        res.end(renderMapPage());
        return;
      }

      if (req.method === "GET" && url.pathname === "/worldmodel") {
        try {
          const model = await cachedWorldModel();
          sendJson(res, 200, model, extraHeaders);
        } catch (e) {
          log.error("worldmodel_request_failed", { error: String((e && e.stack) || e) });
          sendJson(res, 502, { ok: false, error: "failed to build world model" }, extraHeaders);
        }
        return;
      }

      // GET /voxels?cursor=N[&x1=..&z1=..&x2=..&z2=..][&maxBlocks=N] - one
      // page of the world's cubic-voxel shell (gadget:world-scan voxel mode
      // via deps.getVoxels), for the /map page's true-voxel renderer. The
      // page tiles requests with the returned nextCursor until null.
      if (req.method === "GET" && url.pathname === "/voxels") {
        if (typeof getVoxels !== "function") {
          sendJson(res, 501, { ok: false, error: "voxel source not configured" }, extraHeaders);
          return;
        }
        const params = {};
        const cursor = Number(url.searchParams.get("cursor"));
        if (Number.isFinite(cursor) && cursor >= 0) params.cursor = Math.floor(cursor);
        const maxBlocks = Number(url.searchParams.get("maxBlocks"));
        if (Number.isFinite(maxBlocks) && maxBlocks > 0) params.maxBlocks = Math.floor(maxBlocks);
        // Number(null) is 0, so require the params to actually be present -
        // otherwise a bare /voxels request would ask for area {0,0,0,0} (just
        // chunk 0,0) instead of every loaded chunk.
        const areaRaw = ["x1", "z1", "x2", "z2"].map((k) => url.searchParams.get(k));
        if (areaRaw.every((v) => v !== null && v !== "" && Number.isFinite(Number(v)))) {
          params.area = { x1: Number(areaRaw[0]), z1: Number(areaRaw[1]), x2: Number(areaRaw[2]), z2: Number(areaRaw[3]) };
        }
        try {
          const data = await cachedVoxels(url.search || "?", params);
          sendJson(res, 200, data && typeof data === "object" ? data : {}, extraHeaders);
        } catch (e) {
          log.error("voxels_request_failed", { error: String((e && e.stack) || e) });
          sendJson(res, 502, { ok: false, error: "failed to fetch voxels" }, extraHeaders);
        }
        return;
      }

      if (req.method === "GET" && url.pathname === "/decisions") {
        res.writeHead(200, { "content-type": "text/plain; charset=utf-8", ...extraHeaders });
        res.end(tailLines(decisionsPath, 40));
        return;
      }

      if (req.method === "GET" && url.pathname === "/status") {
        try {
          const status = await getStatus();
          sendJson(res, 200, status, extraHeaders);
        } catch (e) {
          log.error("status_request_failed", { error: String((e && e.stack) || e) });
          sendJson(res, 502, { ok: false, error: "failed to build status" }, extraHeaders);
        }
        return;
      }

      if (req.method === "POST" && url.pathname === "/budget/reset") {
        try {
          await resetBudget();
          sendJson(res, 200, { ok: true }, extraHeaders);
        } catch (e) {
          log.error("budget_reset_request_failed", { error: String((e && e.stack) || e) });
          sendJson(res, 500, { ok: false, error: "failed to reset budget" }, extraHeaders);
        }
        return;
      }

      if (req.method === "POST" && url.pathname === "/directive") {
        let body;
        try {
          body = JSON.parse((await readBody(req)) || "{}");
        } catch {
          sendJson(res, 400, { ok: false, error: "invalid JSON body" }, extraHeaders);
          return;
        }
        const text = typeof body.text === "string" ? body.text.trim() : "";
        if (!text) {
          sendJson(res, 400, { ok: false, error: "directive text must not be empty" }, extraHeaders);
          return;
        }
        const timestamp = appendDirective(config, text);
        await reloadLore();
        log.info("operator_directive_added", { timestamp, text: text.length > 120 ? text.slice(0, 117) + "..." : text });
        sendJson(res, 200, { ok: true, timestamp }, extraHeaders);
        return;
      }

      if (req.method === "POST" && url.pathname === "/directive/delete") {
        let body;
        try {
          body = JSON.parse((await readBody(req)) || "{}");
        } catch {
          sendJson(res, 400, { ok: false, error: "invalid JSON body" }, extraHeaders);
          return;
        }
        const timestamp = typeof body.timestamp === "string" ? body.timestamp : null;
        const removed = timestamp ? deleteDirective(config, timestamp) : false;
        if (removed) await reloadLore();
        log.info("operator_directive_deleted", { timestamp, removed });
        sendJson(res, 200, { ok: removed }, extraHeaders);
        return;
      }

      if (req.method === "POST" && url.pathname === "/order") {
        let body;
        try {
          body = JSON.parse((await readBody(req)) || "{}");
        } catch {
          sendJson(res, 400, { ok: false, error: "invalid JSON body" }, extraHeaders);
          return;
        }
        const text = typeof body.text === "string" ? body.text.trim() : "";
        if (!text) {
          sendJson(res, 400, { ok: false, error: "order text must not be empty" }, extraHeaders);
          return;
        }
        const timestamp = appendOrder(config, text);
        submitOrder(text, timestamp);
        log.info("operator_order_added", { timestamp, text: text.length > 120 ? text.slice(0, 117) + "..." : text });
        sendJson(res, 200, { ok: true, timestamp }, extraHeaders);
        return;
      }

      res.writeHead(404, { "content-type": "text/plain" });
      res.end("not found");
    } catch (e) {
      log.error("console_request_failed", { error: String((e && e.stack) || e) });
      if (!res.headersSent) res.writeHead(500, { "content-type": "text/plain" });
      res.end("internal error");
    }
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(config.consolePort, config.consoleBind, () => {
      server.removeListener("error", reject);
      resolve({
        server,
        port: server.address().port,
        stop: () => new Promise((r) => server.close(() => r())),
      });
    });
  });
}
