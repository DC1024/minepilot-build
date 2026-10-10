# Hidden Random-Seed Hardcore Evaluation

The hidden-seed evaluator is a process-level harness, separate from Forge
GameTests. It copies a clean dedicated-server template for each case, creates a
64-bit seed with the operating-system CSPRNG, and writes a locked Hardcore
configuration:

- `hardcore=true`, `difficulty=hard`, `gamemode=survival`;
- `force-gamemode=true`, `allow-flight=false`;
- command blocks and RCON disabled;
- structures enabled.

The evaluator performs one capability preflight and then sends exactly one
initial completion goal. After that goal, chat writes, MCP writes, new
waypoints, item grants, commands, seed inspection, structure APIs, hidden
chunk scans, reloads, and restart attempts are forbidden. A spectator may
observe but cannot act.

## Case contract

Every case records a private seed commitment, exact source commit, exact
product SHA-256, Minecraft/Forge/Java versions, model identifier, start and end
timestamps, server exit code, action audit, death cause, and final verdict.
Raw seeds and credentials never enter public summaries. A real Hardcore death
is an executed failure in the denominator; a contaminated or dead claimed
completion is rejected.

Victory means that the companion itself kills the Ender Dragon and physically
enters the activated return portal. A model sentence, an externally placed
dragon, a teleport, or a script changing the world is not victory.

## Statistical gates

The intended targets are:

| Gate | Sample | Target |
| --- | ---: | --- |
| M1 unseen survival | 100 | at least 95% within 60 minutes |
| M2 unseen completion | 200 | at least 90% within 6 hours |
| M4 hidden speedrun | 1,000 | at least 95% within 2 hours and 99% within 6 hours |

These thresholds are product goals, not current claims. A summary is accepted
only when all shards are unique, terminal, uncontaminated, and bound to the
same clean artifact. Missing or mismatched summaries produce `NOT_RUN`, never a
green result.

## Running the harness

The evaluator is intentionally fail-closed. Run its preflight first, then use
the documented `scripts/run-hidden-seed-evaluations.py` command on an isolated
worker with an authorized model configuration. Do not run it with a personal
world or a production secret. The current Darwin development machine lacks the
Linux/Xvfb and formal-worker resources required for the full gate, so the
repository records the statistical gates as `NOT_RUN`.
