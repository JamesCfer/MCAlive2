// Found the world: eight lone Ancients, each on good flat ground, each with an empty
// store and nothing else. No grace period on food - hunger is lethal from the first
// beat, and a line grows only as fast as it learns to feed itself (see gadget:lineage).
//
//   node scripts/found.mjs            # survey, found, start the world
//   node scripts/found.mjs --survey   # survey and print sites, change nothing
//   node scripts/found.mjs --start    # skip founding, just (re)start the gadget timers
//
// Run this ONCE against a freshly generated world, after the bootstrap checklist in
// docs/new-world-bootstrap.md. It is not idempotent: npc_spawn refuses a duplicate id.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const URL_ = process.env.MCALIVE2_URL || "ws://192.168.40.4:8765";
const TOKEN = process.env.MCALIVE2_TOKEN || "mca2-Xq7vN4kRw9pTz2Lm8Jd3";

const SURVEY_ONLY = process.argv.includes("--survey");
const START_ONLY = process.argv.includes("--start");

// Ring geometry. Chord between neighbours on an 8-point ring is 0.765 * R, so R = 520
// puts the lines ~398 blocks apart and ~520 from spawn: far enough to be separate
// domains, close enough that a lone founder walking at 4.3 b/s can still find game
// before the hunger clock runs out. That balance is the whole point - the last world
// died at 1600.
const RING = 520;
const MIN_SEPARATION = 300;

const founders = JSON.parse(fs.readFileSync(path.join(HERE, "founders.json"), "utf8"));

// ---------------------------------------------------------------- bridge

let ws, seq = 0;
const pending = new Map();

function call(cmd, args = {}) {
  return new Promise((resolve, reject) => {
    const id = String(++seq);
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, cmd, args }));
  });
}

async function cmd(name, args) {
  const r = await call(name, args);
  if (!r.ok) throw new Error(`${name}: ${r.error}`);
  return r.data;
}

function connect() {
  return new Promise((resolve, reject) => {
    ws = new WebSocket(URL_);
    ws.onopen = async () => {
      const a = await call("auth", { token: TOKEN });
      if (!a.ok) return reject(new Error("auth failed: " + a.error));
      resolve();
    };
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) {
        pending.get(m.id).resolve(m);
        pending.delete(m.id);
      }
    };
    ws.onerror = (e) => reject(new Error("ws error: " + (e.message || e)));
    setTimeout(() => reject(new Error("connect timeout")), 20000);
  });
}

// ---------------------------------------------------------------- survey

const BAD = new Set(["water", "lava", "ice", "packed_ice", "blue_ice", "kelp", "kelp_plant", "seagrass"]);

/**
 * Is this a place a founder can live? A 16x16 scan is the full-resolution ceiling
 * (256 columns), and we want the middle of it open, level within a block, and dry.
 * Level matters more than it sounds: the step-walker climbs 1 and drops 3, so a
 * founder on broken ground slides into a hole and never climbs back out.
 */
async function probe(x, z) {
  const d = await cmd("scan_area", { x1: x - 8, z1: z - 8, x2: x + 7, z2: z + 7 });
  const cols = d.columns || [];
  if (!cols.length) return null;

  // Wetness is judged over the whole 16x16 - a founder does not want a camp on a
  // shoreline - but LEVELNESS only over the central 9x9 they actually stand and build
  // on. Judging flatness across the full window rejects a perfectly good clearing for
  // having a hillside at one corner, which is what left six of eight sites broken.
  let wet = 0;
  let min = Infinity, max = -Infinity;
  let cmin = Infinity, cmax = -Infinity;
  const heights = [];
  for (const c of cols) {
    if (BAD.has(c.material)) wet++;
    if (c.y < min) min = c.y;
    if (c.y > max) max = c.y;
    if (Math.abs(c.x - x) <= 4 && Math.abs(c.z - z) <= 4) {
      if (c.y < cmin) cmin = c.y;
      if (c.y > cmax) cmax = c.y;
      heights.push(c.y);
    }
  }
  if (!heights.length) return null;
  heights.sort((a, b) => a - b);
  const y = Math.round(heights[Math.floor(heights.length / 2)]);
  const spread = cmax - cmin;
  const outer = max - min;
  const score = spread * 6 + outer + wet * 3;
  return { x, z, y, spread, outer, wet, score, ok: spread <= 2 && wet === 0 && y > 62 };
}

/** Search out from a nominal point on the ring for ground that will actually do. */
async function findSite(bearingDeg, taken) {
  const rad = (bearingDeg * Math.PI) / 180;
  const candidates = [];
  for (const dr of [0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180]) {
    for (const dTheta of [0, 4, -4, 8, -8, 12, -12, 16, -16, 20, -20]) {
      const r = RING + dr;
      const th = rad + (dTheta * Math.PI) / 180;
      candidates.push([Math.round(Math.cos(th) * r), Math.round(Math.sin(th) * r)]);
    }
  }
  let best = null;
  for (const [x, z] of candidates) {
    if (taken.some((t) => Math.hypot(t.x - x, t.z - z) < MIN_SEPARATION)) continue;
    let p;
    try {
      p = await probe(x, z);
    } catch {
      continue;
    }
    if (!p) continue;
    if (p.ok) return p;
    if (!best || p.score < best.score) best = p;
  }
  return best;
}

// ---------------------------------------------------------------- founding

const CHEST_OFFSET = [3, 0, 0];
const TABLE_OFFSET = [3, 0, 2];
const FURNACE_OFFSET = [3, 0, 3];
const FIELD_OFFSET = [12, 0, 0];

async function foundOne(f, site) {
  const line = f.faction.replace(/^line-/, "");
  // scan_area reports the surface BLOCK's own y. Everything that stands on the ground -
  // a body, a chest, a bench - belongs one above it, or it replaces the ground instead.
  const ground = site.y;
  const stand = site.y + 1;
  const home = { world: "world", x: site.x, y: stand, z: site.z };

  await cmd("npc_spawn", {
    id: f.id,
    name: f.name,
    entityType: "MANNEQUIN",
    defense: "fight",
    world: "world",
    x: site.x + 0.5,
    y: stand,
    z: site.z + 0.5,
    snap: true,
    home,
    schedule: f.schedule,
  });

  // The character sheet, verbatim from the world before this one. Only the state is
  // fresh: full belly, no kin, needs at a neutral start.
  await cmd("ledger_put", {
    collection: "npcs",
    record: {
      id: f.id,
      name: f.name,
      alignment: f.alignment,
      appearance: f.appearance,
      ethos: f.ethos,
      personality: f.personality,
      wants: f.wants,
      home,
      schedule: f.schedule,
      alive: true,
      faction: f.faction,
      bloodline: { house: f.faction, generation: 0, parents: [], progenitor: true },
      activity: "alone, and hungry",
      hunger: 20,
      fedState: "fed",
      needs: {
        fatigue: 16,
        purpose: 10,
        curiosity: 14,
        safety: 12,
        belonging: 4,
        shelter: 6,
        wealth: 0,
        hunger: 20,
      },
    },
  });

  const at = (o) => ({ x: site.x + o[0], y: stand, z: site.z + o[2] });
  const chest = at(CHEST_OFFSET);
  const table = at(TABLE_OFFSET);
  const furnace = at(FURNACE_OFFSET);
  // The field only needs x/z - gadget:farm reads its own level from the plot corners.
  const field = { x: site.x + FIELD_OFFSET[0], y: ground, z: site.z + FIELD_OFFSET[2] };

  // An empty chest, a bench and a fire. Nothing in them - that is the point.
  await cmd("set_block", { world: "world", ...chest, material: "CHEST" });
  await cmd("set_block", { world: "world", ...table, material: "CRAFTING_TABLE" });
  await cmd("set_block", { world: "world", ...furnace, material: "FURNACE" });

  await cmd("ledger_put", {
    collection: "places",
    record: {
      id: `domain-${line}`,
      name: f.domain,
      kind: "seat",
      origin: { x: site.x, y: site.y, z: site.z },
      builtBy: "ai",
      description: `${f.domain}. Founded by ${f.name}, alone, with an empty store.`,
    },
  });
  await cmd("ledger_put", {
    collection: "places",
    record: {
      id: `store-${line}`,
      name: `${f.domain} store`,
      kind: "stockpile",
      origin: chest,
      builtBy: "ai",
      description: `The larder of ${f.domain}. Everything this line eats passes through it.`,
    },
  });

  return { chest, table, furnace, field, home };
}

// ---------------------------------------------------------------- capability

// Install EVERY gadget source in the repo, not just the changed ones. The whole
// capability set lives in brain/gadgets/ now, so founding does not depend on the
// server's gadgets.json having survived the wipe. Defining is idempotent.
//
// position-tracker and world-scan are skipped: the brain installs those itself on boot,
// with its own descriptions and (for the tracker) its own run call.
const BRAIN_OWNED = new Set(["position-tracker", "world-scan"]);

async function installGadgets() {
  const dir = path.join(HERE, "..", "brain", "gadgets");
  let descriptions = {};
  try {
    descriptions = JSON.parse(fs.readFileSync(path.join(dir, "_descriptions.json"), "utf8"));
  } catch {
    /* descriptions are cosmetic */
  }
  const ids = fs
    .readdirSync(dir)
    .filter((n) => n.endsWith(".java"))
    .map((n) => n.replace(/\.java$/, ""))
    .filter((id) => !BRAIN_OWNED.has(id))
    .sort();

  let failed = 0;
  for (const id of ids) {
    const source = fs.readFileSync(path.join(dir, `${id}.java`), "utf8");
    try {
      await cmd("gadget_define", { id, source, description: descriptions[id] || id });
      // A green define is not proof the class is what we think it is - read it back.
      const got = await cmd("gadget_get", { id });
      if (got.source.length !== source.length) {
        throw new Error(`server copy is ${got.source.length} chars, sent ${source.length}`);
      }
      console.log(`  ok   ${id.padEnd(22)} ${String(source.length).padStart(6)} chars`);
    } catch (e) {
      failed++;
      console.log(`  FAIL ${id.padEnd(22)} ${e.message}`);
    }
  }
  if (failed) throw new Error(`${failed} gadget(s) failed to install - fix before founding`);
  console.log(`  ${ids.length} gadgets installed and verified`);
}

// ---------------------------------------------------------------- the standing world

/**
 * Start every timer, in the order project.md documents. Hunger runs LETHAL at its
 * original rate - 1 point per 2 minutes, 40 minutes from a full belly to the first
 * damage. No grace period, by request.
 */
async function startWorld(sites) {
  const stores = {};
  const fields = [];
  const reserved = [];
  for (const [id, s] of Object.entries(sites)) {
    const f = founders.find((x) => x.id === id);
    stores[f.faction] = s.chest;
    // The founder holds the field until lineage calls a real farmer and reassigns it.
    fields.push({ faction: f.faction, farmer: id, chest: s.chest, at: { x: s.field.x, z: s.field.z }, size: 9 });
  }

  const out = {};
  out.presence = await cmd("gadget:presence", { radius: 1, periodTicks: 100 });
  out.needs = await cmd("gadget:needs", { stores, periodTicks: 1200 });
  out.hunger = await cmd("gadget:hunger", {
    stores,
    lethal: true,
    drainPerBeat: 1,
    periodTicks: 2400,
  });
  out.pursuits = await cmd("gadget:pursuits", { stores, warehouses: {}, reserved, periodTicks: 40 });
  out.live = await cmd("gadget:pursuits", { action: "live", on: true });
  out.farm = await cmd("gadget:farm", { fields, periodTicks: 15 });
  out.roster = await cmd("gadget:roster", { periodTicks: 200 });
  out.groundskeeper = await cmd("gadget:groundskeeper", { periodTicks: 300 });
  out.reclaim = await cmd("gadget:reclaim", { periodTicks: 60 });
  out.lineage = await cmd("gadget:lineage", { stores, maxKin: 5, reservePerMouth: 20, periodTicks: 1200 });
  return out;
}

// ---------------------------------------------------------------- main

async function main() {
  await connect();
  console.log(`connected to ${URL_}`);

  if (START_ONLY) {
    console.log("\ninstalling gadgets...");
    await installGadgets();
    const places = await cmd("ledger_query", { collection: "places" });
    const sites = {};
    for (const f of founders) {
      const line = f.faction.replace(/^line-/, "");
      const seat = places.records.find((p) => p.id === `domain-${line}`);
      const store = places.records.find((p) => p.id === `store-${line}`);
      if (!seat || !store) {
        console.log(`  ! no recorded seat/store for ${line} - skipping`);
        continue;
      }
      sites[f.id] = {
        chest: store.origin,
        field: { x: seat.origin.x + FIELD_OFFSET[0], z: seat.origin.z + FIELD_OFFSET[2] },
      };
    }
    const started = await startWorld(sites);
    console.log(JSON.stringify(started, null, 1));
    ws.close();
    return;
  }

  console.log(`\nsurveying ${founders.length} sites on a ${RING}-block ring...`);
  const taken = [];
  const sites = {};
  for (let i = 0; i < founders.length; i++) {
    const f = founders[i];
    const site = await findSite(i * 45, taken);
    if (!site) throw new Error(`no site found for ${f.id}`);
    taken.push(site);
    sites[f.id] = site;
    const flag = site.ok ? "ok  " : "best";
    console.log(
      `  ${flag} ${f.name.padEnd(9)} ${String(site.x).padStart(6)},${String(site.y).padStart(4)},${String(site.z).padStart(6)}` +
        `   9x9 spread ${site.spread}  16x16 ${site.outer}  wet ${site.wet}   ${f.domain}`
    );
  }

  const pairs = [];
  const ids = Object.keys(sites);
  for (let i = 0; i < ids.length; i++) {
    for (let j = i + 1; j < ids.length; j++) {
      pairs.push(Math.round(Math.hypot(sites[ids[i]].x - sites[ids[j]].x, sites[ids[i]].z - sites[ids[j]].z)));
    }
  }
  console.log(`\n  closest pair: ${Math.min(...pairs)} blocks   furthest: ${Math.max(...pairs)} blocks`);

  if (SURVEY_ONLY) {
    console.log("\n--survey: nothing changed.");
    ws.close();
    return;
  }

  console.log("\ninstalling gadgets...");
  await installGadgets();

  console.log("\nfounding...");
  const built = {};
  for (const f of founders) {
    built[f.id] = await foundOne(f, sites[f.id]);
    console.log(`  ${f.name} stands at ${f.domain}, alone, store empty`);
  }

  console.log("\nstarting the world (hunger LETHAL, 1 point / 2 min, no grace)...");
  const started = await startWorld(built);
  for (const [k, v] of Object.entries(started)) {
    console.log(`  ${k.padEnd(14)} ${JSON.stringify(v)}`);
  }

  console.log("\nfounded. Eight lines, eight mouths, nothing banked.");
  ws.close();
}

main().catch((e) => {
  console.error("FAILED:", e.message);
  process.exit(1);
});
