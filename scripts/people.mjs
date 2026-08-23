// Put people in the world and set them living.
//
//   node scripts/people.mjs            # install gadgets, spawn everyone in people.json
//                                      # who is not already in the ledger, start the world
//   node scripts/people.mjs --start    # install gadgets and (re)start timers only - the
//                                      # whole post-restart checklist
//   node scripts/people.mjs --status   # print everyone
//
// Every person starts at world spawn with nothing, exactly like a player. Nothing here
// picks good ground for them, stocks a chest, or hands out tools.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const URL_ = process.env.MCALIVE2_URL || "ws://192.168.40.4:8765";
const TOKEN = process.env.MCALIVE2_TOKEN || "mca2-Xq7vN4kRw9pTz2Lm8Jd3";

const START_ONLY = process.argv.includes("--start");
const STATUS = process.argv.includes("--status");

// ---------------------------------------------------------------- bridge

let ws, seq = 0;
const pending = new Map();

function call(cmd, args = {}) {
  return new Promise((resolve) => {
    const id = String(++seq);
    pending.set(id, resolve);
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
        pending.get(m.id)(m);
        pending.delete(m.id);
      }
    };
    ws.onerror = (e) => reject(new Error("ws error: " + (e.message || e)));
    setTimeout(() => reject(new Error("connect timeout")), 20000);
  });
}

// ---------------------------------------------------------------- gadgets

// The brain installs these two itself on boot.
const BRAIN_OWNED = new Set(["position-tracker", "world-scan"]);

async function installGadgets() {
  const dir = path.join(HERE, "..", "brain", "gadgets");
  let descriptions = {};
  try {
    descriptions = JSON.parse(fs.readFileSync(path.join(dir, "_descriptions.json"), "utf8"));
  } catch {
    /* cosmetic */
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
      const got = await cmd("gadget_get", { id });
      if (got.source.length !== source.length) throw new Error(`server copy ${got.source.length} chars, sent ${source.length}`);
      console.log(`  ok   ${id.padEnd(22)} ${String(source.length).padStart(6)} chars`);
    } catch (e) {
      failed++;
      console.log(`  FAIL ${id.padEnd(22)} ${e.message}`);
    }
  }
  if (failed) throw new Error(`${failed} gadget(s) failed to install`);
}

// ---------------------------------------------------------------- the standing world

async function startWorld() {
  const out = {};
  // keeps the chunks people stand in loaded when nobody is online
  out.presence = await cmd("gadget:presence", { radius: 1, periodTicks: 100 });
  // frees anyone who ends up inside a block - by walking or digging, never lifting
  out.groundskeeper = await cmd("gadget:groundskeeper", { periodTicks: 300 });
  // the people themselves; up to five strangers arrive at 5am each day until the cap
  out.people = await cmd("gadget:people", { populationCap: 40 });
  // and they show in the tab list
  out.tablist = await cmd("gadget:tablist", {});
  // villages: found, joined, inns asked for
  out.villages = await cmd("gadget:villages", {});
  return out;
}

async function printStatus() {
  const s = await cmd("gadget:people", { action: "status" });
  console.log(`\nrunning=${s.running}  beats=${s.beats}`);
  for (const p of s.people) {
    const skills = Object.entries(p.skills).map(([k, v]) => `${k}:${v}`).join(" ") || "-";
    console.log(
      `  ${p.name.padEnd(7)} ${p.alive ? "alive" : "DEAD "}  hp ${String(p.hp).padStart(4)}  hunger ${String(p.hunger).padStart(2)}  ` +
        `${p.need.padEnd(11)} happy ${String(p.happiness).padStart(3)}%  bag ${String(p.stacks).padStart(2)}/36  ` +
        `[${p.job}] ${p.doing}   ${skills}`
    );
  }
}

// ---------------------------------------------------------------- main

async function main() {
  await connect();
  console.log(`connected to ${URL_}`);

  if (STATUS) {
    await printStatus();
    ws.close();
    return;
  }

  console.log("\ninstalling gadgets...");
  await installGadgets();

  if (!START_ONLY) {
    const roster = JSON.parse(fs.readFileSync(path.join(HERE, "people.json"), "utf8"));
    const have = new Set((await cmd("ledger_query", { collection: "npcs" })).records.map((r) => r.id));
    console.log("\nspawning at world spawn...");
    for (const p of roster) {
      if (have.has(p.id)) {
        console.log(`  ${p.name.padEnd(7)} already here`);
        continue;
      }
      const rec = await cmd("gadget:people", { action: "spawn", ...p });
      console.log(`  ${p.name.padEnd(7)} at ${rec.home.x},${rec.home.y},${rec.home.z}   ${p.skill} 1   wants to ${p.need.kind}`);
    }
  }

  console.log("\nstarting...");
  const started = await startWorld();
  for (const [k, v] of Object.entries(started)) console.log(`  ${k.padEnd(14)} ${JSON.stringify(v)}`);

  await printStatus();
  ws.close();
}

main().catch((e) => {
  console.error("FAILED:", e.message);
  process.exit(1);
});
