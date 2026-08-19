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

## 6. Found the world (operator order via the console)

Paste as an order:

> Found the world: choose a good valley within ~300 blocks of spawn. Create
> a founding band of 4–6 NPCs — copper-age wanderers with names, wants, and
> frictions — ledger them with a faction. Register a first-camp blueprint
> and issue behavior programs: gather wood, then raise the first shelter,
> log by log. Open the chronicle: write the world-bible's first page and
> today's session entry. Then stand back and let them work.

## 7. Watch

- The crew should visibly chop trees and place blueprint blocks over the
  next hours, with the director silent in between (check the decisions
  journal: scenes only on `behavior_done` / `behavior_blocked` /
  `world_turn` / player events).
- Attack an NPC: it fights back (or flees) instantly, plugin-side; the
  director may weave consequences afterwards.
- `behavior_status` in a scene (or via the console map) shows crew progress
  like `37/220 placed`.
