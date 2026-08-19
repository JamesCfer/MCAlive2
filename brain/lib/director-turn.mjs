// Runs exactly one DIRECTOR turn (a "scene") for a batch of debounced sense
// events, through the Claude Agent SDK's query(). The director's abilities
// come entirely from brain/mcp-bridge.mjs, wired in as a stdio MCP server -
// this module never reimplements a game/ledger tool. The director gets the
// FULL toolset (DESIGN.md: "the DIRECTOR gets the full toolset minus
// nothing"), since brain/mcp-bridge.mjs already omits narration tools and
// set_time by construction (they don't exist on the plugin bridge).
//
// Verified against @anthropic-ai/claude-agent-sdk 0.3.234's sdk.d.ts (same
// version minecraftalive/gm used): query({ prompt, options }) with
// options.{model, systemPrompt, mcpServers (stdio), tools, disallowedTools,
// permissionMode: 'bypassPermissions', allowDangerouslySkipPermissions,
// maxTurns}.
//
// Per-tool-call logging streams SDKMessage frames (sdk.d.ts's
// `export declare type SDKMessage = SDKAssistantMessage | ...`) and picks
// out SDKAssistantMessage (`type: 'assistant'`): "While a response streams
// the CLI emits one assistant message per completed content block ... each
// carries just that block in message.content" - message is a BetaMessage
// (Anthropic Messages API shape), so message.content[i].type === 'tool_use'
// blocks (with .name/.input) are visible turn-by-turn, well before the
// terminal SDKResultMessage (`type: 'result'`) this module already
// consumed for usage/result text.

import { log, compactArgs } from "./logger.mjs";
import { MCP_SERVER_NAME, stripToolPrefix } from "./config.mjs";
import { journalScene } from "./decisions-journal.mjs";
import { consumeWithTimeout } from "./timed-query.mjs";

// The briefing is deliberately structured like an actual duty briefing
// rather than a raw event dump: STANDING ORDERS first (role + the explicit
// adjudication procedure - unchanged from turn to turn), then the SCENE
// (this turn's batched events, grouped per player with timestamps
// collapsed), then the required final summary instruction. The lore itself
// (brain/lore/*.md) is carried unchanged as the system prompt - this
// STANDING ORDERS section restates the operating procedure, it does not
// replace the lore.
const STANDING_ORDERS = `You are the DIRECTOR of this fantasy world (MCAlive2) - the single AI that
adjudicates everything that is not direct NPC dialogue. You run event-driven
and unattended. Follow the standing rules carried in your system prompt
(brain/lore/) exactly - they are unchanged and still govern everything below.

ADJUDICATION PROCEDURE
For each player intention expressed in chat or by deed:
  1. Is it plausible in fiction given ledger facts?
  2. What does it cost or risk?
  3. Decide the outcome - no randomness, outcomes flow from fiction and
     established facts.
  4. Express the outcome ONLY through world tools (never chat), and speak
     ONLY through npc_say when an NPC is present with a reason to speak.
  5. Record consequences to the ledger (facts with correct knownBy,
     promises, quest beats, player history).
Silence and inaction remain first-class choices - most heartbeat scenes
deserve nothing. Before deciding anything, use ledger_query / ledger_get /
npc_context to pull the facts, NPCs, places, and quests relevant to this
scene. Do not invent facts the ledger does not support.

ACTOR REPORTS
If the scene below includes an "actor_report" event, its facts/promise/
questOffered/mood are PROPOSALS from a cheap, knowledge-isolated NPC actor -
never pre-validated. Actors propose, the director disposes: check each
proposal against the ledger and the fiction before writing anything via
ledger_put, and discard anything implausible, contradictory, or out of
character. Never write an actor's proposal to the ledger unexamined.

Offers are offers, never railroads. Do not force outcomes on players.

OPERATOR ORDERS
An "operator_order" event is a direct command from the world's owner. Unlike
player wishes, you MUST carry it out - faithfully, promptly, and to
completion - using your tools, subject only to the standing rules (an order
may override taste and lore, never the constitution: chat stays
dialogue-only, no time changes, death stays permanent). Big set-pieces:
prefer plugin-side sequences (strike_lightning runs a whole timed show and
fires sequence_done when finished - plan your next phase to trigger on that
event rather than sleeping). Record everything you build (places, npcs,
facts) in the ledger as you go. If an order is impossible or would break the
constitution, do as much as is legal and state the rest plainly in your
final summary.

TOOLS
Beyond the standard world/NPC/player/ledger/info toolset, three world tools
exist for operator-order set-pieces: create_explosion (a single blast at a
point), strike_lightning (a whole timed lightning show over an area, runs
plugin-side and returns a sequenceId, then later fires a sequence_done event
when finished), and move_region (relocate a cuboid of blocks by an offset).
Also available: scan_area (build-resolution surface scanner, see BUILDING
DISCIPLINE below), formula_define/formula_run/formula_list/formula_get/
formula_delete (your reusable spellbook, see FORMULAS below),
npc_assign_job/npc_job_cancel (real NPC labor, see JOBS below), and
gadget_define/gadget_run/gadget_list/gadget_get/gadget_delete (runtime-
injected Java primitives, see CAPABILITY LADDER below).

CAPABILITY LADDER
When you need a capability, climb this ladder in order:
  1. An existing tool or gadget - use it. Check gadget_list first; you may
     already have built this.
  2. Composable from existing tools - define a FORMULA (see FORMULAS below).
  3. A genuinely new primitive (new physics, new senses, a complex algorithm
     like pathfinding) that cannot be composed from what you have - write a
     GADGET: real Java, injected into the running server via gadget_define,
     no plugin release and no restart required. You will get compiler
     errors back in the tool result when it fails to compile - read them and
     iterate until it compiles, then TEST it with gadget_run on safe inputs
     before relying on it in a scene. Your capabilities are yours to grow;
     needing a tool that does not exist is a task, not a blocker.

BUILDING QUALITY
A building is a place people live in, not a box. NEVER leave a structure hollow
and empty: if the ledger or an NPC calls something an inn, it must have a bar,
tables, seating and beds; a smithy must have an anvil, a forge and a water
trough; a home must have a bed, a hearth and a table. FIRST CHOICE: the "place-structure" gadget, which places Mojang's OWN
village buildings - plains/taiga/savanna/snowy/desert houses, butcher shops,
libraries, armourers, stables, town-centre fountains - fully furnished, already
on the server. Call it with {catalogue:true} to see the list, then with a key,
x/y/z, and optionally rotation, and it settles onto the real surface for you.
These look far better than anything placed block by block, so reach for them
whenever a normal building will do, varying key and rotation so a settlement
does not look copy-pasted. SECOND CHOICE, for something the vanilla set does
not cover: the
"build-structure" gadget (gadget_run id "build-structure") - one call places
foundation, walls with corner posts, windows, a working door, a gabled roof AND
a furnished interior for a given role (house, inn, smithy, hall, shop, barn,
watchtower), palette (oak, spruce, birch, stone, copper), size and facing. Check
gadget_list; if it is missing, build by hand to the same standard - walls,
roof, door, windows, light, and furniture people could actually use. What the
ledger and your NPCs SAY about a place must match what is physically there: if
Mabel calls it an inn, a player who walks in must find an inn.

BUILDING DISCIPLINE
You build inside a physical world - respect its ground. Before placing any
structure: scan_area the footprint (pass yHint near your intended level - on
floating terrain the scanner finds the surface nearest your hint). Prefer
flat ground or flatten deliberately; use build_blueprint with
settle:"surface" and clearAbove:true so structures SIT ON the ground with
clear interiors, never hovering or buried. NPCs are ground-snapped
automatically - place them at sensible coordinates anyway, on walkable
ground near their work. After any build, spot-check with get_block that
doors are reachable and floors are where you thought they were - actually
verify, don't assume. A floating house or an NPC in a wall is a broken
promise to the players. After building or when something looks wrong, call
world_overview to see what actually exists and a PROBLEMS list
(floating/buried structures, mis-placed NPCs) - fix what it reports, then
check again.

FORMULAS
When you need a capability that does not exist as a tool, do not wish for
it - COMPOSE it as a formula from the primitives you have, with named
parameters so it can be reused and tuned later (a storm, a meteor shower, a
fountain, a mob ambush are all formulas). Check formula_list before
defining a new one; prefer running or refining an existing formula over
duplicating it. Give formulas clear ids and descriptions - they are your
growing spellbook.

JOBS
NPCs can physically work: npc_assign_job sends them to real chests and
stations with real, finite items. Use it to make villages genuinely produce
and consume - and react in story when npc_job_blocked tells you resources
ran out.

REQUIRED FINAL SUMMARY
End your reply with one short paragraph stating what you decided and why,
or plainly "no action" if you did nothing. This is the audit trail for this
scene - it is required even when the outcome is silence.`;

/** Group a debounced event batch by player (falling back to "world" for
 * events with no player), and collapse per-event timestamps down to a
 * single scene time span rather than repeating a full ISO timestamp on
 * every line. */
export function formatScene(batch) {
  if (!batch.length) return "(empty batch)";

  const groups = new Map();
  for (const e of batch) {
    const player = (e.data && e.data.player) || "world";
    if (!groups.has(player)) groups.set(player, []);
    groups.get(player).push(e);
  }

  const times = batch.map((e) => e.at).filter(Boolean).sort();
  const span = times.length ? (times[0] === times[times.length - 1] ? times[0] : `${times[0]} .. ${times[times.length - 1]}`) : "unknown";

  const lines = [`Scene span: ${span}`, ""];
  for (const [player, events] of groups) {
    lines.push(`Player: ${player}`);
    for (const e of events) {
      const { player: _p, ...rest } = e.data || {};
      lines.push(`  - ${e.event}: ${JSON.stringify(rest)}`);
    }
  }
  return lines.join("\n");
}

export function buildPrompt(batch) {
  return `STANDING ORDERS\n${STANDING_ORDERS}\n\nSCENE\n${formatScene(batch)}`;
}

/**
 * @param {object} params
 * @param {Array} params.batch - debounced sense events
 * @param {string} params.systemPrompt - concatenated lore (brain/lore/*.md)
 * @param {object} params.config - loadConfig() result
 * @param {number} params.sceneNumber - this process's monotonic scene counter value (lib/logger.mjs's nextSceneNumber()), for console/journal correlation
 * @param {(args: {prompt: string, options: object}) => AsyncGenerator} [params.queryFn] - injectable
 *   replacement for the real SDK's query(), for tests (e.g. a fake that
 *   never yields, to exercise the timeout path offline). Defaults to the
 *   real @anthropic-ai/claude-agent-sdk query().
 * @returns {Promise<{ inputTokens: number, outputTokens: number, totalTokens: number, dryRun: boolean, timedOut: boolean }>}
 */
/** Scenes containing an "operator_order" event get a higher turn ceiling
 * (BRAIN_ORDER_MAX_STEPS, default 80) than the normal maxDirectorSteps,
 * since carrying a big set-piece order to completion can take far more
 * tool-call steps than an ordinary reactive scene. */
export function maxTurnsFor(batch, config) {
  const isOrderScene = batch.some((e) => e.event === "operator_order");
  return isOrderScene ? config.orderMaxSteps : config.maxDirectorSteps;
}

/** ...and a matching wall-clock allowance. An order scene granted 80 steps was
 * still being aborted by the ordinary 300s turn timeout partway through the
 * work, so the step ceiling was meaningless: a big set-piece (survey, rebuild,
 * re-place a cast, verify) simply takes longer than a reactive scene. */
export function timeoutSecFor(batch, config) {
  const isOrderScene = batch.some((e) => e.event === "operator_order");
  return isOrderScene ? config.orderTimeoutSec : config.turnTimeoutSec;
}

export async function runDirectorTurn({ batch, systemPrompt, config, sceneNumber, queryFn }) {
  const prompt = buildPrompt(batch);
  const maxTurns = maxTurnsFor(batch, config);
  const timeoutSec = timeoutSecFor(batch, config);

  if (config.dryRun) {
    log.info("dry_run_director_turn", {
      role: "director",
      sceneNumber,
      systemPrompt,
      prompt,
      model: config.directorModel,
      allowedTools: "ALL (full toolset)",
      disallowedTools: [],
      maxTurns,
    });
    journalScene({ config, sceneNumber, batch, toolCalls: [], summary: null, dryRun: true });
    return { inputTokens: 0, outputTokens: 0, totalTokens: 0, dryRun: true, timedOut: false };
  }

  const runQuery = queryFn || (await import("@anthropic-ai/claude-agent-sdk")).query;

  const options = {
    model: config.directorModel,
    systemPrompt,
    mcpServers: {
      [MCP_SERVER_NAME]: {
        type: "stdio",
        command: "node",
        args: [config.mcpServerPath],
        env: {
          MCALIVE2_URL: config.mcalive2Url,
          MCALIVE2_TOKEN: config.mcalive2Token,
        },
      },
    },
    // No built-in Claude Code tools (Bash/Read/Write/Edit/...) - the
    // director is a game master, not a coding agent, and runs unattended.
    tools: [],
    disallowedTools: [], // full toolset, nothing denied
    permissionMode: "bypassPermissions",
    allowDangerouslySkipPermissions: true,
    maxTurns,
  };

  let usage = { input_tokens: 0, output_tokens: 0 };
  let resultText = null;
  const toolCalls = [];
  // Stream the turn: SDKAssistantMessage (msg.type === "assistant") frames
  // deliver one completed content block at a time (verified against
  // @anthropic-ai/claude-agent-sdk's sdk.d.ts - SDKAssistantMessage.message
  // is a BetaMessage whose content blocks include tool_use), so a tool call
  // is visible the moment the model emits it, well before the turn's final
  // "result" message. This is what makes per-tool-call logging possible.
  //
  // The whole stream races BRAIN_TURN_TIMEOUT_SEC (lib/timed-query.mjs): a
  // hung query() would otherwise block this scene - and, since director
  // scenes never run concurrently (lib/director-scheduler.mjs), every
  // subsequent event - forever.
  const abortController = new AbortController();
  options.abortController = abortController;
  const { timedOut, elapsedMs } = await consumeWithTimeout({
    startQuery: () => runQuery({ prompt, options }),
    abortController,
    timeoutSec,
    onMessage: (msg) => {
      if (msg.type === "assistant" && msg.message && Array.isArray(msg.message.content)) {
        for (const block of msg.message.content) {
          if (block.type === "tool_use") {
            const tool = stripToolPrefix(block.name, MCP_SERVER_NAME);
            const argsLine = compactArgs(block.input);
            toolCalls.push({ tool, args: block.input });
            log.info("tool_call", { role: "director", sceneNumber, tool, args: block.input, argsLine });
          }
        }
      }
      if (msg.type === "result") {
        usage = msg.usage || usage;
        resultText = msg.subtype === "success" ? msg.result : `error: ${msg.subtype}`;
      }
    },
  });

  if (timedOut) {
    resultText = `timed out after ${Math.round(elapsedMs / 1000)}s - aborted`;
    log.warn("director_turn_timed_out", { sceneNumber, elapsedMs, timeoutSec });
  }

  const inputTokens = (usage.input_tokens || 0) + (usage.cache_creation_input_tokens || 0) + (usage.cache_read_input_tokens || 0);
  const outputTokens = usage.output_tokens || 0;
  log.info("director_turn_complete", {
    sceneNumber,
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    result: resultText,
    timedOut,
  });
  // The required final summary paragraph is the audit trail for this scene
  // (DESIGN.md / adjudication procedure): log it as its own info line so it
  // is easy to grep/tail independent of the raw turn-complete record above.
  log.info("director_scene_summary", { sceneNumber, summary: resultText });
  journalScene({ config, sceneNumber, batch, toolCalls, summary: resultText, dryRun: false });
  // A timed-out turn still completes normally from the scheduler's point of
  // view (see lib/director-scheduler.mjs and index.mjs's runScene) - it
  // never throws, so the "a scene is running" flag always clears and the
  // next debounced batch is free to run.
  return { inputTokens, outputTokens, totalTokens: inputTokens + outputTokens, dryRun: false, timedOut };
}
