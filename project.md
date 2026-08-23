# MCAlive2 — working notes

The short version of everything learned running this world. `DESIGN.md` is the contract;
this is the operating manual. Read it before touching anything.

---

## The hard rules

These came from James directly and override convenience. Breaking one is a bug, not a tradeoff.

1. **NPCs never teleport.** They pathfind and walk at a player's pace. No instant relocation,
   no moving through solids, no reaching a block they could not stand beside. New player-like
   abilities (spells, tools) are welcome — but only things a player could also be given.
   *Smooth per-tick stepped movement is how the plugin moves a body with no mob AI; that counts
   as walking. Jumping a body across the map does not.*
2. **Tokens are only for authoring new options.** Every decision at runtime is arithmetic in
   gadget Java. A model is consulted to write a *new action* into the catalogue, never to pick
   among the ones that exist.
3. **Never ship a capability as a plugin change.** The ladder is: existing tool → formula →
   **gadget** (Java compiled onto the running server via `gadget_define`). The plugin surface
   is frozen.
4. **Every NPC is a player.** They start at world spawn with nothing, gather like a player,
   craft like a player, die like a player. Nothing is placed for them, stocked for them or
   picked for them. If the ground is bad they walk. If they cannot feed themselves they die.
5. **Food has no grace period.** Hunger is lethal. Do not soften it when someone struggles.
   Softening it is what produced a world of 47 permanently starving people who could never
   die and never grew.

---

## What runs where

- **Plugin** (`plugin/`, Paper 26.2) — bodies, blocks, the ledger, the bridge. Frozen surface.
- **Brain** (`brain/`, Node) — the director, the Lore Console, the world model. Auto-pulls from
  git every 10s and relaunches. **A push restarts it.**
- **Gadgets** — Java compiled at runtime, persisted in `gadgets.json`. This is where all the
  behaviour lives. **Every gadget's source is committed under `brain/gadgets/`** (2026-08-22);
  before that, most existed only inside a running server's `gadgets.json` and one lost disk
  would have taken the entire capability set with it. Edit the file, then install it —
  `scripts/people.mjs --start` installs every one. The brain re-installs
  `position-tracker` and `world-scan` itself on boot.

Bridge: `ws://192.168.40.4:8765`, token `mca2-Xq7vN4kRw9pTz2Lm8Jd3`.
Console: `http://192.168.40.4:7777/map?token=<same token>`.
The legacy `minecraftalive` MCP server speaks an **old protocol** and will fail against this
server. Drive the bridge with a small Node WebSocket client instead (`auth`, then `{id,cmd,args}`).

---

## Where NPC data lives

| What | Where |
|---|---|
| Character sheet — abilities, skills, needs, hunger, inventory, job, promises, village, alive | `plugins/MCAlive2/ledger/npcs.json` |
| Where each person has been (chunk keys), kept off the sheet the actor reads | `ledger/explored.json` |
| Villages and ruins (`kind: village | ruin | claimed`), inns, stores | `ledger/places.json` |
| Body — entity type, skin, uuid, home/work, last location, `dead`/`diedAt` | `plugins/MCAlive2/npcs.json` |
| Other collections | `ledger/{factions,places,facts,quests,promises,players}.json` |
| Running state (jobs, sessions, chunk tickets) | memory only — dies on restart |

Same filename, different folder. **They can disagree**: `npc_revive` clears `dead` in the body
file but not `alive` in the ledger — always write both.

---

## After every server restart

Runtime timers do not survive. Gadget sources do, and every one is committed under
`brain/gadgets/`. One command reinstalls all of them and restarts every timer:

```
node scripts/people.mjs --start
```

What it starts: `presence` (3x3 chunks around every person), `groundskeeper`, `people`
(`populationCap: 40`), `tablist`, `villages`. Nothing else should be running — the founder-era
gadgets (pursuits, needs, hunger, roster, farm, reclaim, industry...) are kept in the repo as
reference and must NOT be started; they fight `people` for the same bodies.

**Do not auto-install a timer gadget from the brain's boot path.** `gadget_define` loads a new
class but does not run it, so the old timer keeps beating the old code. Only `position-tracker`
is brain-installed, and it defines *and* runs.

---

## How people work

**Every NPC is a player.** (2026-08-23, James: "I want npc's to start at spawn just like a
player, gather resources just like players and be just like fucking players.") The founders,
lines, lineage and migration are gone. `gadget:people` is the NPC.

- **Start** at world spawn with nothing. `scripts/people.mjs` spawns `scripts/people.json`; after
  that 0–5 strangers arrive at 5am each Minecraft day (rolled sheets) until `populationCap` (40).
- **Body**: 20 hp (the real entity), 20 hunger draining a point a minute working / two idle,
  a 36-stack inventory in the ledger record (a mannequin cannot carry a real one), tools with
  real durability, vanilla recipes (`Bukkit.getRecipesFor`) and vanilla drops
  (`Block.getDrops(tool)`), the tool held in hand. Gravity applies when they stand still; the
  walker swims through water rather than over it. Death is permanent; the head drops and never
  despawns; anyone who walks over it carries it with its identity.
- **Abilities**: str dex con wis int cha, -3..+3, fixed. **Skills**: farming hunting mining
  building fishing swimming exploring treechopping crafting trading; start 0; minutes at the
  skill accumulate; point n at 2^n-1 minutes. Each leans on one ability (`SKILL_STAT`). Skill +
  ability weights which job is picked and how fast it goes (`pace()`).
- **Needs**: hp, hunger, and one of their own (explore / social / wealth / craft). Happiness
  is how full the three are. The chooser scores each job by shortfall² × how much the job
  serves it × aptitude, and picks among the top three; with nothing short, people do what they
  are good at.
- **Jobs**: hunt fish farm chop mine explore craft visit trade market build lodge pickup rest.
  Each is one static method advancing a persisted state machine one beat at a time, so a
  redefine or a restart resumes mid-job. Crafting resolves its own chain (hoe → sticks → planks
  → logs → a tree). A job chosen from a conversation (`npc_do` → `people assign`) goes first
  and is remembered as a promise on the sheet.
- **Trade**: `valueTo()` prices an item by how much it serves YOUR unmet needs; a deal happens
  only when both gain by their own reckoning, shaded by trading skill; nothing to offer means
  no deal and the person goes and solves it another way. A done deal cools the pair for 5 min.
- **Talking**: the plugin routes chat to an NPC within 8 blocks; an NPC with a player within 4
  blocks stops, faces them and waits 20 s so that can happen. The actor may call `npc_do`.
- **Villages** (`gadget:villages`): a person within ~96 blocks of a generated village records
  it as a ruin; a person with a bench and a neighbour standing in a ruin claims it, or with a
  field, a bench and two neighbours founds one in the open; two minutes nearby makes you a
  member and the village your home; a village without an inn asks a member with timber and
  some building skill, who raises a 5x5 inn block by block (`build`); at night people lodge at
  the nearest inn (`lodge`), strangers paying one item into the inn chest; that chest is the
  market (`market`) anyone buys from and sells to at `baseValue`, strangers at 1.25×.
- **Tab list** (`gadget:tablist`): every living person is a real player-info entry.

`gadget:people {action:"status"}` is the one-screen view. `scripts/people.mjs --status` prints it.

---

## The developer

Once an hour (`BRAIN_DEV_INTERVAL_MIN`, default 60) the brain runs `lib/developer.mjs`: it
takes the first `pending` entry in `brain/roadmap.json` (100 features, in dependency order),
hands a coding agent this file, the roadmap, the live status and the feature, and lets it work
in the laptop checkout with file tools plus the live bridge. The agent compiles its gadget on
the running server, verifies, marks the entry `done` (or `failed`, honestly) with notes,
appends to "Features added by the developer" below, and commits. The brain then pushes, and
the self-updater restarts it on the new code.

Guardrails: one run at a time; skipped when fewer than `BRAIN_DEV_MIN_REMAINING_TOKENS`
(800k) of the daily budget remain; the self-updater waits while a run is in progress; an entry
that runs twice without being marked is parked as `failed`. `state/developer.log` is the
journal. **The push needs git credentials on the laptop** — if it fails the feature is still
live on the server (the gadget was defined) but GitHub lags until someone pushes.

## Features added by the developer

(none yet)

---

## The gadgets

| Gadget | Does |
|---|---|
| `people` | The NPC. Body, abilities, skills, needs, every job, trade, arrivals, heads. `status`, `spawn`, `arrive`, `assign`. |
| `villages` | Ruins found, villages founded and joined, inns asked for. `status`, `inn_built`. |
| `tablist` | People in the player list. `status`, `debug`. |
| `navigate` | A* over standing positions, player rules, swims through water, surface-biased. Everything that moves goes through this. |
| `presence` | Holds chunk tickets in a 3x3 around every person; unloads the rest. |
| `groundskeeper` | Frees people stuck in terrain by walking or digging, never lifting. |
| `world-scan`, `position-tracker` | Brain-owned: the console map and live positions. |
| `craft`, `smelt`, `store`, `mine`, `forage`, `forester`, `shelter`, `farm`, `hunger`, `needs`, `pursuits`, `roster`, `reclaim`, `industry`, `build-structure`, `place-structure` | Founder-era. Kept as reference and as a parts bin; not started. |

## Gotchas paid for in blood

**Gadget authoring**
- A redefine loads a **new class** — statics reset, and any running `ctx.runTimer` becomes an
  orphan nothing can stop. Keep a generation counter in the world's `PersistentDataContainer`
  and reap orphans by reflecting the Runnable out of `getPendingTasks()`.
- `ctx` is not in scope in every method. A push that compiles locally can still fail to install.
  **Verify with `gadget_get` — a green push is not proof.**
- Wrap per-item work in its own try/catch. One bad item silently aborted a whole sweep and only
  one errand ever started.
- To compile-check an edit to a *running* gadget without disturbing it, define the source under
  a throwaway id (`pursuits-compilecheck`), exercise the new action, then `gadget_delete` it.
  The id and the class name are independent, so this is safe.
- Prefer a new **action** on an existing gadget over restarting it. `start` clears every static:
  running jobs, rotations, reservations, field state. That is why `pursuits action:"reserve"`
  and `farm action:"assign"` exist.
- Gadget sources are large. `pursuits.java` is 57k chars, which is past the command-line
  argument limit — a bridge driver must read the payload from a **file**, not `argv`.

**Terrain and founding**
- `scan_area` returns a **fractional** `medianY`. Round it before it reaches `set_block` or
  `npc_spawn`.
- Judge flatness over the central **9x9** the founder actually stands on, not the whole 16x16
  scan. Judging the full window rejects a good clearing for having a hillside in one corner —
  it cut acceptable sites from 7/8 to 2/8 in testing. Judge *wetness* over the full window.
- Full-resolution `columns` only come back at ≤256 columns (16x16). Above that you get a
  downsampled 8x8 `grid` instead.

**Movement and terrain**
- The plugin's own mannequin walker has **no pathfinding** — straight line, climbs 1, drops 3,
  cancels at any wall. Never build on it. Use `navigate`.
- A* must **cost depth below the surface**, or a cave mouth scores as a shortcut and walks
  somebody to bedrock one legal step at a time.
- The start search must look **up** as well as down, or an embedded NPC can never begin a walk —
  which silently breaks every walk-based rescue.
- Settle after each step and apply gravity, or NPCs end ticks inside blocks or hanging in air.
- `getHighestBlockYAt` counts **leaves**. Use `HeightMap.OCEAN_FLOOR` for real ground.
- Building actions must skip a target with a living entity in it, or paving walls people in.

**Behaviour engine**
- `behavior_blocked` is a **DIRECTOR WAKE EVENT**. 43 blocking circuits woke the director 16
  times a minute and burned a 5M daily budget in 79 minutes. Routine life must not use behavior
  programs. Check anything new against `DIRECTOR_WAKE_EVENTS` in `brain/lib/config.mjs`.
- One blocked crewmate pauses a whole program — use single-NPC programs for anything that can block.
- `behavior_create` resets cursors and **wipes carried materials**; `behavior_update` keeps them.

**Economy**
- `craft` must check the result **fits** before consuming inputs. A full chest silently ate the
  output while the ingredients were still spent.
- A store that fills up **cannot craft anything** — every line lost the ability to make a hoe
  this way. Spill surplus to a warehouse.
- Miners must leave bulk spoil in the ground, or one shift buries the stockpile.
- **Wheat is not food.** It must be baked: 3 wheat → 1 bread.
- Unirrigated farmland reverts to dirt; irrigate, but only dig the water into solid ground.
- Never measure a field's height at its centre once water is there — water is not solid, so the
  reading drops a block each restart and every cell then inspects the earth under the surface.
- A crop only ripens in a **loaded chunk**, so the farmer must stay with the field.
- Whenever movement cost changes, **re-tune every consumption rate against it**, and disarm
  lethality until supply is proven. Six NPCs starved because hunger was tuned for teleporting
  foragers and then they had to walk.

**Map**
- The voxel scan emits a shell; only blocks **touching air** can ever show a face. `SHELL = 1`.
- It draws only chunks containing an NPC or player, dilated by one (3×3).
- The client caps total voxels — exceed it and whole domains render as bare labels.
- The console's `buried` warnings measure against a coarse step-8 heightmap that counts canopy.
  They are usually false.

---

## Known open issues

- **Hunting fed nobody for an hour on 2026-08-23** and all four people starved: the first
  version of real drops finished the loot step the instant the hunter was "near enough" to
  where the animal stood, with a 1.5-block pickup radius. Fixed (walk onto each drop, 2.5-block
  pickup, 90 s chase cap) and proven with a test hunter before respawning. Lesson: **a change to
  how food arrives gets tested with a hungry tester before anyone lives on it.**
- The plugin's own registry can lose a body that is still standing there (Wren, next to Mara).
  `people` re-adopts by tag each beat; presence and chat routing then see them again.
- `tablist` is reflection over server internals (`ClientboundPlayerInfoUpdatePacket$Entry`);
  a Paper bump shows up as `lastError` in its status, not a compile error.
- The brain smoke test has one pre-existing failure ("voxel shell is Chebyshev distance 3")
  from the SHELL=1 change; the test was never updated.
- Fishing is unreachable until string exists (no spiders spawn). On the roadmap.

