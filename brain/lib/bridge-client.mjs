// The brain's OWN WebSocket connection to the MCAlive2 plugin bridge, used to:
//   (a) receive pushed sense events (the director's wake-up signal), and
//   (b) issue direct request/response commands the ROUTING layer needs
//       before an agent turn even starts - chiefly `npc_context`, which
//       must be fetched for an NPC actor BEFORE spawning that actor's
//       restricted turn, since actors are not allowed to call npc_context
//       themselves (knowledge isolation would otherwise be bypassable by
//       an actor asking about a different NPC's facts mid-conversation).
//
// This mirrors minecraftalive/gm/lib/bridge-events.mjs's auth handshake and
// reconnect-with-backoff (including the fix that keeps the reconnect timer
// un-unref'd: unref'ing it lets the event loop drain to empty on a failed
// connect and the process exits 0 instead of retrying), but additionally
// exposes call() for request/response commands - the plugin bridge protocol
// supports both request/response and pushed events over the same socket
// (DESIGN.md "Protocol").
//
// The Agent SDK's own MCP connection (brain/mcp-bridge.mjs, spawned fresh
// per query()) is a SEPARATE socket, exactly as gm/'s mcp/server.mjs was
// separate from gm/index.mjs's event connection.

import { log } from "./logger.mjs";

export class BridgeClient {
  constructor({ url, token, baseMs = 1000, maxMs = 30000, onEvent }) {
    this.url = url;
    this.token = token;
    this.baseMs = baseMs;
    this.maxMs = maxMs;
    this.onEvent = onEvent || (() => {});
    this.attempt = 0;
    this.stopped = false;
    this.ws = null;
    this.authed = false;
    this.pending = new Map(); // id -> {resolve, reject}
    this.seq = 0;
    this._reconnectTimer = null;
    this._readyWaiters = []; // resolve/reject pairs waiting for the next auth outcome
  }

  start() {
    this.stopped = false;
    this._connect();
  }

  stop() {
    this.stopped = true;
    if (this._reconnectTimer) clearTimeout(this._reconnectTimer);
    if (this.ws) {
      try { this.ws.close(); } catch { /* ignore */ }
    }
    for (const [, p] of this.pending) p.reject(new Error("bridge client stopped"));
    this.pending.clear();
  }

  _scheduleReconnect() {
    if (this.stopped) return;
    const delay = Math.min(this.maxMs, this.baseMs * 2 ** this.attempt);
    this.attempt += 1;
    log.warn("bridge_reconnect_scheduled", { attempt: this.attempt, delayMs: delay, url: this.url });
    // Intentionally NOT unref'd: this timer is what keeps the process alive
    // while the bridge is unreachable. Unref'ing it lets the event loop
    // drain to empty (nothing else is pending once a connect attempt fails
    // synchronously/immediately) and the process exits with code 0 instead
    // of retrying. (Fixed bug from minecraftalive/gm - keep the fix.)
    this._reconnectTimer = setTimeout(() => this._connect(), delay);
  }

  _connect() {
    if (this.stopped) return;
    const authId = "brain-auth-" + Math.random().toString(36).slice(2);
    let authed = false;
    let sock;
    try {
      sock = new WebSocket(this.url);
    } catch (e) {
      log.error("bridge_connect_failed", { error: String(e) });
      this._scheduleReconnect();
      return;
    }
    this.ws = sock;

    sock.onopen = () => {
      sock.send(JSON.stringify({ id: authId, cmd: "auth", args: { token: this.token } }));
    };

    sock.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id === authId) {
        authed = !!msg.ok;
        this.authed = authed;
        if (authed) {
          this.attempt = 0; // reset backoff on a clean connection
          log.info("bridge_auth_ok", { url: this.url });
          this._flushReadyWaiters(null);
        } else {
          log.error("bridge_auth_failed", { error: msg.error });
          this._flushReadyWaiters(new Error("auth failed: " + msg.error));
        }
        return;
      }
      if (msg.event) {
        this.onEvent(msg.event, msg.data);
        return;
      }
      const p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        if (msg.ok) p.resolve(msg.data ?? {});
        else p.reject(new Error(msg.error || "unknown error"));
      }
    };

    sock.onclose = () => {
      this.ws = null;
      this.authed = false;
      for (const [, p] of this.pending) p.reject(new Error("connection to MCAlive2 plugin lost"));
      this.pending.clear();
      this._flushReadyWaiters(new Error("connection closed before auth completed"));
      if (!this.stopped) this._scheduleReconnect();
    };
    sock.onerror = () => { /* onclose follows */ };
  }

  _flushReadyWaiters(err) {
    const waiters = this._readyWaiters;
    this._readyWaiters = [];
    for (const { resolve, reject } of waiters) {
      if (err) reject(err); else resolve();
    }
  }

  _waitUntilReady(timeoutMs) {
    if (this.authed) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const entry = { resolve, reject };
      this._readyWaiters.push(entry);
      if (timeoutMs > 0) {
        setTimeout(() => {
          const idx = this._readyWaiters.indexOf(entry);
          if (idx !== -1) {
            this._readyWaiters.splice(idx, 1);
            reject(new Error("timed out waiting for bridge auth"));
          }
        }, timeoutMs).unref?.();
      }
    });
  }

  /** Issue a direct request/response command against the plugin bridge. */
  async call(cmd, args = {}, timeoutMs = 8000) {
    await this._waitUntilReady(timeoutMs);
    if (!this.ws || !this.authed) throw new Error("bridge not connected");
    const id = "c" + (++this.seq);
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, cmd, args }));
      const t = setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`timed out waiting for reply to ${cmd}`));
        }
      }, timeoutMs);
      t.unref?.();
    });
  }
}
