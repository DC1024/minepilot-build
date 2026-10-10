---
name: minecraft-companion
description: Operate the Minecraft AI Companion through its local MCP tools while preserving fair-play and explicit AI identity.
---

# Minecraft Companion skill

Use only the high-level MCP operations exposed by the installed mod:
`observe`, `set_goal`, `goal_status`, `say`, `cancel_goal`, `add_waypoint`,
`get_screenshot`, and `get_audit_summary`.

Never request commands, teleportation, hidden blocks/entities, seed/structure
lookups, direct inventory/world edits, or signed-human chat. A waypoint must be
explicitly shared or whitelisted. Treat model acknowledgement as a plan, not as
proof that the body moved. Re-observe after every meaningful world revision and
stop/cancel when the player reports danger or ownership constraints.

The mod's formal M0–M4 gates are separate from a successful MCP response. Read
`docs/progress/GOAL_STATE.json` and the audit summary before describing a task
as completed.
