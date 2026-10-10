# MinePilot Persistent Objective: M1–M4 and Real Black-Box Evidence

This file is the single recoverable engineering objective for MinePilot. Read
it before changing code. Continue from the repository checkpoint after a
context compaction or process restart; do not infer progress from chat history.

## Definition of done

The objective is complete only when one frozen source commit and one frozen
installable jar pass every required M1–M4 gate, including the real server,
authorized model, normal client, hidden seed protocol, evidence binding, and
performance checks. A compile, interface, placeholder, demo arena, model
acknowledgement, or unexecuted test is not completion.

The only honest terminal states are:

- `FINAL_PASS`: all gates pass on the same frozen artifact;
- `BLOCKED_EXTERNAL`: a blocker cannot be removed locally and has a minimal
  reproduction, log, exact missing condition, and recovery command.

Never convert a missing Linux worker, Xvfb display, model credential, or API
permission into a PASS. Finish all independent work and record the blocker.

## Product contract

Deliver a visible, account-free AI teammate for Minecraft Java 26.2 and Forge
65.x. It must use a stable vanilla `ServerPlayer` with normal inventory,
hands, armor, hunger, experience, effects, statistics, death, respawn,
portals, menus, and saves. It must be visible to clients with correct skin,
pose, equipment, and animations, and it must communicate through normal chat.

For executable requests, the model must start a registered skill and produce a
server-verifiable result. The body must move, jump, swim, climb, sail, ride,
open doors, use portals, avoid hazards, mine, craft, smelt, fight, build,
organize storage, and recover from danger through vanilla paths. Long-term
companion scenarios include waypoints, ownership, construction, farms,
transport, workstation menus, PVE/PVP, and memory.

The Hardcore evaluation accepts exactly one initial goal, “complete Minecraft,”
on a hidden random seed. After that goal, no human chat, MCP writes, items,
waypoints, commands, reloads, seed inspection, hidden scans, or restart tricks
are allowed. Victory is the companion's own Ender Dragon kill followed by
physical entry into the return portal.

## Architecture constraints

The model returns only a versioned high-level `DecisionEnvelope`. It cannot
emit Java, packets, commands, teleports, hidden coordinates, direct blocks,
direct inventory NBT, or synthetic results. Every decision is bound to the
observed world and goal revisions and checked for permissions, line of sight,
reach, collision, cooldown, durability, and menu state.

Perception is the companion's first-person eye/FOV/distance/occlusion view of
loaded chunks. Entity and block observations include provenance, sample age,
sequence, and revisions. Screenshots are task-triggered only. The observer's
third-person camera is never model input.

Movement, shielding, food, fall/water clutch, projectile evasion, and emergency
survival run locally at 20 TPS, so model latency cannot leave the body in a
known danger. SQLite WAL/FTS5/R*Tree memory is bounded and provenance-aware.
Verified portal edges are required for cross-dimensional routes.

## Milestones and gates

### M0 — technical gate

Create the repository, Java 25/Forge 65 build, one installable jar, headless
`ServerPlayer`, SQLite lifecycle, menus, save/restart, cross-dimension path,
player tracking, and a 24-hour stability harness. Stop and report if the
headless player cannot remain equivalent to a normal player.

### M1 — basic survival

With an empty inventory and one “build a safe base and survive to tomorrow”
goal, the companion must gather wood, stone, food, fuel, and iron; craft tools,
shield, bucket, chest, furnace, and bed; build an enclosed, lit, walkable
shelter; smelt and store items; defend and recover at night; and resume after a
restart. The controlled target is 95/100 unseen M1 seeds within 60 minutes.

### M2 — vanilla completion

Implement village/trade, enchanting, brewing, Nether portal casting, fortress,
blaze/pearl resources, stronghold triangulation, End entry, fair crystal and
dragon combat, return portal, death recovery, and audit evidence. The controlled
target is 90/200 unseen seeds within six hours.

### M3 — expert companion

Cover original workstation menus, boats and minecarts, redstone basics,
farms, storage, dynamic construction, PVE/PVP tactics, ownership, hundreds of
waypoints/assets, restart/chunk unload, and a 100-hour world. Third-party mod
support is adapter-based and version-gated.

### M4 — random-seed optimization

Optimize route selection, risk, combat, movement continuity, and model context
for a 1,000-seed hidden Hardcore sample: at least 95% within two hours and 99%
within six hours. These are targets, not current claims.

## Testing protocol

Run focused JUnit and isolated Forge GameTests first. Real-model slices must
inject credentials through the process environment and run with the real-time
clock. Their audit must contain, in order:

```text
server_chat_received
conversation_task_accepted
goal_running
ai_perception_received
model_request_started
model_response_received
decision_schema_validated
decision_revision_accepted
skill_started
low_level_actions_issued
server_verified_result
```

The hidden-seed harness binds every case to one exact jar SHA-256, source
commit, version matrix, model, seed commitment, exit code, and final verdict.
Rendered skin/animation, Linux/Xvfb Actor/Observer, long-soak, and statistical
gates cannot be replaced by no-model server tests.

## Recovery workflow

1. Read `docs/CODEX_RECOVERY_CHECKPOINT.md` and
   `docs/progress/GOAL_STATE.json`.
2. Reproduce the latest failure with the narrowest safe test.
3. Write root cause, changed files, last failed gate, and next command to the
   checkpoint before continuing.
4. Implement the smallest production fix and a meaningful regression test.
5. Run the matching focused tests, then the release gate when the working tree
   is ready.
6. Update evidence with real exit codes and keep unmet gates `NOT_RUN` or
   `FAIL`.

Never store credentials in the repository. Keep public documents in English;
runtime chat and multilingual test fixtures may use any language.
