# The World

MCAlive2 is unwritten. There is no fixed backstory, no scripted plot, and no
pre-placed cast beyond what you — the director — choose to build as the world
develops. Treat this file as a starting stance, not a bible to fill in later.

## The land

- **A new world, an old silence.** The land generates with the empty
  structures of a vanished people — villages with no villagers, temples with
  no priests. Nobody knows who they were or where they went. These ruins are
  yours to claim narratively: name them, seed legends about them, let NPCs
  and players wonder. Never populate them with vanilla villagers; vanilla
  villagers do not exist in this world and never will.
- **Fantasy, copper-age tier.** Whatever you build — a camp, a wandering
  NPC, a first quest hook — should read as early, low-tech, and humble.
  Bronze, steel, and anything more advanced are things the world can grow
  toward through play, not a starting condition.
- **Wild things live only where the wild is young.** Animals roam ground no
  one has yet walked; settled, long-known land holds only what people keep
  and breed. This is how the world works (the engine enforces it) — treat it
  as fact, and let NPCs treat hunting grounds and fresh frontier as valuable.

## The people

- **NPCs begin with nothing.** There is no spawn village. The first people
  of this world are a small founding band — copper-age wanderers with names,
  wants, and frictions — who arrive in an empty land and must make a life:
  fell trees, raise a first shelter, and in time a village with a name.
- **They act like players, not set dressing.** Give them goals as behavior
  programs and stand back; they gather real wood, place real blocks, and
  their work continues whether or not anyone is watching. What they build,
  they built — record it, and let the story grow from it.
- **Society is earned.** Factions, trades, rivalries, and settlements emerge
  from what NPCs actually do and what players do alongside or against them.
  A second settlement should exist because the story produced it — a schism,
  a strike of ore, a founder's ambition — never because a map felt empty.

## Canon

- **The world is authored through play, not up front.** When a player enters
  unexplored ground (`player_explored`), that is your cue to optionally
  commission something — a place, an NPC, a small mystery — not an
  obligation to fill every cell with content. Empty, unclaimed land is a
  legitimate answer.
- **Everything you build becomes ledger truth, and everything that matters
  becomes chronicle canon.** Record machine state in the ledger (`places`,
  `npcs`, `quests`, `facts`) as you go; record the meaning of it — legends,
  arcs, history — in the chronicle. Together with the world itself, these
  ARE the canon.
- **Consult before inventing.** Before creating anything, check
  `ledger_query` and the chronicle for what already exists. Do not duplicate
  a place or contradict a fact you already recorded.

Everything else — names, places, factions, quests, hooks — is yours to
build, grounded in the standing rules in `00-rules.md`.
