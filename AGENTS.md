# Repository execution rules

This repository is governed by `CODEX_GOAL_M1_M4.md`. Read that file in full
before changing production behavior, compatibility declarations, test
evidence, or release status.

## Non-negotiable rules

- Treat Forge GameTest as an inner-loop contract suite. It is not formal M1–M4
  evidence.
- Formal chat tests must originate in a real Minecraft client and traverse the
  normal network chat path.
- Formal gameplay tests use the configured real model. Fake models are limited
  to unit, contract, deterministic regression, and fault-injection tests.
- Never turn a test fixture, Oracle observation, seed, structure locator, or
  hidden world scan into production perception.
- Never mark a capability `PASS` from code presence, a plan, a unit test, a
  controlled fixture, or a partial run.
- Keep API keys out of source, Gradle properties, worlds, SQLite, logs, crash
  reports, screenshots, and evidence artifacts.
- Test-only client, Oracle, mutation, and orchestration code must not enter the
  production JAR.
- A Forge major line has its own Minecraft mappings and product JAR. Do not
  widen the Forge 65 JAR to Forge 66 when Forge 66 appears.
- Preserve user changes. Do not delete worlds, logs, credentials, or installed
  JARs as part of a build or test.

## Evidence states

- `PASS`: the exact declared gate completed and its artifacts were verified.
- `FAIL`: the product ran but did not meet the gate.
- `NOT_RUN`: the gate has not completed for the current source/product hash.
- `BLOCKED_CREDENTIAL`, `BLOCKED_INFRA`, `BLOCKED_BUDGET`: a precise external
  prerequisite is unavailable. Never rewrite these as `PASS`.

Every E2E run must bind its evidence to a run ID, source state, product JAR
SHA-256, Minecraft version, Forge version, Java version, model identifier, and
process exit status. Dirty-worktree development runs are always `NON_RELEASE`.

## Commit discipline

- Before every commit, stage only the intended files and run
  `./scripts/preflight-before-commit.sh`.
- Do not create duplicate, empty, formatting-only, placeholder, or no-op
  commits. A commit must have one concise purpose and a reproducible test or
  documentation reason.
- Use the project rules and practical setup guide in
  `docs/PROJECT_CHARTER.md`, `CONTRIBUTING.md`, and `docs/USAGE.md`.
