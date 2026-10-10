# Security policy

## Scope

This policy covers the `mcai_companion` source tree, the installable Forge JAR,
the loopback MCP endpoint, world `SavedData`, and the SQLite memory database.
The current branch is a development build (`0.1.11-dev-mc26.2`), not a security
or gameplay release.

## Report a vulnerability

Do not put API keys, access tokens, world saves, player IPs, or private logs in a
public issue. Send a minimal reproduction and the affected commit through the
repository's private security channel. If no private channel is configured,
open an issue containing only a redacted description and request a private
contact; never attach credentials.

## Security boundaries

- Credentials are process configuration or OS credential-store data only; they
  must not enter `SavedData`, SQLite, JARs, logs, crash reports, screenshots,
  or evidence manifests.
- MCP binds to loopback and requires a bearer token plus Host/Origin checks.
- Remote model URLs must use HTTPS; plain HTTP is limited to loopback.
- Model output is a typed high-level decision. It cannot issue Java, packets,
  commands, teleport requests, direct block writes, or item creation.
- Chat, books, item names, signs, and shared waypoints are untrusted content;
  they cannot alter the system prompt or safety policy.
- Hardcore and fair-play configurations permanently reject cheats, hidden-world
  reads, seed/structure APIs, observer-camera input, and direct world mutation.

## Supported versions

The current source targets Minecraft 26.2 / Forge 65.x / Java 25. The declared
loader range is `[65.0.0,66.0.0)`, but the full 65.x runtime matrix is still
`NOT_RUN`; see [compatibility](compat/forge-lines.toml) and
[GOAL_STATE](docs/progress/GOAL_STATE.json).
