# MinePilot — Minecraft AI Companion

MinePilot is the public repository name. The production Forge mod id is
`mcai_companion`.

[Usage guide](docs/USAGE.md) · [Project charter](docs/PROJECT_CHARTER.md) ·
[Contribution rules](CONTRIBUTING.md) · [Security policy](SECURITY.md) ·
[Implementation status](docs/IMPLEMENTATION_STATUS.md) ·
[15.x capability audit](docs/PROFESSIONAL_COMPANION_MATRIX.md)

MinePilot adds a visible, account-free AI teammate to Minecraft Java 26.2 on
Forge 65.x. It uses one authoritative vanilla `ServerPlayer`, normal server
rules, and a single high-level language model. It does not run a second game
client, use a Microsoft account, teleport, read hidden blocks, or grant cheat
abilities.

> **Current status: `0.1.16-dev-mc26.2`.** The embodied player, model gateway,
> fair perception, memory, configuration screen, skin synchronization, and
> many atomic survival skills are implemented. The release is still a
> development build: the 24-hour stability gate, unseen random Hardcore
> completion gates, rendered-client gate, and M0–M4 product gates are not
> complete. Do not market this build as a two-hour speedrun product or as a
> professional companion.

## What is implemented

- A persistent `ServerPlayer` body with a stable UUID, vanilla inventory,
  hands, armor, health, hunger, experience, effects, statistics, ender chest,
  death, respawn, dimension, and save behavior.
- A headless connection pump that follows the normal player-list lifecycle and
  handles keepalive, teleport confirmation, packet draining, login, and
  removal without a client account.
- Vanilla mining, placement, item use, attacks, equipment, crafting, menus,
  cooldowns, durability, drops, and statistics. Skills cannot directly write
  a world result or create an item.
- A Java 25 single-model gateway for Responses and OpenAI-compatible Chat
  Completions, with structured-output fallbacks, single-flight requests,
  timeouts, cancellation, revision checks, and redacted errors.
- Twenty-TPS local movement, shielding, food, retreat, fall, water-clutch,
  parkour, and emergency-survival reactions while the model is thinking.
- Grounded teammate lifecycle updates after server-owned skill start,
  completion, failure, or cancellation, plus local `stop`/`hold`/`pause`
  cancellation that remains responsive during model latency.
- First-person semantic perception with distance, field-of-view, occlusion,
  provenance, sample sequence, and world/goal revision checks. Screenshots are
  task-triggered and never a continuous video upload. Pixel capture requires
  an explicitly opted-in, transparent background renderer; the server never
  borrows or changes an ordinary player's camera. Image input is enabled only
  after a real provider handshake, then attached to the next eligible planner
  request through the provider's negotiated protocol.
- SQLite WAL memory with event and task checkpoints, FTS5 name search, R*Tree
  spatial search, waypoints, assets, and verified portal edges.
- Structured Xaero shared-waypoint input. The companion walks, sails, rides,
  or uses verified portals; it never reads radar/cave maps or teleports.
- Loopback MCP at `127.0.0.1:25766/mcp` with bearer, host, origin, and body
  limits. Tools are `observe`, `set_goal`, `goal_status`, `say`,
  `cancel_goal`, `add_waypoint`, `get_screenshot`, and `get_audit_summary`.
- Vanilla-style pause-menu configuration: agent name validation, color,
  `0.0–1.0` temperature, local 64×64 skin import, arm model, API key, base
  URL, model name, system preference, and first-run tutorial.
- Atomic and composite skills for movement, follow, visible item pickup,
  mining, crafting, furnace/menu transactions, shelter construction, crops,
  portal casting, water clutching, parkour, ranged combat, emergency PVE,
  End entry, and controlled dragon combat.

“Implemented” means that code and bounded tests exist. It does not mean that a
natural random world, arbitrary modpack, or every high-level request has been
validated.

## Compatibility

- Minecraft Java 26.2.
- Forge **65.0.0 inclusive through 66.0.0 exclusive**. The current artifact is
  a Forge 65.x build and is not a Forge 64.x or Forge 66.x artifact.
- Java 25 (Temurin recommended).
- macOS, Linux, and Windows are supported in principle; the formal rendered
  client gate requires an isolated Linux/Xvfb worker.

Forge is the loader for the selected launcher instance; it is not a jar to
drop into `mods`. Install the same MinePilot jar on the client and integrated
server. A third-party server without the mod cannot create the account-free
player body.

## Build and install

```bash
./gradlew build
cp build/libs/mcai_companion-*.jar path/to/instance/mods/
```

Copy only the installable jar from `build/libs`. The build archives old jars in
`build/archive-libs` and puts audit-only slim jars in `build/audit-libs`; those
directories are not mod folders. SQLite JDBC is embedded with Forge Jar-in-Jar.

For a local server-only physics check:

```bash
./gradlew runGameTestServer \
  -Pforge_compile_version=65.1.1 \
  -Ptest_selector=mcai_companion:real_parkour_course
```

For an authorized live-model test, inject credentials only through the process
environment and use the real-time flag:

```bash
MCAI_API_KEY='...' MCAI_BASE_URL='https://provider.example/v1' \
MCAI_MODEL='model-name' JAVA_HOME='/path/to/jdk-25' \
./gradlew runGameTestServer \
  -Pforge_compile_version=65.1.1 \
  -Plive_model_test=true -Prealtime_gametest=true \
  -Plive_model_selector=mcai_companion:real_player_task_to_live_model_movement
```

The real-time flag keeps the server close to 20 TPS; without it, a test server
may fast-forward thousands of ticks while the provider is waiting. Never put a
key in TOML, SavedData, SQLite, logs, crash reports, screenshots, or Git.

## Evidence policy

The repository distinguishes source implementation, controlled no-model
GameTests, authorized live-model slices, and formal release gates. A unit test,
an old run, a model acknowledgement, or a server smoke test cannot be promoted
to an M0–M4 PASS. Current M0, M1, M2, M3, M4, unseen-seed, rendered-client, and
long-soak statuses are recorded as `NOT_RUN` or `FAIL` until the exact frozen
artifact passes the required protocol.

The latest controlled live-model runs include a physical End victory/return,
ordinary movement, follow, surprise-zombie defense, and owned golden-apple
consumption. These are bounded slices, not random-seed, Hardcore, speedrun, or
formal M0-M4 results. The offline dragon baseline remains only a controlled
no-model physical lower bound.

## Fairness and safety boundaries

The model returns only a versioned high-level decision. It cannot emit Java,
packets, commands, coordinates invented from nowhere, direct block changes, or
inventory NBT. The skill supervisor rechecks permissions, observations,
revisions, reach, line of sight, collision, cooldown, durability, and menu
state on the server thread. Emergency reactions remain local and bounded when
the provider is offline, slow, unauthorized, or returns invalid JSON.

The public multiplayer identity is explicit `[AI]`; the mod never forges a
player signature or pretends to be a human account.

## License

Original MinePilot code is Apache-2.0. Third-party code, data, and assets keep
their original licenses and are listed in `THIRD_PARTY_NOTICES.md` when used.
