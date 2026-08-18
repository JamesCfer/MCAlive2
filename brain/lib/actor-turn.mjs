// Runs exactly one NPC ACTOR turn - a single cheap conversational reply for
// one NPC talking to one player, through the Claude Agent SDK's query().
// Actors are knowledge-isolated: they only ever see the NPC's own sheet and
// the facts brain/index.mjs already fetched via the plugin's npc_context
// command (which the plugin filters by knownBy=[npcId|faction|"all"] -
// "enforced plugin-side, so an actor can never metagame", DESIGN.md). The
// actor turn itself is NEVER given the npc_context tool - it cannot go
// fishing for another NPC's facts mid-conversation.
//
// Actors may ONLY call npc_say / npc_look_at / npc_pose (DESIGN.md "NPC
// actors"), enforced via allowedTools + a disallowedTools complement built
// from lib/config.mjs's ALL_TOOLS list.

import { log } from "./logger.mjs";
import { MCP_SERVER_NAME, ACTOR_TOOLS, actorDisallowedTools, namespaceAll } from "./config.mjs";

const STANDING_REMINDER = `You are voicing ONE character in the fantasy world of MCAlive2: an NPC actor,
not the director. You know only what is in your character sheet and the
"Facts you know" list below - nothing else. Never invent or claim knowledge
outside that list, and never break character. Chat is dialogue-only: reply
as your character would speak, and use npc_say to actually say it in the
world (do not just describe speaking - call the tool). You may also use
npc_look_at and npc_pose for small physical beats. You have no other
abilities: you cannot change the world, spawn anything, or edit the ledger.

If something noteworthy happens in this conversation that the world should
remember (a new fact you revealed, a promise you made, a quest you offered),
end your reply with a single fenced block:

\`\`\`report
{"facts": [{"text": "..."}], "promises": [{"text": "...", "toWhom": "..."}], "questOffered": null}
\`\`\`

Omit fields that do not apply. The director validates and records this into
the ledger - you never write to the ledger yourself.`;

/**
 * @param {object} params
 * @param {object} params.npc - the NPC's sheet (from npc_context / ledger)
 * @param {Array} params.facts - facts npc_context returned for THIS npc only
 * @param {string} params.player - the player's name
 * @param {string} params.trigger - "npc_interact" | "player_chat"
 * @param {string} [params.message] - the player's chat message, if any
 * @param {{summary: string, recent: Array}} params.transcript - ActorMemory.transcript()
 */
export function buildActorPrompt({ npc, facts, player, trigger, message, transcript }) {
  const lines = [];
  lines.push(STANDING_REMINDER);
  lines.push("");
  lines.push("Your character sheet:");
  lines.push(JSON.stringify(npc, null, 1));
  lines.push("");
  lines.push("Facts you know (and ONLY these):");
  lines.push(JSON.stringify(facts, null, 1));
  if (transcript.summary) {
    lines.push("");
    lines.push("Summary of earlier conversation:");
    lines.push(transcript.summary);
  }
  if (transcript.recent.length) {
    lines.push("");
    lines.push("Recent conversation:");
    for (const t of transcript.recent) lines.push(`${t.who}: ${t.text}`);
  }
  lines.push("");
  if (trigger === "npc_interact") {
    lines.push(`${player} just walked up and interacted with you. Greet them in character and speak with npc_say.`);
  } else {
    lines.push(`${player} said to you: "${message}"`);
    lines.push("Reply in character with npc_say.");
  }
  return lines.join("\n");
}

/**
 * @returns {Promise<{ inputTokens: number, outputTokens: number, totalTokens: number, dryRun: boolean, reportText: string|null }>}
 */
export async function runActorTurn({ npc, facts, player, trigger, message, transcript, config }) {
  const prompt = buildActorPrompt({ npc, facts, player, trigger, message, transcript });
  const allowedTools = namespaceAll(ACTOR_TOOLS, MCP_SERVER_NAME);
  const disallowedTools = actorDisallowedTools(MCP_SERVER_NAME);

  if (config.dryRun) {
    log.info("dry_run_actor_turn", {
      role: "actor",
      npcId: npc.id,
      player,
      trigger,
      prompt,
      model: config.actorModel,
      allowedTools,
      disallowedTools,
    });
    return { inputTokens: 0, outputTokens: 0, totalTokens: 0, dryRun: true, reportText: null };
  }

  const { query } = await import("@anthropic-ai/claude-agent-sdk");

  const options = {
    model: config.actorModel,
    systemPrompt: "You are an NPC actor in MCAlive2. Stay strictly in character.",
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
    tools: [],
    allowedTools,
    disallowedTools,
    permissionMode: "bypassPermissions",
    allowDangerouslySkipPermissions: true,
    maxTurns: config.maxActorSteps,
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
  log.info("actor_turn_complete", {
    npcId: npc.id,
    player,
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    result: resultText,
  });
  return { inputTokens, outputTokens, totalTokens: inputTokens + outputTokens, dryRun: false, reportText: resultText };
}
