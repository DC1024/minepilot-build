# Product specification

## Product contract

Minecraft AI Companion is a visible, clearly labelled AI teammate for Minecraft
Java 26.2 / Forge 65.x. It uses one real `ServerPlayer` body with a stable UUID,
normal player lifecycle, ordinary item/menu/world interactions, bounded first-
person observations, a single configurable high-level model, and local
20-TPS safety control.

The installable deliverable is one Forge JAR. It does not require a Microsoft or
Mojang account for the companion. The AI identity is disclosed in multiplayer
chat and the player list; it never forges a signed human message.

## User-facing flows

1. Install the product JAR in a matching Forge instance.
2. Open the Minecraft-style companion screen from the pause menu.
3. Configure the current Agent, choose a legal name/color/64×64 skin, and
   enter Base URL, model, and a credential through the platform secret store
   or process injection. Multi-agent management remains a later M3 surface;
   the first release gate deliberately runs one active companion body.
4. Start a goal in ordinary chat, the local MCP interface, or the UI. The goal
   is acknowledged only; physical progress is reported after an admitted skill
   starts and server evidence advances.
5. Observe the `[AI]` body, inventory, actions, status in TAB, audit summary,
   and recoverable goal checkpoint. A missing model leaves the body present but
   in a safe idle/emergency lane rather than pretending to act.

## Functional scope

The source contains atomic skills for movement, following, observation,
gathering, ordinary interaction, inventory/menu transactions, shelter/foundation
work, food, combat safety, transport, portals, and selected progression chains.
Their exact natural-world and statistical status is maintained in
[CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md), not inferred from registration.

## Non-goals for this development build

The current build does not claim a professional game-companion experience, a
two-hour random-seed Hardcore completion rate, M1–M4 completion, 24/100-hour
soak stability, full Forge 65 patch coverage, Forge 66 support, or universal
third-party mod knowledge. Those are release gates, not marketing text.
