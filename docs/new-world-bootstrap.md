# New World Bootstrap

Ordered checklist for starting the fresh world on the server laptop. The
plugin release with the spawn regime, NPC self-defense, and the behavior
engine must be live first (the auto-update chain handles that — just verify
the version before wiping anything).

## 1. Verify the release landed

- In the server console: `mcalive2 status` (or check the startup log line)
  and confirm the plugin version matches the latest GitHub release.
- The brain self-updates from git within ~10 seconds of a push; check its
  console banner for the current commit.

## 2. Stop the server and reset the world

- Stop the server (the restart loop's sentinel means: stop the loop first,
  or the server just comes back).
- Delete (or archive) `world`, `world_nether`, `world_the_end`.
- **Also clear the plugin's own state.** It does *not* live in the world
  folders, so it survives the wipe and then collides with founding —
  `npc_spawn` refuses an id that already exists:

  ```
  plugins/MCAlive2/ledger/          <- delete the whole folder
  plugins/MCAlive2/npcs.json        <- delete
  plugins/MCAlive2/behaviors.json   <- delete
  plugins/MCAlive2/blueprints.json  <- delete
  ```

- **Keep `plugins/MCAlive2/gadgets.json`** — that is the compiled capability
  set. Losing it is survivable now that every source is committed under
  `brain/gadgets/`, but there is no reason to.
- `server.properties`:
  - `generate-structures=true` — empty ruins are lore; the purge strips
    their inhabitants.
  - `spawn-monsters=true` and `difficulty=normal` — must stay ON. The
    SpawnGate is the mechanism; turning these off would neuter brain-placed
    lore monsters.
  - `pause-when-empty-seconds=-1` — the world keeps living (NPC crews keep
    working) with nobody online.

## 3. First boot: gamerules (once, they persist in the world)

```
gamerule doPatrols false
gamerule doTraderSpawning false
gamerule doInsomnia false
gamerule doWardenSpawning false
```

`doMobSpawning` stays **true** — new-chunk animal population needs it; the
SpawnGate handles everything else.

## 4. Confirm plugin config

`plugins/MCAlive2/config.yml` should contain (defaults are correct — just
confirm nothing old overrides them):

```yaml
spawn-control:
  hostile: whitelist
  peaceful: new-chunks
  villagers: banned
npc-defense:
  enabled: true
behavior:
  enabled: true
  keep-chunks-loaded: true
world-turn-minutes: 90
```

## 5. Verify the spawn regime

- Fly around loaded (old) chunks at night: nothing hostile appears.
- Generate fresh chunks: passive animals appear there — and only there.
- Locate a generated village: buildings present, **zero** villagers.
- Have the brain `spawn_entity` a zombie: it appears (lore monsters work).

## 6. Found the world

**Not an operator order any more.** Routine life must not run on behavior
programs — `behavior_blocked` is a director wake event, and 43 blocking
circuits once burned a 5M token budget in 79 minutes. Founding is a script:

```
node scripts/found.mjs --survey    # survey only, changes nothing
node scripts/found.mjs             # survey, found, start every timer
```

It surveys a 520-block ring for eight sites whose central 9x9 is level and
dry, stands up the eight Ancients from `scripts/founders.json` (the verbatim
character sheets), gives each an empty chest, a crafting table and a furnace,
records the domain and store in `places`, installs `lineage`/`pursuits`/`farm`
and starts every gadget timer with hunger **lethal**.

Each founder starts **alone with an empty store**. They earn their five
generation-1 followers by banking food — see "How a line grows" in
`project.md`.

## 7. Watch

- Every founder should set off foraging within a minute or two — with an
  empty larder and a 40-minute clock, `forage_far` is the only thing the
  arithmetic can choose.
- `gadget:lineage {action:"why"}` prints the full decision for all eight
  lines: food banked vs food needed, pressure vs restraint, and which role
  the next follower would fill. This is the thing to watch on day one.
- The first follower appears once a line banks 40 nutrition. If nothing has
  been called after an hour, read `why` before changing anything — the
  answer is almost always `cannot feed another mouth`.
- Expect deaths. Starvation is lethal and permanent by design; a line that
  cannot feed itself is meant to end. `npc_death` wakes the director, so the
  losses get narrated.
- Attack an NPC: it fights back (or flees) instantly, plugin-side; the
  director may weave consequences afterwards.
