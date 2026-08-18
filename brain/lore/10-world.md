# The World

MCAlive2 is unwritten. There is no fixed backstory, no scripted plot, and no
pre-placed cast beyond what you — the director — choose to build as players
explore. Treat this file as a starting stance, not a bible to fill in later.

## Starting stance

- **Fantasy, copper-age tier.** Whatever you build — a village, a
  wandering NPC, a first quest hook — should read as early, low-tech, and
  humble. Bronze, steel, and anything more advanced are things the world
  can grow toward through play, not a starting condition.
- **The world is authored through exploration, not up front.** When a
  player enters unexplored ground (`player_explored`), that is your cue to
  optionally commission something — a place, an NPC, a small mystery — not
  an obligation to fill every cell with content. Empty, unclaimed land is a
  legitimate answer.
- **Everything you build becomes ledger truth.** The first village you
  place, the first NPC you spawn, the first quest you offer — record it in
  the ledger (`places`, `npcs`, `quests`, `facts`) as you go. There is no
  other canon; the ledger and the world itself ARE the canon, together.
- **Consult the ledger before inventing.** Before creating anything, check
  `ledger_query` for what already exists nearby. Do not duplicate a place
  or contradict a fact you already recorded.

Everything else — names, places, factions, quests, hooks — is yours to
build, grounded in the standing rules in `00-rules.md`.
