// The developer: once an hour, look at what exists and add the next feature.
//
// This is the one place tokens are spent on AUTHORING rather than on play. It
// picks the first pending entry in brain/roadmap.json, hands a coding agent the
// operating manual, the roadmap, the live status of the world and the feature
// text, and lets it work in this checkout with file tools plus the live bridge
// (so it can compile a gadget on the running server and verify it). When the
// agent marks the feature done in roadmap.json and commits, we push - and the
// self-updater restarts the brain on the new code, exactly as for any push.
//
// Guardrails: one run at a time; skipped when the daily budget is short; the
// self-updater is held off while a run is in progress so the agent is never
// restarted mid-edit; a feature that fails twice is parked as "failed" and the
// roadmap moves on.

import fs from "node:fs/promises";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { log } from "./logger.mjs";
import { MCP_SERVER_NAME } from "./config.mjs";

const execFileP = promisify(execFile);

const SYSTEM_PROMPT = `You are the developer of MCAlive2, an NPC life simulation inside a Minecraft server.
You add ONE feature per run, the one you are given, and nothing else.

THE HARD RULES (from project.md; breaking one is a bug, not a tradeoff):
1. NPCs never teleport. They walk (gadget:navigate), and may only do what a player could do.
2. Tokens are only for authoring. Every runtime decision is arithmetic in gadget Java.
3. Never ship a capability as a plugin change. The plugin surface is frozen. New behaviour
   is a gadget (brain/gadgets/*.java, compiled on the running server via gadget_define) or
   brain-side JavaScript. You may edit brain/gadgets/people.java freely - it is the NPC.
4. Every NPC is a player: 20 hp, 20 hunger, a 36-stack inventory, real tools, vanilla
   recipes and drops, abilities -3..3, skills earned by minutes (point n at 2^n-1 minutes),
   three needs. Nothing is handed to them.
5. All NPC state lives in the ledger record so a redefine loses nothing.

HOW TO WORK:
- Read project.md and brain/roadmap.json first. Read the relevant gadget source.
- Make the change in this checkout. Keep it small and real. Match the style around it.
- Compile it on the live server: call the mcalive2 tool gadget_define with the new source,
  then gadget_run its status (or people status) to verify it is running. A compile error
  comes back as text - fix it and define again. Do not stop at "it compiled".
- A new gadget must be added to scripts/people.mjs startWorld() and brain/gadgets/_descriptions.json.
- When it works: set the feature's status to "done" in brain/roadmap.json with a one-line
  "notes" saying what was built and how it was verified, append a short entry to project.md
  under "## Features added by the developer" (create the heading if missing), and commit
  with git (git add -A; git commit -m "<feature title>"). Do NOT push. Do not touch other
  roadmap entries.
- If you cannot make it work, set status "failed" with notes saying exactly why, commit that,
  and stop. An honest failure is worth more than a fake success.
- Never delete files, never rewrite history, never edit the plugin/ directory.`;

export class Developer {
  /**
   * @param {object} p
   * @param {object} p.config - loadConfig() result
   * @param {object} p.usage - UsageTracker
   * @param {string} p.repoRoot - the checkout the brain runs from
   * @param {(cmd:string, args:object, timeoutMs?:number)=>Promise<any>} p.bridgeCall
   * @param {() => Promise<void>} [p.waitForIdle]
   */
  constructor({ config, usage, repoRoot, bridgeCall, waitForIdle, queryFn, needsLog }) {
    this.config = config;
    this.needsLog = needsLog || null;
    this.usage = usage;
    this.repoRoot = repoRoot;
    this.bridgeCall = bridgeCall;
    this.waitForIdle = waitForIdle || (async () => {});
    this.queryFn = queryFn;
    this.running = false;
    this.timer = null;
    this.runs = 0;
    this.lastResult = null;
    this.roadmapPath = path.join(repoRoot, "brain", "roadmap.json");
    this.logPath = path.join(config.stateDir, "developer.log");
  }

  start() {
    const minutes = this.config.devIntervalMin;
    if (!minutes || minutes <= 0) {
      log.info("developer_disabled", { reason: "BRAIN_DEV_INTERVAL_MIN=0" });
      return this;
    }
    const ms = minutes * 60 * 1000;
    this.timer = setInterval(() => this.tick().catch((e) => log.error("developer_tick_failed", { error: String(e && e.stack || e) })), ms);
    if (typeof this.timer.unref === "function") this.timer.unref();
    log.info("developer_started", { everyMinutes: minutes, minRemaining: this.config.devMinRemainingTokens });
    return this;
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async readRoadmap() {
    return JSON.parse(await fs.readFile(this.roadmapPath, "utf8"));
  }

  async writeRoadmap(r) {
    await fs.writeFile(this.roadmapPath, JSON.stringify(r, null, 2) + "\n");
  }

  /**
   * What to build this hour.
   *
   * The needs log comes first: a thing the world is actually failing at right now
   * beats the next item on a roadmap written before any of it was running. Deaths
   * outrank grumbles inside the log itself (see NeedsLog.topFeature). The roadmap
   * is the fallback for an hour when the world has nothing to complain about.
   */
  async pickNext(roadmap) {
    if (this.needsLog) {
      try {
        const asked = await this.needsLog.topFeature();
        if (asked) return asked;
      } catch (e) {
        log.error("needs_log_pick_failed", { error: String((e && e.message) || e) });
      }
    }
    return roadmap.features.find((f) => f.status === "pending") || null;
  }

  async journal(text) {
    try {
      await fs.mkdir(this.config.stateDir, { recursive: true });
      await fs.appendFile(this.logPath, `\n=== ${new Date().toISOString()}\n${text}\n`);
    } catch {
      /* journaling is best effort */
    }
  }

  async git(args) {
    return execFileP("git", args, { cwd: this.repoRoot, maxBuffer: 4 * 1024 * 1024 });
  }

  async liveStatus() {
    const out = {};
    for (const [k, cmd, args] of [
      ["people", "gadget:people", { action: "status" }],
      ["villages", "gadget:villages", { action: "status" }],
      ["gadgets", "gadget_list", {}],
    ]) {
      try {
        out[k] = await this.bridgeCall(cmd, args, 15000);
      } catch (e) {
        out[k] = { error: String(e && e.message || e) };
      }
    }
    // keep the prompt small: names and counts, not inventories
    if (out.people && out.people.people) {
      out.people = { running: out.people.running, beats: out.people.beats, count: out.people.people.length,
        people: out.people.people.map((p) => `${p.name}: ${p.alive ? "alive" : "dead"}, hp ${p.hp}, hunger ${p.hunger}, ${p.need}, doing ${p.doing}, skills ${JSON.stringify(p.skills)}`) };
    }
    if (out.gadgets && out.gadgets.gadgets) out.gadgets = out.gadgets.gadgets.map((g) => g.id);
    return out;
  }

  buildPrompt({ feature, roadmap, projectMd, status }) {
    const done = roadmap.features.filter((f) => f.status === "done").map((f) => `- ${f.id} ${f.title}: ${f.notes || ""}`);
    const failed = roadmap.features.filter((f) => f.status === "failed").map((f) => `- ${f.id} ${f.title}: ${f.notes || ""}`);
    return [
      `THE FEATURE TO ADD THIS RUN:`,
      `id: ${feature.id}`,
      `title: ${feature.title}`,
      `area: ${feature.area}`,
      `what: ${feature.what}`,
      feature.acceptance ? `acceptance: ${feature.acceptance}` : "",
      ``,
      `WHAT EXISTS (done so far):`,
      done.length ? done.join("\n") : "- nothing beyond the base people system yet",
      failed.length ? `\nPARKED (failed before, do not retry unless your feature needs it):\n${failed.join("\n")}` : "",
      ``,
      `LIVE STATUS RIGHT NOW:`,
      JSON.stringify(status, null, 1),
      ``,
      `project.md (the operating manual):`,
      projectMd,
      ``,
      `Begin. Work in ${this.repoRoot}. Finish by committing (no push).`,
    ].join("\n");
  }

  async tick() {
    if (this.running) {
      log.info("developer_skipped", { reason: "run in progress" });
      return;
    }
    const remaining = this.usage.remaining();
    if (remaining < this.config.devMinRemainingTokens) {
      log.info("developer_skipped", { reason: "budget", remaining, need: this.config.devMinRemainingTokens });
      return;
    }
    const roadmap = await this.readRoadmap();
    const feature = await this.pickNext(roadmap);
    if (!feature) {
      log.info("developer_skipped", { reason: "nothing asked for and roadmap complete" });
      return;
    }
    // A needs-log feature is not in roadmap.json, so the agent has nowhere to mark it
    // done. Put it there first: it becomes a normal roadmap entry that happens to have
    // been written by the world instead of by hand, and the rest of the run is unchanged.
    if (feature.source === "needs-log") {
      roadmap.features.unshift(feature);
      await this.writeRoadmap(roadmap);
    }
    this.running = true;
    this.runs += 1;
    const started = Date.now();
    try {
      await this.waitForIdle();
      const projectMd = await fs.readFile(path.join(this.repoRoot, "project.md"), "utf8");
      const status = await this.liveStatus();
      const prompt = this.buildPrompt({ feature, roadmap, projectMd, status });
      log.info("developer_run_starting", { feature: feature.id, title: feature.title, run: this.runs });
      await this.journal(`RUN ${this.runs}: ${feature.id} ${feature.title}`);

      const runQuery = this.queryFn || (await import("@anthropic-ai/claude-agent-sdk")).query;
      const options = {
        model: this.config.devModel,
        systemPrompt: SYSTEM_PROMPT,
        cwd: this.repoRoot,
        tools: ["Read", "Edit", "Write", "Glob", "Grep", "Bash"],
        mcpServers: {
          [MCP_SERVER_NAME]: {
            type: "stdio",
            command: "node",
            args: [this.config.mcpServerPath],
            env: {
              ...process.env,
              MCALIVE2_URL: this.config.mcalive2Url,
              MCALIVE2_TOKEN: this.config.mcalive2Token,
              BRAIN_MAX_TOOL_RESULT_CHARS: String(this.config.maxToolResultChars),
            },
          },
        },
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        maxTurns: this.config.devMaxTurns,
      };

      let usage = { input_tokens: 0, output_tokens: 0 };
      let resultText = null;
      const toolCalls = [];
      for await (const msg of runQuery({ prompt, options })) {
        if (msg.type === "assistant" && msg.message && Array.isArray(msg.message.content)) {
          for (const block of msg.message.content) {
            if (block.type === "tool_use") toolCalls.push(block.name);
          }
        }
        if (msg.type === "result") {
          usage = msg.usage || usage;
          resultText = msg.subtype === "success" ? msg.result : `error: ${msg.subtype}`;
        }
      }
      const tokens = (usage.input_tokens || 0) + (usage.cache_creation_input_tokens || 0) + (usage.cache_read_input_tokens || 0) + (usage.output_tokens || 0);
      await this.usage.addTokens(tokens);

      // did it mark the feature done?
      const after = await this.readRoadmap();
      const entry = after.features.find((f) => f.id === feature.id);
      const outcome = entry ? entry.status : "missing";
      if (outcome === "pending") {
        // the agent neither finished nor admitted failure: count an attempt
        entry.attempts = (entry.attempts || 0) + 1;
        if (entry.attempts >= 2) {
          entry.status = "failed";
          entry.notes = (entry.notes ? entry.notes + " | " : "") + "developer ran twice without marking done";
        }
        await this.writeRoadmap(after);
        try { await this.git(["add", "-A"]); await this.git(["commit", "-m", `roadmap: ${feature.id} attempt ${entry.attempts}`]); } catch { /* nothing to commit */ }
      }
      let pushed = false;
      try {
        await this.git(["push", "origin", "HEAD:main"]);
        pushed = true;
      } catch (e) {
        log.warn("developer_push_failed", { error: String(e && e.message || e) });
      }
      // Whatever the world asked for has now been answered, well or badly. Close the
      // entries it came from either way - a need that could not be built must not be
      // picked again next hour and every hour after it, which is how a loop that fixes
      // nothing burns a day. It will reappear on the next sweep if it is still true.
      if (feature.source === "needs-log" && this.needsLog) {
        try { await this.needsLog.close(feature.sourceEntries); }
        catch (e) { log.warn("needs_log_close_failed", { error: String((e && e.message) || e) }); }
      }
      const elapsedSec = Math.round((Date.now() - started) / 1000);
      this.lastResult = { feature: feature.id, outcome, tokens, elapsedSec, pushed, toolCalls: toolCalls.length, source: feature.source || "roadmap" };
      log.info("developer_run_complete", this.lastResult);
      await this.journal(`${feature.id}: ${outcome} in ${elapsedSec}s, ${tokens} tokens, ${toolCalls.length} tool calls, pushed=${pushed}\n${(resultText || "").slice(0, 2000)}`);
    } catch (e) {
      log.error("developer_run_failed", { feature: feature.id, error: String(e && e.stack || e) });
      await this.journal(`${feature.id}: CRASH ${String(e && e.message || e)}`);
    } finally {
      this.running = false;
    }
  }
}
