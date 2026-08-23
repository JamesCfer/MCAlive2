# MCAlive2

**Read `project.md` before doing anything in this repo.** It is the operating manual: the hard
rules, where the data lives, what to restart after a server reboot, and the gotchas that have
already cost real time and real NPCs. `DESIGN.md` is the architectural contract underneath it.

The four rules that override convenience, repeated here so they are never missed:

1. **NPCs never teleport.** They pathfind and walk at a player's pace, and may only do things a
   player could do. New player-like abilities are welcome; shortcuts are not.
2. **Tokens are only for authoring new options.** Every runtime decision is arithmetic in gadget
   Java. Never call a model to choose among existing actions.
3. **Never ship a capability as a plugin change.** Existing tool → formula → gadget. The plugin
   surface is frozen.
4. **Check anything that emits an event against `DIRECTOR_WAKE_EVENTS`** in
   `brain/lib/config.mjs`. One wake event on a per-NPC loop burned a 5M daily token budget in
   79 minutes.

When you learn something the hard way, add it to `project.md` rather than leaving it in a chat.
