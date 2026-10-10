# Security and Trust Boundaries

## Secrets

API keys must never enter:

- Forge TOML, world `SavedData`, or SQLite;
- logs, exception messages, crash reports, screenshots, or MCP responses;
- Git history, build artifacts, GameTest fixtures, or public issue text.

`SecretSource` supplies a short-lived `char[]` for one request. The HTTP header
is built and the array is cleared immediately. A provider URL must use HTTPS;
plain HTTP is accepted only for loopback development endpoints.

Credential storage is platform-aware:

- macOS uses Keychain when available;
- Windows uses DPAPI-backed secure storage;
- Linux uses Secret Service when available;
- headless deployments may use a process-only environment variable or a
  permission-restricted secret file for the next process.

If secure storage is unavailable, the setup screen reports process-only or
restart-required status. It does not silently write the key to a world or config
file. Explicit next-process injection takes precedence over a stale platform
entry so rotation is deterministic.

## Network and MCP

The model gateway allows one in-flight request. Connection, soft, and hard
deadlines are bounded; 401, 429, and 5xx responses do not trigger unbounded
duplicate retries. Responses are schema-checked and bound to the observed
world/goal revisions.

MCP binds to `127.0.0.1` by default and requires a generated bearer token,
accepted Host/Origin, bounded request bodies, and a request timeout. It exposes
high-level observation, goals, speech, waypoints, screenshots, and audit only.
It cannot issue a server command, teleport, edit a block, grant an item, or
read hidden world data.

## Multiplayer permissions

The public identity is explicit `[AI]`. The mod never forges a player UUID,
secure chat signature, account, or human name. Ordinary gameplay goals are
limited to the single-player owner, server operators, or an administrator-
configured sender allowlist. `/mcai` administration, evaluation, and cheat
commands remain separate and are permanently disabled in the fair Hardcore
profile.

## Fairness boundary

Perception is derived from the companion's eye, look direction, loaded chunks,
distance/FOV, and ordinary visual/collider clips. It does not read unloaded
blocks, structure locations, entity radar, cave maps, server seed, or hidden
container contents. The third-person observer camera is not model input.

Actions use the vanilla `ServerPlayer`, game mode, menus, collision, cooldown,
durability, reach, line of sight, and world rules. Direct inventory/block writes,
teleports, synthetic drops, and command shortcuts are rejected by code review
and release contracts.

## Data minimization

Audit records store request ids, revisions, outcome codes, safe metrics, and
redacted provider status. Screenshots remove HUD/chat/IP/player-name data before
any optional model upload. Memory records have source, dimension, confidence,
revision, and expiry/verification data. They do not retain an unbounded chat
transcript.

## Reporting

Do not publish a key, private world, player identity, or exploit reproduction in
an issue. Use the repository's private security channel with a minimal
reproduction, affected commit/version, expected and actual behavior, and
redacted logs. Do not claim a formal gate passed until the artifact-bound audit
protocol has passed.
