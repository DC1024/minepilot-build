# Review of Dwinovo/minecraft-numen

Review date: 2026-07-29

Reviewed snapshots:

- `Dwinovo/minecraft-numen`: `2243ca2a8dda2b0a2e4d8a336f087eeda716f9a4`
- `Dwinovo/numen-api`: `3a8716e8dd1303607209e78998baedcac681b1a3`
- [minecraft-numen](https://github.com/Dwinovo/minecraft-numen)
- [numen-api](https://github.com/Dwinovo/numen-api)

This document extracts architecture lessons, failure modes, and test ideas. It
does not copy or port Numen source code, prompts, skills, textures, or assets.
Numen is LGPL-3.0 with additional resource terms; MinePilot's original code
remains Apache-2.0.

## Useful lessons

Numen demonstrates the value of a small action vocabulary, explicit world
observations, task checkpoints, and a separation between model planning and
game execution. Those principles informed MinePilot's `DecisionEnvelope`,
`SkillSupervisor`, bounded perception, and audit events.

The reference also highlights risks that MinePilot must handle explicitly:

- a natural-language acknowledgement is not a completed action;
- stale observations can make a planner repeat an unsafe or impossible move;
- a client-side or privileged shortcut breaks the fairness contract;
- long model contexts need compact, revisioned memory rather than an unbounded
  transcript;
- an offline or rate-limited provider must leave the body in a local safe lane;
- a test that only inspects chat output cannot prove movement or inventory
  changes.

## Deliberate differences

MinePilot uses a real server-side `ServerPlayer`, vanilla menus and physics,
first-person fairness evidence, a local 20-TPS survival lane, and a formal
artifact-bound evaluation protocol. It does not use a second Minecraft client,
hidden block access, direct world mutation, or copied pathfinding/cheat code.

The model is allowed to choose among registered high-level skills, but every
skill is typed, permission checked, revision bound, cancellable, and verified by
server state. The project keeps complex mod support behind a versioned adapter
SPI instead of assuming that a third-party registry or menu is stable.

## Attribution boundary

The upstream repositories are references only. Any future contribution that
uses a third-party implementation, asset, or license must be reviewed and
listed in `THIRD_PARTY_NOTICES.md` before it is added to MinePilot.
