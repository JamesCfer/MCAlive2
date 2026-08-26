#!/usr/bin/env node
// A tiny stand-in for the MCAlive2 plugin's WebSocket bridge, for testing
// brain/ with no real Minecraft server. Performs the same auth handshake as
// the real bridge, RECORDS every command it receives (so the smoke test can
// assert on npc_context calls in particular), answers canned npc_context
// data per NPC id (to prove knowledge isolation - two NPCs, two disjoint
// fact sets), and emits a scripted sequence of fake sense events:
//
//   t=50ms    player_join
//   t=150ms   player_chat, no nearNpcId  (1st of 2 rapid director-bound msgs)
//   t=300ms   player_chat, no nearNpcId  (2nd - still inside debounce window)
//   t=700ms   npc_interact  -> mara-baker  (must route to an ACTOR turn)
//   t=900ms   player_chat WITH nearNpcId -> kess-smith (also an ACTOR turn;
//             proves this NPC only ever sees ITS OWN facts, not mara's)
//   t=2200ms  npc_death (written to the needs log, never a scene)
//   t=3000ms  region_enter   \  ambient: dropped, these wake nothing
//   t=3050ms  region_exit    /
//   t=3100ms  player_idle_scene  a player moment: still gets its own scene
//
// Env:
//   MOCK_BRIDGE_PORT   port to listen on (default 8899)
//   MOCK_BRIDGE_TOKEN  expected auth token (default "test-token")

import { WebSocketServer } from "ws";

const PORT = Number(process.env.MOCK_BRIDGE_PORT || 8899);
const TOKEN = process.env.MOCK_BRIDGE_TOKEN || "test-token";

const wss = new WebSocketServer({ port: PORT });

// Canned ledger data, keyed by npc id - deliberately DISJOINT fact sets so a
// smoke-test assertion that an actor prompt leaked the wrong NPC's facts
// would actually fail.
const NPC_CONTEXT = {
  "mara-baker": {
    npc: { id: "mara-baker", name: "Mara the Baker", personality: { drive: 1, warmth: 2, boldness: 0, composure: 1 }, home: { x: 500, y: 75, z: 2 }, alive: true },
    facts: [{ id: "f-mara-1", text: "Mara has run the bakery for eleven years.", knownBy: ["mara-baker"] }],
  },
  "kess-smith": {
    npc: { id: "kess-smith", name: "Kess the Smith", personality: { drive: 2, warmth: -1, boldness: 2, composure: 0 }, home: { x: 505, y: 75, z: -3 }, alive: true },
    facts: [{ id: "f-kess-1", text: "Kess found a strange ore vein north of the village.", knownBy: ["kess-smith"] }],
  },
};

// Every command received, in order - inspectable by the smoke test via the
// (out-of-band) log file this script writes on SIGTERM/exit, OR more simply
// by the smoke test parsing this script's own JSON-line stderr log below.
function record(cmd, args) {
  process.stderr.write(JSON.stringify({ mock: "command_received", cmd, args }) + "\n");
}

function send(sock, msg) {
  if (sock.readyState === sock.OPEN) sock.send(JSON.stringify(msg));
}

wss.on("connection", (sock) => {
  sock.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (msg.cmd === "auth") {
      const ok = msg.args && msg.args.token === TOKEN;
      send(sock, { id: msg.id, ok, error: ok ? undefined : "bad token" });
      if (ok) scheduleEvents(sock);
      return;
    }

    record(msg.cmd, msg.args);

    if (msg.cmd === "npc_context") {
      const npcId = msg.args && msg.args.npcId;
      const data = NPC_CONTEXT[npcId];
      if (data) {
        send(sock, { id: msg.id, ok: true, data });
      } else {
        send(sock, { id: msg.id, ok: false, error: "unknown npc " + npcId });
      }
      return;
    }

    // Any other command (a real game tool call) - not expected in the
    // smoke test since BRAIN_DRY_RUN=1 never spawns the real MCP tool
    // server, but reply ok:true defensively so nothing hangs.
    send(sock, { id: msg.id, ok: true, data: {} });
  });
});

function scheduleEvents(sock) {
  const timeline = [
    [50, "player_join", { player: "Steve", location: { x: 480, y: 75, z: 5, world: "world" } }],
    [150, "player_chat", { player: "Steve", message: "hello?", location: { x: 480, y: 75, z: 5 } }],
    [300, "player_chat", { player: "Steve", message: "anyone home?", location: { x: 481, y: 75, z: 5 } }],
    [700, "npc_interact", { npcId: "mara-baker", player: "Steve", location: { x: 500, y: 75, z: 2 } }],
    [900, "player_chat", { player: "Steve", message: "what have you found?", nearNpcId: "kess-smith", location: { x: 505, y: 75, z: -3 } }],
    // Pushed telemetry (see gadgets/position-tracker.java) - the smoke test
    // asserts this reaches index.mjs's event handler but never lands inside
    // a director scene's batched events (it is pure cache-feed, not a wake
    // event; see config.mjs's DIRECTOR_WAKE_EVENTS).
    [1000, "entity_positions", { at: Date.now(), npcs: [{ id: "mara-baker", world: "world", x: 500.5, y: 75, z: 2.5 }], players: [{ name: "Steve", world: "world", x: 480, y: 75, z: 5 }] }],
    [2200, "npc_death", { npcId: "sella", npcName: "Sella", dead: true, location: { x: 502, y: 75, z: 2 } }],
    [3000, "region_enter", { player: "Steve", regionId: "village-square", regionName: "Village Square", location: { x: 490, y: 75, z: 3 } }],
    [3050, "region_exit", { player: "Steve", regionId: "village-square", regionName: "Village Square", location: { x: 492, y: 75, z: 3 } }],
    [3100, "player_idle_scene", { player: "Steve", location: { x: 493, y: 75, z: 3 }, minutesQuiet: 5 }],
  ];
  for (const [delay, event, data] of timeline) {
    setTimeout(() => send(sock, { event, data }), delay);
  }
}

console.error(JSON.stringify({ mock: "listening", url: `ws://127.0.0.1:${PORT}` }));
