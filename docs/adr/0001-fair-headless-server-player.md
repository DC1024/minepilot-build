# ADR 0001: fair headless ServerPlayer body

## Decision

Use an ordinary `ServerPlayer` with a stable UUID and an embedded connection
pump. Use normal PlayerList, menu, item, physics, damage, death, and respawn
paths; keep skill orchestration outside the entity instance.

## Rationale

This preserves multiplayer visibility and vanilla semantics without a second
account. It also avoids a fake mob that cannot own an inventory or participate
in menus. Hidden-world reads and direct mutations remain prohibited.

## Consequence

Version-specific lifecycle code belongs behind an adapter and must be tested on
each Forge line. The 2026-08-06 async spawn-anchor correction is part of this
decision and avoids the `waitForEntities` deadlock seen in a headless zero-human
server.
