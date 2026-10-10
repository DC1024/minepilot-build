# Test architecture

## Evidence layers

| Layer | What it can prove | What it cannot prove |
|---|---|---|
| JVM unit/source-contract tests | deterministic parsers, revisions, skills, safety invariants | Minecraft physics, real model behavior, visual UI |
| Forge GameTest server | actual Forge/Minecraft entities, menus, physics, lifecycle, controlled model calls | unknown random seeds, rendered clients, human-like naturalness |
| Exact-JAR server smoke | the shipped product JAR loads, SQLite Jar-in-Jar and lifecycle are clean | functional AI, chat-to-action, survival statistics |
| Actor + Observer clients | ordinary client packets, rendered UI/skin and player-visible causal action | long statistical claims from one run |
| Hidden-seed evaluator | Hardcore completion distributions and time | general user experience outside its frozen protocol |

Every run has a unique directory, product/JAR hashes, Forge version, source
state, model status, and a verifier result. A missing credential, Linux/Xvfb
worker, or client is `NOT_RUN`, never an implicit pass.

## Required topology for formal gates

The formal real-client slice requires a Linux worker with Xvfb, one exact
production JAR, test-only Actor and read-only Observer JARs, a dedicated server,
and a valid model credential supplied only to the AI process. The current macOS
host has no Xvfb/Docker/Podman worker, so these gates remain unexecuted.

The repository now includes a provider-neutral worker contract in
`e2e/worker_protocol.py`, `scripts/create-worker-job.py`, and
`scripts/run-e2e-worker.py`. The creator binds a shard to the exact source
commit and product JAR SHA-256, rejects a dirty checkout unless explicitly
marked development-only, and writes only model host metadata and 64-hex seed
commitments. Raw seeds and credentials cannot cross the coordinator/worker
boundary. Results are canonical-hash and artifact-hash verified by
`scripts/verify-worker-result.py`. Credentials remain injected into the worker
environment and are scanned out of published artifacts. A claimed passing
bundle must also contain a ready preflight whose public model binding matches
the job, a PASS `e2e-verdict.json`, both ordered model-to-action traces, an
independent Oracle PASS, and all three exact product-copy hashes; the verifier
never compares secret values. The worker refuses to run a scenario it does
not implement, rather than executing a smaller substitute under a misleading
M1/M2/M4 label. This enables a real Linux/Xvfb run when a worker is supplied,
but does not change the current `NOT_RUN` status.

## Commands

```text
./gradlew test
./gradlew compat-checker
./gradlew runGameTestServer -Pforge_compile_version=65.1.0 \
  -Plive_model_selector=mcai_companion:zero_human_dedicated_server_chunk_and_respawn
python3 -m unittest discover -s e2e -p 'test_*.py' -v
python3 e2e/orchestrator.py server-smoke --forge-version 65.1.0
```

These commands are development evidence. They do not change the formal status
stored in [GOAL_STATE.json](progress/GOAL_STATE.json).
