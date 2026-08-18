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
    <h1>Lore Console</h1>
    <p class="sub">Type an instruction for the director to fold into the world. It takes effect on the next scene.</p>
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
refreshDecisions();
setInterval(refreshDecisions, 5000);
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
 * @param {{ reloadLore: () => Promise<void> | void, submitOrder: (text: string) => void }} deps
 *   - reloadLore is the exact reload lore.mjs's watcher uses on its own
 *   timer (index.mjs passes `loreWatch.tick`), invoked after every
 *   directive add/delete so the NEXT scene already sees the change.
 *   - submitOrder (index.mjs) pushes an "operator_order" scene event onto
 *   the director scheduler - see index.mjs's submitOrder for how an order
 *   becomes a director scene rather than lore.
 * @returns {Promise<{ server: import('node:http').Server, port: number, stop: () => Promise<void> }>}
 */
export function startConsoleServer(config, { reloadLore, submitOrder }) {
  const token = config.consoleToken;
  const decisionsPath = path.join(config.stateDir, "decisions.log");

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

      if (req.method === "GET" && url.pathname === "/decisions") {
        res.writeHead(200, { "content-type": "text/plain; charset=utf-8", ...extraHeaders });
        res.end(tailLines(decisionsPath, 40));
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
        submitOrder(text);
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
