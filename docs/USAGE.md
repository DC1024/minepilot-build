# MinePilot Usage Guide

This guide covers the `0.1.16-dev-mc26.2` development line: Minecraft Java
26.2, Forge 65.x, and Java 25. It is not a two-hour completion guarantee; the
formal M0–M4 gates remain in progress.

## Install

### Client and integrated server

1. Install Java 25 and Minecraft Java 26.2.
2. Install Forge 65.0.0–65.x in the instance. Do not put the Forge installer
   in `mods`.
3. Copy exactly one `mcai_companion-*.jar` from `build/libs` into `mods`.
4. Use the same jar on the client and integrated server. A server without the
   mod cannot create an account-free AI `ServerPlayer`.
5. Open a world and press `Esc`. Select **AI Companion** in the pause menu.

### Dedicated server

Install the same jar on the server and the normal client. Review the generated
`config/mcai-companion.toml`, accept the Mojang EULA, and start the server with
Java 25. A non-operator may issue gameplay goals only when their UUID is listed
in `chat.allowedSenders`; this does not grant `/mcai` administration or cheat
commands.

## First-run setup

The first open shows a four-step tutorial:

1. Choose a unique agent name, color, and arm model.
2. Import an optional local 64×64 PNG skin, or keep the stable Steve/Alex
   fallback.
3. Enter the API key, HTTPS base URL, and model name. The key is stored in a
   platform secure store or process-only memory; it is never written to the
   world or Git.
4. Validate the connection and send a first ordinary chat message.

The temperature slider is `0.0–1.0` and is sent as the provider's sampling
temperature. It does not override fairness, permissions, Hardcore rules, or
the system policy. The form can scroll at small window sizes; Save/Validate and
Back remain visible outside multi-line fields.

## Chat and goals

In single-player, normal chat is enough; no `@` prefix is required. In
multiplayer, use the agent name or an allowlisted sender. A request is not
complete when the companion replies “working on it”: inspect the status or
audit summary for `decision_revision_accepted`, `skill_started`,
`low_level_actions_issued`, and a server-verified result.

Examples:

```text
MinePilot, follow me to the shared waypoint.
Collect visible oak logs, replant nearby saplings, and return to the chest.
Build a small lit shelter here, then store spare food in the chest.
```

The companion may ask a clear question when a task is unsafe, ambiguous,
unauthorized, or impossible. It must not invent a location, claim to have used a
menu it did not open, or silently terminate an active player goal.

For an unambiguous interruption, say `stop`, `hold`, or `pause`. The command is
handled locally and requests the current goal's
safe cancellation checkpoint; it does not wait for another model response. A
status is emitted only after the server accepts the cancellation, and the
body's final stop remains subject to the skill's normal checkpoint contract.
In multiplayer, the sender must be the addressed player and be allowed to
control the companion. Hardcore evaluation keeps its permanent no-write lock.

During a long task, player-visible lifecycle messages are grounded in the
server-owned skill supervisor: `start` follows `skill_started`, `action complete`
follows `skill_completed`, and failure/cancellation messages are emitted once
per transition. A conversational acknowledgement by itself is not evidence
that the body moved.

## Xaero shared waypoints

Share a structured waypoint through the supported Xaero integration. The
companion accepts only an explicit shared marker or an allowlisted sender,
checks the dimension and safety, then chooses walking, a boat, a minecart,
rail, or a verified portal edge. It walks to the nearest safe standing point
within the requested target; it never teleports and never reads cave maps or
entity radar.

## MCP and Codex

The local MCP endpoint is `http://127.0.0.1:25766/mcp`. It requires the generated
Bearer token plus valid Host and Origin headers. Available tools:

```text
observe, set_goal, goal_status, say, cancel_goal,
add_waypoint, get_screenshot, get_audit_summary
```

MCP is a control surface, not a privileged execution path. The same permission,
revision, fair-perception, and skill checks apply as for chat. The bundled
`skills/minecraft-companion` Codex skill describes the high-level workflow and
does not embed a game encyclopedia.

`get_screenshot` does not open or focus a Minecraft window. The dedicated
server and headless companion produce semantic perception without a renderer.
Optional pixel capture requires a separately launched background client with
`-Dmcai.companion.hiddenRenderer=true`; that client registers explicitly,
renders one HUD-free companion first-person frame on demand, and keeps its
window transparent and non-focusing. Normal player clients never register and
their cameras are never borrowed. Without the renderer, the tool fails closed
with `authenticated_renderer_unavailable`.

Pixel capture is demand-driven rather than a video stream. The model first
requests a low-detail screenshot, the hidden renderer returns one bounded PNG
in 24 KiB serverbound fragments, and the fresh frame is attached once to the
next planner request. Length, dimensions, fragment shape, and SHA-256 are
verified before use. The configured provider must also pass a real image-input
handshake; otherwise semantic perception continues and the PNG is omitted.
The production server is always launched with `nogui`. A dedicated server has
no framebuffer, so actual pixels require the separate hidden renderer even
though neither an operator nor the AI needs a visible game window.

## Testing without a launcher

Forge's `runGameTestServer` starts a dedicated server without a window or mouse
input. It creates a real `ServerPlayer`, sends chat through Forge events, and
checks vanilla world, inventory, entity, physics, and statistics. Example:

```bash
./gradlew runGameTestServer \
  -Pforge_compile_version=65.1.1 \
  -Ptest_selector=mcai_companion:real_water_clutch \
  --no-daemon --no-configuration-cache
```

For authorized live-model work, keep the key in environment variables and set
`-Plive_model_test=true -Prealtime_gametest=true`. A provider response is real
evidence only when the audit records the causal sequence from chat to model
request, schema validation, accepted decision, skill start, low-level action,
and server result.

## Troubleshooting

- **No body:** confirm the mod is installed on both client and server, the
  server has completed world spawn, and TAB shows the explicit `[AI]` entry.
- **No speech or action:** inspect `goal_status` and `get_audit_summary`, then
  check the provider status, URL, model, allowlist, and revision. No-key mode
  deliberately keeps the body present but idle and safe.
- **Key requested again:** re-open the setup screen and check the secure-store
  status. On a headless server, use the documented next-process environment or
  secret-file injection path; do not put the key in TOML.
- **A live test times out:** preserve the exact log and diagnostics. A timeout
  is a failed slice, not evidence that the model completed the goal.

## Limits of this build

The current artifact is not a universal modpack agent, not a cheat client, and
not a guaranteed random-seed speedrunner. Complex Create, MTR, Farmer's Delight,
and aircraft skills are adapters under test. Rendered skin/animation checks,
Linux/Xvfb live clients, randomized Hardcore statistics, and 24/100-hour soak
remain separate release gates.
