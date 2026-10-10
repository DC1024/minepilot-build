# Contributing to MinePilot

MinePilot must satisfy game fairness, model safety, server stability, and
auditable evidence at the same time. A screen that appears to work is not a
release criterion.

## Before editing

1. Read [the project charter](docs/PROJECT_CHARTER.md), `AGENTS.md`, and the
   persistent M1–M4 objective in `CODEX_GOAL_M1_M4.md`.
2. Search existing implementations, ADRs, tests, and
   `docs/progress/GOAL_STATE.json`. Reuse an existing path instead of creating
   a second synonym for a skill, configuration, or memory store.
3. Confirm the Minecraft/Forge version line. Do not widen metadata to “all
   Forge”; use an adapter or a separate artifact for another major line.
4. Keep public documentation in English. Chinese or other languages are valid
   runtime chat, translation, and test fixtures, but they do not belong in
   project-facing documentation.

## Branches and commits

- Use `codex/`, `feature/`, `fix/`, or `docs/` prefixes for local work. The
  published Forge 65.x line is backed up on `main`; a future major version
  uses a separate branch such as `mc26.2-forge65`.
- One commit should solve one describable problem. The message and body must
  state the cause, behavior change, test command, compatibility/security impact,
  and evidence status.
- Do not create duplicate commits, empty commits, formatting-only noise,
  renamed copies of old code, unused placeholder classes, or weaker assertions
  merely to make a test green. If there is no observable behavior, test,
  documentation, or security value, do not commit it.
- Prefer a Conventional Commit subject in English, present tense, and about
  50–80 characters.
- Write `NOT_RUN`, `FAIL`, or `BLOCKED_*` when evidence is incomplete. Never
  turn an external blocker into a PASS.

## Mandatory pre-commit check

Stage only the intended files, then run:

```bash
git diff --cached --check
scripts/preflight-before-commit.sh
```

The script rejects an empty index, repeated patch text, generated
`build/run/logs/e2e/results` files, common secret patterns, production TODOs,
`UnsupportedOperationException`, and whitespace errors. It warns when
production code changes have no corresponding test or documentation; explain
such a warning in the commit body rather than ignoring it.

Minimum verification by change type:

| Change | Minimum evidence |
| --- | --- |
| Documentation | Cached diff check and manual link/command review |
| Java logic | `./gradlew test` or focused `--tests` |
| Skill, physics, or menu | Matching Forge GameTest; do not weaken assertions |
| Model, chat, or permissions | Contract tests plus an authorized live-model/client slice, or `BLOCKED_CREDENTIAL` |
| Version or build | `./gradlew clean build` and jar/dependency inspection |
| Security or credentials | Redacted logs, secret-path review, and failure-branch tests |

Formal M0–M4 evidence must bind the exact source commit, jar SHA-256,
Minecraft/Forge/Java versions, model, seed policy, and exit code. Unit tests,
controlled GameTests, historical runs, and model prose alone cannot upgrade a
formal gate.

## Code behavior rules

- Production results come through the vanilla `ServerPlayer`, menus, physics,
  cooldowns, durability, visibility, and world rules. Do not directly edit a
  block, inventory, container NBT, position, or hidden structure result.
- Every model decision is schema-checked, permission-checked, and bound to the
  observed world and goal revisions. “I did it” is not success; require an
  observed `skill_started` event and a verified world/container delta.
- Low-latency survival reactions run on the server tick. Model waits, offline
  mode, rate limits, malformed JSON, and stale responses must not leave the body
  frozen in danger.
- API keys, player identity, IP addresses, private coordinates, and screenshot
  UI data never enter logs, tests, crash reports, or Git.
- Every new skill defines preconditions, tick behavior, checkpoints, cancel
  behavior, and failure states. Every memory record carries source, world,
  dimension, revision, and last verification time.
- Test fixtures, oracles, client scripts, and mutation tools must not enter the
  production jar. Build contracts must prevent leakage.

## Pull request checklist

- [ ] What concrete user-visible problem does this solve, and which existing
      implementation does it reuse?
- [ ] Did it change Minecraft/Forge compatibility, permissions, secrets, or
      fairness boundaries?
- [ ] Is there a meaningful regression test, documentation update, or audit
      record?
- [ ] Were matching commands run and their real results recorded?
- [ ] Are secrets, generated files, duplicate code, and unused entry points
      absent?
- [ ] Are unmet gates still marked `NOT_RUN`/`BLOCKED_*`?
- [ ] Is every public documentation file English-only?

Original contributions are released under Apache-2.0. Do not copy code,
textures, prompts, or assets whose source or license is unclear; record third-
party material in `THIRD_PARTY_NOTICES.md` first.
