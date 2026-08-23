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

---

## What runs where

- **Plugin** (`plugin/`, Paper 26.2) — bodies, blocks, the ledger, the bridge. Frozen surface.
- **Brain** (`brain/`, Node) — the director, the Lore Console, the world model. Auto-pulls from
  git every 10s and relaunches. **A push restarts it.**
- **Gadgets** — Java compiled at runtime, persisted in `gadgets.json`. This is where all the
  behaviour lives. The brain re-installs *its own* gadgets (e.g. `world-scan`) on boot, so a
  runtime `gadget_define` of one of those is **not durable** — change the file in `brain/gadgets/`
  and push.

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

Runtime timers do not survive. The gadget *source* does. Run these or the world looks dead and
stops feeding itself:

```
gadget:presence      {radius:1, periodTicks:100}
gadget:needs         {stores, periodTicks:1200}
gadget:hunger        {stores, foragers, lethal, drainPerBeat:1, periodTicks:2400}
gadget:pursuits      {stores, warehouses, reserved, periodTicks:40}  then action:"live"
gadget:farm          {fields, periodTicks:15}
gadget:roster        {periodTicks:200}
gadget:groundskeeper {periodTicks:300}
gadget:reclaim       {periodTicks:60}
```

---

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

- **The food economy has not reached equilibrium.** The farm loop is proven (crops grow, are
  harvested, return seed) but production does not yet feed 47 NPCs. Seed is the binding
  constraint and compounds slowly. Starvation is currently **non-lethal** while it scales.
- **Sigrun (`moradin-4`) is dead** with a memorial laid; an Ancient is walking to it.
- Phases 5–7 of the decision plan are unbuilt: multi-step plans, the social layer, and the
  Ancients' goal arcs.
