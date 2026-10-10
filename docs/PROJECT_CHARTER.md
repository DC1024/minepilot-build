# MinePilot Project Charter and Behavior Standard

## Identity

MinePilot is the repository and product name. The Forge mod id remains
`mcai_companion`. The mod creates a visible AI teammate for Minecraft Java
26.2 using a normal server-side `ServerPlayer` body and one high-level model.

The current line is `0.1.16-dev-mc26.2`:

- Minecraft Java 26.2;
- Forge 65.0.0 inclusive through 66.0.0 exclusive;
- Java 25;
- one account-free companion per active world, with data structures reserved
  for future multi-agent support.

This is a Forge 65.x artifact. It does not claim Forge 64.x/Minecraft 26.1.x
or Forge 66.x compatibility. A different major line requires its own mapped
artifact and adapter tests.

## Product promise

The long-term objective is an auditable game-technology companion that can
cooperate with a player, survive, explore, build, fight, manage resources,
remember locations, and complete a full vanilla progression route. The product
must feel responsive, but it must not fake success or hide its identity.

The project distinguishes four evidence levels:

1. **Implemented:** source and a meaningful automated test exist.
2. **Controlled physical:** a vanilla server/GameTest proves a bounded behavior
   without a model or rendered client.
3. **Live slice:** an authorized model, real server, and normal client prove a
   causal chat/observation/decision/skill/action chain.
4. **Formal gate:** a frozen artifact passes the required randomized, rendered,
   long-running, or statistical protocol.

Only level 4 may be marketed as an M0–M4 product result.

## Fair play

The authoritative body is a vanilla `ServerPlayer` with a stable UUID. It
enters and leaves through the normal player-list lifecycle. Mining, placement,
attacks, item use, menus, crafting, equipment, movement, collision, hunger,
effects, damage, drops, experience, death, respawn, portals, and statistics use
the same server rules as a player.

The model may return only a versioned high-level `DecisionEnvelope`. It may not
return Java, packets, commands, teleport destinations, hidden coordinates,
direct inventory edits, direct block edits, or generated results. The skill
supervisor rechecks observations, permissions, revisions, reach, line of sight,
collision, cooldown, durability, and menu state before every action.

Emergency movement, food, shielding, fall recovery, projectile evasion, and
danger stops run locally at server tick rate. They continue safely during model
latency, malformed output, rate limits, or disconnection. A safe fallback may
pause or ask the player; it may never claim that an unexecuted goal succeeded.

## Communication and identity

The companion observes the normal server chat stream and can answer in the
player's language. In single-player it does not require an `@` prefix; in
multiplayer a name or configured allowlist determines which player may issue
game goals. Administrative `/mcai` commands remain separate from ordinary
gameplay permissions.

Messages are labeled `[AI]` and the mod does not forge secure player signatures,
accounts, or a human identity. A live model may use a screenshot only when the
model capability was verified and the task explicitly requests it; the
observer's third-person camera is never the companion's perception. A pixel
capture may come only from an explicitly opted-in off-screen renderer using
the companion as its first-person camera; an ordinary player's client never
qualifies merely because the Mod network channel is installed.

## UI, skin, and credentials

The pause menu opens a vanilla-style MinePilot screen with an agent list,
validated name (3–16 ASCII letters, digits, or underscore), color, temperature
slider (`0.0–1.0`), 64×64 local PNG skin import, classic/slim arm choice, API
key, HTTPS base URL, model name, system preference, and a first-run tutorial.
The form is scrollable and keeps Save/Validate and Back outside the text fields.

Keys are stored only in a platform secure store or the current process. They
never enter TOML, SavedData, SQLite, logs, screenshots, or Git. macOS Keychain,
Windows DPAPI, Linux Secret Service, and explicit next-process environment/file
injection are supported status paths; unavailable secure storage is reported
honestly rather than silently weakening the boundary.

## Memory and navigation

Immediate observations, a rolling navigation graph, regional metrics, semantic
topology, waypoints, portal edges, asset records, and task checkpoints are
stored with provenance, dimension, revision, and verification time. SQLite WAL,
FTS5, and R*Tree queries are bounded and do not grow the model context without
limit. Cross-dimension routes use only portals that the companion has actually
verified; Nether coordinate scaling is a hint, not a teleport or a hidden map.

## Scope and release gates

M0 covers the headless player lifecycle and fair action boundary. M1 covers
basic survival and a safe shelter. M2 covers the vanilla progression and End
return. M3 covers long-term companion scenarios, construction, farming,
transport, PVE/PVP, memory, and supported workstation adapters. M4 covers
random Hardcore speed optimization.

The current repository records these formal milestones as `NOT_RUN` until the
exact frozen artifact passes their protocols. A controlled arena, an offline
skill baseline, or a model acknowledgement is never substituted for a random
seed, rendered-client, or long-soak result.

## Licensing and references

Original code is Apache-2.0. The Numen review in `docs/NUMEN_REVIEW.md` records
architecture lessons without copying code, prompts, textures, or assets. Create,
MTR, Farmer's Delight, and other complex mod adapters are SPI placeholders until
their exact Forge 65.x contracts pass independent compatibility tests.
