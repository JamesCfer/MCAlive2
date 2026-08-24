// The house library: what people are allowed to know how to build.
//
//   node scripts/blueprints.mjs seed             # (re)generate the built-in starter houses
//   node scripts/blueprints.mjs ingest <path>    # convert a .schem (Sponge v2/v3) into a
//                                                # blueprint, tiered by what it costs to build
//   node scripts/blueprints.mjs list             # show the library, sorted by tier
//   node scripts/blueprints.mjs push             # upload the library to the server ledger
//
// A blueprint is a bottom-up list of {dx,dy,dz,m} blocks plus a bill of materials. The
// material tokens $PLANKS / $LOG / $SLAB / $FENCE resolve at build time against whatever
// wood the builder actually carries, so an oak-gatherer raises an oak house and a
// spruce-gatherer a spruce one. Tier comes from the bill: what it costs in gathered
// resources decides who can afford it, so people start with the simple houses and earn
// their way up. gadget:villages picks the best blueprint a member can afford;
// gadget:people raises it block by block from the bag.
//
// Ingested schematics are simplified to what an NPC can gather and craft: wooden
// stairs/slabs/fences fold into the plank bill, stone-family blocks become cobblestone,
// glass and doors become openings, terrain blocks are dropped. A schematic that is
// mostly blocks nobody here can produce is rejected rather than mangled.

import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIR = path.join(HERE, "..", "brain", "blueprints");
const URL_ = process.env.MCALIVE2_URL || "ws://192.168.40.4:8765";
const TOKEN = process.env.MCALIVE2_TOKEN || "mca2-Xq7vN4kRw9pTz2Lm8Jd3";

// ---------------------------------------------------------------- tiering

// Cost in gathered-resource units: a plank is the unit, cobble mines slower than trees
// chop, logs kept whole are four planks of tree.
function costOf(materials) {
  return (
    (materials.$PLANKS || 0) +
    4 * (materials.$LOG || 0) +
    1.2 * (materials.COBBLESTONE || 0) +
    3 * (materials.OTHER || 0)
  );
}

function tierOf(materials) {
  const c = costOf(materials);
  if (c < 120) return 0;
  if (c < 260) return 1;
  if (c < 600) return 2;
  if (c < 1400) return 3;
  if (c < 3000) return 4;
  return 5;
}

// ---------------------------------------------------------------- purpose and size

// What a building is for, guessed from its name (a search-term hint breaks ties).
// The order matters: the first match wins, and "house" words come last because
// half the uploads are called "medieval tavern house".
const PURPOSES = [
  ["inn", /tavern|\binn\b|pub\b|alehouse/],
  ["church", /church|chapel|cathedral|temple|shrine|monastery/],
  ["smithy", /smith|forge|foundry|smelter/],
  ["farm", /\bfarm|barn|stable|coop|pen\b|granary|silo|greenhouse|orchard/],
  ["storage", /storage|warehouse|store\s?room|depot|cellar|vault/],
  ["shop", /shop|market|stall|store\b|bakery|butcher|trading|merchant|bank/],
  ["tower", /tower|watchtower|lookout|keep\b|bastion|outpost/],
  ["civic", /town\s?hall|hall\b|library|school|tribunal|court|guild|meeting/],
  ["dock", /dock|pier|harbou?r|port\b|boathouse|lighthouse|fisher/],
  ["mill", /windmill|watermill|\bmill\b|sawmill/],
  ["well", /\bwell\b|fountain/],
  ["bridge", /bridge/],
  ["house", /house|home|cottage|cabin|hut|shack|residence|manor|dwelling|starter|survival|cozy|casa|villa/],
];

function purposeOf(name, hint) {
  const n = String(name).toLowerCase();
  for (const [p, re] of PURPOSES) if (re.test(n)) return p;
  if (hint) for (const [p, re] of PURPOSES) if (re.test(hint)) return p;
  return "misc";
}

// Which plot it needs. Village house plots are "small"; the rest wait for asks
// (town halls, big churches) that know to clear more ground.
function sizeClassOf(w, d) {
  const m = Math.max(w, d);
  return m <= 16 ? "small" : m <= 24 ? "medium" : "large";
}

const GRID_CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

/** Blocks-form -> the compact grid form everything is stored in. */
function blocksToGrid(bp) {
  const palette = [];
  const palIdx = {};
  const cells = new Map();
  let maxY = 0;
  for (const b of bp.blocks) {
    if (!(b.m in palIdx)) { palIdx[b.m] = palette.length; palette.push(b.m); }
    cells.set(`${b.dx},${b.dy},${b.dz}`, GRID_CH[palIdx[b.m]]);
    if (b.dy > maxY) maxY = b.dy;
  }
  if (palette.length > GRID_CH.length) throw new Error(`${palette.length} materials - palette overflow`);
  const layers = [];
  for (let y = 0; y <= maxY; y++) {
    let s = "";
    for (let z = 0; z < bp.d; z++)
      for (let x = 0; x < bp.w; x++) s += cells.get(`${x},${y},${z}`) || ".";
    layers.push(s);
  }
  const { blocks, ...rest } = bp;
  return { ...rest, h: maxY + 1, palette, layers };
}

/** Recount the bill and dimensions straight from the layers, so a grid can never
 *  disagree with its own metadata. */
function annotate(g, hint) {
  const mats = {};
  let n = 0;
  for (let y = 0; y < g.layers.length; y++) {
    const s = g.layers[y];
    if (s.length !== g.w * g.d) throw new Error(`layer ${y} is ${s.length} chars, expected ${g.w * g.d} - corrupt`);
    for (const ch of s) {
      if (ch === ".") continue;
      const m = g.palette[GRID_CH.indexOf(ch)];
      if (m == null) throw new Error(`layer ${y}: '${ch}' not in palette`);
      mats[m] = (mats[m] || 0) + 1;
      n++;
    }
  }
  if (!n) throw new Error("empty grid");
  g.h = g.layers.length;
  g.materials = mats;
  g.tier = tierOf(mats);
  g.purpose = g.purpose || purposeOf(g.name || g.id, hint);
  g.sizeClass = sizeClassOf(g.w, g.d);
  return g;
}

// ---------------------------------------------------------------- seed designs

function box(blocks, mats, x1, y1, z1, x2, y2, z2, m) {
  for (let y = y1; y <= y2; y++)
    for (let z = z1; z <= z2; z++)
      for (let x = x1; x <= x2; x++) put(blocks, mats, x, y, z, m);
}

function put(blocks, mats, dx, dy, dz, m) {
  const key = `${dx},${dy},${dz}`;
  const old = blocks.get(key);
  if (old) mats[old.m] = (mats[old.m] || 1) - 1;
  blocks.set(key, { dx, dy, dz, m });
  mats[m] = (mats[m] || 0) + 1;
}

function cut(blocks, mats, dx, dy, dz) {
  const key = `${dx},${dy},${dz}`;
  const old = blocks.get(key);
  if (old) {
    mats[old.m] = (mats[old.m] || 1) - 1;
    blocks.delete(key);
  }
}

/** A simple house: plank or cobble walls, log corner posts, a doorway, window gaps,
 *  and a stepped plank roof. Floor at dy 0, walls dy 1..wallH. */
function house({ id, name, w, d, wallH, wall, corners, windows }) {
  const blocks = new Map();
  const mats = {};
  box(blocks, mats, 0, 0, 0, w - 1, 0, d - 1, "$PLANKS"); // floor
  for (let y = 1; y <= wallH; y++)
    for (let z = 0; z < d; z++)
      for (let x = 0; x < w; x++) {
        const edge = x === 0 || z === 0 || x === w - 1 || z === d - 1;
        if (!edge) continue;
        const corner = (x === 0 || x === w - 1) && (z === 0 || z === d - 1);
        put(blocks, mats, x, y, z, corner && corners ? "$LOG" : wall);
      }
  // doorway: two-high gap mid-front
  cut(blocks, mats, Math.floor(w / 2), 1, 0);
  cut(blocks, mats, Math.floor(w / 2), 2, 0);
  // windows: gaps at eye height on the sides
  if (windows) {
    for (const z of [Math.floor(d / 2)]) {
      cut(blocks, mats, 0, 2, z);
      cut(blocks, mats, w - 1, 2, z);
    }
    if (w >= 7) {
      cut(blocks, mats, Math.floor(w / 4), 2, d - 1);
      cut(blocks, mats, w - 1 - Math.floor(w / 4), 2, d - 1);
    }
  }
  // stepped roof: each layer pulls in one ring until it closes
  let inset = 0;
  for (let y = wallH + 1; ; y++) {
    const x1 = inset, x2 = w - 1 - inset, z1 = inset, z2 = d - 1 - inset;
    if (x1 > x2 || z1 > z2) break;
    if (x2 - x1 <= 1 || z2 - z1 <= 1) {
      box(blocks, mats, x1, y, z1, x2, y, z2, "$PLANKS");
      break;
    }
    for (let z = z1; z <= z2; z++)
      for (let x = x1; x <= x2; x++) {
        const rim = x === x1 || x === x2 || z === z1 || z === z2;
        if (rim) put(blocks, mats, x, y, z, "$PLANKS");
      }
    inset++;
  }
  const list = [...blocks.values()].sort(
    (a, b) => a.dy - b.dy || a.dz - b.dz || a.dx - b.dx
  );
  for (const k of Object.keys(mats)) if (mats[k] <= 0) delete mats[k];
  return annotate(blocksToGrid({
    id,
    name,
    purpose: "house",
    w,
    d,
    blocks: list,
    materials: mats,
    source: "seed",
  }));
}

function seedDesigns() {
  return [
    house({ id: "shack", name: "Timber shack", w: 5, d: 5, wallH: 3, wall: "$PLANKS", corners: false, windows: false }),
    house({ id: "cabin", name: "Log-post cabin", w: 7, d: 6, wallH: 3, wall: "$PLANKS", corners: true, windows: true }),
    house({ id: "stone-cottage", name: "Stone cottage", w: 9, d: 7, wallH: 4, wall: "COBBLESTONE", corners: true, windows: true }),
    house({ id: "longhouse", name: "Longhouse", w: 11, d: 8, wallH: 4, wall: "COBBLESTONE", corners: true, windows: true }),
  ];
}

// ---------------------------------------------------------------- .schem ingest

/** Minimal big-endian NBT reader - just enough for Sponge schematics. */
function readNbt(buf) {
  let pos = 0;
  const u8 = () => buf.readUInt8(pos++);
  const i16 = () => { const v = buf.readInt16BE(pos); pos += 2; return v; };
  const i32 = () => { const v = buf.readInt32BE(pos); pos += 4; return v; };
  const i64 = () => { const v = buf.readBigInt64BE(pos); pos += 8; return v; };
  const f32 = () => { const v = buf.readFloatBE(pos); pos += 4; return v; };
  const f64 = () => { const v = buf.readDoubleBE(pos); pos += 8; return v; };
  const str = () => { const n = buf.readUInt16BE(pos); pos += 2; const s = buf.toString("utf8", pos, pos + n); pos += n; return s; };
  function payload(type) {
    switch (type) {
      case 1: return buf.readInt8(pos++);
      case 2: return i16();
      case 3: return i32();
      case 4: return i64();
      case 5: return f32();
      case 6: return f64();
      case 7: { const n = i32(); const a = buf.subarray(pos, pos + n); pos += n; return a; }
      case 8: return str();
      case 9: { const t = u8(); const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(payload(t)); return a; }
      case 10: {
        const o = {};
        for (;;) {
          const t = u8();
          if (t === 0) return o;
          o[str()] = payload(t);
        }
      }
      case 11: { const n = i32(); const a = new Int32Array(n); for (let i = 0; i < n; i++) a[i] = i32(); return a; }
      case 12: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i64()); return a; }
      default: throw new Error("nbt tag " + type);
    }
  }
  const rootType = u8();
  if (rootType !== 10) throw new Error("not an NBT compound");
  str(); // root name
  return payload(10);
}

function varints(bytes) {
  const out = [];
  let v = 0, shift = 0;
  for (const b of bytes) {
    v |= (b & 0x7f) << shift;
    if (b & 0x80) shift += 7;
    else { out.push(v); v = 0; shift = 0; }
  }
  return out;
}

/** One block state name -> a material an NPC can gather, or "" to drop, or null to reject. */
function simplify(state) {
  const name = state.replace(/^minecraft:/, "").replace(/\[.*$/, "").toUpperCase();
  if (name.includes("AIR")) return "";
  if (name.endsWith("_PLANKS")) return "$PLANKS";
  if (name.endsWith("_LOG") || name.endsWith("_WOOD")) return "$LOG";
  if (name.endsWith("_SLAB") || name.endsWith("_STAIRS") || name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_TRAPDOOR") || name === "BOOKSHELF" || name === "BARREL" || name === "COMPOSTER" || name === "LECTERN")
    return name.match(/STONE|BRICK|COBBLE|ANDESITE|GRANITE|DIORITE|DEEPSLATE|BLACKSTONE|SANDSTONE|QUARTZ|MUD/) ? "COBBLESTONE" : "$PLANKS";
  if (name.match(/COBBLESTONE|STONE_BRICKS|^STONE$|ANDESITE|GRANITE|DIORITE|DEEPSLATE|BLACKSTONE|BRICKS$|MUD_BRICKS|SANDSTONE|TUFF|WALL$/)) return "COBBLESTONE";
  if (name.includes("GLASS") || name.includes("DOOR") || name.match(/TORCH|LANTERN|LADDER|SIGN|BANNER|CARPET|BUTTON|PRESSURE_PLATE|FLOWER|POTTED|CANDLE|CHAIN|BELL|CAMPFIRE|PANE/)) return "";
  if (name.match(/DIRT|GRASS|PODZOL|SAND|GRAVEL|WATER|LAVA|LEAVES|VINE|SNOW|MOSS|FARMLAND|PATH|COARSE|ROOTED|MYCELIUM|CLAY/)) return "";
  if (name.match(/BED$|CHEST|CRAFTING_TABLE|FURNACE|SMOKER|LOOM|CARTOGRAPHY|SMITHING|STONECUTTER|GRINDSTONE|ANVIL/)) return ""; // furniture: people add their own
  return null; // something nobody here can produce
}

/** Litematica regions -> a flat cell list in a common {w,h,d,get(x,y,z)} shape.
 *  Block states are bit-packed into a long array, entries spanning long boundaries. */
function litematicGrid(root) {
  const regions = root.Regions;
  const boxes = [];
  for (const rg of Object.values(regions)) {
    const p = rg.Position, s = rg.Size;
    boxes.push({
      rg,
      x0: s.x >= 0 ? p.x : p.x + s.x + 1,
      y0: s.y >= 0 ? p.y : p.y + s.y + 1,
      z0: s.z >= 0 ? p.z : p.z + s.z + 1,
      w: Math.abs(s.x), h: Math.abs(s.y), d: Math.abs(s.z),
    });
  }
  const gx = Math.min(...boxes.map((b) => b.x0));
  const gy = Math.min(...boxes.map((b) => b.y0));
  const gz = Math.min(...boxes.map((b) => b.z0));
  const w = Math.max(...boxes.map((b) => b.x0 + b.w)) - gx;
  const h = Math.max(...boxes.map((b) => b.y0 + b.h)) - gy;
  const d = Math.max(...boxes.map((b) => b.z0 + b.d)) - gz;
  const cells = new Map(); // "x,y,z" -> state name
  for (const b of boxes) {
    const palette = b.rg.BlockStatePalette.map((e) => e.Name);
    const longs = b.rg.BlockStates.map((v) => BigInt.asUintN(64, v));
    const bits = Math.max(2, Math.ceil(Math.log2(palette.length)));
    const mask = (1n << BigInt(bits)) - 1n;
    for (let y = 0; y < b.h; y++)
      for (let z = 0; z < b.d; z++)
        for (let x = 0; x < b.w; x++) {
          const idx = (y * b.d + z) * b.w + x;
          const off = idx * bits;
          const li = off >> 6, sb = BigInt(off & 63);
          let v = longs[li] >> sb;
          if ((off & 63) + bits > 64) v |= longs[li + 1] << (64n - sb);
          const state = palette[Number(v & mask)];
          if (state && !state.includes("air"))
            cells.set(`${b.x0 - gx + x},${b.y0 - gy + y},${b.z0 - gz + z}`, state);
        }
  }
  return { w, h, d, get: (x, y, z) => cells.get(`${x},${y},${z}`) || "minecraft:air" };
}

function spongeGrid(root) {
  if (root.Schematic) root = root.Schematic; // sponge v3 wraps everything
  const w = root.Width, h = root.Height, d = root.Length;
  if (w == null || h == null || d == null) throw new Error("no Width/Height/Length - not a Sponge .schem?");
  const container = root.Blocks && root.Blocks.Palette ? root.Blocks : root;
  const palette = container.Palette;
  const data = container.Data || container.BlockData;
  if (!palette || !data) throw new Error("no palette/block data");
  const byIndex = [];
  for (const [state, idx] of Object.entries(palette)) byIndex[Number(idx)] = state;
  const indices = varints(data);
  if (indices.length !== w * h * d) throw new Error(`block data ${indices.length} != ${w}x${h}x${d}`);
  return { w, h, d, get: (x, y, z) => byIndex[indices[x + z * w + y * w * d]] };
}

function ingest(file) {
  let raw = fs.readFileSync(file);
  if (raw[0] === 0x1f && raw[1] === 0x8b) raw = zlib.gunzipSync(raw);
  const root = readNbt(raw);
  const grid = root.Regions ? litematicGrid(root) : spongeGrid(root);
  const { w, h, d } = grid;

  if (w > 32 || d > 32) throw new Error(`footprint ${w}x${d} - too big even for a civic plot (max 32x32)`);
  if (h > 26) throw new Error(`height ${h} - too tall (max 26)`);

  const blocks = [];
  const mats = {};
  let dropped = 0, rejected = 0, total = 0;
  const rejectedNames = new Map();
  // find the ground: the lowest layer that is mostly solid becomes dy 0
  for (let y = 0; y < h; y++)
    for (let z = 0; z < d; z++)
      for (let x = 0; x < w; x++) {
        const state = grid.get(x, y, z);
        const m = simplify(state);
        if (m === "") { if (!state.includes("air")) dropped++; continue; }
        total++;
        if (m === null) {
          rejected++;
          const n = state.replace(/^minecraft:/, "").replace(/\[.*$/, "");
          rejectedNames.set(n, (rejectedNames.get(n) || 0) + 1);
          continue;
        }
        blocks.push({ dx: x, dy: y, dz: z, m });
        mats[m] = (mats[m] || 0) + 1;
      }
  if (total === 0) throw new Error("nothing buildable in it");
  if (rejected / total > 0.25) {
    const top = [...rejectedNames.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5)
      .map(([n, c]) => `${n} x${c}`).join(", ");
    throw new Error(`too fancy: ${Math.round((100 * rejected) / total)}% of it is blocks nobody can produce (${top})`);
  }
  if (blocks.length > 12000) throw new Error(`${blocks.length} blocks - too many (max 12000)`);
  // drop empty bottom layers so dy 0 is the floor
  const minY = blocks.reduce((m, b) => Math.min(m, b.dy), Infinity);
  for (const b of blocks) b.dy -= minY;
  blocks.sort((a, b) => a.dy - b.dy || a.dz - b.dz || a.dx - b.dx);

  const id = path.basename(file).replace(/\.(schem|schematic|litematic)$/i, "")
    .toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  const bp = annotate(blocksToGrid({
    id,
    name: id.replace(/-/g, " "),
    w, d,
    blocks,
    materials: mats,
    source: path.basename(file),
  }));
  return { bp, dropped, rejected };
}

// ---------------------------------------------------------------- library on disk

function loadLibrary() {
  if (!fs.existsSync(DIR)) return [];
  return fs
    .readdirSync(DIR)
    .filter((n) => n.endsWith(".json"))
    .map((n) => JSON.parse(fs.readFileSync(path.join(DIR, n), "utf8")));
}

function saveBlueprint(bp) {
  fs.mkdirSync(DIR, { recursive: true });
  fs.writeFileSync(path.join(DIR, `${bp.id}.json`), JSON.stringify(bp));
}

function blockCount(bp) {
  return bp.layers.reduce((n, s) => n + s.replace(/\./g, "").length, 0);
}

function describe(bp) {
  const bill = Object.entries(bp.materials).map(([k, v]) => `${k} ${v}`).join("  ");
  return `  t${bp.tier}  ${(bp.purpose || "?").padEnd(7)} ${(bp.sizeClass || "?").padEnd(6)} ${bp.id.padEnd(30)} ${String(bp.w).padStart(2)}x${bp.d} h${bp.h}  ${String(blockCount(bp)).padStart(5)} blocks  cost ${Math.round(costOf(bp.materials))}   ${bill}`;
}

// ---------------------------------------------------------------- bridge

async function push() {
  const lib = loadLibrary();
  if (!lib.length) throw new Error("library is empty - run seed first");
  const ws = new WebSocket(URL_);
  let seq = 0;
  const pending = new Map();
  const call = (cmd, args) =>
    new Promise((resolve) => {
      const id = String(++seq);
      pending.set(id, resolve);
      ws.send(JSON.stringify({ id, cmd, args }));
    });
  await new Promise((resolve, reject) => {
    ws.onopen = async () => {
      const a = await call("auth", { token: TOKEN });
      a.ok ? resolve() : reject(new Error("auth failed: " + a.error));
    };
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    };
    ws.onerror = (e) => reject(new Error("ws error: " + (e.message || e)));
    setTimeout(() => reject(new Error("connect timeout")), 20000);
  });
  // gadget:blueprints keeps the library gzipped in the world's persistent data
  // (the ledger's collections are fixed, and facts feed actor prompts). Replace
  // wholesale so deletions on disk propagate, in chunks the bridge is happy with.
  const CHUNK = 60;
  for (let i = 0; i < lib.length; i += CHUNK) {
    const r = await call("gadget:blueprints", {
      action: "put",
      blueprints: lib.slice(i, i + CHUNK),
      replace: i === 0,
    });
    if (!r.ok) throw new Error(`push: ${r.error}`);
    console.log(`  pushed ${Math.min(i + CHUNK, lib.length)}/${lib.length}  (library now ${r.data.total})`);
  }
  ws.close();
}

// ---------------------------------------------------------------- main

const [mode, arg] = process.argv.slice(2);

if (mode === "seed") {
  for (const bp of seedDesigns()) {
    saveBlueprint(bp);
    console.log(describe(bp));
  }
} else if (mode === "ingest") {
  if (!arg) throw new Error("ingest needs a .schem file or a directory of them");
  const files = fs.statSync(arg).isDirectory()
    ? fs.readdirSync(arg).filter((n) => n.match(/\.(schem|schematic|litematic)$/i)).map((n) => path.join(arg, n))
    : [arg];
  for (const f of files) {
    try {
      const { bp, dropped, rejected } = ingest(f);
      saveBlueprint(bp);
      console.log(describe(bp) + (dropped || rejected ? `   (simplified: ${dropped} decorative dropped, ${rejected} substituted away)` : ""));
    } catch (e) {
      console.log(`  SKIP ${path.basename(f)}: ${e.message}`);
    }
  }
} else if (mode === "grid") {
  // A JSON array of compact grids (or a gzip of one, from the browser harvester).
  if (!arg) throw new Error("grid needs a JSON (or .json.gz) file of compact grids");
  let raw = fs.readFileSync(arg);
  if (raw[0] === 0x1f && raw[1] === 0x8b) raw = zlib.gunzipSync(raw);
  const data = JSON.parse(raw.toString("utf8"));
  for (const g of Array.isArray(data) ? data : [data]) {
    try {
      const bp = annotate(g, g.hint);
      delete bp.hint;
      saveBlueprint(bp);
      console.log(describe(bp));
    } catch (e) {
      console.log(`  SKIP ${g.id}: ${e.message}`);
    }
  }
} else if (mode === "migrate") {
  // one-off: convert pre-grid (blocks-form) files on disk to the grid form
  for (const bp of loadLibrary()) {
    if (bp.layers) continue;
    const g = annotate(blocksToGrid(bp));
    saveBlueprint(g);
    console.log(describe(g));
  }
} else if (mode === "list") {
  for (const bp of loadLibrary().sort((a, b) => a.tier - b.tier || costOf(a.materials) - costOf(b.materials)))
    console.log(describe(bp));
} else if (mode === "push") {
  await push();
} else {
  console.log("usage: node scripts/blueprints.mjs seed | ingest <path> | grid <json[.gz]> | migrate | list | push");
}
