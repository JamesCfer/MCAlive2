// The chronicle: the director's D&D-style campaign notebook - prose canon
// kept ALONGSIDE the ledger, never instead of it. The ledger is machine
// state (structured facts/promises/quests the plugin and tools query); the
// chronicle is the story as written words, for the director's own future
// context. Three files under brain/chronicle/ (created lazily on first
// write, like state/ in decisions-journal.mjs):
//
//   world-bible.md         lasting truths - places, legends, permanent
//                          changes to the world; compactable
//   arcs.md                active storylines, each with a status
//                          (brewing/active/resolved); compactable
//   sessions/YYYY-MM-DD.md a dated journal of what actually happened, one
//                          file per UTC day - the PERMANENT record, append-
//                          only forever (rewriteFile refuses sessions)
//
// digest() folds the chronicle into a compact prompt block for the
// director's system prompt (lib/director-turn.mjs): world-bible headings
// with the first paragraph under each, arcs verbatim, and the tails of the
// two most recent session files, all under a hard character budget.
//
// Env vars:
//   CHRONICLE_DIR            overrides the chronicle root (tests)
//   CHRONICLE_PROMPT_BUDGET  digest()'s default character budget (6000)

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BRAIN_ROOT = path.resolve(__dirname, "..");

// Resolved per call (not at module load) so tests can point CHRONICLE_DIR
// at a temp dir after import - mirrors decisions-journal.mjs's maxBytes().
function chronicleRoot() {
  return process.env.CHRONICLE_DIR || path.join(BRAIN_ROOT, "chronicle");
}

// The chronicle is brain-local state, not in git. When the premise of the world
// changes (it did on 2026-08-23: no gods, no lines, every NPC a player), the old
// prose would keep feeding the director a world that no longer exists. The repo
// carries an epoch number in lore/CHRONICLE_EPOCH; when it differs from the one
// stamped in the chronicle dir, the old chronicle is archived beside it and a
// fresh one started. Idempotent; runs once at brain boot.
export function resetIfNewEpoch() {
  let epoch = "0";
  try { epoch = fs.readFileSync(path.join(BRAIN_ROOT, "lore", "CHRONICLE_EPOCH"), "utf8").trim(); } catch { return { reset: false, epoch }; }
  const root = chronicleRoot();
  const stamp = path.join(root, ".epoch");
  let have = null;
  try { have = fs.readFileSync(stamp, "utf8").trim(); } catch { have = null; }
  if (have === epoch) return { reset: false, epoch };
  if (fs.existsSync(root)) {
    const archive = `${root}-archive-epoch${have || "0"}-${Date.now()}`;
    fs.renameSync(root, archive);
  }
  fs.mkdirSync(root, { recursive: true });
  fs.writeFileSync(stamp, `${epoch}\n`);
  return { reset: true, epoch };
}

function sessionsDir() {
  return path.join(chronicleRoot(), "sessions");
}

/** Today's session date, UTC (sessions roll over at UTC midnight). */
export function todaySessionDate(now = new Date()) {
  return now.toISOString().slice(0, 10);
}

/** Map a chronicle file name ("world-bible" | "arcs" | "session" |
 * "session:YYYY-MM-DD") to its absolute path. Throws on anything else. */
function resolvePath(file) {
  if (file === "world-bible") return path.join(chronicleRoot(), "world-bible.md");
  if (file === "arcs") return path.join(chronicleRoot(), "arcs.md");
  if (file === "session") return path.join(sessionsDir(), `${todaySessionDate()}.md`);
  const dated = /^session:(\d{4}-\d{2}-\d{2})$/.exec(file);
  if (dated) return path.join(sessionsDir(), `${dated[1]}.md`);
  throw new Error(`unknown chronicle file "${file}" - use world-bible, arcs, session, or session:YYYY-MM-DD`);
}

function isSessionFile(file) {
  return file === "session" || /^session:\d{4}-\d{2}-\d{2}$/.test(file);
}

function readIfExists(p) {
  try {
    return fs.readFileSync(p, "utf8");
  } catch {
    return "";
  }
}

/** Append one timestamped, headed entry to a chronicle file. `file` is
 * "world-bible" | "arcs" | "session" ("session" targets TODAY's dated
 * journal, creating it with a "# Session YYYY-MM-DD" header on first
 * write). Returns the absolute path written. */
export function appendEntry(file, heading, text) {
  const p = resolvePath(file);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  if (file === "session" && !fs.existsSync(p)) {
    fs.writeFileSync(p, `# Session ${todaySessionDate()}\n`);
  }
  fs.appendFileSync(p, `\n## ${heading} — ${new Date().toISOString()}\n\n${text}\n`);
  return p;
}

/** Read a chronicle file's contents ("" if it doesn't exist yet). `file`
 * additionally accepts "session:YYYY-MM-DD" to read a past day's journal.
 * `tail` returns only the last N characters. */
export function readFile(file, { tail } = {}) {
  const text = readIfExists(resolvePath(file));
  if (Number.isFinite(tail) && tail > 0 && text.length > tail) return text.slice(-tail);
  return text;
}

/** Replace a chronicle file wholesale - ONLY for "world-bible" and "arcs"
 * (periodic compaction). Session journals are the permanent record and can
 * never be rewritten. */
export function rewriteFile(file, fullText) {
  if (isSessionFile(file)) {
    throw new Error("session journals are the permanent record - they can never be rewritten, only appended to");
  }
  if (file !== "world-bible" && file !== "arcs") {
    throw new Error(`chronicle rewrite is only allowed for world-bible and arcs, not "${file}"`);
  }
  const p = resolvePath(file);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, fullText);
  return p;
}

/** Sorted (oldest first) list of session dates ("YYYY-MM-DD") on disk. */
export function listSessions() {
  let entries;
  try {
    entries = fs.readdirSync(sessionsDir());
  } catch {
    return [];
  }
  return entries
    .map((f) => /^(\d{4}-\d{2}-\d{2})\.md$/.exec(f))
    .filter(Boolean)
    .map((m) => m[1])
    .sort();
}

/** World-bible skim: every "## " heading line plus the FIRST paragraph
 * under it - enough to remind the director what canon exists without
 * paying for the full prose. A bible with content but no headings yet is
 * passed through whole (the overall budget still caps it). */
function skimWorldBible(text) {
  const lines = text.split("\n");
  if (!lines.some((l) => l.startsWith("## "))) return text.trim();
  const out = [];
  let i = 0;
  while (i < lines.length) {
    if (!lines[i].startsWith("## ")) { i += 1; continue; }
    out.push(lines[i]);
    i += 1;
    while (i < lines.length && !lines[i].trim() && !lines[i].startsWith("## ")) i += 1; // skip blanks
    const para = [];
    while (i < lines.length && lines[i].trim() && !lines[i].startsWith("## ")) {
      para.push(lines[i]);
      i += 1;
    }
    if (para.length) out.push(para.join("\n"));
  }
  return out.join("\n");
}

/** Tail of one file's text under a per-section character allowance, with a
 * marker when earlier content was cut. */
function tailSection(text, allowance) {
  const trimmed = text.trim();
  if (trimmed.length <= allowance) return trimmed;
  return "[... earlier entries omitted]\n" + trimmed.slice(-allowance);
}

/** Compact prompt block for the director's system prompt: world-bible
 * headings + first paragraphs, arcs verbatim, and the tails of the two most
 * recent session journals - hard-capped at `budgetChars` with a truncation
 * marker. Returns "" when the chronicle is empty (the caller skips the
 * whole section then). */
export function digest(budgetChars = Number(process.env.CHRONICLE_PROMPT_BUDGET) || 6000) {
  const worldBible = readIfExists(resolvePath("world-bible")).trim();
  const arcs = readIfExists(resolvePath("arcs")).trim();
  const sessions = listSessions();

  const parts = [];
  if (worldBible) parts.push(`WORLD BIBLE (headings + first lines - chronicle_read "world-bible" for the full text):\n${skimWorldBible(worldBible)}`);
  if (arcs) parts.push(`ARCS:\n${arcs}`);
  // Two most recent session journals, oldest of the pair first, each
  // allowed at most a quarter of the budget so the bible/arcs keep room.
  const sessionAllowance = Math.max(200, Math.floor(budgetChars / 4));
  for (const date of sessions.slice(-2)) {
    const text = readIfExists(path.join(sessionsDir(), `${date}.md`)).trim();
    if (text) parts.push(`SESSION ${date} (tail):\n${tailSection(text, sessionAllowance)}`);
  }
  if (!parts.length) return "";

  let out = parts.join("\n\n");
  if (out.length > budgetChars) {
    const marker = `\n[... chronicle digest truncated to fit ${budgetChars} chars - use chronicle_read for the rest]`;
    out = out.slice(0, Math.max(0, budgetChars - marker.length)) + marker;
  }
  return out;
}
