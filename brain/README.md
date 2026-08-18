# brain/ — Estari director + NPC actors

The AI side of Estari (see `../DESIGN.md` for the full contract). One
**director** (default `claude-sonnet-5`) runs the world event-driven and
unattended; cheap per-NPC **actors** (default `claude-haiku-4-5-20251001`)
hold knowledge-isolated conversations. Neither reimplements a game ability —
both talk to the Estari Paper plugin exclusively through `mcp-bridge.mjs`,
a stdio MCP server that passes every actuator/ledger/info command straight
through to the plugin's WebSocket bridge.

```
Estari plugin (ws://.../8766)
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

`index.mjs` owns one WebSocket connection to the plugin (`ESTARI_URL`) and
routes every pushed sense event one of two ways:

- **`npc_interact`** → always an actor turn for that NPC.
- **`player_chat` with `data.nearNpcId` set** (the plugin decides proximity,
  default range `BRAIN_NPC_CHAT_RANGE=8` blocks, and stamps the nearby
  NPC's id on the event) → an actor turn for that NPC.
- **Everything else** — `player_join`, `player_death`, `npc_attacked`,
  `npc_death`, `player_explored`, `player_idle_scene`, `region_enter/exit`,
  and `player_chat` with no nearby NPC — is debounced (`BRAIN_DEBOUNCE_MS`,
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
export ESTARI_URL=ws://127.0.0.1:8766   # matches the plugin's bridge config
export ESTARI_TOKEN=pick-a-long-random-token
npm start
```

Run this under whatever keeps a process alive on your machine (systemd,
pm2, a `tmux`/`screen` session, NSSM/Task Scheduler on Windows) —
`brain/` is a plain Node script with no daemonizing of its own.

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

`set_time` and all narration tools (`broadcast`/titles/action bar) are
**absent by design**, not by a deny-list: they simply do not exist as
plugin bridge commands, so `mcp-bridge.mjs` has nothing to forward them to.

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

Edit these files freely — the service re-reads `brain/lore/` on a timer
(`BRAIN_LORE_REFRESH_MS`, default 10 minutes) and picks up changes without
a restart. Add more numbered files to grow the lore; the numeric prefix
just controls read order.

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

It exits non-zero if any assertion fails.

## Environment variables

| Var | Default | Meaning |
|---|---|---|
| `ESTARI_URL` | `ws://127.0.0.1:8766` | Plugin bridge WebSocket URL (both the brain's own event connection and the MCP tool server it spawns) |
| `ESTARI_TOKEN` | `change-me` | Auth token matching the plugin's config |
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
| `BRAIN_MAX_DIRECTOR_STEPS` | `12` | `maxTurns` passed to the Agent SDK per director scene (tool-call steps within one turn) |
| `BRAIN_MAX_ACTOR_STEPS` | `6` | `maxTurns` passed to the Agent SDK per actor turn |
| `BRAIN_RECONNECT_BASE_MS` / `BRAIN_RECONNECT_MAX_MS` | `1000` / `30000` | Exponential backoff bounds for reconnecting to the plugin bridge |
| `BRAIN_NPC_CHAT_RANGE` | `8` | Documented range (enforced plugin-side) within which chat routes to an actor |
| `BRAIN_ACTOR_HISTORY_TURNS` | `20` | Turns of verbatim conversation kept per (npc, player) before folding into a running summary |
| `BRAIN_NPC_CONTEXT_TIMEOUT_MS` | `8000` | Timeout for the direct `npc_context` bridge call made before an actor turn |

## Files

- `index.mjs` — wires everything together: the bridge connection, event
  routing (director vs. actor), guardrail checks, director scene scheduler,
  actor turn queueing.
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
  `brain/state/usage.json`.
- `lib/rate-limiter.mjs` — sliding-window turns-per-minute limiter.
- `lib/lore.mjs` — loads and watches `brain/lore/*.md`.
- `lore/` — the world's lore, loaded into the director's system prompt.
- `blueprints/` — reserved for M3 (`build_blueprint` JSON blueprint
  library); empty for M1.
- `test/mock-bridge.mjs`, `test/smoke.mjs` — see Testing above.
