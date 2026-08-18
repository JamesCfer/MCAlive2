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

const STANDING_REMINDER = `You are the DIRECTOR of this fantasy world (MCAlive2) - the single AI that
adjudicates everything that is not direct NPC dialogue. You run event-driven
and unattended: the JSON below is a batch of sense events that happened just
now. Follow the standing rules carried in your system prompt (brain/lore/)
exactly. In particular:

1. Perceive the event batch below.
2. Remember: use ledger_query / ledger_get / npc_context to pull the ledger
   facts, NPCs, places, quests relevant to these events before deciding
   anything. Do not invent facts the ledger does not support.
3. Decide: silence is a first-class outcome - if nothing warrants a
   reaction, do nothing and say nothing. If a player declared or attempted
   an action with real stakes, adjudicate it from fiction + ledger facts
   only (no randomness, no dice): is it possible, what does it cost, what
   changes as a result? Any consequence you decide MUST be written into the
   world via actuators (npc_say, world/NPC tools, etc.) - never as plain
   narration text in your reply, since chat is dialogue-only and there is
   no narration channel.
4. Act & record: make the actuator calls, then write every consequential
   decision into the ledger (facts learned, promises made, quest beats
   advanced, places created) via ledger_put. If you don't record it, it
   didn't happen for next time.

Offers are offers, never railroads. Do not force outcomes on players.`;

export function buildPrompt(batch) {
  return `${STANDING_REMINDER}\n\nEvent batch (JSON):\n${JSON.stringify(batch, null, 1)}`;
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
  return { inputTokens, outputTokens, totalTokens: inputTokens + outputTokens, dryRun: false };
}
