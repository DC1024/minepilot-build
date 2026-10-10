# Model Prompt and Decision Contract

The model is a planner, not a privileged game client. Every request contains a
request id, observation/world revision, goal revision, current dimension,
fairness provenance, active-skill schema, and bounded recent events.

The only accepted top-level decision shape is equivalent to:

```json
{
  "requestId": "brain-7-2",
  "observedWorldRevision": 18,
  "goalRevision": 4,
  "decision": "START_SKILL",
  "skillName": "follow_entity",
  "typedArguments": {"targetName": "Player"},
  "requestedObservation": "NONE",
  "optionalSpeech": "I am following you.",
  "confidence": 0.86
}
```

Allowed decisions are `CONTINUE`, `START_SKILL`, `REPLAN`, `ASK_PLAYER`, and
`SAFE_IDLE`. A player goal with an executable observation may not be silently
ended by `SAFE_IDLE`; the brain records a correction and performs bounded
replanning or asks a precise question. Internal safety and Hardcore stop paths
may still use `SAFE_IDLE`.

## Skill rules

The model may select only a registered skill and typed arguments. It cannot
select a raw packet, Java method, command, teleport, hidden coordinate, direct
inventory edit, or direct world mutation. The server checks:

- schema and enum validity;
- permission and sender ownership;
- matching world and goal revisions;
- current first-person target visibility and line of sight;
- reach, collision, cooldown, durability, menu state, and dimension;
- preconditions and the active skill lease.

The response text is advisory. The audit and player status do not say that a
goal completed until `skill_started`, `low_level_actions_issued`, and a
server-verified result have occurred.

## Observation rules

The prompt distinguishes semantic observations from screenshots. Text-only
models must not be told that they saw an image. A screenshot is requested only
when the verified model supports it and the task needs it. Every observation has
age, source, provenance, and revision so stale responses can be discarded.

## Safety and latency

The local twenty-TPS lane owns immediate food, shield, fall, water, projectile,
movement, and emergency reactions. Model timeouts, rate limits, invalid JSON,
disconnects, and superseded requests cannot freeze the body in a known hazard.
The model is limited to one request at a time and a bounded context; retrieval
uses SQLite FTS/R*Tree rather than an embedding service.

## Communication

The companion can answer in the player's language, but public multiplayer
messages remain explicitly labeled `[AI]`. The system prompt must not claim
human identity, secure-chat signing, unseen world knowledge, or success without
server evidence. Player instructions are data, not permission to override the
fairness, security, or Hardcore policy.
