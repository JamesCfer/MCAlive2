# brain/ — MCAlive2 director + NPC actors

The AI side of MCAlive2 (see `../DESIGN.md` for the full contract). One
**director** (default `claude-sonnet-5`) runs the world event-driven and
unattended; cheap per-NPC **actors** (default `claude-haiku-4-5-20251001`)
hold knowledge-isolated conversations. Neither reimplements a game ability —
both talk to the MCAlive2 Paper plugin exclusively through `mcp-bridge.mjs`,
a stdio MCP server that passes every actuator/ledger/info command straight
through to the plugin's WebSocket bridge.

```
MCAlive2 plugin (ws://.../8765)
   │
   ├─ brain/index.mjs's OWN ws connection (lib/bridge-client.mjs)
   │     ├─ receives pushed sense events (the wake-up signal, auto-reconnect)
   │     └─ issues direct npc_context calls BEFORE spawning an actor turn
   │        (an actor is never given the npc_context tool itself - see below)
   │
   └─ brain/mcp-bridge.mjs, spawned fresh per query() by the Agent SDK
         ├─ DIRECTOR turn: full toolset (world/NPC/player/ledger/info)
         └─ ACTOR turn: npc_say / npc_look_at / npc_pose ONLY
               ▲
               │ query({ prompt, options }) — one call per turn
          @anthropic-ai/claude-agent-sdk
```

## Director loop vs. NPC actor routing

`index.mjs` owns one WebSocket connection to the plugin (`MCALIVE2_URL`) and
routes every pushed sense event one of two ways:

- **`npc_interact`** → always an actor turn for that NPC.
- **`player_chat` with `data.nearNpcId` set** (the plugin decides proximity,
  default range `BRAIN_NPC_CHAT_RANGE=8` blocks, and stamps the nearby
  NPC's id on the event) → an actor turn for that NPC.
- **Everything else** — `player_join`, `player_death`, `npc_attacked`,
  `npc_death`, `player_explored`, `player_idle_scene`, `region_enter`,
  `region_exit`, and `player_chat` with no
  nearby NPC — is debounced (`BRAIN_DEBOUNCE_MS`,
  default 2500ms) into a batch and handed to **one director turn**
  ("scene"). Events arriving while a scene is mid-turn are queued for the
  next scene; director scenes never run concurrently
  (`lib/director-scheduler.mjs`).
- `player_quit` only updates in-memory presence tracking; it never wakes
  anything.

**Knowledge isolation:** before spawning an actor turn, `index.mjs` calls
`npc_context` directly over its own bridge connection (NOT as a tool the
actor itself can call) to fetch that NPC's sheet plus only the facts whose
`knownBy` includes that NPC, their faction, or `"all"` — filtered
plugin-side, so an actor can never ask about (or accidentally receive)
another NPC's private knowledge. The actor's per-role tool restriction
(`allowedTools` = exactly `npc_say`/`npc_look_at`/`npc_pose`, plus a
`disallowedTools` complement covering the rest of the toolset including
`npc_context` itself) means an actor also cannot go fetch more context
mid-conversation even if it wanted to.

Each `(npc, player)` conversation is serialized (`lib/actor-memory.mjs` +
a per-key promise queue in `index.mjs`) so two rapid interactions never
race, while different NPCs' conversations and the director's scenes all run
independently of each other.

When an actor's reply ends with a fenced ` ```report ` block (new facts,
promises, a quest offered), `index.mjs` forwards it to the director as an
`actor_report` scene event — the director validates and writes it into the
ledger; the actor never writes to the ledger itself.

## Setup

```bash
cd brain
npm install
export ANTHROPIC_API_KEY=sk-ant-...     # needed for real (non-dry-run) turns
export MCALIVE2_URL=ws://127.0.0.1:8765   # matches the plugin's bridge config
export MCALIVE2_TOKEN=pick-a-long-random-token
npm start
```

Run this under whatever keeps a process alive on your machine (systemd,
pm2, a `tmux`/`screen` session, NSSM/Task Scheduler on Windows) —
`brain/` is a plain Node script with no daemonizing of its own.

## Auto-update

Run `run-forever.cmd` (Windows) instead of `npm start` to get zero-operator
self-updating plus crash recovery:

```
cd brain
run-forever.cmd
```

While running, `lib/self-update.mjs` checks `origin/main` every
`BRAIN_UPDATE_CHECK_SEC` seconds (default `10`; `0` disables self-update
entirely). It's also disabled gracefully — logged once, no crash — when
`brain/..` isn't a git checkout at all. Each check is a plain
`git rev-parse HEAD` vs. `git ls-remote origin main` — a lightweight git ref
lookup, not a GitHub API call (no token, works with any plain git install,
and there's no rate limit to worry about) — so checking every 10 seconds is
fine. A reentrancy guard means a check that happens to run long (a slow
network) can never stack up overlapping checks: while one is in flight, the
timer's next tick just no-ops instead of queuing another one behind it.

`BRAIN_UPDATE_CHECK_MIN` (minutes) is still read as a fallback for anyone
who already has it exported — converted to seconds — but
`BRAIN_UPDATE_CHECK_SEC` wins whenever both are set.

- **Same commit** → nothing happens (logged at debug level only — set
  `BRAIN_DEBUG=1` to see it — since this fires on every check and there's
  nothing for an operator to act on).
- **Different commit** → logged clearly (`⟳ brain update available: <old> ->
  <new>`), then `git pull --ff-only`. If `package-lock.json` changed in the
  pulled range, `npm install --no-audit --no-fund` runs in `brain/` first;
  otherwise that step is skipped. The brain then waits for its current
  director scene and any in-flight NPC actor turns to finish (never kills a
  turn mid-flight), runs the same `stop()` path a manual shutdown uses, and
  exits with code **75** — "restart me".
- **`git pull` fails** (dirty tree, diverged history, network blip) → a
  warning is logged with the reason and the check is skipped; the checkout
  is never left in a broken state, and there's no hot retry loop — the next
  scheduled check just tries again.

`run-forever.cmd` is a small restart-loop wrapper around `npm start`:

| `npm start` exit code | `run-forever.cmd` does |
|---|---|
| `75` | loop again immediately (self-update wants to restart) |
| `0` | stop looping (deliberate shutdown) |
| anything else | wait 10 seconds, then loop again (crash recovery) |

Plain `npm start` still works exactly as before — it just won't restart
itself on an update or a crash; use `run-forever.cmd` for anything meant to
run unattended.

## Watching the brain

By default the console is a plain-English narration of what the brain is
doing, one line at a time — meant to be watched live, not piped through
`jq`:

```
15:17:04  INFO   bridge connected (ws://127.0.0.1:8899)
15:17:04  INFO   event player_join Steve joined
15:17:04  INFO   event player_chat Steve: "hello?"
15:17:04  INFO   scene #1 starting (3 events)
15:17:05  INFO     ⚒ npc_say {npcId:mara-baker,text:"Welcome to the bakery!"}
15:17:05  INFO   ✓ scene #1 decided: Steve greeted Mara; nothing else changed in the world.
15:17:06  INFO   event npc_interact Steve -> kess-smith
15:17:06  INFO   actor kess-smith starting (npc_interact) with Steve
15:17:06  INFO     ⚒ npc_say {npcId:kess-smith,text:"Found a strange ore vein north of the village."}
15:17:06  INFO   ✓ actor kess-smith replied to Steve: "Found a strange ore vein north of the village."
```

Every tool call the director or an NPC actor makes mid-turn is logged as it
streams in (`⚒ <tool> <compact one-line args>`, tool names stripped of
their `mcp__mcalive2__` prefix) — not just the final summary — so you can
watch what it's actually doing, not just what it says it did afterward.
Each director scene is numbered (`scene #N`, a monotonically increasing
per-process counter) so its tool calls and its final decision line are easy
to follow together; guardrail skips (kill switch / daily budget / rate
limit) and bridge connect/reconnect/auth events are rendered the same way.

Set `BRAIN_LOG_JSON=1` to get the original one-JSON-object-per-line output
instead (for `| jq`, log shipping, or the smoke test, which always runs
with it set).

### `brain/state/decisions.log`

Independent of the console, every director scene and every NPC actor turn
also appends a plain-English block to `brain/state/decisions.log` — the
operator's durable "what has my world been doing" record, readable with no
JSON knowledge at all:

```
=== 2026-08-18T19:17:04.708Z  Scene #1 ===
Triggered by: player_join, player_chat x2 (player: Steve)
Tool calls:
  ⚒ npc_say {npcId:mara-baker,text:"Welcome to the bakery!"}
Decision: Steve greeted Mara; nothing else changed in the world.

=== 2026-08-18T19:17:06.251Z  Actor kess-smith <- Steve (npc_interact) ===
Tool calls:
  ⚒ npc_say {npcId:kess-smith,text:"Found a strange ore vein north of the village."}
Decision: Found a strange ore vein north of the village.
```

A turn that a guardrail blocked before it ever ran is still journaled, as
`Decision: skipped: kill_switch` / `budget_exceeded` / `rate_limited`. A
turn run under `BRAIN_DRY_RUN=1` is still journaled too, with `[dry-run]`
on its header line, since a dry run is exactly the case you most want a
paper trail for while tuning the brain — its decision line reads
`(dry run - no API call made)` since no model was ever called.

The file rotates when it exceeds 1MB: it is renamed to `decisions.log.1`
(replacing any previous one) and a fresh `decisions.log` is started — one
rotation kept, not a numbered series. Override the threshold with
`BRAIN_DECISIONS_MAX_BYTES` (mainly for tests).

## Dry run

Set `BRAIN_DRY_RUN=1` to see the exact system prompt + prompt + model +
tool allowlist for every director scene and actor turn WITHOUT calling the
Anthropic API or spending any tokens:

```bash
BRAIN_DRY_RUN=1 npm start
```

Each dry-run turn is logged as a single JSON line (`dry_run_director_turn`
or `dry_run_actor_turn`) on stdout.

## Guardrails

Runs completely unattended with world-editing power, so it is deliberately
locked down:

| Guardrail | Default | Env override |
|---|---|---|
| Daily token budget (UTC reset, `brain/state/usage.json`) | 500,000 tokens/day | `BRAIN_DAILY_TOKEN_BUDGET` |
| Turn rate limit (director scenes + actor turns combined) | 10/minute | `BRAIN_MAX_TURNS_PER_MIN` |
| Turn timeout (director scene or actor turn) | 300 seconds | `BRAIN_TURN_TIMEOUT_SEC` |
| Per-turn token ceiling (aborts mid-turn, not just at the start) | 120,000 tokens (director/actor turn), 600,000 tokens (operator-order scene) | `BRAIN_MAX_TOKENS_PER_TURN`, `BRAIN_MAX_TOKENS_PER_ORDER` |
| Tool-result size cap (every tool result returned to the model) | 3,000 characters | `BRAIN_MAX_TOOL_RESULT_CHARS` |
| Self-update idle-wait cap before restarting anyway | 600 seconds | `BRAIN_UPDATE_IDLE_WAIT_SEC` |
| Kill switch | off (service runs) | `BRAIN_ENABLED=0`, or create the file `brain/DISABLED` |
| Director model | `claude-sonnet-5` | `BRAIN_DIRECTOR_MODEL` |
| Actor model | `claude-haiku-4-5-20251001` | `BRAIN_ACTOR_MODEL` |
| Actor tool allowlist | `npc_say`, `npc_look_at`, `npc_pose` only | not overridable (hardcoded in `lib/config.mjs`) |
| Director tool allowlist | full toolset (nothing denied) | not overridable |

The kill switch is checked on every director scene AND every actor turn
before anything else: while active, the service still connects to the
bridge and logs incoming events, it just never starts a turn. The budget
and rate limit are shared across director scenes and actor turns (one pool,
since actors are cheap but frequent and directors are rare but expensive).

**Budget exhaustion is loud once, then quiet.** The first time a turn is
skipped for budget on a given UTC day, it logs a `⚠` **WARN** spelling out
that the budget is exhausted, tokens used vs. the limit, that the world will
stay inert until the UTC-midnight reset, and how to raise
`BRAIN_DAILY_TOKEN_BUDGET` (or reset it now — see [Status & budget
reset](#status--budget-reset) below). Every later skip that same UTC day logs
the same information at debug level instead (visible with `BRAIN_DEBUG=1` or
`BRAIN_LOG_JSON=1`), so an exhausted budget doesn't bury an operator's
terminal in the same line for hours — this is exactly what happened in
production once: the budget ran out mid-morning and every scene/turn after
that silently skipped with only a `decisions.log` line as evidence, with
nothing in the console loud enough to notice. Every skip is still journaled
to `state/decisions.log` regardless (`Decision: skipped: budget_exceeded`).
The kill switch and rate limit are NOT deduplicated this way — they still log
a `WARN` on every single skip, since they're either operator-controlled
(kill switch) or expected to self-clear within a minute (rate limit), unlike
a budget exhaustion that can silently last hours.

Every director scene and actor turn races `BRAIN_TURN_TIMEOUT_SEC` against
the underlying Agent SDK call (`lib/timed-query.mjs`). Because director
scenes never run concurrently (`lib/director-scheduler.mjs`), a single hung
SDK call used to block every subsequent event — including operator orders —
forever; on timeout the query is aborted (`AbortController` + `Query#close()`),
journaled as `Decision: timed out after Ns - aborted`, and still counts as a
completed turn, so the scheduler moves straight on to the next queued batch.
Self-update's restart wait has its own, independent cap
(`BRAIN_UPDATE_IDLE_WAIT_SEC`): if the director/actor idle point still isn't
reached by the cap, self-update restarts anyway rather than waiting forever
behind a turn that (with the timeout above) is now presumed wedged.

`set_time` and all narration tools (`broadcast`/titles/action bar) are
**absent by design**, not by a deny-list: they simply do not exist as
plugin bridge commands, so `mcp-bridge.mjs` has nothing to forward them to.

### Token-cost guardrails (tool-result cap + per-turn token ceiling)

A production incident once burned 15.3 MILLION tokens in a single
operator-order scene against a 5,000,000/day budget - **3x the entire daily
budget in one scene**. Three separate factors compounded:

1. Tool results were returned to the model at full size - `world_overview`
   with `detail:"full"` emitted the whole terrain grid plus every place/NPC/
   diagnostic as text (the director called it 4 times in that one scene),
   `scan_area` could return up to 256 per-column entries, and `ledger_query`
   returned every record with full role/personality prose.
2. An Agent SDK turn re-sends its WHOLE accumulated context on every step -
   so one oversized early tool result got paid for again on all ~80
   subsequent steps of that scene, not just once. This is the real
   multiplier: a single 50,000-token tool result costs ~4,000,000 tokens
   over an 80-step order scene, not 50,000.
3. `dailyTokenBudget` (above) only ever gated the START of a turn. Once a
   scene was running, nothing stopped it - it kept going for the scene's
   full `maxTurns`/`timeoutSec` allowance regardless of how many tokens it
   had already burned.

Four fixes, matching the three factors above:

- **`BRAIN_MAX_TOOL_RESULT_CHARS`** (default `3000`) - every tool result
  `mcp-bridge.mjs` returns to the model is capped at this many characters
  before it goes out over stdio (`lib/tool-result-cap.mjs`). Truncation is
  intelligent, not a blind character chop: for an object/array result, the
  largest array-valued field (`records`, `columns`, `npcs`, ...) is shrunk
  to its first few entries with a clear `"... N more item(s) omitted -
  narrow your query (filter/smaller area) to see them"` marker, falling
  back to a hard string truncation (same kind of marker) only if shrinking
  arrays alone doesn't get under budget. **`isError` results are NEVER
  truncated** - the director/actor needs the FULL compiler/validation error
  text to iterate (e.g. `gadget_define`'s javac diagnostics). Forwarded
  explicitly into `mcp-bridge.mjs`'s spawned-process env by
  `director-turn.mjs`/`actor-turn.mjs` (that process is spawned fresh per
  turn by the Agent SDK, so it can't rely on env inheritance).
- **`formatWorldOverview` is compact by construction** (`lib/worldmodel.mjs`):
  the `detail:"full"` ASCII map is downsampled to at most 24 characters wide
  and 24 tall; the PLACES list, the flagged-NPCs list, and the PROBLEMS list
  are each capped at a handful of entries with a `"+N more"` note; every
  free-text field (place name/kind/builtBy, NPC name/role, diagnostic
  message) is truncated to ~40-60 characters. A synthetic model with 200
  places/200 NPCs/200 diagnostics and a 64x64 terrain grid still renders
  comfortably under `BRAIN_MAX_TOOL_RESULT_CHARS` (see `test/smoke.mjs`
  section "world_overview compaction").
- **`BRAIN_MAX_TOKENS_PER_TURN`** (default `120000`) / **`BRAIN_MAX_TOKENS_PER_ORDER`**
  (default `600000`) - a hard ceiling that actually ABORTS a turn while it
  is still streaming, mirroring the `maxDirectorSteps`/`orderMaxSteps` and
  `turnTimeoutSec`/`orderTimeoutSec` pattern above (`maxTokensFor()` in
  `lib/director-turn.mjs`). Because the Agent SDK's streamed
  `SDKAssistantMessage` frames carry non-final usage (`message.usage` -
  final usage only arrives on the terminal `result` message, which an
  aborted turn never receives - verified against the installed
  `@anthropic-ai/claude-agent-sdk`'s `sdk.d.ts`), actual token counts aren't
  available while streaming. Usage is instead ESTIMATED by accumulating the
  character length of streamed assistant content and tool-result blocks and
  dividing by 4 (a standard, deliberately conservative chars-per-token
  approximation), checked against the ceiling after every streamed message.
  The moment it's exceeded, the query is aborted via the same
  `AbortController` + `Query#close()` mechanism `lib/timed-query.mjs`
  already uses for `turnTimeoutSec` timeouts (`consumeWithTimeout`'s new
  `checkAbort` parameter). On abort: logged at WARN
  (`director_turn_token_ceiling_hit`), journaled as `Decision: hit the
  per-turn token ceiling (N) - aborted`, the scheduler is released exactly
  like a timeout, and an operator order reverts to `"queued"` (picked back
  up on the next restart or requeue) exactly like a timed-out scene.
- **The director prompt states its own budget.** Every director prompt now
  includes a `BUDGET:` line naming the remaining daily token budget and
  this turn's hard token ceiling, with an explicit instruction to be
  economical: prefer `world_overview`'s `"summary"` detail over `"full"`,
  query the ledger with filters instead of dumping whole collections, and
  avoid re-reading data already present in the conversation.

## Lore (`brain/lore/`)

Everything in `brain/lore/*.md` is loaded, sorted by filename, concatenated
into the DIRECTOR's system prompt (actors get a short fixed system prompt
plus their own character sheet instead — see `lib/actor-turn.mjs`):

- `00-rules.md` — standing operating rules: chat is dialogue only, never
  change time, offers not railroads, silence is a first-class outcome,
  adjudicate by fiction + ledger facts (no randomness), every consequential
  decision lands in the ledger, NPC death is permanent, copper-age tier.
- `10-world.md` — a short stub: the world is unwritten, and the director
  authors it as players explore (fantasy, low-tier copper age start).
- `20-bestiary.md` — monsters are story, never weather: natural hostile
  spawns are suppressed server-wide, so every monster the director places
  (`spawn_entity`) must be deliberate and rooted in place/lore (spider
  woods, necromancer-made zombies/skeletons, rare dreaded creepers).

### Formulas & jobs

Two more director tool families, alongside the world/NPC/player/ledger/info
set: **formulas** (`formula_define`/`formula_run`/`formula_list`/
`formula_get`/`formula_delete`) let the director compose a reusable,
parameterized recipe over existing bridge commands instead of wishing for a
tool that doesn't exist — a lightning storm, a meteor shower, a fountain, a
mob ambush are all formulas, runnable again later with different arguments.
A `formula_run` fires `sequence_done` (`kind: "formula:<id>"`) when it
finishes, or `formula_error` if a step fails — both director wake events.
**NPC jobs** (`npc_assign_job`/`npc_job_cancel`) send an NPC to physically
walk to a real station and chest, withdraw real (finite) items, work, and
deposit real outputs — making villages genuinely produce and consume.
`npc_job_done` and `npc_job_blocked` (e.g. missing inputs) are director wake
events too.

### Gadgets

A third director tool family, alongside formulas and jobs: **gadgets**
(`gadget_define`/`gadget_run`/`gadget_list`/`gadget_get`/`gadget_delete`) are
the top rung of the director's capability ladder:

1. An existing tool or gadget — use it (check `gadget_list` first).
2. Composable from existing tools — define a **formula** (see above).
3. A genuinely new primitive (new physics, new senses, an algorithm like
   pathfinding) — write a **gadget**: real Java source, injected and
   compiled ON the running server via `gadget_define`, registered
   immediately as bridge command `gadget:<id>` with no plugin release and no
   restart. `gadget_run {id, args}` calls it exactly like any other bridge
   command.

The contract: a gadget's source is a Java class implementing the plugin's
`GadgetContract` — `JsonObject run(JsonObject args, GadgetContext ctx)` —
where `ctx` exposes `plugin()`, `server()`, `world(String)`, a
dispatcher-invoke helper to call other bridge commands, and scheduler
helpers. A failed compile comes back as an ERROR RESULT carrying the full
`javac` diagnostics in the message; the director is briefed to read them,
fix the source, and call `gadget_define` again with the same id to iterate
until it compiles — then test it with `gadget_run` on safe inputs before
relying on it in a scene. Gadgets are meant to be kept small and
single-purpose.

Edit these files freely — the service re-reads `brain/lore/` on a timer
(`BRAIN_LORE_REFRESH_MS`, default 10 minutes) and picks up changes without
a restart. Add more numbered files to grow the lore; the numeric prefix
just controls read order.

## Lore Console

A tiny local (or LAN) HTML page for the operator to type a free-text
instruction that the DIRECTOR must fold into the world — e.g. "add a ruined
tower somewhere in the eastern mountains with a hermit who knows about the
old war" — without touching any files by hand. On by default:

```
http://127.0.0.1:7777/?token=YOUR-TOKEN
```

The token is `BRAIN_CONSOLE_TOKEN` (defaults to `MCALIVE2_TOKEN`, so it's
already set if you've configured the plugin connection). Visit once with
`?token=...` in the URL and the page sets an `HttpOnly` cookie so its own
page (the textarea, the delete buttons, the auto-refreshing decisions tail)
keeps working without the token in the address bar. Any request without a
valid token — cookie or query param — gets a `401` telling you to append
`?token=YOUR-TOKEN`. Bind `BRAIN_CONSOLE_BIND=0.0.0.0` to reach it from
another machine on the LAN (the token is the only thing standing between
that and anyone on the network, so pick a real one).

The page is a textarea + "Send to the world" button, a list of past
directives (newest first, each deletable if you change your mind), and a
live tail of the last ~40 lines of `state/decisions.log` (auto-refreshing
every 5s).

### World model & map data

`lib/worldmodel.mjs` builds one snapshot of the live world from
`ledger_query` (`places`/`npcs`), a terrain survey, and a handful of budgeted
`get_block` probes. It has two consumers:

- **`world_overview` (director-only tool)** — the text-only director's "what
  exists and what's wrong" view. Returns a compact digest: a header line
  with counts, every place with its coords/size/`builtBy`/flags, an NPC
  alive/dead summary with flagged NPCs called out, an optional top-down
  ASCII map (`detail:"full"`; places as letters, `+` for spawn, ≤40 chars
  wide), and a `PROBLEMS` section listing every diagnostic worst-first. The
  director's briefing (`lib/director-turn.mjs`'s BUILDING DISCIPLINE
  section) tells it to call this after building or whenever something looks
  wrong, fix what it reports, then check again.
- **`GET /worldmodel`** — the same snapshot as raw JSON, token-authed like
  every other console route, for the `/map` viewer below. Cached ~5s
  (`console-server.mjs`) so rapid page loads/auto-refresh polling don't
  hammer the bridge with a fresh terrain survey every time.

**Terrain acquisition — the WHOLE loaded world, not just recorded places.**
`buildWorldModel()` prefers calling `"gadget:world-scan"` (`gadgets/world-
scan.java`, auto-installed on boot by `index.mjs`'s `installWorldScan` — see
[Gadgets](#gadgets)) with `{maxCells: BRAIN_WORLD_SCAN_MAX_CELLS}` and no
explicit region, so it surveys every currently-loaded chunk — spawn,
wilderness, wherever players have explored — auto-choosing a sample step so
the grid stays under the cell cap no matter how much of the world is loaded.
An unloaded chunk's column comes back `null` ("unknown"), never a guess. The
gadget's result maps onto the model's existing terrain shape
(`{origin:{x,z}, step, cols, rows, heights}}` — unchanged, so nothing
downstream needed to know which source produced it) and also gives the model
three more top-level fields: `spawn: {x,y,z}|null` (the world's actual
configured spawn point), `players: [{name,x,y,z}]` (everyone currently
online), and `loadedChunks` (a raw count). The model's `bounds` come from the
gadget's surveyed extent, still unioned with every recorded place's
bounds/origin and every NPC's current position so nothing recorded ever
falls outside the map even if it sits beyond the currently-loaded world.

If the gadget call fails — older server, gadgets disabled, a bridge that's
mid-reconnect — `buildWorldModel()` falls back to its original behavior
exactly as before this feature existed: one coarse `scan_area` scan over the
bounding box of recorded places (padded), `spawn`/`players` come back
`null`/`[]`, and a human-readable line is appended to the model's new
`notes: [string]` array (surfaced in both `formatWorldOverview()`'s header
and the `/map` page) explaining why. Either way, the pre-existing
floating/buried/off-ground diagnostics (below) keep working unchanged — they
look up the terrain grid by world x/z regardless of which source produced
it, and a `null` cell (chunk not loaded) just skips that diagnostic rather
than reporting a false problem.

Diagnostics computed (bounded probe budget: at most ~40 `get_block` calls
total per snapshot, spent only on the NPC off-ground check):

| Severity | Kind | Trigger |
|---|---|---|
| error | `floating` | an `ai`-built place whose origin/bounds-bottom sits >3 blocks above the scanned ground surface beneath it |
| warn | `buried` | a place whose bounds-bottom sits >3 blocks below the scanned ground surface |
| warn | `no-position` | an alive NPC with no live position AND no ledger `home` to fall back to |
| error | `off-ground` | an alive NPC whose position is >2 blocks above or >1 block below the nearest solid ground found within its probe budget |
| info | `dead` | a dead NPC |

No `npc_list`/`npc_get` (live NPC position) REQUEST/RESPONSE bridge command
exists — only `ledger_query`/`npc_context` expose NPC data that way, neither
carrying a *live* position. See "Live position tracking" directly below for
where a live position DOES now come from, and NPC `pos`'s fallback to the
ledger record's `home` coordinate when there is none.

### Live position tracking

Instead of the world model polling for positions on every request, the
plugin now **pushes** them: `gadgets/position-tracker.java` is a runtime-
injected [gadget](#gadgets) that broadcasts an `entity_positions` bridge
event (`{at, npcs:[{id,world,x,y,z}], players:[{name,world,x,y,z}]}`) on a
fixed timer (`BRAIN_POSITION_INTERVAL_TICKS`, default 20 ticks ≈ 1s).

- **Boot auto-install** — right after `index.mjs`'s own bridge connection
  authenticates, it reads `gadgets/position-tracker.java`, calls
  `gadget_define {id:"position-tracker", source, description}` then
  `gadget_run {id:"position-tracker", args:{intervalTicks}}`. Both calls are
  wrapped so a failure (server not yet on a gadget-capable plugin version,
  gadgets disabled, a bridge that's still reconnecting) is logged as a clear
  `⚠ live position tracking unavailable: <reason>; world model will fall
  back to ledger home positions` and otherwise ignored — the brain never
  crashes over this. Set `BRAIN_POSITION_TRACKING=0` to skip the auto-install
  outright. Safe to run again on every boot: `gadget_define` overwrites the
  same id's source, and the gadget's own `run()` cancels its previous timer
  task before starting a new one (tracked in a JVM system property, so it
  survives a brain restart even though the gadget's classloader doesn't).
- **Caching** — `index.mjs`'s event router feeds every pushed
  `entity_positions` event into `lib/position-cache.mjs` (a small
  per-npc-id / per-player-name latest-position map with an injectable
  clock, independently unit-tested). This is **pure telemetry**: it is
  deliberately absent from `config.mjs`'s `DIRECTOR_WAKE_EVENTS`, so it
  never debounces into a director scene — only `lib/worldmodel.mjs` reads
  from it.
- **World model** — `buildWorldModel()` takes an optional
  `opts.npcPositions` getter/Map/object; `index.mjs` passes one bound to the
  live cache when building `/worldmodel`'s snapshot. A fresh cached position
  wins over the ledger `home` fallback (dropping the
  `position-from-ledger-home` flag); a *stale* one (older than
  `BRAIN_POSITION_STALE_SEC`, default 30s) is still used rather than
  discarded, just flagged `position-stale`; with nothing cached at all, an
  NPC falls back to its ledger `home` coordinate exactly as before this
  feature. The off-ground diagnostic runs against whichever position won.
- **Scope** — this only benefits world-model consumers running in the SAME
  process as `index.mjs`'s bridge connection (today: `GET /worldmodel`, the
  `/map` viewer's data source). `mcp-bridge.mjs` (the director/actor Agent
  SDK tool server, spawned fresh per `query()`) is a separate process that
  explicitly ignores pushed bridge events on its own connection, so its
  `world_overview` tool keeps using ledger-home positions only.

### 3D map

`/map` (linked from the console header, and links back) is a dependency-free
voxel/cube viewer of the live world — a hand-rolled orthographic 3D
projection on a `<canvas>`, no three.js, no CDN, fetching `GET /worldmodel`
same-origin (same cookie auth as the rest of the console). The world is
Minecraft, so it looks like blocks: every terrain grid cell is drawn as a
cube (world-size `step`) with its top face at that cell's scanned height,
extruded down to a shared floor so the ground reads as solid rather than a
floating tile-sheet (skipping any `null` — unloaded-chunk — cell rather than
drawing a hole or guessing). Only the top face plus the two side faces the
camera can actually see are drawn (picked from the orbit camera's yaw), kept
correctly occluded by the existing painter's-algorithm depth sort. Cubes are
colored by `terrain.materials` when the scan is small enough to carry it — a
lookup of common Minecraft blocks (grass, dirt, stone, deepslate, sand,
gravel, water, snow, logs, leaves, sandstone, clay, terracotta, ice,
netherrack, cobblestone, mud, moss, podzol) to flat colors, top face
brightest and the two visible sides shaded darker so the cube form reads;
unknown materials or a scan with no material data at all fall back to the
original height-based grey/green ramp. Places stay translucent crisp-edged
boxes (color = `builtBy`, red outline = an error flag) or origin markers
when they have no bounds. NPCs, players, and the world spawn are now small
cube stacks instead of pillar markers — a 1-wide, 2-tall stack for an NPC or
player (green alive, gray dead, orange/red if flagged; cyan for a player,
labelled with their name), and a distinct gold cube labelled `SPAWN` at
`model.spawn` (from the `gadget:world-scan` terrain source — absent if it's
unavailable, see above). Drag to orbit, right-drag/shift-drag to pan, wheel
to zoom, hover or click a marker (place, NPC, spawn, or player) for its
details in the side panel. A fixed problems panel lists every diagnostic
worst-first (error → warn → info); clicking one recenters the camera on its
coordinate and drops a brief pulse marker there — the "what's going wrong"
view. The header shows live counts (places, NPCs alive/dead, players online,
problem count), the surveyed world extent and loaded-chunk count when
available, a manual refresh button, and a 10s auto-refresh toggle; it
degrades gracefully with a banner if `/worldmodel` 401s, and shows any
`notes` (e.g. "gadget:world-scan unavailable, falling back to scan_area")
alongside its existing empty-state notes rather than failing silently.

Sending a directive appends a dated block to
`brain/lore/90-operator-directives.md` (created on first use) and
immediately triggers the same lore reload `lib/lore.mjs`'s watcher uses on
its own timer — so the change is live for the **next** scene, no restart
and no waiting for `BRAIN_LORE_REFRESH_MS`. The `90-` prefix sorts this file
LAST among `lore/*.md`, so operator directives land at the very end of the
director's system prompt, and the file's header line makes the priority
explicit: they override taste, never the standing rules in `00-rules.md`.

### Directives vs. orders

The console has **two** forms, deliberately styled apart (blue for
directives, amber for orders) so they're never confused:

- **Directives** ("Send to the world") are permanent taste. They land in
  `lore/90-operator-directives.md` and quietly shape every future scene —
  nothing happens *right now*, the world just leans that way from here on.
- **Orders** ("Order the world") are one-shot commands the director must
  **execute now** — e.g. "strike spawn with lightning 100 times, then build
  a floating village." An order never touches `lore/`; posting one appends
  an entry to `state/orders.json` (rolling last 50, with a timestamp and
  status `"queued"`) and immediately pushes an `operator_order` scene event
  onto the same debounced director scheduler that pushed bridge sense
  events use, so the **very next scene** carries it as something to *act
  on*. The director's briefing (`lib/director-turn.mjs`) has a dedicated
  OPERATOR ORDERS section instructing it to carry the order out faithfully,
  promptly, and to completion, subject only to the standing constitution
  (chat stays dialogue-only, no time changes, death stays permanent) — an
  order may override taste and lore, never that. The page also lists recent
  orders with their timestamps and status.

  Orders survive a restart: `state/orders.json` is the source of truth, not
  the in-memory scheduler push. On boot, `index.mjs` reads every entry still
  marked `"queued"` and re-pushes it onto the scheduler, oldest first,
  logging `order_requeued_on_boot` for each — so an order that was queued
  behind a wedged scene, or mid-flight when the process crashed or
  self-updated, is retried automatically instead of silently dying with the
  old process. Once an order's scene actually completes, its `orders.json`
  entry flips to `"done"`; if that scene instead timed out
  (`BRAIN_TURN_TIMEOUT_SEC`), it reverts to `"queued"` so the next restart
  (or boot) retries it rather than losing it.

  The same guarantee holds if a scene carrying an order is skipped by a
  **guardrail** instead of actually running — kill switch, exhausted budget,
  or rate limit: the order's `orders.json` entry is left (or explicitly
  reconfirmed) as `"queued"`, logging `order_status_updated` with
  `status:"queued"`, and is **never** marked `"done"`. This is the exact
  production gap that motivated it: an order submitted during a
  budget-exhausted window used to just sit there with no visible trace
  beyond `decisions.log`, indistinguishable from having been lost, until
  someone thought to restart the process (the only thing that used to
  actually retry it).

Scenes triggered by an `operator_order` event get a much higher turn
ceiling than a normal reactive scene — `BRAIN_ORDER_MAX_STEPS` (default
`80`) instead of `BRAIN_MAX_DIRECTOR_STEPS` (default `12`) — since carrying
out a big set-piece order to completion can take far more tool-call steps
than deciding "nothing happens" about a stray chat message.

For big set-pieces, the director is instructed to prefer plugin-side timed
sequences over looping tool calls itself: `strike_lightning` runs a whole
timed lightning show and returns immediately with a `sequenceId`, then
later fires a `sequence_done` event (`{sequenceId, kind, center, count}`,
also a director wake event) when the show finishes, so the director can
plan its next phase off that event instead of sleeping. Three world tools
exist specifically for this, director-only (absent from `ACTOR_TOOLS`):
`create_explosion {x,y,z,power,fire,breakBlocks}`, `strike_lightning
{x,z,count,radiusBlocks,intervalTicks,explosionPower} -> {sequenceId}`, and
`move_region {x1,y1,z1,x2,y2,z2,dx,dy,dz,clearSource}`.

Placement safety: `scan_area {x1,z1,x2,z2,world?,yHint?}` is a build-resolution
surface scanner (`yHint` resolves to the surface nearest that height, so
floating terrain scans the island top rather than the ground far below) the
director is briefed to call before every build; `build_blueprint` accepts
`settle:"surface"` (shifts the paste so its lowest layer sits on the scanned
surface) and `clearAbove:true` (clears the bounding box first), and
`npc_spawn`/`npc_walk_to` ground-snap NPCs to the nearest valid standing spot
within ±12 of the requested y by default (`snap:false` to opt out).

| Var | Default | Meaning |
|---|---|---|
| `BRAIN_CONSOLE` | `1` | Set to `0` to disable the console entirely (it won't bind a port) |
| `BRAIN_CONSOLE_BIND` | `127.0.0.1` | Interface to bind; `0.0.0.0` to reach it from the LAN |
| `BRAIN_CONSOLE_PORT` | `7777` | Port to listen on |
| `BRAIN_CONSOLE_TOKEN` | `MCALIVE2_TOKEN` | Shared token required on every request |
| `BRAIN_ORDER_MAX_STEPS` | `80` | `maxTurns` passed to the Agent SDK for a director scene triggered by an `operator_order` event, instead of `BRAIN_MAX_DIRECTOR_STEPS` |

### Status & budget reset

- **`GET /status`** — token-authed like every other console route, JSON
  snapshot of whether the world is actually able to act right now:

  ```jsonc
  {
    "budget": { "used": 500000, "limit": 500000, "remaining": 0, "exhausted": true, "resetsAt": "2026-08-20T00:00:00.000Z" },
    "killSwitch": false,
    "rateLimit": { "maxPerMin": 10 },
    "scenes": { "running": false },
    "bridge": { "connected": true },
    "uptimeSec": 3600
  }
  ```

  `budget.resetsAt` is always the next UTC midnight. `bridge.connected`
  reflects the brain's own bridge connection (`lib/bridge-client.mjs`'s
  `authed` flag), not the console's.

- **`POST /budget/reset`** — token-authed, zeroes today's counter in
  `state/usage.json` and persists it immediately, for an operator who
  decides to keep the world running past an exhausted budget rather than
  wait for the UTC-midnight reset. Returns `{"ok": true}`. Also clears the
  loud-once-per-day WARN flag above, so a later same-day exhaustion warns
  loud again instead of being mistaken for something already reported.
  Always logged at `WARN` (`budget_reset`), since it's a deliberate operator
  action that changes what the world will do next.

Both console pages (`/` and `/map`) render a small **status strip** in their
header — budget used/limit as a percentage, and, when the budget is
exhausted or the kill switch is on, a prominent coloured banner explaining
that the world is not currently acting and why. The strip polls `GET
/status` on the same cadence each page already polls its own data on (the
`/` page's 5s decisions-log tail; the `/map` page's status poll runs every
10s, matching its own optional world-model auto-refresh). When the budget
shows exhausted, a **"Reset budget"** button appears in the strip; clicking
it asks for confirmation, then calls `POST /budget/reset`.

## Testing (no API key, no Minecraft server required)

```bash
npm test
```

Boots `test/mock-bridge.mjs` (a WebSocket server performing the real auth
handshake, recording every command it receives, answering canned
`npc_context` data for two NPCs with disjoint fact sets, and emitting a
scripted event timeline), runs `index.mjs` against it with
`BRAIN_DRY_RUN=1`, and asserts:

- auth against the bridge succeeds, and reconnect-with-backoff against a
  refused port keeps the process alive and retrying (≥2 attempts logged)
  instead of exiting
- two rapid `player_chat` events with no nearby NPC are debounced into ONE
  director scene together with the preceding `player_join`
- `npc_interact` routes to an ACTOR turn, never a director scene
- `player_chat` with `nearNpcId` set also routes to an ACTOR turn
- each actor's dry-run prompt contains ONLY the facts the mock
  `npc_context` returned for THAT npc — proven against two NPCs with
  disjoint facts, so a knowledge-isolation leak would fail the test
- each actor turn's `allowedTools` is exactly the three actor tools, and
  `disallowedTools` covers the rest of the toolset (including
  `npc_context`)
- a later, well-separated event (`npc_death`) starts its own director
  scene
- the kill switch (`BRAIN_DISABLED_FILE` / `brain/DISABLED`) blocks both
  director scenes and actor turns entirely
- the daily usage budget file (`usage.json`) is written to `BRAIN_STATE_DIR`
- Lore Console: requests without a valid token get `401`; posting a
  directive with a valid token appends it to
  `lore/90-operator-directives.md` AND a subsequent director scene's
  dry-run system prompt already contains it (proving the immediate
  hot-reload, not just the file write); the page lists it, deleting it
  removes it from both the file and the page; the console never binds a
  port when `BRAIN_CONSOLE=0`
- Lore Console orders: posting to `/order` with a valid token persists it to
  `state/orders.json` (status `queued`, with a timestamp) AND starts a
  director scene whose dry-run prompt contains the order text; that scene's
  prompt also carries the OPERATOR ORDERS briefing section; `operator_order`
  and `sequence_done` are director wake events; the three new world tools
  (`create_explosion`, `strike_lightning`, `move_region`) are in `ALL_TOOLS`
  and NOT in `ACTOR_TOOLS`; the order scene's `maxTurns` is the higher
  `BRAIN_ORDER_MAX_STEPS` ceiling while an ordinary scene's stays at the
  regular `BRAIN_MAX_DIRECTOR_STEPS`; the page lists the order with its
  timestamp

It exits non-zero if any assertion fails.

## Environment variables

| Var | Default | Meaning |
|---|---|---|
| `MCALIVE2_URL` | `ws://127.0.0.1:8765` | Plugin bridge WebSocket URL (both the brain's own event connection and the MCP tool server it spawns) |
| `MCALIVE2_TOKEN` | `change-me` | Auth token matching the plugin's config |
| `BRAIN_DEBOUNCE_MS` | `2500` | Director scene debounce window |
| `BRAIN_LORE_REFRESH_MS` | `600000` | How often to re-read `brain/lore/*.md` |
| `BRAIN_DAILY_TOKEN_BUDGET` | `500000` | Cumulative input+output tokens/day (UTC reset) before new turns stop starting |
| `BRAIN_MAX_TURNS_PER_MIN` | `10` | Max turns (director + actor combined) started per rolling 60s window |
| `BRAIN_DIRECTOR_MODEL` | `claude-sonnet-5` | Model for director scenes |
| `BRAIN_ACTOR_MODEL` | `claude-haiku-4-5-20251001` | Model for NPC actor turns |
| `BRAIN_ENABLED` | `1` | Set to `0` as a kill switch (equivalent to the `brain/DISABLED` file) |
| `BRAIN_DRY_RUN` | `0` | `1` = log the prompt/system prompt/allowlist instead of calling the Anthropic API |
| `BRAIN_LORE_DIR` | `brain/lore` | Where lore `.md` files are read from |
| `BRAIN_STATE_DIR` | `brain/state` | Where `usage.json` (daily token counter) is kept |
| `BRAIN_DISABLED_FILE` | `brain/DISABLED` | Presence of this file is the kill switch |
| `BRAIN_MCP_SERVER_PATH` | `brain/mcp-bridge.mjs` | Path to the stdio MCP server the Agent SDK spawns for game/ledger tools |
| `BRAIN_TURN_TIMEOUT_SEC` | `300` | Hard wall-clock cap on a single director scene or actor turn's Agent SDK call before it is aborted and treated as a completed (timed-out) turn |
| `BRAIN_MAX_TOOL_RESULT_CHARS` | `3000` | Cap on a single tool result's serialized size returned to the model (`lib/tool-result-cap.mjs`); intelligently truncated (array fields shrunk with an omitted-count marker), never applied to `isError` results (see "Token-cost guardrails" above) |
| `BRAIN_MAX_TOKENS_PER_TURN` | `120000` | Hard ceiling on ESTIMATED cumulative tokens for a single director/actor turn - the query is aborted mid-stream the moment this is exceeded (see "Token-cost guardrails" above) |
| `BRAIN_MAX_TOKENS_PER_ORDER` | `600000` | Same as `BRAIN_MAX_TOKENS_PER_TURN`, but for a director scene triggered by an `operator_order` event (mirrors `BRAIN_ORDER_MAX_STEPS` below) |
| `BRAIN_MAX_DIRECTOR_STEPS` | `12` | `maxTurns` passed to the Agent SDK per director scene (tool-call steps within one turn) |
| `BRAIN_ORDER_MAX_STEPS` | `80` | `maxTurns` for a director scene triggered by an `operator_order` event, instead of `BRAIN_MAX_DIRECTOR_STEPS` (see Lore Console above) |
| `BRAIN_MAX_ACTOR_STEPS` | `6` | `maxTurns` passed to the Agent SDK per actor turn |
| `BRAIN_RECONNECT_BASE_MS` / `BRAIN_RECONNECT_MAX_MS` | `1000` / `30000` | Exponential backoff bounds for reconnecting to the plugin bridge |
| `BRAIN_NPC_CHAT_RANGE` | `8` | Documented range (enforced plugin-side) within which chat routes to an actor |
| `BRAIN_ACTOR_HISTORY_TURNS` | `20` | Turns of verbatim conversation kept per (npc, player) before folding into a running summary |
| `BRAIN_NPC_CONTEXT_TIMEOUT_MS` | `8000` | Timeout for the direct `npc_context` bridge call made before an actor turn |
| `BRAIN_LOG_JSON` | `0` | `1` = emit one-JSON-object-per-line logs instead of the default human-readable console narration |
| `BRAIN_DECISIONS_MAX_BYTES` | `1048576` (1MB) | Size threshold at which `brain/state/decisions.log` rotates to `decisions.log.1` (mainly for tests) |
| `BRAIN_CONSOLE` | `1` | Set to `0` to disable the Lore Console (see above) entirely |
| `BRAIN_CONSOLE_BIND` | `127.0.0.1` | Interface the Lore Console binds to; `0.0.0.0` to reach it from the LAN |
| `BRAIN_CONSOLE_PORT` | `7777` | Port the Lore Console listens on |
| `BRAIN_CONSOLE_TOKEN` | `MCALIVE2_TOKEN` | Shared token required on every Lore Console request |
| `BRAIN_UPDATE_CHECK_SEC` | `10` | Seconds between self-update checks against `origin/main`; `0` disables self-update (see Auto-update above) |
| `BRAIN_UPDATE_CHECK_MIN` | unset | Fallback (minutes, converted to seconds) if `BRAIN_UPDATE_CHECK_SEC` is unset; ignored otherwise |
| `BRAIN_UPDATE_IDLE_WAIT_SEC` | `600` | Cap on how long self-update's restart waits for the director/actor idle point before restarting anyway (`<=0` waits forever) |
| `BRAIN_DEBUG` | `0` | `1` = also show debug-level lines (e.g. self-update's "up to date" check) in the default human-readable console |
| `BRAIN_POSITION_TRACKING` | `1` | Set to `0` to skip the boot auto-install of `gadgets/position-tracker.java` entirely (world model always falls back to ledger home positions, exactly as before this feature existed) |
| `BRAIN_POSITION_INTERVAL_TICKS` | `20` | `intervalTicks` passed to the position-tracker gadget on `gadget_run` — how often (in server ticks, 20/s) it broadcasts `entity_positions` |
| `BRAIN_POSITION_STALE_SEC` | `30` | Age (seconds) past which a cached live position is marked `stale` (still used, not discarded — see Live position tracking below) |
| `BRAIN_WORLD_SCAN` | `1` | Set to `0` to skip the boot auto-install of `gadgets/world-scan.java` entirely (world model always falls back to `scan_area` over recorded places, exactly as before this feature existed) |
| `BRAIN_WORLD_SCAN_MAX_CELLS` | `4096` | `maxCells` passed to the world-scan gadget — cap on `cols*rows` sampled when surveying the whole loaded world |

## Files

- `index.mjs` — wires everything together: the bridge connection, event
  routing (director vs. actor), guardrail checks, director scene scheduler,
  actor turn queueing, the Lore Console.
- `mcp-bridge.mjs` — stdio MCP server exposing DESIGN.md's actuator/ledger/
  info command set as tools, pure passthrough to the plugin WebSocket
  (pattern reference: `minecraftalive/mcp/server.mjs`).
- `lib/config.mjs` — env parsing, tool namespacing, `ALL_TOOLS` /
  `ACTOR_TOOLS` and the disallow-list complement, shared with the smoke
  test.
- `lib/bridge-client.mjs` — the brain's own WebSocket client: auth
  handshake, auto-reconnect with exponential backoff (reconnect timer is
  deliberately NOT unref'd — a fixed bug from `minecraftalive/gm`, kept
  fixed here), plus direct `call()` for `npc_context` before actor turns.
- `lib/director-scheduler.mjs` — debouncing + "never two director turns
  concurrently, queue overflow into next turn."
- `lib/actor-memory.mjs` — rolling per-(npc, player) conversation memory,
  summarized past `BRAIN_ACTOR_HISTORY_TURNS` turns.
- `lib/director-turn.mjs` — builds the director prompt and calls the Agent
  SDK with the full toolset (or logs it under `BRAIN_DRY_RUN`).
- `lib/actor-turn.mjs` — builds the knowledge-isolated actor prompt and
  calls the Agent SDK with the 3-tool allowlist (or logs it under
  `BRAIN_DRY_RUN`).
- `lib/usage-tracker.mjs` — daily token budget, persisted to
  `brain/state/usage.json`; `reset()` backs `POST /budget/reset`.
- `lib/tool-result-cap.mjs` — caps a tool result's serialized size
  (`BRAIN_MAX_TOOL_RESULT_CHARS`), shrinking array-valued fields with an
  omitted-count marker before falling back to a hard string truncation;
  never truncates `isError` results. Used by `mcp-bridge.mjs`.
- `lib/logger.mjs` — the console logger: human-readable narration by
  default (`formatHumanLine`, unit-tested directly), full JSON lines under
  `BRAIN_LOG_JSON=1`; also owns the per-process scene counter
  (`nextSceneNumber()`).
- `lib/decisions-journal.mjs` — appends plain-English blocks to
  `brain/state/decisions.log` for every director scene and actor turn
  (including dry runs and guardrail-skipped turns), with size-based
  rotation.
- `lib/rate-limiter.mjs` — sliding-window turns-per-minute limiter.
- `lib/lore.mjs` — loads and watches `brain/lore/*.md`.
- `lib/console-server.mjs` — the Lore Console: a `node:http` server (token
  auth via query param + cookie) serving the operator page, the
  `lore/90-operator-directives.md` add/list/delete logic (triggering an
  immediate lore reload after every change), the `state/orders.json`
  add/list logic for one-shot operator orders, `GET /worldmodel` (cached
  ~5s), `GET /status` and `POST /budget/reset` (see Status & budget reset
  above), and the `/map` 3D schematic viewer page (see Lore Console above).
- `lib/worldmodel.mjs` — `buildWorldModel()`/`formatWorldOverview()`:
  aggregates ledger places/NPCs + a coarse `scan_area` + budgeted
  `get_block` probes into the world model and its text digest, backing the
  `world_overview` tool and `GET /worldmodel` (see World model & map data
  above). Prefers a live position from `opts.npcPositions` over the ledger
  `home` fallback when one is available (see Live position tracking above).
- `lib/position-cache.mjs` — small per-npc-id / per-player-name latest-
  position cache fed by pushed `entity_positions` bridge events; see Live
  position tracking above.
- `gadgets/position-tracker.java` — the runtime-injected gadget source
  (Java) that streams `entity_positions`; auto-installed on boot by
  `index.mjs` (see Live position tracking above).
- `gadgets/world-scan.java` — the runtime-injected gadget source (Java) that
  surveys every currently-loaded chunk into a coarse heightmap on demand
  (`"gadget:world-scan"`); auto-installed on boot by `index.mjs`'s
  `installWorldScan` (see World model & map data above).
- `lib/self-update.mjs` — checks `origin/main` on a timer and pulls +
  restarts (exit 75) when new code has landed; see Auto-update above.
- `run-forever.cmd` — Windows restart-loop wrapper around `npm start` that
  understands exit code 75 (update) vs. any other nonzero exit (crash).
- `lore/` — the world's lore, loaded into the director's system prompt.
- `blueprints/` — reserved for M3 (`build_blueprint` JSON blueprint
  library); empty for M1.
- `test/mock-bridge.mjs`, `test/smoke.mjs` — see Testing above.
