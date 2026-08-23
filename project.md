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
4. **Placement of a settlement is authoring, not NPC movement.** Founding or re-founding a
   line's home may put bodies where they belong. Say so explicitly when you do it.
5. **Food has no grace period.** Hunger is lethal, drains 1 point per 2 minutes, and starts
   from an empty store. Do not soften it when a line struggles — a line that cannot feed
   itself is supposed to die. Softening it is what produced a world of 47 permanently
   starving people who could never die and never grew.

---

## What runs where

- **Plugin** (`plugin/`, Paper 26.2) — bodies, blocks, the ledger, the bridge. Frozen surface.
- **Brain** (`brain/`, Node) — the director, the Lore Console, the world model. Auto-pulls from
  git every 10s and relaunches. **A push restarts it.**
- **Gadgets** — Java compiled at runtime, persisted in `gadgets.json`. This is where all the
  behaviour lives. **Every gadget's source is committed under `brain/gadgets/`** (2026-08-22);
  before that, most existed only inside a running server's `gadgets.json` and one lost disk
  would have taken the entire capability set with it. Edit the file, then install it —
  `scripts/found.mjs --start` does that for the ones it owns. The brain re-installs
  `position-tracker` and `world-scan` itself on boot.

Bridge: `ws://192.168.40.4:8765`, token `mca2-Xq7vN4kRw9pTz2Lm8Jd3`.
Console: `http://192.168.40.4:7777/map?token=<same token>`.
The legacy `minecraftalive` MCP server speaks an **old protocol** and will fail against this
server. Drive the bridge with a small Node WebSocket client instead (`auth`, then `{id,cmd,args}`).

---

## Where NPC data lives

| What | Where |
|---|---|
| Character sheet — personality, needs, hunger, activity, faction, bloodline, alive | `plugins/MCAlive2/ledger/npcs.json` |
| Body — entity type, skin, uuid, home/work, last location, `dead`/`diedAt` | `plugins/MCAlive2/npcs.json` |
| Other collections | `ledger/{factions,places,facts,quests,promises,players}.json` |
| Running state (jobs, sessions, chunk tickets) | memory only — dies on restart |

Same filename, different folder. **They can disagree**: `npc_revive` clears `dead` in the body
file but not `alive` in the ledger — always write both.

---

## After every server restart

Runtime timers do not survive. The gadget *source* does — and since the 2026-08-22 rebuild
every gadget's source is committed under `brain/gadgets/`, so nothing has to be reconstructed
from a running server ever again.

One command re-installs the sources that matter and restarts every timer, reading each line's
store and field back out of the `places` ledger:

```
node scripts/found.mjs --start
```

That is the whole checklist. It is idempotent and safe to run twice. What it starts:

```
gadget:presence      {radius:1, periodTicks:100}
gadget:needs         {stores, periodTicks:1200}
gadget:hunger        {stores, lethal:true, drainPerBeat:1, periodTicks:2400}
gadget:pursuits      {stores, warehouses, reserved, periodTicks:40}  then action:"live"
gadget:farm          {fields, periodTicks:15}
gadget:roster        {periodTicks:200}
gadget:groundskeeper {periodTicks:300}
gadget:reclaim       {periodTicks:60}
gadget:lineage       {stores, maxKin:5, reservePerMouth:20, periodTicks:1200}
```

**Do not auto-install a timer gadget from the brain's boot path.** `gadget_define` loads a new
class but does not run it, so the *old* timer keeps beating the *old* code and the new source
silently does nothing until something runs it. Only `position-tracker` is brain-installed, and
it defines *and* runs. Everything else belongs in the command above.

---

## How a line grows

The world is founded (2026-08-22) with **eight lone Ancients** and nothing else — no kin, no
tools, an empty chest, a bench and a furnace on flat ground about 520 blocks from spawn and
~300–500 from each other. `scripts/found.mjs` surveys for the sites and stands them up.

Each founder can raise **five generation-1 followers**, and chooses when. `gadget:lineage`
scores the decision every minute out of the same needs-and-personality arithmetic the chooser
uses — no threshold, no model call:

```
pressure  = (necessity + belonging + purpose) x confidence
restraint = 1.5 + 0.4 x kin + 0.35 x max(0, 12 - fed)
```

- **necessity** — 1.4 per unfilled role (forager, farmer, woodcutter, builder, miner). This is
  what lets a cold founder grow: Vecna does not want company, he wants the wood cut. Measured,
  a lone founder scores 8.6 (Vecna) to 12.2 (Lliira) against a restraint of 1.5, so every line
  calls its first follower as soon as it can feed one.
- **confidence** — banked nutrition ÷ `(mouths + 1) x 20`, capped at 2.0. **Below 1.0 the
  decision cannot fire at all.** A line grows exactly as fast as it learns to feed itself.
- Belonging saturates near four kin (it reads as line-mates within 28 blocks), so the fifth
  follower is carried by necessity and purpose alone and needs a genuinely stocked larder —
  measured at 150–164 nutrition banked.

Followers inherit alignment, ethos and the founder's long want, and each personality axis with
a ±1 drift. Their short want, appearance and activity come from the role they were called for.
They are `bloodline.generation: 1` with the founder as parent, and carry a `role` field.

A called farmer is immediately reserved with `gadget:pursuits {action:"reserve"}` and handed
the field with `gadget:farm {action:"assign"}` — both added for exactly this, because
restarting either gadget to change one name wipes every running job.

## The gadgets

| Gadget | Does |
|---|---|
| `navigate` | A* over standing positions, player rules, surface-biased. Everything that moves goes through this. |
| `pursuits` | The chooser: 54 actions scored against needs and personality. `action:"why"` explains a decision. |
| `needs` | Eight drives. Fatigue/purpose/curiosity accumulate; safety/shelter/belonging/wealth are read from the world. |
| `hunger` | Fed level, eating from the line store, starvation, auto-dispatch of foragers. |
| `farm` | The sustainable food loop: weed, till, sow, reap; harvests return seed. |
| `forage` / `forester` / `mine` | Walking expeditions for meat, timber, ore. |
| `craft` / `smelt` / `store` | Vanilla recipes against a chest; `store` also places blocks and spills surplus. |
| `shelter` | Raises a hut block by block from a line's store. |
| `groundskeeper` | Frees NPCs stuck in terrain; digs them out; resumes what it can. |
| `reclaim` | Any Ancient walks to a memorial head and restores the dead. |
| `presence` | Holds chunk tickets where NPCs stand; unloads everything else. |
| `roster` | Writes a plain-English activity onto every ledger record for the map. |
| `lineage` | An Ancient's own decision to call kin, and what role that kin fills. `action:"why"` shows the arithmetic per line. |

---

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

- **The first world died of a fed economy, not a broken one.** 47 NPCs sat at 0/20 fed
  permanently because starvation had been disarmed while foraging was retuned for walking, and
  the eight dedicated farmers were never passed as `reserved` after a restart, so the chooser
  sent every one of them off to forage or fell timber. Both are fixed: hunger is lethal again
  and `found.mjs --start` always passes the reservations.
- The **new world is unproven at the far end.** A lone founder reaching five kin has been
  modelled, not observed. Watch whether any line actually banks 150+ nutrition.
- Phases 5–7 of the decision plan are unbuilt: multi-step plans, the social layer, and the
  Ancients' goal arcs. `lineage` is a narrow slice of the social layer, not the whole thing.
- `npc_begotten` is deliberately **not** in `DIRECTOR_WAKE_EVENTS`. Add it only if you want the
  director narrating births — it fires at most 40 times in a world's life, so it is affordable.
- Founder **skins are not recovered.** The old world's skins lived only in the laptop's
  `plugins/MCAlive2/npcs.json`, which the wipe destroys. Everyone founds as a default mannequin
  until skins are set with `npc_update`.
