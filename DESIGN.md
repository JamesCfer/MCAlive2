# Estari — an AI decision brain that runs a fantasy RPG inside Minecraft

Successor to the MinecraftAlive prototype, designed from zero. One AI **director**
runs the world; cheap per-NPC **actors** hold conversations. Minecraft itself is the
description layer: the world tells the story through structures, NPCs, sound, light,
and weather. **Chat is exclusively for dialogue** — no narration text, ever.
There are no dice: the director adjudicates declared actions from fiction, ledger
facts, and stakes, and answers with consequences in the world.

## Components

```
players ⇄ Paper server ⇄ Estari plugin (senses, actuators, ledger, NPC runtime)
                              ⇅ WebSocket (token auth, JSON)
                        brain/ (Node, Claude Agent SDK)
                          ├─ director loop (scene → memory → decision → action)
                          └─ NPC actors (one cheap call per conversation turn)
```

- `plugin/` — Java 21, Paper API 26.2, Maven (shade Java-WebSocket). Plugin name `Estari`.
- `brain/` — Node >= 22 ESM, `@anthropic-ai/claude-agent-sdk`.

## Protocol (plugin ⇄ brain)

WebSocket server in the plugin (config: host/port/token). Messages:
request `{id, cmd, args}` → response `{id, ok, data|error}`; pushed events `{event, data}`.
First message must be `{id, cmd:"auth", args:{token}}`.

## Plugin subsystems

### 1. Senses (pushed events)
- `player_join`, `player_quit`, `player_death` (with cause), `player_chat`
- `npc_interact` (right-click), `npc_attacked`, `npc_death` (killer if any)
- `player_explored` — fired when a player enters an unexplored 8x8-chunk cell
  (plugin tracks visited cells in its data folder). Payload includes cell coords,
  biome sample, surface heightmap summary (min/max/median y over a 16-point grid),
  and notable features (water present, lava, village-distance if known).
- `player_idle_scene` — every N minutes of active play without other events,
  a light "heartbeat" scene so the director may (or may not) breathe life nearby.
- `region_enter` / `region_exit` — named regions from the ledger (see below).

### 2. Actuators (commands)
World: `set_block`, `fill_region` (capped), `build_blueprint` (paste a JSON blueprint:
list of {dx,dy,dz,material[,data]} relative to an origin — the unit of AI construction),
`spawn_entity`, `remove_entity`, `spawn_particles`, `play_sound`, `set_weather`.
NPCs: `npc_spawn` (mannequin default, skin by username), `npc_update`, `npc_remove`,
`npc_say` (dialogue ONLY channel), `npc_walk_to`, `npc_look_at`, `npc_equip`,
`npc_pose`, `npc_revive`.
Players: `give_item` (MiniMessage names/lore), `apply_effect`, `list_players`.
Ledger: see below. Info: `get_server_info`, `get_block`, `sample_terrain` (grid summary
for a rect — the director's eyes when planning a build).
**Deliberately absent: broadcast/title/actionbar narration tools and set_time.**

### 3. Ledger (structured world memory, the consistency backbone)
Typed collections persisted as JSON files in the plugin data folder, CRUD over the bridge:
- `npcs`: {id, name, skin, appearance, personality {drive, warmth, boldness, composure: -3..3},
  wants: [{horizon: short|long, text}], home, work, schedule, faction?, alive, diedAt?}
- `factions`: {id, name, ethos, goals[], standing: {factionId: -3..3}}
- `places`: {id, name, kind, origin {x,y,z}, bounds?, description, builtBy: ai|world|player}
- `quests`: {id, title, giverNpc, state: offered|active|done|failed, beats: [{text, done}], playersInvolved[]}
- `facts`: {id, text, knownBy: [npcId|faction:id|"all"], source, createdAt}
- `promises`: {id, byNpc|byPlayer, toWhom, text, due?, kept?}
- `players`: {name, firstSeen, history: [{at, text}], reputation: {factionId: -3..3}}
Commands: `ledger_put {collection, record}`, `ledger_get {collection, id}`,
`ledger_query {collection, filter}` (subset match), `ledger_delete`,
`npc_context {npcId}` — returns that NPC's sheet + ONLY facts whose knownBy includes
them / their faction / "all" (knowledge isolation enforced plugin-side, so an actor
can never metagame).

### 4. NPC runtime (lessons already paid for in blood)
Mannequin entities, PDC-tagged, walking via stepped surface movement that refuses
cliffs (>3 drop) and gaps <2 tall; immune to environmental damage (fall/suffocation/
drowning/void) but not to players or mobs. Death is permanent: never auto-respawn a
dead NPC, drop a named memorial player head, mark dead in ledger. Entity resolution
must chunk-load + adopt-by-PDC-tag before ever respawning, with a 60s cooldown, plus
an orphan sweep on startup and EntitiesLoadEvent (duplicate NPCs must be impossible).
Daily schedules (goto_home/goto_work/wander/idle by world time) run plugin-side.

## Brain

### Director loop
Event-driven, zero idle cost. Debounce events into a scene (default 2500ms window,
never two turns concurrently, queue overflow into next turn). Per scene:
1. Perceive: the event batch, current players, relevant ledger slices.
2. Remember: `ledger_query` for involved NPCs/places/quests/facts.
3. Decide: silence is a first-class outcome. If a player declared an action with
   stakes (in chat or by deed), adjudicate: possible? costly? consequences? —
   grounded in ledger facts, no randomness. Write the outcome INTO THE WORLD via
   actuators, never as text.
4. Act & record: actuator calls + ledger writes (facts learned, promises made,
   quest beats advanced). Every consequential decision must land in the ledger.
The director prompt carries `brain/lore/*.md` (hot-reloaded): world bible, tone,
standing rules (no time changes, offers not railroads, copper-age tier, chat=dialogue only).

### NPC actors
`npc_interact` or chat within 8 blocks of an NPC routes to that NPC's actor: a cheap
model call with the NPC sheet + `npc_context` facts + rolling conversation transcript
(kept in brain memory per npc+player, summarized past 20 turns). Actors may ONLY
call `npc_say`, `npc_look_at`, `npc_pose` — enforced by per-role tool allowlists.
Actors end their reply with an optional structured `report` (new fact proposals,
promise made, quest offered) which the DIRECTOR validates into the ledger.

### Worldgen
On `player_explored`, the director may commission a build: it calls `sample_terrain`,
chooses/adapts a blueprint (brain/blueprints/*.json library, plus generated ones),
and executes `build_blueprint` + `npc_spawn` + ledger `places` entry. Encounters
follow the standing rule: seeded near players, never on top of them, always ignorable.

### Guardrails (all env-tunable, mirror gm/ lessons)
Daily token budget (state file, UTC reset), max turns/min, kill-switch file `brain/DISABLED`,
`BRAIN_DRY_RUN=1` prints prompts instead of calling, per-role tool allowlists,
director model env `BRAIN_DIRECTOR_MODEL` (default sonnet-class), actor model
`BRAIN_ACTOR_MODEL` (default haiku-class). Deny-list always: `set_time` (absent anyway),
console/raw commands (never exposed).

## Testing
Both sides testable with no API key and no server:
- plugin: unit-testable ledger + blueprint parser; manual smoke via a mock WS driver script.
- brain: mock-bridge (ws) emitting scripted events; smoke asserts auth, debounce,
  actor routing vs director routing, knowledge isolation (actor context excludes
  unknown facts), kill switch, allowlists. `npm test` must pass offline.

## Milestones
- **M1 skeleton (now)**: bridge+auth, core senses (join/quit/chat/interact/explored),
  NPC runtime (spawn/say/walk/schedules/permadeath), ledger CRUD + npc_context,
  brain director loop + actor routing in dry-run, offline smoke tests green.
- **M2**: adjudication quality, actor conversations live, promises/quests flowing.
- **M3**: worldgen — blueprints, terrain-aware building, exploration encounters.
