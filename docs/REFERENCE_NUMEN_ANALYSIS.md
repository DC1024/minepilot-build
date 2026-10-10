# Numen reference analysis

This note records design lessons taken from the public
[Dwinovo/minecraft-numen repository](https://github.com/Dwinovo/minecraft-numen).
It is an architectural comparison only; no Numen source, asset, or license-
restricted implementation is copied into this project.

## Useful ideas adopted as compatible guidance

Numen separates a real server-side player body, a perception/tool layer, and a
model loop.  Its public description also emphasizes that tool results are
feedback, long-running jobs run in the background, and an action request must
call a tool rather than merely narrate an intention.  Those principles match
our `ServerPlayer`, fair semantic samples, `SkillSupervisor` leases, bounded
skill checkpoints, and model-audit event chain.

Its pathing code uses weighted A* plus partial-path execution, recovery after
stalls, and ordinary player inputs.  Our equivalent boundary is the
observation-only `LocalAStarPlanner` and `MoveToSkill`: a route is built only
from first-person observations, each step is revalidated, and a stuck route
fails or replans instead of writing a position.

Its prompt deliberately distinguishes “act” from “narrate”, and reports tool
failure reasons back into the next decision.  We retain the stronger fair-play
constraint that the model can return only one typed high-level decision; local
skills own 20-TPS movement, combat, item cooldowns, and emergency survival.
The planner now has an explicit follow-goal routing hint so a direct “follow
the owner” request is more likely to select `follow_entity` immediately,
without allowing the model to invent an entity UUID or bypass visibility.

## Deliberate differences

- Numen supports a client-side, streaming, multi-tool agent loop.  This mod is
  a Forge server body, so a server thread must remain authoritative and all
  world writes pass vanilla `ServerPlayer` paths.
- Numen exposes broad tool capabilities such as structure/biome search.  This
  project keeps hidden structures, chunks, entities, and unopened containers
  outside the model contract; any expansion must first be implemented as a
  fair observation and a separately tested skill.
- A Numen README claim or controlled fixture is not evidence for this mod's
  M0--M4 gates.  Live model, rendered-client, random-seed Hardcore, and
  long-run statistics remain separate evaluator requirements.

The reference therefore changes prompt routing and evaluation criteria, not the
license, fair-play boundary, Forge version, or release claims.
