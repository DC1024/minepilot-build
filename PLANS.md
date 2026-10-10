# Continuous implementation plan

Source of truth: `CODEX_GOAL_M1_M4.md`.

## Current critical path

1. Freeze authoritative Forge 65 facts and discover new official patches.
2. Build a real dedicated-server vertical slice with:
   - the production JAR;
   - a real ChatActor client;
   - a second Observer client;
   - a read-only-after-start server Oracle;
   - a process Orchestrator with isolated runs and evidence manifests.
   - a provider-neutral Linux worker contract that refuses unsupported
     scenario labels and verifies the exact causal evidence behind PASS.
3. Prove a real client chat message reaches the server, the configured model
   produces a valid decision, the AI body moves and performs a real inventory
   transaction, and the client receives the follow-up.
4. Add mutation gates that must catch chat drop, talk-only, movement no-op,
   menu no-op, direct inventory writes, teleport cheats, false completion, and
   packet leaks.
5. Complete M0 body, persistence, dual-client, 24-hour, and performance gates.
6. Complete M1 on 100 unseen Hardcore seeds.
7. Complete M2 on 200 unseen seeds.
8. Complete M3 expert companion and 100-hour long-world gates.
9. Complete M4 on 1,000 hidden random seeds.
10. Freeze one clean release commit and rerun all applicable gates against the
    exact release JAR hash.

## Immediate acceptance slice

The first slice is deliberately small but fully external:

1. A real Actor client joins a real Forge dedicated server.
2. It sends a nonce-bearing Chinese command with the vanilla client chat API.
3. The production `ServerChatEvent` receives it.
4. The production model/goal/skill/action chain runs.
5. The AI changes position without teleporting.
6. An independent Oracle observes the world delta.
7. The Actor client receives the `[AI]` message through
   `ClientChatReceivedEvent`.
8. A second client independently observes the AI body and motion.

The slice is not M1 completion. It is the minimum proof that later M1–M4
results cannot be manufactured by server-side test injection.
