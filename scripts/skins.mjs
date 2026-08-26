// Give everyone their own face.
//
//   node scripts/skins.mjs <names-file>          # publish the pool + reskin the roster
//   node scripts/skins.mjs <names-file> --pool   # publish the pool only
//
// The plugin dresses a mannequin by resolving a real Minecraft username against Mojang
// (NpcManager.applySkin -> Bukkit.createProfile(name).complete()), so a skin here is an
// account that actually exists. The pool lives in the ledger as facts/skin-pool, which
// is where gadget:people reads it - it is not baked into the gadget source, so it can be
// refreshed by rerunning this without recompiling people.java.
import fs from "node:fs";

const URL_ = process.env.MCALIVE2_URL || "ws://192.168.40.27:8765";
// Deliberately not defaulted to the literal token the way the older scripts do: this
// repo is public, and every hardcoded copy is another one to chase down if it is ever
// rotated. Export MCALIVE2_TOKEN before running.
const TOKEN = process.env.MCALIVE2_TOKEN;
if (!TOKEN) {
  console.error("set MCALIVE2_TOKEN (the bridge token) before running this");
  process.exit(1);
}
const FILE = process.argv[2];
const POOL_ONLY = process.argv.includes("--pool");
if (!FILE) { console.error("usage: node scripts/skins.mjs <names-file> [--pool]"); process.exit(1); }

let ws, seq = 0;
const pending = new Map();
const call = (cmd, args = {}) => new Promise((r) => {
  const id = String(++seq); pending.set(id, r); ws.send(JSON.stringify({ id, cmd, args }));
});
async function cmd(name, args) {
  const r = await call(name, args);
  if (!r.ok) throw new Error(`${name}: ${r.error}`);
  return r.data;
}

const names = [...new Set(fs.readFileSync(FILE, "utf8").split("\n").map(s => s.trim()).filter(Boolean))];

ws = new WebSocket(URL_);
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
ws.onopen = async () => {
  const a = await call("auth", { token: TOKEN });
  if (!a.ok) { console.error("auth failed"); process.exit(1); }
  console.log(`connected to ${URL_}`);

  await cmd("ledger_put", { collection: "facts", record: {
    id: "skin-pool",
    kind: "skin-pool",
    description: `${names.length} real Minecraft accounts, verified against Mojang's bulk profile lookup. gadget:people picks from here.`,
    skins: names,
  }});
  console.log(`pool published: ${names.length} names`);
  if (POOL_ONLY) { process.exit(0); }

  const npcs = (await cmd("ledger_query", { collection: "npcs" })).records;
  const live = npcs.filter(n => n.alive !== false);
  // Deal them off a shuffled deck so nobody shares, and so a rerun does not hand out
  // the same faces in the same order.
  const deck = names.slice();
  for (let i = deck.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [deck[i], deck[j]] = [deck[j], deck[i]]; }

  let n = 0;
  for (const npc of live) {
    const skin = deck[n];
    try {
      // Two files, same name, different folders: npc_update writes the BODY
      // (plugins/MCAlive2/npcs.json) and knows nothing about the character sheet in
      // ledger/npcs.json. Write both, or the entity wears a new face while every
      // duplicate check - pickSkin included - still reads the old one.
      await cmd("npc_update", { id: npc.id, skin });
      await cmd("ledger_put", { collection: "npcs", record: { ...npc, skin } });
      console.log(`  ${String(npc.name).padEnd(7)} ${String(npc.skin || "-").padEnd(18)} -> ${skin}`);
      n++;
    } catch (e) {
      console.log(`  ${String(npc.name).padEnd(7)} FAILED: ${e.message}`);
    }
  }
  console.log(`\nreskinned ${n}/${live.length}`);
  process.exit(0);
};
setTimeout(() => { console.error("timeout"); process.exit(1); }, 300000);
