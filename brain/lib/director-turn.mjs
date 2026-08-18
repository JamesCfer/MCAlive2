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

import { log } from "./logger.mjs";
import { MCP_SERVER_NAME } from "./config.mjs";

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
 * @returns {Promise<{ inputTokens: number, outputTokens: number, totalTokens: number, dryRun: boolean }>}
 */
export async function runDirectorTurn({ batch, systemPrompt, config }) {
  const prompt = buildPrompt(batch);

  if (config.dryRun) {
    log.info("dry_run_director_turn", {
      role: "director",
      systemPrompt,
      prompt,
      model: config.directorModel,
      allowedTools: "ALL (full toolset)",
      disallowedTools: [],
    });
    return { inputTokens: 0, outputTokens: 0, totalTokens: 0, dryRun: true };
  }

  const { query } = await import("@anthropic-ai/claude-agent-sdk");

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
    maxTurns: config.maxDirectorSteps,
  };

  let usage = { input_tokens: 0, output_tokens: 0 };
  let resultText = null;
  for await (const msg of query({ prompt, options })) {
    if (msg.type === "result") {
      usage = msg.usage || usage;
      resultText = msg.subtype === "success" ? msg.result : `error: ${msg.subtype}`;
    }
  }

  const inputTokens = (usage.input_tokens || 0) + (usage.cache_creation_input_tokens || 0) + (usage.cache_read_input_tokens || 0);
  const outputTokens = usage.output_tokens || 0;
  log.info("director_turn_complete", {
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    result: resultText,
  });
  // The required final summary paragraph is the audit trail for this scene
  // (DESIGN.md / adjudication procedure): log it as its own info line so it
  // is easy to grep/tail independent of the raw turn-complete record above.
  log.info("director_scene_summary", { summary: resultText });
  return { inputTokens, outputTokens, totalTokens: inputTokens + outputTokens, dryRun: false };
}
