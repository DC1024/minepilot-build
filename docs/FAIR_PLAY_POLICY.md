# Fair-play policy

## Allowed

- State from the AI body's own loaded chunks, collision, inventory, health,
  sounds, and first-person line-of-sight observations.
- Normal `ServerPlayer` movement, attacks, mining, placement, use, menu clicks,
  crafting, smelting, sleep, boats, minecarts, portals, and item durability.
- Explicitly shared player/Xaero waypoints and previously verified portal edges.
- A local emergency controller that reacts to observable damage, fall, lava,
  hunger, and hostile entities while the high-level model is delayed.

## Prohibited

Seed/structure/biome oracle calls, hidden block or container reads, observer
camera frames, entity radar, free teleport, direct block or item mutation,
synthetic drops, command/cheat execution, kill aura, reach extension, packet
spoofing, signed-chat impersonation, and copied code/assets from cheat clients
or autonomous-player projects.

Hardcore evaluation disables cheat commands permanently. Multiplayer displays an
explicit `[AI]` identity. A screenshot is optional, first-person, HUD-redacted,
task-triggered input; it is never a continuous observer feed.
