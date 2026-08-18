# MCAlive2

MCAlive2 is an AI decision brain that runs a fantasy RPG inside Minecraft. One AI
**director** runs the whole world, event-driven and unattended; cheap per-NPC
**actors** hold conversations. Minecraft itself is the description layer — the world
tells the story through structures, NPCs, sound, light, and weather, and chat is
exclusively for dialogue, never narration text. There are no dice: the director
adjudicates declared player actions from fiction, ledger facts, and stakes, and
answers with consequences written into the world.

See [`DESIGN.md`](DESIGN.md) for the full architecture, protocol, and milestone plan.

## Server install (the plugin)

1. Drop `MCAlive2.jar` into your Paper server's `plugins/` folder and start the server
   once to generate `plugins/MCAlive2/config.yml`.
2. Edit `config.yml`:
   - `bridge.host` / `bridge.port` — where the plugin's WebSocket bridge listens
     (default `127.0.0.1:8765`; keep it loopback unless `brain/` runs on another
     machine).
   - `bridge.token` — the auth token `brain/` must present as its first message.
     **Change this from the default.**
3. Restart (or reload) the server. The plugin logs
   `MCAlive2 bridge listening on ws://<host>:<port>` once it's up.

## Updates

Releasing a new plugin version is just "bump `plugin/pom.xml`'s `<version>` and push
to `main`" — everything after that is automatic:

1. **CI release.** [`.github/workflows/release.yml`](.github/workflows/release.yml)
   triggers on any push to `main` that touches `plugin/**`. It reads the version out
   of `plugin/pom.xml`, and if a GitHub release tagged `v<version>` doesn't already
   exist, it builds the plugin (`mvn package`, running the full test suite) and
   publishes a release with `plugin/target/MCAlive2.jar` attached. If that tag's
   release already exists, the workflow exits without doing anything — so pushes that
   don't bump the version are no-ops.
2. **Server staging.** Each running server's plugin checks `auto-update.github-repo`'s
   GitHub releases on startup and then again every `auto-update.check-minutes`
   (default 2 minutes; 0 = startup-only; don't go below 1 — GitHub's unauthenticated
   API allows 60 requests/hour). When it finds a newer release with an `MCAlive2.jar`
   asset, it downloads it into `plugins/update/` and never re-downloads a version it
   has already staged. Paper's own update-folder mechanism means the update **stages
   on restart N and applies on restart N+1** — nothing changes on disk for the
   currently running server until it restarts.
3. **Applying it.** By default that restart is up to you (or your process manager).
   Optionally, set `auto-update.apply-when-empty: true` in `config.yml` to have the
   plugin call a full server shutdown once an update is staged *and* the server has
   been empty of players for `auto-update.empty-minutes` (default 10) — but only do
   this if the server process runs under a restart loop that relaunches it after it
   exits (see [`scripts/run-server.cmd`](scripts/run-server.cmd) for a ready-made
   Windows one), otherwise the server just goes down and stays down.

Set `auto-update.enabled: false` in `config.yml` to disable the check entirely.

**Typical timings end to end:** for the plugin, a release lands ~1-2 minutes after
the version-bump push (CI build + publish), a running server stages it within ~2
minutes after that (its own `check-minutes` poll), and it applies on the server's
next restart (or automatically once empty, if `apply-when-empty` is on). `brain/`
is much faster since it just tracks a git ref, not a GitHub release: a push to
`main` reaches every running brain within ~10 seconds (`BRAIN_UPDATE_CHECK_SEC`),
which then pulls, npm-installs if needed, and restarts itself.

`brain/` updates itself separately from the plugin — see
[`brain/README.md`](brain/README.md) for its own update/versioning story.

## Brain setup (the AI side)

```bash
cd brain
npm install
export ANTHROPIC_API_KEY=sk-ant-...       # needed for real (non-dry-run) turns
export MCALIVE2_URL=ws://127.0.0.1:8765     # matches the plugin's bridge.host/port
export MCALIVE2_TOKEN=pick-a-long-random-token   # matches the plugin's bridge.token
npm start
```

Run this under whatever keeps a process alive on your machine (systemd, pm2, a
`tmux`/`screen` session, NSSM/Task Scheduler on Windows) — `brain/` is a plain Node
script with no daemonizing of its own.

Before pointing it at real API spend, do a dry run: set `BRAIN_DRY_RUN=1` to log the
exact system prompt, prompt, model, and tool allowlist for every director scene and
actor turn instead of calling the Anthropic API.

```bash
BRAIN_DRY_RUN=1 npm start
```

**Kill switch:** create the file `brain/DISABLED` (or set `BRAIN_ENABLED=0`) at any
time to stop new turns from starting — the service keeps connecting to the bridge and
logging events, it just goes quiet. Delete the file (or unset the var) to resume.

See [`brain/README.md`](brain/README.md) for the full environment variable reference,
guardrails (token budget, turn rate limit, model choices), lore setup, and offline
test suite (`npm test`, no API key or Minecraft server required).

## More detail

- [`DESIGN.md`](DESIGN.md) — components, the plugin⇄brain WebSocket protocol, senses,
  actuators, the ledger schema, NPC runtime, director/actor loops, guardrails, and
  milestones.
- [`brain/README.md`](brain/README.md) — brain-side architecture, event routing,
  environment variables, and testing.
