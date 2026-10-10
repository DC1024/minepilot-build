# Real-client E2E harness

This directory contains test-only infrastructure. None of it belongs in the
production Mod JAR.

## Rendered Observer evidence

The Observer is a real Minecraft client running with an isolated Xvfb display,
not a coordinate-only process. After its own world sample first contains the
AI player, the test-only client captures exactly one first-person PNG into
`screenshots/observer-rendered.png`. The verifier checks the PNG signature,
bounded size and dimensions, and requires a matching
`observer_screenshot_saved` event in the same run nonce and ordered client
lifecycle. The image is audit evidence only: it is not sent to the model, and
it does not replace manual visual acceptance for skin, HUD, animation or UI
quality.

## Prepare on any development machine

```bash
python3 e2e/orchestrator.py prepare
```

This builds the bundled product JAR and both test Mods, creates a new immutable
run directory, copies the exact product JAR into the server/Actor/Observer
instances, and records hashes. The result is `PREPARED_NOT_RUN`, not a pass.

## Dedicated-server lifecycle smoke on any development machine

```bash
python3 e2e/orchestrator.py server-smoke
```

This launches the exact staged product JAR on a real dedicated server, with no
human client. It checks that the AI `ServerPlayer` joins, its SQLite memory
opens from the product Jar-in-Jar payload, and shutdown removes it cleanly.
Its verdict is deliberately scoped to lifecycle infrastructure and sets
`functionalAiClaim` to `false`; it is not evidence for chat, movement, combat,
survival, M0, or M1.

The exact product also writes secret-free `runtime_lifecycle_audit` and
`connection_transport_audit` rows. A real restart archive must contain two
ordered startup rows from two server boots; a single smoke is intentionally
rejected:

```bash
MCAI_RESTART_RUN_ROOT=/path/to/two-boot-archive \
  python3 e2e/formal_gates.py --gate e2eRestart --forge-version 65.1.0
```

Without that archived two-boot run the gate remains `NOT_RUN`.

## Delayed first-human anchor smoke (no model)

```bash
python3 e2e/orchestrator.py anchor-smoke --forge-version 65.1.1
```

This is a separate lifecycle check for the production initial-spawn path. It
starts the exact dedicated-server JAR with no human client, waits until the
real headless `ServerPlayer` is `ACTIVE`, keeps the server human-free for at
least 40 server ticks, and only then starts the real offscreen Actor and
Observer clients. The Oracle changes only the vanilla respawn point for the
later human login; it never teleports either production player after the
scenario starts. The verifier requires the normal remove/relogin anchor path,
same-dimension safe feet, distance no greater than 12 blocks, both clients'
own rendered observation, and the exact product hash in all three instances.

This smoke deliberately sends no chat and does not configure a model. Its
scope is `real_client_delayed_first_human_anchor_non_model`; a pass is useful
evidence for “server ran before the first human joined” and initial body
placement only. It cannot promote the chat-to-action causal chain, combat,
survival, Hardcore, or any M0--M4 gate. On hosts without Linux/Xvfb it should
remain an infrastructure `NOT_RUN` result.

The release artifact is always built against the Forge 65.0.0 API floor. To
run that same copied JAR on another official Forge 65 patch, select only the
runtime:

```bash
python3 e2e/orchestrator.py server-smoke --forge-version 65.1.0
```

For the complete published Forge 65.x real-client matrix, manually trigger
`.github/workflows/real-client-functional-e2e-matrix.yml`. It fans out one
isolated Linux/Xvfb job for each of 65.0.0--65.0.9, 65.1.0, and 65.1.1, archives each
nonce-bound result separately, and fails closed per patch. Defining this
workflow does not count as running or passing the matrix; the progress state
remains `NOT_RUN` until every patch has a real-model result.

## Run offscreen on Linux

Before launching any client, check the host without starting Minecraft or
touching the physical display:

```bash
python3 e2e/orchestrator.py preflight --forge-version 65.1.0
```

This prints a machine-readable infrastructure report. A non-zero result means
the functional gate is `NOT_RUN`; it is not a product or gameplay failure.

Install Java 25, Xvfb, Mesa/llvmpipe, and the normal Forge build. The
preflight executes the selected Java binary with `-version` and fails closed
unless its major version is exactly 25; merely having a file named `java` is
not sufficient for this gate. Inject the configured real model without
writing its key into the repository:

```bash
export MCAI_API_KEY_FILE=/run/secrets/mcai-api-key
export MCAI_BASE_URL=https://provider.example/v1
export MCAI_MODEL=provider-model
python3 e2e/orchestrator.py functional --forge-version 65.1.0
```

`MCAI_API_KEY` is also accepted, but a secret file is preferred. The
orchestrator never starts real clients on macOS or Windows and never uses the
physical display. Linux functional runs create an isolated Xvfb display and
bind the insecure offline test server strictly to `127.0.0.1`.

If preflight is not ready, `functional` exits with code 2 but still writes an
immutable run directory containing `manifest.json` with `status=NOT_RUN`,
`functional-preflight.json`, and a secret-safe `infrastructure-error.json`.
This is intentionally different from a product failure or a gameplay pass.

The current slice performs two player-style tasks in sequence. The real Actor
first asks the AI to follow, waits until its own rendered world shows the AI
arrive and it receives an AI reply, then sends a second normal chat asking the
AI to collect a visible dropped oak-log stack. The independent server Oracle
creates that ordinary item entity before the ready marker and becomes
read-only after the first command.

A pass requires two distinct, ordered production
model/schema/revision/skill/action audit chains
(`follow_entity -> move` and `collect_observed_item -> move`), Actor receipt
of an AI reply after each command, TAB visibility, Observer-visible
non-teleport motion, removal of the exact fixture item entity, and the
corresponding inventory-count increase through vanilla pickup. It still does
not constitute M0 or M1 completion.

The formal Gradle entry points required by the project plan are available as
`e2eFunctional`, `e2eRendered`, `e2eChat`, `e2eMovement`, `e2eInventory`,
`e2eRestart`, `e2eXaero`, `e2eM1`, `e2eM2`, `e2eM3`, `e2eM4Shard`,
`aggregateHiddenSeeds`, `soak24h`, `soak100h`, `recordHumanBaseline`,
`naturalnessReport`, and `mutationGate`. The first five invoke the real
external-client slice; the M3 entry additionally validates the real companion
summary protocol in `e2e/m3_protocol.py`. The remaining entries record
`NOT_RUN` until their gate-specific scenarios and statistical evidence exist.
A dirty checkout, missing model, or fixture-only result cannot be promoted to
`PASS`.

For the hidden-seed statistical entry points, set an OS-path-separator list of
executed public `summary.json` files and run the matching gate:

* `MCAI_M1_FOUNDATION_SUMMARIES` → `e2eM1` (at least 100 foundation cases);
* `MCAI_M2_COMPLETION_SUMMARIES` → `e2eM2` (at least 200 completion cases);
* `MCAI_HIDDEN_SEED_SUMMARIES` → `aggregateHiddenSeeds` (at least 1,000
  completion cases).

The aggregator is fail-closed: it rechecks terminal evidence and recomputes
all rates, rejects duplicate cases or raw seed material, and applies the
M1/M2/M4 thresholds. Each summary must also bind the exact product JAR
SHA-256 and 40-hex source commit, and all shards must agree. Without the
matching summaries or artifact binding the entry point remains `NOT_RUN` or
fails closed.
On a release-eligible checkout, set `MCAI_EXPECTED_PRODUCT_SHA256` as well;
the formal gate compares the summary binding with that exact artifact and the
current 40-hex Git commit before allowing `PASS`.

## Provider-neutral Linux worker

The exact-client slice can be handed to any isolated Linux/Xvfb worker without
binding the project to a cloud provider. `e2e/worker_protocol.py` validates a
public job manifest whose only seed material is a 64-hex commitment; API keys,
raw seeds, world paths and player identities are rejected. The result bundle
contains a canonical result hash and a SHA-256 inventory of every artifact.

Create a job manifest in the coordinator, copy it to the worker, and run. The
creator refuses a dirty checkout by default and accepts only a file of public
64-hex seed commitments; it never reads an API key. `--allow-dirty` is for
development diagnostics only and leaves `source.dirty=true` in the manifest.

```bash
python3 scripts/create-worker-job.py \
  --job-id m4-20260810-01 \
  --shard-id shard-01 \
  --scenario-id real_client_chat_follow_inventory \
  --case-count 1 \
  --seed-commitments-file seed-commitments.txt \
  --product-jar build/libs/mcai_companion-0.1.11-dev-mc26.2.jar \
  --forge-version 65.1.0 \
  --model mimo-v2.5 \
  --base-url https://token-plan-cn.xiaomimimo.com/v1 \
  --credential-present --credential-source injected \
  --output job.json
```

The worker receives the public job and an independently injected credential:

```bash
python3 scripts/run-e2e-worker.py validate-job --manifest job.json
python3 scripts/run-e2e-worker.py run \
  --manifest job.json \
  --output /var/lib/mcai-worker/results/job-001
python3 scripts/verify-worker-result.py \
  --job job.json \
  --result /var/lib/mcai-worker/results/job-001
```

The worker reads `MCAI_API_KEY` or `MCAI_API_KEY_FILE` only from its injected
environment. Before launch and after the child run it binds the actual public
model name, endpoint host, credential presence, and credential source to the
job; an `injected` job source accepts either the environment or file channel,
but never the secret value. A missing Linux/Xvfb runtime or credential is
recorded as `BLOCKED_INFRA`/`BLOCKED_CREDENTIAL`, not as a gameplay result. A child
orchestrator run is `PASS` only when its real dedicated server, ChatActor,
Observer, model audit and Oracle evidence pass; no fixture or fake model can
upgrade the worker result. Use an immutable per-job output directory and send
only the verified bundle to the coordinator.

The worker currently implements only the one-case
`real_client_chat_follow_inventory` slice. If a job names an unimplemented
scenario (for example `m4_hidden_hardcore`) or supplies a different case
count, the worker records `NOT_RUN` and does not execute a substitute
scenario. M1/M2/M4 shards must wait for their dedicated real-world runners;
their seed commitments are never treated as evidence that the scenario ran.
For the supported slice, provide exactly one public commitment in
`seed-commitments.txt`; it identifies the run without exposing the actual
Minecraft seed.

For M3, set an OS-path-separator list of public worker summaries in
`MCAI_M3_SUMMARIES` and run `./gradlew e2eM3`. Every case must be a terminal
PASS from a real dedicated server, normal client, configured model and
read-only Observer, with explicit no-command/no-direct-mutation evidence.
The aggregator requires at least 50 natural-language companion cases, 30
unseen-site building cases, three unseen variants for every farm and machine
capability label in the protocol, and a measured 100-hour/10,000-waypoint/
100,000-asset memory run within the query budgets. Partial matrices and
controlled GameTests remain `FAIL` or `NOT_RUN`; they are never promoted by
the entry point.

## Run on GitHub Actions

The manually triggered
`.github/workflows/real-client-functional-e2e.yml` workflow provisions
Temurin Java 25, Xvfb, and Mesa software rendering on Ubuntu 24.04, then runs
the same `functional` command. Configure the repository secret
`MCAI_API_KEY`; the Base URL, model name, and any currently published Forge
65.x patch (65.0.0 through 65.0.9, 65.1.0, or 65.1.1) are workflow inputs. The
workflow has no pull-request trigger, so untrusted fork code cannot request
the model secret. Selecting a patch runs one isolated real-client attempt;
the full patch matrix is still a separate formal gate and is not implied by a
single green run.

Every attempt uploads `e2e/results`, logs, exact installed JAR copies,
observations, the production SQLite causal audit, verifier output, and any
infrastructure error before enforcing the exit status. A workflow file or a
green build-only step is not a functional pass; the archived
`e2e-verdict.json` must say `PASS`.
