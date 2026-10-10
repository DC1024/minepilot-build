# Codex Recovery Checkpoint

Last updated: 2026-08-30T02:40:00+09:00

This checkpoint is intentionally concise and English-only. Runtime chat and
multilingual test fixtures may contain other languages; public repository
documentation must not.

## Current objective

Continue the MinePilot M1–M4 objective on Minecraft Java 26.2 / Forge 65.x with
real, fair `ServerPlayer` behavior. Do not claim professional-companion,
random-seed, two-hour, or M0–M4 completion until the artifact-bound protocols
pass.

## Current recovery state

This section supersedes older chronological notes below when they conflict.

### 2026-08-30 non-ocean matrix and composite live gate

- A stricter clean physical matrix now passes 10/10 on Forge 65.0.9 in
  `run-container-door-ten-terrain-v2`. The body starts eight blocks from the
  work area and the ten cases cover flat ground, a raised approach, a
  depression, a cross slope, a two-level terrace, a shallow basin, an offset
  ridge, broken ground, a two-obstacle detour, and rolling ground. The same
  cases cover all ten convertible wood families listed below.
- The stricter matrix first failed 4/10 because four-container discovery only
  rotated in place and discarded legitimate observations made while walking.
  Production now approaches a remembered storage location through ordinary
  `MoveToSkill`, resumes the discovery phase after arrival or bounded failure,
  and accumulates first-person chest observations throughout the approach.
  No fixture coordinate, terrain variant, or wood-family coordinate appears
  in production logic. The clean final run reported all ten tests passed in
  16.40 seconds. This remains physical executor evidence, not a live-model or
  formal randomized-world result.

- The previous formal batches remain failures and are not release evidence:
  stone tools passed 7/10, iron acquisition passed 8/10, and the composite
  storage-to-door batch has not produced a pass. M0--M4 remain `NOT_RUN`.
- Composite diagnostics 26--28 exposed three separate production defects:
  inherited open menus and stale crafting-table perception prevented the
  compound from starting; door placement aimed at the support block side
  while requiring its top face; and Grok 4.5 occasionally combined the two
  route enums as `FOUNDATION/LOG_STORAGE_DISTRIBUTED`. The menu lifecycle,
  fresh-air placement proof, top-face aim, and a strictly bounded known-enum
  canonicalizer are now implemented.
- The physical container-to-door matrix now passes 10/10 from a clean GameTest
  directory on Forge 65.0.9. It covers oak, spruce, birch, jungle, acacia,
  dark oak, mangrove, cherry, crimson, and warped families; both chest-group
  orientations; inherited open menus; an occluded fourth chest; and a nearby
  one-block terrace. Every oracle checks real chest-menu withdrawal, vanilla
  recipe statistics, item consumption, and the matching door block in the
  world. This is physical executor evidence, not live-model evidence.
- The matrix exposed three approach defects before reaching 10/10: a remembered
  fixture target incorrectly occupied the fixture block, the 3.0-block move
  arrival radius could immediately accept a still-occluded chest, and the door
  support route selected a two-block-out terrace cell rather than the ordinary
  adjacent interaction cell. Fixture navigation now selects an adjacent stand,
  uses a 0.75-block docking radius, and approaches the door from its cardinally
  adjacent cell before requiring fresh top-face and air-clearance evidence.
  The clean final run reported all 10 required tests passed in 13.34 seconds.
- Diagnostic 29 stopped before player chat. Both the production capability
  probe and independent minimal Responses/Chat Completions probes returned
  HTTP 503 with provider code `model_not_found`. The authenticated `/models`
  endpoint returned HTTP 200 with an empty model array. The official model id
  and its `grok-4.5-latest` alias both failed. This is not a gameplay pass or
  failure. The classifier now preserves structured `model_not_found` even
  when a relay wraps it in HTTP 5xx, preventing misleading retry loops.
- The relocated live fixture now selects one of ten deterministic non-ocean
  variants by test iteration: oak, spruce, birch, jungle, acacia, dark oak,
  mangrove, cherry, crimson, and warped wood families over flat, sloped,
  basin, ridge, terraced, broken, rolling, and stepped land. Mining, pickup,
  chest contents, plank recipes, door recipes, and placed-door oracles bind
  dynamically to the selected family. The natural player request is no
  longer oak-specific. Production skills remain free of fixture coordinates
  and biome names; generic wood exploration also no longer labels its target
  as oak.
- Targeted unit gates and a fresh full Gradle `build` pass on Forge 65.0.9
  after the stricter matrix. The coherent development version is now
  `0.1.16-dev-mc26.2`. The formal ten-run live batches cannot start until the
  configured relay key exposes a working Grok 4.5 channel.
- `./gradlew build` passes with 1,254 tests, zero failures, and two skips. The
  development JAR SHA-256 is
  `635662b79e6093265112e68841b868a8e6fbefd944a244115e5ca8735f23b707`.
  The verified implementation commit is public on `main` as
  `a12b2b84be7f8a1a1bca0acb8db036bb52fb065b`, tree
  `bcdf716db2af253bc5c0c8486da47bf4f56ee871`. The same JAR is installed in the
  user's Forge 65.0.9 XMCL instance; the prior 0.1.15 JAR is retained outside
  `mods` in the instance backup directory.
- The last attempted unit command accidentally selected Forge 65.0.0 and was
  manually stopped while Mavenizer was downloading. All subsequent gates must
  explicitly use `-Pforge_compile_version=65.0.9`.

### 2026-08-24 thirty-run relocated companion gate

- The active request is three black-box task suites repeated ten times each.
  Every repetition must retain the companion's stable identity while clearing
  inventory, selected slot, armor, offhand, ender chest, active goal, and
  controller state, then moving the body to a newly isolated area.
- Suite one requires a retained stone tool within 60 real seconds. Suite two
  requires physically obtained iron within five real minutes. Suite three
  requires thirty gathered logs, four independent chests with the remaining
  logs balanced across them, then exactly one convertible wood item withdrawn
  through each real chest menu, normal plank and door recipes, and a matching
  door placed three blocks in front of the chest group.
- Production work in progress adds the generic
  `prepare_container_wood_door` compound. It discovers independent chests from
  current fair perception, transfers real items through vanilla menus, derives
  planks and the door from the withdrawn wood family, and computes placement
  relative to observed chest geometry. It contains no fixture coordinate,
  biome, or oak-only route.
- Changed files include the goal/route/brain/planner mappings,
  `FoundationCraftingSkills`, `EstablishFoundationWorkstationsSkill`, the new
  `PrepareContainerWoodDoorSkill`, runtime completion evidence, and focused
  physical GameTest scaffolding. The last passed gate was `compileJava` before
  the new GameTest scenario was added; the scenario has not yet compiled or
  run and no repetition is currently counted.
- Next: compile the new scenario, register and run spruce and warped physical
  gates, add iteration-isolation and exact physical oracles, then run the real
  Grok 4.5 chat suites sequentially for ten distinct locations each. Provider
  timeouts are infrastructure failures, never passes. Formal M0--M4 remain
  `NOT_RUN`.

### 2026-08-24 distributed-storage post-pass audit

- A prior live Grok 4.5 run passed the exact physical task in 4.118 minutes:
  thirty mined and picked-up oak logs, ordinary tool and chest recipes, four
  nonadjacent chest block entities, no raw logs in body inventory, and a
  maximum stored-count difference of one. Later clean reruns are retained
  because they exposed defects not exercised by that pass.
- A rerun stopped at basic crafting when eight nearby cows occupied every
  otherwise valid support face. `PrepareBasicCraftingSkill` now selects a
  fresh, safely observed standing cell through ordinary `MoveToSkill`, moves
  at most three times, and rescans. Its completion tick is idempotent. The
  cow-obstructed physical GameTest now passes with a real placed table and a
  recipe-crafted wooden pickaxe.
- Two subsequent reruns reached 29/30 and 28/30 physical oak logs before
  stalling. The first showed that the gathering no-progress watchdog covered
  a connected tree cluster but not its exploration child. Exploration is now
  cancelled and rescanned after the same bounded 400-tick interval, and its
  nested checkpoint is persisted. The second showed a rolling travel segment
  stopped 0.609 blocks from its cell center despite the body's feet already
  occupying the exact planner-verified endpoint cell. Internal segments now
  complete on physical endpoint-cell occupancy; public journey precision is
  unchanged. The real diagonal-detour GameTest passes after this fix.
- The locked-recipe chest GameTest passes: eight planks are physically placed
  in the real crafting menu, one chest is produced, ingredients are consumed,
  and the recipe is awarded only after the vanilla transaction. The full
  `check build` gate passes with 1,249 tests, zero failures, and two skips.
  The current product is `build/libs/mcai_companion-0.1.14-dev-mc26.2.jar`
  with SHA-256
  `1e4518791f7efcfb7c4b6e7815273d6f6a271df055f46797ce6f1c4894f8b8e7`.
- The current-source full live rerun remains pending, not passed. Two fresh
  attempts failed before player chat because the configured Grok 4.5
  capability request received zero bytes for 90 seconds. A direct credentialed
  `/v1/models` request returned HTTP 200 in 0.98 seconds, while a minimal
  `/v1/chat/completions` generation request received zero bytes for 25 seconds.
  A later 35-second minimal generation probe also received zero bytes and no
  HTTP status, confirming that inference remained unavailable while account
  authentication and model discovery were reachable.
  No scripted decision replaced the unavailable model. Next: rerun the exact
  live physical gate when provider inference recovers, then publish the exact
  artifact-bound source snapshot. Formal M0--M4 remain `NOT_RUN`.

### 2026-08-24 bounded stone and iron acquisition gates

- The relocated, empty-inventory, abstract-player-chat stone gate passed on
  Forge 65.0.9 with the real configured Grok 4.5 model in 36.04 seconds. The
  body physically picked up three oak logs and three cobblestone, used normal
  crafting, and retained a stone pickaxe. Vanilla awarded `Stone Age` and
  `Getting an Upgrade`. Speech and product audit text were not accepted as
  completion evidence.
- A distinct `IRON_OBTAINED` terminal now represents the first physical raw
  iron, ingot, or iron product. The live gate starts after clearing every
  inventory, armor, offhand, and ender-chest slot, accepts one abstract Chinese
  player message, and requires an iron-ore mining statistic, raw-iron pickup
  statistic, verified route milestone, physical raw iron or ingot in inventory,
  and the production iron-acquisition skill before five real minutes expire.
- The first iron run was a valid failure. The model encoded exactly
  `FOUNDATION/IRON_OBTAINED`; the body acquired four logs, crafted a stone
  pickaxe, then stalled in the unrelated food-reserve phase with six food
  items. An explicitly bounded first-iron route now omits `FOOD_SECURED` while
  later foundation and completion terminals retain that safety phase.
- Two replacement runs were also valid failures before action: the provider's
  generation endpoint returned no bytes and the conversation request hit its
  90-second hard timeout. A separate non-content health check returned HTTP
  200 from `/v1/models` in 0.83 seconds, while a minimal Grok 4.5 chat request
  received zero bytes and timed out after 20 seconds. No iron pass was claimed
  from those attempts.
- After the generation endpoint recovered, the exact replacement gate passed
  on Forge 65.0.9 in 1.789 GameTest minutes (about 107 seconds). Grok 4.5's
  first response combined the route and terminal into one invalid field; the
  existing bounded repair request produced canonical
  `FOUNDATION/IRON_OBTAINED`, after which the embedded human left. The body
  physically acquired five logs, crafted the wooden and stone progression,
  mined and picked up the required cobblestone and coal, then mined iron and
  picked up raw iron. Completion required a positive vanilla iron-ore mining
  delta, positive raw-iron pickup delta, retained raw iron or ingot, the
  verified `IRON_OBTAINED` milestone, and an observed production
  `prepare_iron_toolkit` controller. Forge reported all required tests passed.
- `./gradlew check build --no-configuration-cache` passes after the route
  and observer-stage changes (1,247 tests, two skipped). The development line
  is now `0.1.14-dev-mc26.2`; its local product JAR SHA-256 is
  `4554b2f6787b3be82ff75b1931bfaed01e9439a532baa260017d8e985400f29f`.
  The changed files include `BrainOrchestrator`,
  `CompanionConversationCoordinator`, `JdkModelGateway`,
  `GoalExecutionPlan`, `SurvivalMilestone`, `SurvivalRouteTracker`, the live
  chat GameTest and Forge fixture registration/resource, completion verification,
  and focused tests. The latest bounded iron gate passes; next, preserve both
  bounded gates as regressions and move to an uncontrolled natural-world iron
  acquisition trial. The dedicated GameTest server has no renderer, so it
  cannot produce a genuine video without adding a real rendering client.

### 2026-08-24 relocated 60-second stone-tool gate

- The active requirement is one abstract player chat after inventory and all
  equipment are cleared and the companion is moved more than 64 blocks to a
  new controlled area. Physical acquisition of a retained stone pickaxe must
  finish within 60 real seconds; model speech and product audit claims are not
  completion evidence.
- The first replacement run failed honestly. The first Grok 4.5 response took
  about 23 seconds and correctly selected
  `FOUNDATION/STONE_TOOL_OBTAINED`, but its non-authoritative revision echo was
  rejected as `stale_goal`. A second paid response was accepted after about 48
  seconds. The local typed-route dispatcher then started physical gathering
  without another gameplay-model request and acquired two oak logs before the
  60-second deadline expired.
- Root cause: request ID and revision values were being treated as if the
  provider's copied JSON fields established freshness. They do not; the local
  `InFlight` object already owns the HTTP completion. `JdkModelGateway` now
  binds those transport fields from the local request before validating the
  semantic payload. `CompanionConversationCoordinator` separately rejects a
  completed response if the live goal revision has actually changed, so a
  newer player task cannot be overwritten.
- Current changed files are `BrainOrchestrator`,
  `CompanionConversationCoordinator`, `JdkModelGateway`,
  `LiveModelChatGameTests`, `BrainOrchestratorTest`, and
  `JdkModelGatewayContractTest`. Focused gateway and orchestrator tests pass.
- Last failed gate: relocated abstract-chat stone tools in 60 seconds, due to
  the discarded first response and resulting timeout during physical wood
  gathering. Next: rerun that exact gate, then add and run a distinct
  zero-equipment abstract-chat iron-acquisition gate with a five-minute real
  deadline. A dedicated GameTest server has no registered hidden renderer, so
  no video can be produced from this path unless a genuine renderer is added.

### 2026-08-24 natural-language goal encoding and stone-tool gate

- Ordinary imperative chat no longer installs staged survival goals through
  local keyword matching. The configured high-level model must encode an
  accepted task into a bounded `GoalExecutionPlan`: route `NONE`,
  `FOUNDATION`, or `COMPLETION`, plus a validated terminal milestone. The
  original player text remains unchanged, and only server-owned skills may
  execute the plan or certify progress.
- The plan is persisted in the goal detail field, survives restart, truncates
  route guidance and completion requirements at the requested outcome, and is
  consumed by both route tracking and the foundation interaction audit.
  Immediate follow, immediate threat response, cancellation, and active-task
  continuation retain their bounded low-latency local paths.
- The last failed live gate correctly encoded
  `FOUNDATION/STONE_TOOL_OBTAINED`, mined and picked up eight oak logs, crafted
  and placed a crafting table, and crafted a wooden pickaxe. It then stopped
  because `FoundationActionAudit` still recognized only legacy goal-text
  keywords, discarded the real crafting-table interaction receipt, and never
  advanced `BASIC_CRAFTING_READY`.
- The audit now recognizes the persisted typed Foundation or Completion route
  before its legacy fallback. A focused unit gate proves arbitrary goal text
  activates audit only when accompanied by a valid typed route, and that an
  unrelated goal cannot create foundation evidence. The Foundation wood phase
  now exposes the continuous `gather_nearby_wood` compound rather than asking
  the model for one block coordinate at a time.
- The replacement Forge 65.0.9 live-model GameTest passed in 2.047 minutes.
  One embedded player sent one ordinary Chinese chat request and then left.
  Grok 4.5 encoded exactly `FOUNDATION/STONE_TOOL_OBTAINED`; the companion
  physically mined and picked up six oak logs, crafted and used a crafting
  table, crafted a wooden pickaxe, mined and picked up three cobblestone, and
  crafted and retained a stone pickaxe. Vanilla awarded `Stone Age` and
  `Getting an Upgrade`. Assertions used vanilla statistics, inventory, world
  state, persisted interaction evidence, and verified milestones rather than
  model speech or product log claims.
- `./gradlew check` passes after the fix. This is an exact chat-to-stone-pickaxe
  controlled gate only. It does not promote M1, random-world survival,
  professional companion, Nether, End, Hardcore, or the two-hour M4 target.
- Current changed files are the new `GoalExecutionPlan`, conversation and
  decision validation, goal persistence, route tracking/completion, foundation
  audit, phase-owned skill exposure, the live physical GameTest, and focused
  tests. Next: preserve this gate while extending semantic goal encoding and
  physical terminal verification to the next ordinary professional-companion
  task; formal natural-world and M1--M4 gates remain open.

### 2026-08-24 headless natural-world black-box checkpoint

- The current test path is a background Forge 65.0.9 dedicated server with
  one product JAR, a fresh random Hard survival world, one real embedded human
  `ServerPlayer`, and the normal companion `ServerPlayer`. No Minecraft client
  window is opened. Pass evidence is restricted to actual chat packets,
  coordinates, health, inventory/hotbar, and nearby world state written by
  `HeadlessBlackboxModule`; product audit rows are diagnostic only.
- `grok-4.5` now negotiates `reasoning.effort=low` through the Responses API.
  The persisted capability profile migrated from v2 `DEFAULT` to v3 `LOW`.
  A wood action selection fell from roughly 47--79 seconds in earlier runs to
  10--14 seconds. This is improved but is not yet a human-like latency pass.
- Natural-world trials 1--2 failed with repeated speech/survey cycles and no
  motion. Trial 3 selected the new `gather_nearby_wood` compound but failed on
  an oak-leaf canopy with an empty inventory. Trial 4 exposed an over-strict
  stable-ground filter that left the companion absent when the whole spawn
  ring was canopy; the locator now prefers ground but retains a vanilla-safe
  canopy fallback. Trial 5 physically moved the body over ordinary terrain
  and acquired 12 spruce logs with full health. However, it ran the bounded
  gather action three times instead of completing after the first verified
  inventory delta. Trial 5 therefore remains a failure for the player goal.
- Trial 8 passed the bounded wood task with Grok 4.5 selecting the compound in
  6,997 ms: the body moved, acquired five birch logs, completed once, and then
  remained stable. The terminal result is now the server-verified completion
  boundary for that exact one-action player goal; it does not wait for a model
  echo or admit a repeated mutation skill. The trial remains a single-task
  pass, not an M1--M4 promotion.
- Trial 9 failed the first natural-world moving-target follow gate. The
  background observer walked through normal clientless player input from
  `(-6.5,77,-5.5)` to `(-6.5,67,2.7)`. The companion spent four model turns
  surveying, started follow after about 84 seconds, moved only from
  `(-3.5,74,-8.5)` to about `(-3.5,74,-6.54)`, then failed twice with
  `follow_entity.no_physical_progress`. External coordinates established the
  failure before product events were inspected. The follow skill now performs
  at most two first-person floor/side route scans only after measured physical
  stalling, retries its local route, and terminates with
  `follow_entity.no_walkable_route` rather than entering an unlimited
  paid replan/spin loop. This has focused unit coverage but no replacement
  natural-world pass yet.
- Trial 10 failed an ordinary five-block separation on snow/gravel: the
  companion remained at `(-4.5,64,112.5)` while the observer moved to
  `(-3.5,64,117.7)`. The bound player crossed the follow threshold between
  semantic samples; the skill retained the authorized non-sneaking player
  coordinate but did not turn toward it before conservative route planning.
  The fix adds one bounded directed reacquisition look toward that already
  bound teammate, then requires a fresh ordinary first-person sample before
  the direct movement lane is used. It is not an all-direction scan.
- Trial 11 was not a valid ordinary-follow score because the development
  observer blindly walked off a 20-block mountainside. The companion stayed
  safe at the rim. The observer driver now checks only its next collision and
  same-level support, preferring a cardinal detour over a cliff; it still uses
  vanilla clientless input and never assigns coordinates.
- Trial 12 passed one bounded natural-world moving-target follow slice on the
  rebuilt artifact. One real player chat and one Grok 4.5 response started one
  `follow_entity` skill in 9,928 ms. Across 32 one-second movement samples the
  observer travelled about 25 blocks with turns and a one-block elevation
  change; the companion moved from `(-3.5,90,0.5)` to about
  `(3.65,90,22.59)`, remained at 20 health, held a median separation of 1.75
  blocks, recovered from a maximum of 5.02 blocks, and settled at 1.88 blocks
  after the observer stopped. No follow failure or replan occurred. The exact
  JAR SHA-256 is
  `1a41b0718f0fcf1d97077e6fb735cf1e532be3111f89add4e62496fe31b8c1f3`.
  This passes only continuous ordinary-terrain follow; cliff descent,
  long-distance pursuit, vehicles, portals, and M3 remain unverified.
- Current relevant changed files: `HeadlessBlackboxModule`,
  `GatherNearbyWoodSkill`, `ResourceGatheringSkills`,
  `SafeCompanionSpawnLocator`, `PlayerTaskIntent`,
  `MinecraftPlannerInputFactory`, the provider capability negotiation/profile
  classes, and their focused tests.
- Last completed gate: fresh-world trial 8 passed only the chat-to-model-to-
  physical-wood terminal boundary. Next: preserve this regression while
  testing a different professional-companion task and the optional off-screen
  screenshot path without opening or borrowing a human player's client.

### 2026-08-24 black-box reset

- Formal M0--M4 and real-world survival gates remain `NOT_RUN`. Controlled
  GameTests, source-contract tests, action-issued events, and model text are
  not evidence that the body visibly moved or completed a task in a normal
  world. Earlier wording that implied otherwise was invalid.
- The active field failures are broader than the one passing wood slice:
  follow can still stop/reorient or lose its target; arbitrary terrain and
  combat survival remain unverified in natural worlds; conversation continuity
  has no formal long-session gate; and the new first-person screenshot path
  has protocol/opt-in tests but no live off-screen renderer pass.
- Screenshot capture now accepts PNGs only from an explicitly registered
  client launched with `mcai.companion.hiddenRenderer=true`. Ordinary modded
  players are excluded from renderer selection, so the service cannot switch
  or borrow a human camera. A dedicated server with no such background client
  remains fail-closed instead of fabricating pixels.
- Current modified files are
  `src/main/java/dev/mcai/companion/skills/core/FollowEntitySkill.java` and
  `src/test/java/dev/mcai/companion/skills/core/FollowEntitySkillTest.java`.
  They contain an uncommitted direct visible-follow lane and a physical-stall
  timer correction; this has only focused inner-loop coverage and is not a
  black-box pass.
- The last valid product gate is the user's ordinary-world trial, which
  failed movement, tree gathering, terrain recovery, combat, respawn recovery,
  and chat-to-action. No Nether or End claim is valid.
- Next: use Minecraft 26.2 / Forge 65.0.9 with only this Mod, securely select
  the verified `grok-4.5` provider profile, implement continuous target memory,
  durable summarized conversation, and authenticated AI-view capture, then
  run chat-only Hard-survival trials using only world view, coordinates,
  inventory/hotbar, chat, and requested AI-view images as behavioral evidence.

- The current 15.1--15.4 audit found no evidence that generic block/menu
  interaction implied complete farm or machine commissioning. The detailed
  English-only matrix is `docs/PROFESSIONAL_COMPANION_MATRIX.md`. The concrete
  source gap fixed in this increment was the foundation placement guard: it
  omitted brewing stands, cauldrons, fletching tables, hoppers, dispensers,
  droppers, observers, pistons, repeaters, comparators, and redstone lamps.
  The focused `PrepareIronToolkitSkillTest` now covers the complete added set.
  This is a bounded guard fix, not an M3 promotion. The follow-up audit also
  found that the Forge menu suite already exercises brewing, cartography,
  stonecutter, merchant, enchanting, loom, smithing, grindstone, anvil,
  barrels, shulker boxes, hoppers, dispensers, and ender chests. Production
  observations now expose bounded role hints for those currently open menus;
  live model slices for each family and machine commissioning remain open.

- Internet validation confirms that real Minecraft co-play/coaching includes
  survival, base building, exploration, combat/PvP, redstone/farms, mod/server
  help, tailored live sessions, feedback, and continuity. The audit therefore
  keeps social coaching, ownership, rendered presence, farm commissioning, and
  machine rate/repair evidence as explicit unmet gates rather than claiming
  them from a chat acknowledgement.

- Modified files in this increment are
  `src/main/java/dev/mcai/companion/skills/foundation/PrepareIronToolkitSkill.java`,
  `src/test/java/dev/mcai/companion/skills/foundation/PrepareIronToolkitSkillTest.java`,
  `docs/PROFESSIONAL_COMPANION_MATRIX.md`,
  `docs/CAPABILITY_MATRIX.md`, `docs/verification/M3.md`,
  `docs/IMPLEMENTATION_STATUS.md`, and
  `docs/progress/GOAL_STATE.json`. Focused JUnit is the next completed gate;
  the last formal failure remains the natural dynamic End dragon target
  acquisition gate, and M0--M4 remain `NOT_RUN`.

- Current follow-up modified files are
  `src/main/java/dev/mcai/companion/perception/MenuSlotSummary.java`,
  `src/main/java/dev/mcai/companion/perception/PerceptionValidation.java`,
  `src/main/java/dev/mcai/companion/perception/FairPerceptionSampler.java`,
  `src/main/java/dev/mcai/companion/perception/SemanticObservationJsonCodec.java`,
  `src/main/java/dev/mcai/companion/skills/menu/MenuSkills.java`, the two
  focused perception/menu tests, and the English audit/status/changelog files.
  The semantic observation format advanced from 8 to 9 because each open
  slot now carries a role hint. The role is derived only from the open
  vanilla menu and never authorizes an unobserved or direct mutation.
  Focused tests passed after the source change; the next gate is the full
  Gradle test/build plus Forge menu GameTest run.

- Focused `PrepareIronToolkitSkillTest` and menu/perception tests passed, the
  full Gradle `test build`
  passed, the 65 Python protocol tests passed, and the 10/10 mutation gate
  passed. The rebuilt development JAR is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `7c7c2376a1337dea0deaf5f609239160f6d9b6a15a4652833b5b6a95a94ce523`.
  The Forge 65.0.0 `real_brewing_stand_batch` GameTest also passed with
  explicit assertions for all five open-menu role labels. The next action is
  to publish this exact staged snapshot and then continue with real
  Actor/Observer, farm commissioning, and machine repair gates.

- The broader `headless_player_lifecycle_state_and_fair_action` composite was
  re-run after the role changes but still failed later in its existing Nether
  portal phase with `build_and_light_nether_portal.visible_face_unavailable`.
  It did not fail in a menu transaction; the focused brewing, cartography,
  and stonecutter tests passed. This composite failure remains negative
  evidence for the portal/lifecycle gate and is not promoted or hidden.

- The public source snapshot for this increment is commit
  `600f37051765a578eb2b2bc595bd71b7188ed56f` and tree
  `4ce45565ae2996f063b3e205a25cd96d23f42c62`. All changed file blobs were
  re-read with a non-login shell and matched their local Git blob hashes before
  the `main` ref update; a later docs-only binding commit keeps the public
  release metadata synchronized. The public tree is English-only for
  documentation.

- The latest real MiMo iron-golem duel initially exposed a fixture defect: the
  model fairly bound a naturally spawned slime while the fixture activated the
  authored golem, so that death was not a valid golem-target result. The
  fixture now removes nearby ambient mobs and disables natural mob and monster
  spawning before the authored golem is exposed. A fresh rerun in
  `/tmp/minepilot-live-companion-golem-clean-1787419205` bound the only visible
  target (`minecraft:iron_golem`), raised the shield through the high-impact
  warmup, moved and exchanged vanilla damage, survived at 11.995 health, and
  passed in 26.35 seconds. This is controlled live-model evidence only; PVP,
  Hardcore, random-seed, rendered Actor/Observer, and formal M3/M4 gates remain
  unverified. Temporary diagnostic logging used during diagnosis was removed
  after the valid run.

- The last failed combat gate before the valid rerun was the same golem test
  with an ambient slime (`/tmp/minepilot-live-companion-golem-clean-1787419117`):
  the body bound the slime and was killed by the separately activated golem.
  This is retained as negative fixture evidence, not as a product combat score.

- Modified files for this recovery increment are
  `src/main/java/dev/mcai/companion/communication/LiveModelChatGameTests.java`,
  `src/main/java/dev/mcai/companion/skills/combat/EngageObservedEntitySkill.java`,
  `src/main/java/dev/mcai/companion/skills/core/EmergencySurvivalController.java`,
  and `src/test/java/dev/mcai/companion/skills/combat/EngageObservedEntitySkillTest.java`.
  The next gate is a clean focused JUnit/build pass followed by a fresh
  artifact-bound controlled companion run; no formal M0-M4 status is promoted.

- A fresh authorized Forge 65.1.1 MiMo `real_player_task_to_live_model_zombie_defense_stop_resume` slice passed in `/tmp/minepilot-live-combat-stop-resume-lscf7F`. Two ordinary embedded-player chat turns produced two HTTP-200 MiMo planner responses; each valid non-action response was safely recovered from the current fair Zombie observation into `engage_observed_entity`. The first skill started, the player sent `stop`, the body reached the safe cancellation checkpoint and emitted `skill_cancelled.engage_observed_entity`, then the same ServerPlayer UUID accepted the second chat and started a fresh combat skill bound to the new goal revision. SQLite recorded two `skill_started` events, two `model_response_received` events, and `low_level_actions_issued`; the Zombie was killed and `Monster Hunter` was granted. Planner usage was 15,625 input plus 298 output tokens (15,923 planner tokens; 22,401 including the two conversation requests), with 43,258 ms aggregate planner latency. This is the first strict controlled combat stop/resume pass, not PVP, random Hardcore, rendered Actor/Observer, long-soak, or formal M3/M4 evidence.

- The production brain now rebases only pure `CONTINUE`/`REPLAN` responses across a small observation drift. Skill-bearing or parameterized decisions remain strict except for the existing UUID-bound combat rebase. The new unit test covers this distinction; the strict live combat test requires an active skill bound to the resumed goal revision and therefore cannot pass from a stale terminal snapshot.

- A fresh authorized Forge 65.1.1 MiMo live-model stop/resume slice passed in
  `/tmp/minepilot-live-follow-stop-resume-1787344952`. One real embedded
  player chat produced HTTP-200 `START_SKILL follow_entity`; the same player
  then sent the local `stop` command while the skill was running. The body
  reached `SAFE_IDLE/goal_cancelled` at a safe checkpoint, emitted both
  `skill_cancelled` and `skill_cancelled.follow_entity`, and a second ordinary
  follow request produced another HTTP-200 `START_SKILL follow_entity` plus
  vanilla `low_level_actions_issued(action=move)`. SQLite recorded two model
  chains, 14,292 input and 356 output tokens (14,648 total), with 9,439 ms
  aggregate provider latency. This is controlled professional-companion
  evidence, not a rendered Actor/Observer, random Hardcore, long-soak, or
  formal M3 gate.

- A fresh authorized Forge 65.1.1 MiMo navigation stop/resume slice passed in
  `/tmp/minepilot-live-movement-stop-resume-1787345589`. A real embedded
  player request produced HTTP-200 `START_SKILL travel_to`; the player then
  sent `stop` while the skill was running. The body reached
  `SAFE_IDLE/goal_cancelled`, remained within the bounded stop envelope, and a
  second coordinate request produced another HTTP-200 `START_SKILL travel_to`
  and `low_level_actions_issued(action=move)`. SQLite recorded two model
  chains, 18,591 input and 504 output tokens (19,095 total), and 8,556 ms
  aggregate provider latency. This is controlled navigation evidence, not a
  rendered Actor/Observer, random Hardcore, long-soak, or formal M3 gate.

- A combat stop/resume attempt was intentionally not promoted: the real MiMo
  provider produced repeated `REPLAN` responses while the local emergency
  lane was guarding, and one later `START_SKILL engage_observed_entity`
  response was rejected by the fair `stale_world` binding after the body had
  changed during the long request. No combat stop, resume, damage, or victory
  claim was recorded. This is actionable negative evidence for the next
  combat-lifecycle fix, not a passing test. It is superseded only for the
  ordinary combat task by the bounded recovery result below; the stop/resume
  combat lifecycle remains unverified.

- A fresh authorized Forge 65.1.1 MiMo `real_player_task_to_live_model_zombie_defense`
  run passed after the combat recovery fix in
  `/tmp/minepilot-live-combat-recovery-1787415200`. The provider first returned
  a non-action response while the local emergency lane guarded; the server
  then copied one currently visible hostile observation into the normal
  `engage_observed_entity` skill, which started, moved, attacked, and killed
  the Zombie through vanilla actions. SQLite recorded one conversation chain,
  one planner chain (7,702 input and 172 output tokens),
  `combat_action_recovered_from_no_action`, `skill_started`, and
  `low_level_actions_issued`. The GameTest passed in 30 seconds. This is
  controlled professional-companion evidence, not combat stop/resume, PVP,
  random Hardcore, rendered Actor/Observer, or formal M3 evidence.

- A fresh authorized Forge 65.1.1 MiMo live-model run of
  `real_player_task_to_live_model_stronghold_portal_room_to_victory` passed
  its complete controlled causal chain. One ordinary player chat produced
  real model decisions for stronghold search, portal activation, End entry,
  dragon combat, and return. The production fight skill first rejected an
  invalid spawn-envelope pose in an earlier fixture attempt; the corrected
  fixture now establishes its explicit central End-stone rally before the
  first post-entry planner tick, while the natural ingress gate remains
  separate and strict. The passing run recorded six HTTP-200 model requests,
  50,248 input and 778 output tokens (51,026 total), ordinary movement and
  vanilla combat actions, the `Monster Hunter` and `Free the End` advancements,
  `DRAGON_KILLED` at game tick 5,022, and `RETURNED_FROM_END` at tick 5,467.
  The same ServerPlayer UUID completed the chain and the GameTest reported
  all required tests passed in 4m45s. This is controlled live-model evidence,
  not random-seed Hardcore, rendered Actor/Observer, or formal M2/M4 evidence.

- Current published patch: `FightEnderDragonSkill` now routes a natural
  centerward obsidian frontier through bounded fair island re-entry before a
  lateral detour, and `EndIslandIngressSkill` rejects breaking the observed
  support voxel under the body (including the corrected block/grid coordinate
  conversion). The test-only terrain diagnostic records the natural platform
  shape without being used by production decisions.
- Focused ingress and combat JUnit suites pass, and the fresh Forge
  65.0.0 `natural_end_island_ingress` selector passed in 5.835 seconds.
- The latest completed zero-human Forge 65.1.1
  `natural_end_dynamic_dragon_combat` gate remains `FAIL`: after 8,002 fight
  ticks the body reached approximately `(48.845,49.0,0.500)` and the dynamic
  ingress child completed once on a player-owned bridge with fresh body-contact
  support and two clear voxels. The fight then remained in `SEARCHING` with zero
  arrows and zero dragon damage because the vanilla manager dragon stayed about
  95.75 blocks away, beyond the 32-block semantic entity range. The unified
  re-entry-attempt budget prevented duplicate child creation; this is now a fair
  centerward-observation / target-acquisition gap, not a child-liveness loop. No
  teleport, command, hidden terrain read, dragon freeze, or fixture terrain
  mutation was used. This is the active release blocker; it is not M2/M4 or
  random-seed evidence.
- `EndIslandIngressSkill` now treats a failed fair `TowerUp` observation as a
  local route event: a same-frame, nearby, side-facing End-stone block is
  opened before another frontier probe, and a freshly observed lateral cell
  may be retried after the invalidated eye-line. This remains bounded by the
  existing mining, bridge, scan, child-failure, and timeout budgets; no hidden
  terrain is admitted. The focused ingress and combat suites pass. The fresh
  dynamic selector above is the required negative evidence for this change.
- The current local `build` passed after these combat and ingress changes. The
  resulting development artifact is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `7c7c2376a1337dea0deaf5f609239160f6d9b6a15a4652833b5b6a95a94ce523`.
- A diagnostic-only rerun with the obsidian-frontier re-entry completion
  target tightened to 30 blocks was reverted: it moved the body to about
  `(47.5,51.0,-0.3)` but the fair ingress child timed out after 6,000 ticks
  with 51 bridge steps and two tower steps. This confirms that the tighter
  target is not a safe standalone fix; it is not part of the published source
  or any pass claim.
- `EndIslandIngressSkill` now retains a bounded set of fairly observed
  landfall supports whose vanilla `TravelTo` child became stuck, excludes
  those cells from the next candidate search, and clears the set only after a
  real bridge, tower, mining, or completed-travel topology change. This is a
  local liveness guard, not a route oracle or world read. Focused ingress and
  dragon tests plus the full Gradle build pass with the change.
- A fresh natural dynamic run with that guard still fails honestly after
  8,002 fight ticks: body `(46.636,51.0,-1.503)`, ingress 29 observed mined
  blocks and 32 placed bridge blocks, 41 frontier probes, one failed landfall
  attempt, and `tower_up.overhead_clearance_unverified`; zero arrows, dragon
  damage, kill, or return. The guard prevents stale landfall re-selection but
  does not solve the natural pillar-edge route.
- The M3 professional-companion increment now derives player-visible
  lifecycle messages only from accepted server-owned skill transitions. It
  also accepts unambiguous `stop`/`hold`/`pause` chat as a
  local GoalCoordinator cancellation request, preserving permissions,
  Hardcore locks, and ordinary safe checkpoints. Focused JVM and source
  contract tests pass; no formal M3 status is promoted by this code-only
  change.
- A post-publication configured-model follow slice was attempted three times
  with the supplied process-only credential, but ForgeGradle failed before
  server startup at `slimeLauncherMetadataForForge` while resolving
  `runsJson` (`outputDirectory` queried before producer completion). No model
  request, server tick, or gameplay evidence was produced. This is retained
  as an infrastructure `NOT_RUN`, not a companion result.
- The ForgeGradle metadata blocker was resolved by pinning the known-good
  7.0.34 plugin instead of the floating 7.0.35 range. A fresh Forge 65.1.1
  configured-model follow slice then passed in
  `/tmp/minepilot-live-follow-fg7034`: real chat/task acceptance, MiMo
  `START_SKILL follow_entity` over Responses HTTP 200, schema/revision
  acceptance, `skill_started`, `low_level_actions_issued(action=move)`, and
  the server-owned lifecycle message were all recorded. The request used
  7,075 input and 177 output tokens (7,252 total) with 4,654 ms provider
  latency; this is controlled GameTest evidence, not rendered Actor/Observer
  or formal M3 evidence.
- The first real item-collection rerun exposed a genuine stale-observation
  failure: MiMo replanned repeatedly and then submitted an old
  `collect_observed_item` handle, which the local skill correctly rejected as
  `visible_dropped_item_required`; no movement or inventory claim was made.
  The recovery now performs one bounded four-sector fair survey after an
  explicit pickup request loses its first-person item frame, and also
  recovers a malformed item decision only from a unique current fair handle.
  Focused JVM tests pass. A fresh Forge 65.1.1 real-model rerun then recorded
  `REPLAN` → local `survey_surroundings` → MiMo `START_SKILL
  collect_observed_item` → `skill_started` → vanilla movement/pickup, with
  18,400 model tokens in 20.06 seconds. This is controlled companion evidence,
  not rendered Actor/Observer or formal M3 evidence.
- A short exploratory tower-recovery change was tested against the same fresh
  natural selector and was immediately reverted because it regressed the
  measured route: the body stopped near `(42.603,50.0,-4.500)`, the nested
  ingress ended with `reach_end_island.scan_budget_exhausted` after
  `travel_to.stuck`, and the dragon received zero damage. The run is retained
  as negative experiment evidence only; the reverted change is not part of the
  published source or any pass claim.
- A fresh authorized Forge 65.1.1 live-model movement slice passed in
  `run/gametestserver`: SQLite recorded the real chat/task chain, HTTP-200
  Responses output, 7,741 input and 194 output tokens, `START_SKILL
  travel_to`, `skill_started`, and ordinary `move` actions. The body reached
  the bounded target without teleport or command. This remains controlled
  GameTest evidence, not rendered Actor/Observer or formal M1--M4 evidence.
- A fresh authorized Forge 65.1.1 live-model parkour slice passed in 26.61
  seconds. The real model issued `START_SKILL parkour_to` three times: the
  first attempt failed `missed_landing`, the second failed
  `unexpected_fall`, and the third crossed the three one-block gaps. SQLite
  recorded three HTTP-200 Responses chains, schema/revision acceptance, skill
  starts, and ordinary movement actions; no teleport or command was used. This
  is controlled parkour retry evidence, not a rendered-client, PVP, Hardcore,
  random-seed, or M1--M4 gate.
- A fresh authorized Forge 65.1.1 live-model water-clutch slice passed in
  15.26 seconds. MiMo returned HTTP 200 with a schema/revision-accepted
  `REPLAN` for the Chinese fall-rescue request; the local emergency lane then
  equipped the owned water bucket, placed observed vanilla water in the End,
  and completed the twelve-block fall with exactly one vanilla bucket use,
  full health, and no death. This proves the fair emergency path plus a real
  model request, not a model-selected water skill or a rendered/random-world
  gate. The run recorded 11,588 model tokens and remains controlled evidence.
- A fresh authorized Forge 65.1.1 live-model farm-work slice passed in
  45.65 seconds. MiMo selected `harvest_and_replant_step` twice and then
  `maintain_observed_crop_field`; the body used ordinary observed crop mining
  and planting actions, and the server recorded `A Seedy Place`. SQLite
  recorded five gameplay planning requests and 40,964 total model tokens.
  This is bounded wheat-plot evidence only, not a long-term farm, random-world,
  rendered-client, or formal M1--M4 result.
- The late End-completion rerun now reaches the real model after fixing the
  initial-anchor chat race, but fails honestly at the portal handoff. MiMo
  returned `START_SKILL activate_observed_end_portal` three times; each start
  was rejected by the current safety/observation preconditions while ambient
  hostile pressure displaced the body. The supervisor entered
  `SAFE_IDLE/repeated_skill_rejection_without_world_change` at tick 2465 with
  zero active portal blocks, no End entry, and 25,927 recorded model tokens.
  This is a genuine negative model-to-safety result, not a fabricated action
  or a formal M2 claim.
- A fresh authorized Forge 65.1.1 run of
  `real_player_task_to_live_model_nether_materials_to_victory` exercised the
  real MiMo model through Nether preparation, Eye crafting, verified portal
  return, and stronghold triangulation. On the first portal-room request, the
  server fairly rejected `search_stronghold_portal_room.stronghold_evidence_required`.
  The next model request received a schema with that rejected action removed
  and selected `reach_observed_stronghold`, which issued ordinary movement and
  exposed a real stronghold structure. The route then encountered genuine
  `excavate_safe_tunnel.torch_place_target_occluded` and
  `search_stronghold_portal_room.search_exhausted` failures while continuing
  bounded visible-node exploration (120 visited, 61 exhausted at the last
  checkpoint). The run was stopped before a terminal result after 11 HTTP-200
  model requests (99,361 input, 1,419 output, 100,780 total tokens); no End
  entry, dragon kill, return, teleport, command, or hidden structure read was
  claimed. This validates the rejection-recovery guard as controlled live-model
  evidence, but remains incomplete and is not M2/M4 or random-seed evidence.
- `ExcavateSafeTunnelSkill` now treats a semantic torch-support face as turn
  guidance only and waits for the live centre crosshair to hit the same block
  and face before issuing vanilla `useOnBlock`. This closes the observed
  `torch_place_target_occluded` timing mismatch without widening interaction
  permissions. The focused excavation suite (including a stale-crosshair
  regression), planner suite, and brain suite pass; no live victory claim is
  attached to this fix yet.
- A fresh authorized Forge 65.1.1 live-model container slice also passed. The
  model first returned an invalid `use_block` envelope; local validation
  rejected it without an action, then the model corrected the decision,
  completed vanilla chest opening, and completed `transfer_menu_item` for
  three oak planks. SQLite recorded the failure/recovery and accepted causal
  chains; this remains controlled chest evidence, not the formal Inventory
  black-box gate.
- A fresh authorized Forge 65.1.1 live-model Zombie-defense slice passed. The
  emergency lane guarded the body while the model request crossed its soft
  deadline; the model then returned `START_SKILL engage_observed_entity`, the
  body moved and attacked through vanilla combat, survived, and triggered
  Monster Hunter. SQLite recorded the task/perception/HTTP-200/skill/action
  chain. This remains one controlled hostile, not PVP, Horde, Hardcore, or
  formal M2 evidence.
- A fresh authorized Forge 65.1.1 live-model ten-Zombie plus ten-Skeleton
  horde slice passed in 39.98 seconds. The real model accepted the Chinese
  protection request, selected `engage_observed_entity`, and SQLite recorded
  HTTP-200 Responses, schema/revision acceptance, `skill_started`, and
  `low_level_actions_issued`. The fixture observed 10 damaged targets out of
  20, body movement, and full body health. This is controlled multi-hostile
  evidence only; it is not human PVP, Hardcore, random-seed, or M4 evidence.
- Focused JUnit, full build, repository preflight, Git Data API publication,
  fresh-clone byte comparison, and fresh-clone Forge `compileJava` all passed
  for this snapshot. The dynamic natural-End gate remains recorded as failed;
  real client/model, Hardcore random-seed, and M1--M4 gates remain `NOT_RUN`
  or externally blocked.
- The current source snapshot is public `main` commit
  `664e99d1532657d10ee20308877c3cf33f30b35d`; the ingress source, this
  checkpoint, and the goal state match a fresh clone byte-for-byte; that clone
  parsed the goal JSON and passed Forge `compileJava`. The local source commit
  is `d6cfe14`; the controlled MiMo evidence is recorded above. Formal gates
  remain `NOT_RUN` or externally blocked.

- The current uncommitted combat correction contains three narrowly bounded
  fair-perception changes. `FightEnderDragonSkill` now treats a changed face
  on the same observed block as a valid reacquisition candidate, prioritizes
  a current-column overhead block only while the head cell is actually
  blocked, and permits a fresh observed lateral wall to be mined after head
  clearance. It also permits a fresh, supported, two-block-clear neighboring
  detour around an observed pillar when a strictly centerward cell is not
  available; the detour remains inside the 56-block arena radius and never
  reads hidden level state. The ingress target geometry and the 0.50 TravelTo
  arrival bound were corrected in the same patch.
- Focused `FightEnderDragonSkillTest`, dynamic-rally source-contract tests,
  and `EndIslandIngressSkillTest` pass. The Forge 65.1.1 physical selector
  `mcai_companion:natural_end_island_ingress` also passes in the current
  worktree.
- The latest natural dynamic run after the ingress recovery corrections is
  `/tmp/mcai-dynamic-end-reentry-budget.QENpTy`. It still fails honestly at
  8,002 fight ticks in `SEARCHING`: the body reached `(48.845,49.0,0.500)`,
  the one permitted re-entry completed with `reentryEligible=true`, and the
  re-entry budget then prevented duplicate child creation. The live dragon
  remained outside the 32-block semantic entity range, so zero arrows and
  zero dragon damage were recorded. This isolates the next gap to fair
  centerward observation/target reacquisition rather than ingress timeout or
  repeated-child liveness.
- The latest zero-human natural dynamic-dragon selector was run on Forge
  65.1.1 at 2026-08-22 00:55 JST and remains an honest failure. The body
  reached approximately `(49.752,49.0,0.498)`, mined 66 currently observed
  End-stone blocks, and performed seven observed rally starts; the live
  manager dragon remained loaded, but there were zero arrows, damage events,
  kill, or return. The terminal failure was
  `fight_ender_dragon.no_visible_combat_target` after 2,649 skill ticks. This
  is not dynamic-dragon, random-seed, Hardcore, speedrun, or M1--M4 evidence.
- No model credential was used by this physical gate, and no production
  teleport, command, hidden terrain read, dragon freeze, or fixture terrain
  mutation was introduced. The next release decision is therefore gated on
  full regression and a fresh review of this still-failing dynamic combat
  path; do not promote this patch to a release claim.

- Commit `cc877a4` records a `TowerUp` precondition rejection
  in `lastChildFailureCode`, allowing the already bounded frontier-probe path
  to run instead of silently retrying the same tower. Focused ingress/combat
  tests, the full JUnit suite, and the Forge 65.1.1 build passed after this
  one-line state fix.
- The completed natural dynamic-dragon rerun
  `/tmp/mcai-dynamic-dragon-towerfrontier-NnqCfv` still failed honestly at
  8,002 executed ticks with `reach_end_island.scan_budget_exhausted`; body
  `(47.433,51.0,-0.187)`, zero arrows, zero dragon damage, zero kill, and zero
  return. The latest frame showed only observed End-stone and obsidian faces,
  so no unsupported route was inferred. The state fix is published on public
  `main` as `fd20b5c52dc518b61e9fe2ba02f062764c225ae4` through Git Data API
  objects. Fresh clone `/tmp/minepilot-tower-state-OO07E9` matched the three
  changed file SHA-256 values byte-for-byte, its goal JSON parsed successfully,
  and Forge 65.1.1 `compileJava` passed.
- The current development JAR is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `ece574e8ca4b345d4ee2127bb72ad68d3163f10fbedd48a627c8341e964a1ac5`.

- Commit `882d3e5` makes observed side-step targets land near
  the far edge of the destination cell instead of allowing `BridgeToSkill` to
  complete inside its minimum arrival radius, and lets a fresh frontier probe
  try a visible wall attachment before ordinary side travel. The new edge
  target regression, focused ingress/combat suites, full JUnit suite, and Forge
  65.1.1 build passed. The natural ingress selector passed in
  `/tmp/mcai-natural-ingress-sidestep-w37BT4`.
- A fresh zero-human natural dynamic-dragon run after this correction remains
  a physical failure in `/tmp/mcai-dynamic-dragon-attachstep-YtWEcZ`: the
  bounded fight reached 8,002 executed ticks, body approximately
  `(47.433,51.0,-0.187)`, ingress ended with
  `reach_end_island.scan_budget_exhausted`, and there were zero arrows, dragon
  damage, kill, or return. The final fair frame exposed only observed
  End-stone/obsidian faces at the pillar edge; no safe destination was inferred.
  Dynamic combat and every formal M1--M4/random-Hardcore/speedrun claim remain
  unreleased.
- The current development JAR after this correction is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `d171284cb19242398cb9e2bfc408f277188a6d6eb0e7f9639d0af065a320a71f`.
  The correction is published on public `main` as
  `e2e1d009797d59404e0fa59fe4e7b69745295da6` through Git Data API objects.
  Fresh clone `/tmp/minepilot-side-step-J0hDs2` matched all four changed file
  SHA-256 values byte-for-byte, its goal JSON parsed successfully, and Forge
  65.1.1 `compileJava` passed.

- Commit `0f3ab2a` adds fresh observed rally targets,
  bounded sky-clearance target rebinding, observed lateral End-stone mining,
  safe side-step recovery before opaque pillar ascent, and a frontier probe
  after a failed tower child. It also records the nested ingress checkpoint so
  a failure remains diagnosable after the parent fight resumes. All movement,
  mining, looking, and block use remain ordinary `ServerPlayer`/vanilla skill
  actions authorized by fresh first-person semantic frames; no teleport,
  hidden terrain read, command, or fixture world mutation is used by the
  production controller.
- Focused ingress/combat tests, the full JUnit suite, and the Forge 65.1.1
  build passed after this patch. The fresh natural ingress selector
  `mcai_companion:natural_end_island_ingress` passed in isolated directory
  `/tmp/mcai-natural-ingress-posttower-HjoyRe` (`All 1 required tests passed`).
- The latest zero-human natural dynamic-dragon selector remains an honest
  failure in `/tmp/mcai-dynamic-dragon-sidestep-kY5x7c`: the body reached
  approximately `(47.629,51.0,-0.121)`, but the ingress child ended with
  `reach_end_island.scan_budget_exhausted` and the fight with
  `fight_ender_dragon.no_visible_combat_target` after 7,045 executed ticks.
  It recorded 17 rally starts and 96 observed sky blocks mined, but zero
  arrows, dragon damage, kill, or return. Dynamic dragon combat therefore
  remains a release blocker; no random-seed, Hardcore, two-hour, or M1--M4
  claim is made.
- The current release JAR is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `ca2a6076694b393c36413ae222a9abf93ed59172089949b04f76117cce784863`.
  The snapshot is published on public `main` as
  `b2294c825c942626a676d64b276f105667c836d5` through Git Data API objects.
  Fresh clone `/tmp/minepilot-public-7UJzUD` matched all four changed file
  SHA-256 values byte-for-byte, and its Forge 65.1.1 `compileJava` passed.
  `git diff --check` is clean after publication.

- Commit `f0301fc4a4f95ccc42d2eb68dde7ed8f41964e51` adds a narrowly scoped
  observed-wall attachment path to `BridgeToSkill`: only the End ingress
  caller enables it,
  only a first-person visible solid face may authorize the adjacent placement,
  and a newer semantic frame must prove the placed block is weight-bearing
  before crossing. It also adds bounded low/side frontier observations and a
  stable child-failure diagnostic. The commit is published as
  `05b0697cc1e9bbfca7f17ba4c4ab84b00a2f3a7a` through Git Data API objects;
  a fresh clone verified all five changed blob hashes and Forge 65.1.1
  `compileJava` passed. The release JAR built from this commit is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `cd7727d7527e8deb9fa582a954a98d175b5a1b6a33b5c071b3cc24afdde6553f`.
- Commit `828c7ea` adds a server-tick-safe mining handoff: End ingress now
  waits for a newer first-person frame before starting `BreakBlockSkill`, uses
  the tick-local crosshair hit point, and equips material before an observed
  wall attachment. Dragon sky-clearance retries remember one occluded target
  and exclude it from the next bounded scan. The focused bridge, ingress, and
  combat suites pass; the full JUnit suite and Forge 65.1.1 build also pass.
- A completed Forge 65.1.1 natural dynamic-dragon rerun after this change is
  `/tmp/mcai-dynamic-dragon-occlusion-skip-lRtQE2`. It still fails honestly at
  `fight_ender_dragon.no_visible_combat_target` after 48 bounded scans with
  `lastSkyFailure=break_block.action_target_occluded`, 30 sky blocks mined,
  zero arrows/damage/kill/return, and body `(47.84,49.0,0.59)`. This is not a
  release or speedrun result; the remaining gap is target reacquisition around
  the natural pillar/ceiling edge.
- The current development JAR after the full build is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `c84c63e3d5259d21cde2c5874aba3321e96aed4cdc8815a45f0c421b9f257fcb`.
- The commit is published on public `main` as `f530504557714f9b9eecb2c2f55b8daec26bfcb5`
  through Git Data API objects. A fresh clone verified seven changed file
  blob hashes byte-for-byte and passed Forge 65.1.1 `compileJava`.
- A fresh real Forge 65.1.1 natural dynamic-dragon run after the eight-pattern
  cardinal frontier probe was `/tmp/mcai-dynamic-dragon-cardinalprobe-njnr9C`.
  It failed at 2026-08-21 03:50:28 with the same honest result:
  `fight_ender_dragon.no_visible_combat_target`, ingress child
  `reach_end_island.timed_out`, 30 observed End-stone blocks mined, zero
  arrows/damage/kill/return, body approximately `(47.84,49.0,0.59)`, and
  `scanTurns=48`. The natural terrain slice showed an observed/persisting
  obsidian pillar frontier from x46 through x38 and End-stone beginning at
  x37. This is evidence that the remaining gap is a real pillar-edge route,
  not a missing model call.
- The subsequent attached-step run directory
  `/tmp/mcai-dynamic-dragon-attachedstep-OJNTER` was interrupted before a
  server result directory was created. It is `NOT_RUN` evidence and must not
  be counted as a pass. The attached-step implementation is included in the
  published commit, but the physical behavior still requires a later
  completed fresh gate.

- The latest End-fight commit adds fair sky-clearance recovery,
  bounded observed one-cell rally movement, and one bounded reuse of
  `EndIslandIngressSkill` when the dragon scan reaches an observed frontier.
  It uses only the existing `ServerPlayer` movement, mining, crouch/place, and
  fresh semantic-frame contracts; it does not teleport, inspect a level, or
  invent a bridge destination.
- Focused combat and rally-evidence tests pass after that patch. The latest
  real Forge 65.1.1 zero-human selector run was
  `/tmp/mcai-dynamic-dragon-reentry-result-xQ0M6S`; it failed honestly after
  2,311 fight ticks with `fight_ender_dragon.no_visible_combat_target`. The
  body advanced to approximately `(47.8,49.0,0.6)`, mined 30 observed
  End-stone blocks, consumed no arrows, and never produced a dragon damage
  event or kill/return. The fight-specific ingress completion radius is now
  32 blocks and the re-entry child ran once, ending with the bounded
  `lastIngressResult=failed:skill_failure`; natural dynamic dragon combat
  remains a release blocker and is not claimed as passed.
- The earlier two real runs remain negative evidence: the one-cell movement
  run ended at `(54.9,49.0,0.5)` with the sky budget exhausted; the prior
  unpatched run ended near `(55.8,49.0,0.5)` after four rally scans. These
  results show real physical progress but not a complete End fight.
- Commit `bf70b73f891ab816d21ca8110d198654cd3ea369` is published on the
  public `main` branch through Git Data API objects. A fresh clone verified
  all five changed blob hashes byte-for-byte and `compileJava` passed for
  Forge 65.1.1. The local release JAR remains
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `5e703f7945df7bdea2283033d9b36688a292609af20a017962c136935df69f67`.

- The latest Forge 65.1.1 natural End ingress rerun passed in isolated
  directory `/tmp/mcai-end-ingress-piTmLV`: `All 1 required tests passed` in
  2.519 minutes. It reached natural End-stone inside the ready radius using
  the production headless `ServerPlayer`, observed mining/bridge actions, and
  no teleport or hidden terrain access. The preceding run that stalled in
  `VERIFYING_CURRENT_SUPPORT` was stopped and the over-strict extra support
  wait was removed; the focused JUnit suite is green again.
- A new release-excluded natural dynamic-dragon combat selector was exercised
  three times on Forge 65.1.1. Vanilla manager-dragon presence/motion remains
  real and AI-enabled, but the production fight still fails honestly with
  `fight_ender_dragon.no_visible_combat_target` after four bounded rally scans;
  the latest failure ended near `(69.99,49.0,0.49)` with zero shots and a
  first-person frame showing a low natural End-stone ceiling. This is now the
  active P0 combat gap: the controller must mine/escape an observed overhead
  obstruction and reacquire the live dragon before any dragon-kill claim.
- Fight admission now requires the natural standing-cell evidence, then lets
  the production skill clear a bounded, freshly observed low roof before
  scanning for the dragon. Observed rally candidates are filtered to
  centerward progress; the latest run still found no damageable dragon after
  the bounded clearance and re-entry attempt. The focused
  `FightEnderDragonSkillTest`, ingress tests, and
  `EndIslandRallyEvidenceSourceContractTest` pass. The natural dynamic fight
  gate remains `FAILED`, not a release or speedrun result.

- The latest authorized real-model `mimo-v2.5` Ender-pearl reserve slice now
  passes on Forge 65.1.1 after the epoch fix below. The model returned
  `START_SKILL secure_ender_pearl_reserve`; the real ServerPlayer built the
  observed roof, killed and picked up Endermen, reached 14 pearls with full
  health, and Forge reported `All 1 required tests passed` in 3.094 minutes.
  SQLite recorded `conversation_task_accepted`, HTTP-200 model response,
  schema/revision acceptance, `skill_started`, and
  `low_level_actions_issued`. This is a bounded live-model acquisition slice,
  not random-world, Hardcore, speedrun, or M1-M4 evidence.
- The preceding run reached the same 14-pearl state but failed its final tick
  with `stale_world_revision`. The cause was an active skill's route milestone
  changing while the skill was still authorized; the observation provider
  released the frozen decision epoch on that ordinary progress change. Active
  skills now retain their bound epoch until completion, so a long skill cannot
  be rejected at the exact completion boundary. The regression is covered by
  the observation-provider source contract and the 25-field route-snapshot
  test.
- The source/test fix is published on public `main` as the validated
  production snapshot commit `50fb70bbbb0441b16e1456e5b96e6d2c08defdb5`
  with tree `66cf4c51c975d8c0f82e7cb8659c2b8e4fdf5736`; later docs-only
  commits carry the evidence updates. Fresh clones of the published line
  matched the production blobs and Forge 65.1.1 `compileJava` passed.

- The fair natural-End ingress gate now passes on both Forge 65.1.1 and
  Forge 65.0.0 in fresh isolated GameTest server directories. The gate uses a
  real vanilla End portal, zero human players, the production `ServerPlayer`
  skill path, fresh first-person semantic frames, ordinary observed mining,
  natural terrain, and no post-entry teleport or hidden terrain query in the
  production controller. The body reached the strict natural End-stone
  support/radius handoff in both runs. One seed had a continuous route and did
  not need a bridge, so the fixture accepts zero block placement only when no
  gap is encountered; a gap still requires ordinary owned block use.
- A fresh Forge 65.1.1 rerun of
  `mcai_companion:natural_end_island_ingress` also passed after the observed
  wall/ceiling recovery changes. It completed in 2.518 minutes from the
  isolated directory `/private/tmp/mcai-end-ingress-current.CKtNcl`, with
  `All 1 required tests passed`. This remains physical ingress and
  dynamic-presence evidence only; it is not a dragon-combat, random-seed,
  Hardcore, speedrun, or rendered-client result.
- The ingress budget was raised from 16/32 to a bounded 128 observed-block
  mining allowance after real natural terrain showed a continuous End-stone
  wall from the entry platform toward the central island. This remains an
  observed BreakBlock path with an ordinary iron pickaxe, not a world scan or
  teleport. The focused ingress, bridge, break, combat, route, and planner
  tests pass after this change.
- The immediate ingress production failure before the pass was
  `reach_end_island.mining_budget_exhausted` at radii 90.8, 83.96, and 67.71;
  those runs are retained as negative evidence. The final 65.1.1 and 65.0.0
  runs both report `All 1 required tests passed`.
- The release line is now `0.1.11-dev-mc26.2`. The clean Gradle `build`
  passed after the ingress, rally-evidence, loadout, and planner-guide
  changes. The release JAR is
  `build/libs/mcai_companion-0.1.11-dev-mc26.2.jar` with SHA-256
  `44d9a8013c16a96c227046cef233e51c4ce6affa54d5d360ce2775f3c914512b`.
- The validated production source tree is published on public `main` through
  the snapshot above. A fresh public clone verified the same production blobs,
  the previously truncated emergency-survival files, the new epoch/projection
  and observed-rally fixes, and `compileJava` on Forge 65.1.1. The repository
  remains `NON_RELEASE` because dynamic-dragon and formal M1--M4 gates are
  not complete.
- The natural End selector was extended with a strict dynamic-presence slice.
  A fresh Forge 65.1.1 zero-human run observed the vanilla manager dragon with
  `isNoAi=false`, a real displacement of 104.5 blocks in that run, and a
  recorded vanilla phase; the selector passed. This verifies only manager
  presence/motion. It does not claim dynamic dragon combat, crystal clearing,
  survival, return, random Hardcore, or two-hour completion.
- The dynamic dragon controller now has an observed rally search: after an
  empty sky sweep it may choose a fresh, low-danger standing cell from the
  current first-person navigation frame, bounded to the verified End radius,
  and must pass the ordinary `TravelTo` precondition before moving. A completed
  rally leg becomes the next bounded search anchor. Focused combat tests and
  the fair-source contract pass; a natural manager-dragon combat/kill/return
  gate remains `NOT_RUN`.
- The dynamic-presence documentation and release-excluded fixture are also
  published on `main` as commit `8edf819ba87c7a5624011a6a5b96ac11ae21af87`
  with tree `1e537f0ca53b4283ce2687014fb6b99b5342f528`; a fresh clone matched
  the local tree and compiled on Forge 65.1.1.
- The current combat-safety patch also classifies an active, visible
  ender-dragon breath cloud as a fair proximity threat; waiting or expired
  clouds remain neutral. The focused perception source-contract test passes;
  no hidden effect data or world mutation is used.
- A fresh authorized MiMo movement slice on Forge 65.1.1 passed in an
  isolated GameTest server. The SQLite chain was
  `conversation_task_accepted → model_request_started →
  model_response_received(HTTP 200) → decision_revision_accepted(START_SKILL
  travel_to) → skill_started → low_level_actions_issued(move)`, and the body
  reached its requested point with ordinary `ServerPlayer` movement. Forge
  reported `All 1 required tests passed` in 22.86 seconds; the model request
  itself completed in 7.136 seconds. This is controlled live evidence, not a
  rendered Actor/Observer or formal M1-M4 gate.
- The same provider's explicit multi-phase smoke test is retained as negative
  evidence: capability negotiation succeeded, but its real structured answer
  for `secure_ender_pearl_reserve` was `REPLAN` instead of the required
  `START_SKILL`. The assertion failed after 67.66 seconds, and no fallback
  action was invented.

- Root causes closed in the current worktree: the vanilla End fight bootstrap
  could retire a test dragon created before its legacy scan settled; a
  headless player could retain the same section coordinates across dimensions
  without refreshing the new level's ordinary player-tracking window; unsafe
  optional cage traversal terminated the whole dragon fight instead of
  reacquiring a visible dragon; and the continuous-route harness incorrectly
  required destruction of one optional crystal and one artificial bar even
  after vanilla had credited the dragon kill to the companion.
- Two additional production defects are closed in the current worktree. The
  emergency fall controller incorrectly treated the End as water-forbidden;
  it now rejects water only in the Nether and retains all observed-surface and
  vanilla-use gates. Dragon combat also used to enter ranged mode permanently
  after its first finite melee burst; four completed arrows now reopen one
  bounded melee window so a later perch can be used safely.
- Current implementation files include `AiPlayerSession`,
  `FightEnderDragonSkill`, and the controlled live/zero-human Forge GameTests.
  Focused JUnit coverage includes risky-cage recovery, dimension-aware session
  tracking, and Gradle selector wiring.
- The last failed live gate reached `Free the End` but the harness rejected the
  credited kill because of its over-constrained optional-obstacle assertion.
  That failure is retained as negative harness evidence.
- The corrected authorized `mimo-v2.5` run passed the complete controlled
  stronghold-room-to-return chain on Forge 65.1.1 in 6.188 minutes. The model
  selected normal skills for maze search, twelve-eye activation, End entry,
  dragon combat, and return. Vanilla recorded `The End?`, `Free the End`, and
  companion kill credit; route milestones recorded `DRAGON_KILLED` at game
  tick 4802 and `RETURNED_FROM_END` at game tick 7425. SQLite records the
  perception/request/HTTP-200/schema/revision/skill/action causal chain.
- A zero-human, same-section Overworld-to-Nether regression passes on Forge
  65.0.0 and 65.1.1. It verifies destination simulation, an outlying entity
  and scheduled block tick, and retirement of the old dimension's window
  without a test force-load at the destination.
- The exact current-tree Gradle clean/test/build, release-JAR inspection, and
  compatibility checker pass after the End safety corrections. The preceding
  evidence-integrity snapshot separately passed all 65 Python protocol tests
  and all ten mutation variants. The preceding 0.1.10 production JAR SHA-256
  was
  `79859cc6ce7d4ff1c7ec0bf16b13a7ef333c696ff9ff4f9ca863f7e4485cdac1`.
  The last published snapshot before these two focused corrections is public
  commit `11975c380c243aa331ac19edbb0e494d99663313`, exact tree
  `32c864eff3c26c0119ee23f62537b58ff9acac49`; its fresh clone compiled
  successfully. Formal
  Actor/Observer, random Hardcore, and M0--M4 statistical gates remain
  `NOT_RUN`.

## Latest root cause and evidence

The latest authorized `mimo-v2.5` live progression slices now include a
complete controlled M1 foundation route, a complete controlled Nether portal
handoff, and a complete End combat/return chain. Final evidence:

- Forge 65.1.1, Java 25, real-time GameTest server;
- model response was HTTP 200 and schema-valid `START_SKILL`;
- `skill_started` and real melee/bow low-level actions were observed;
- the body used a bounded first-person retreat of approximately eight ticks,
  kept the crystal inside the observed firing corridor, and remained on the
  constructed obsidian course;
- the model-selected fight skill destroyed the visible crystal and dragon with
  normal melee and bow actions; the route verifier recorded `DRAGON_KILLED` at
  game tick 2164;
- the same UUID then selected `find_and_enter_observed_portal`, physically
  entered the activated End return portal, and the verifier recorded
  `RETURNED_FROM_END` at game tick 2283;
- test `real_player_task_to_live_model_end_victory_and_return` passed with
  `All 1 required tests passed` in a 2.183-minute real-time GameTest run after
  the cross-dimension initial-anchor guard and the 16-block observed-portal
  approach policy were applied.

The run also reproduced and closed two lifecycle/skill defects: a first human
joining the overworld must not remove a body that is already in the End, and a
visible return portal at roughly ten blocks must be eligible for ordinary
approach. The model selected `fight_ender_dragon`, vanilla recorded `Monster
Hunter` and `Free the End`, the route verifier recorded `DRAGON_KILLED` and
`RETURNED_FROM_END`, and the stable body UUID returned to the overworld. The
earlier run that ended in `embodiment_failed` and the run that ended in
`portal_not_observed` remain failure evidence, not hidden successes.

The End scenario is controlled and bounded: the test-only End arena disables
ambient mob spawning after installing the dragon and crystal. The foundation
and portal scenarios are also bounded fixtures. They are evidence for live
model-to-vanilla causal chains, not random-seed or speedrun results.

The latest stronghold entry slice also passed after two real-model failures
exposed interaction bugs. The first failure came from an overly local final-eye
fallback that kept the body rotating at one station; the second came from
accepting a semantic hit in the 4.5-to-4.7 block range that vanilla correctly
rejected. The fallback was removed and the candidate distance was tightened to
4.45 blocks. Run `debug15` then passed with the real model: physical maze
search, dead-end backtracking, a second turn, four-station portal activation,
the full twelve-eye transaction, model-selected End entry, and the vanilla
`The End?` advancement. The SQLite causal order included four model
responses, four accepted skill starts, and low-level movement/use actions.
This remains a controlled fixture, not an M2 or speedrun result.

The next live End failure exposed a separate stale multipart index: after End
entry, `ServerLevel.dragonParts()` retained old collider objects with the same
UUIDs but positions 90--101 blocks from the live dragon root. The root-owned
`getSubEntities()` objects were 2--8 blocks away and fully loaded, yet the
old candidates consumed the fair FOV/LOS budget. Candidate collection now
replaces same-UUID indexed parts with the current root-owned parts, uses the
tracked dragon-fight UUID as a bounded fallback, and treats the canonical
dragon root as a hostile boss for finite candidate prioritization. A separate
standalone authorized MiMo End test passed dragon combat and the return portal
in 1.958 minutes. A fresh repeat after the anchor and portal policy fixes also
passed in 2.183 minutes. Those older full-route failures remain useful negative
evidence, but the current controlled stronghold-room-to-return chain now passes
as recorded in the current recovery state above.

The latest damage-focused patch also makes a shielded body perform one bounded
side-step after a directionless recent damage cue (such as indirect magic),
instead of remaining shield-only. This is covered by a regression test and was
present in the passing controlled full-route run; that single bounded pass is
still not a random-world survival statistic.

The latest live debugging found one concrete first-human lifecycle failure:
the body could be removed before a malformed or void-like test anchor had a
vanilla-safe placement, leaving no visible AI. `AiPlayerManager` now validates
the bounded `SafeCompanionSpawnLocator` result before removing the current
body; if no safe placement exists it keeps the authoritative body and claims
the one-time startup provenance. The production zero-human delayed-login
GameTest now passes with the body active before human login and safely present
after the login. Real `mimo-v2.5` movement, follow, surprise-zombie defense,
and golden-apple slices also pass on the patched line. The first movement run
before this guard remains recorded as a genuine failure (`body did not return
after initial-anchor relogin`), not silently upgraded.

The first fresh live combat rerun exposed a test-fixture defect rather than a
model or production combat result: the embedded test player was logged in at
the vanilla world origin. That invalid login anchor caused the legitimate
first-human lifecycle to remove the body before the chat packet reached the
model, so the run was stopped and recorded as a failure. The combat fixture
now logs the player in two blocks beside the live body through the normal
`ServerPlayer` login path. The rerun passed with the supplied `mimo-v2.5`
gateway in 18.80 seconds: Chinese chat acceptance, model response, schema and
revision audit, `engage_observed_entity`, `skill_started`, low-level movement
and attacks, and the vanilla `Monster Hunter` advancement were all observed.
This remains a controlled one-zombie live slice, not a PVP, horde, Hardcore,
random-seed, or M4 result.

The next real-model surprise-defense rerun then exposed a production safety
gap: a rear Zombie could deliver a fair, directional damage cue while the
emergency lane only swept its view and waited for the planner. The first run
(`run-live-surprise-20260814e`) killed the body at tick 251 and is retained as
a genuine failure. `EmergencySurvivalController` now takes one bounded,
sneak-protected step away from a recent damage direction after the first scan
tick, using only the fair source vector and freshly observed adjacent-cell
evidence. The same real `mimo-v2.5` scenario then passed in
`run-live-surprise-20260814f`: the body reacquired the Zombie, dispatched
ordinary vanilla attacks, survived, and triggered `Monster Hunter` before the
test ended. The model lane returned a replan during the pressure window; the
survival result is therefore evidence for emergency recovery plus a real
model request, not a claim that the model alone cleared the encounter.

The first bounded multi-hostile fixture exposed a harness anchoring defect:
`run-live-horde-20260814j` left the six targets at their pre-login positions
while the normal first-human anchor transaction moved the body, so the model
correctly reported that no hostile was visible and selected a survey replan.
The fixture now repositions those same entities around the authoritative body
after the anchor transaction. The latest rerun recreates the authored targets
after the replacement body is stable, discards ambient entities, requires the
model skill to be genuinely `RUNNING`, and starts target-health attribution at
that causal edge. The Chinese team request first produced a bounded model
replan, then HTTP-200 `START_SKILL(engage_observed_entity)`,
schema/revision acceptance, `skill_started`, and
`low_level_actions_issued` in SQLite; the server log recorded four of six
authored targets damaged, body movement, and full body health. The latest
clean run passed in 40.14 seconds. This is not a human PVP, Hardcore,
random-seed, or M4 protocol.

The ten-plus-ten extension initially exposed two test-only attribution defects:
target death flags and local/ambient damage were counted before a model skill
started, and the first-human relogin could unload the authored mobs. The
fixture now recreates ten Zombies and ten Skeletons after the body relogin,
requires `engage_observed_entity` to enter `RUNNING`, and compares health only
against a post-start baseline. The latest authorized `mimo-v2.5` run passed in
39.98 seconds: SQLite recorded the full model-to-skill-to-action chain, ten of
twenty authored targets were damaged (the fixture threshold is ten), the body
moved, and body health stayed full. The prior 39.70-second run with sixteen
damaged targets and the earlier 38.28-second run with all twenty damaged remain
retained controlled evidence.
This remains a controlled multi-hostile slice, not human PVP, Hardcore,
random-seed, or M4 evidence.

The first three live iron-golem attempts remain negative evidence: the model
returned `START_SKILL(engage_observed_entity)` and SQLite recorded a vanilla
`attack_entity` dispatch that damaged the golem, but the body did not receive
a verified incoming attack. The root cause was stale creative/invulnerability
state inherited by the test body, not a model decision. The latest authorized
run clears those abilities and asserts genuine survival before chat. It
passed in 12.15 seconds: SQLite recorded 7,736 input and 148 output tokens,
HTTP-200 Responses output, schema/revision acceptance, skill start, vanilla
attack dispatch, body movement, golem damage, a real golem attack, and body
survival. This is a controlled neutral-mob slice, not a human PVP or Hardcore
result. A preceding invocation passed a literal placeholder instead of a
credential and made zero requests; it is harness/configuration negative
evidence, not a combat result.

## Changes in the current worktree

- `AiPlayerSession`: refreshes the ordinary vanilla player chunk-tracking
  window when the `ServerLevel` changes even if the section coordinates do
  not.
- `FightEnderDragonSkill`: an unsafe optional cage tower now clears tentative
  traversal authority and resumes fair target scanning instead of terminating
  the complete fight.
- `LiveModelChatGameTests`: waits for vanilla End-fight settling, uses bounded
  loaded geometry for the controlled arena, allows the historically measured
  150-second combat window, and distinguishes credited physical dragon damage
  from optional crystal/bar tactics.
- `EmbodimentGameTests`: zero-human same-section cross-dimension simulation
  regression for the headless player's vanilla tracking window.
- `build.gradle`: supports the documented generic `test_selector`, preserves
  `live_model_selector` as an alias, and rejects conflicting values.
- `build.gradle`: disables caching only for ForgeGradle's merged main
  `compileJava` output. A clean build had reported `FROM-CACHE` without
  materializing `build/sourceSets/main`, causing `compileTestJava` to lose
  production classes; the same clean command now compiles main locally and
  passes.
- Public governance: current security version, an auditable 0.1.9 chronology,
  and an English Contributor Covenant-based code of conduct.

- `FairPerceptionSampler`: multipart Ender Dragon samples begin at a collider
  center and bounded horizontal points, with ordinary first-person LOS checks;
  stale same-UUID index entries are replaced by current root-owned parts,
  dragon roots and parts are prioritized as hostile candidates within the
  finite budget, and sibling colliders publish once as one semantic parent.
- `EmergencySurvivalController`: a recent directionless damage cue keeps a
  shield lease but adds a bounded alternating side-step, preserving ordinary
  collision and fall rules instead of allowing repeated magic damage to become
  a stationary guard loop.
- `FightEnderDragonSkill`: bounded 24-swing melee burst, eight-tick ranged
  retreat, projectile/damage evasion, crystal firing-lane recovery, and
  transient child-shot failure recovery.
- `SkillSupervisor`: exposes the last checkpoint for timeout diagnostics.
- `EmbodimentGameTests`: offline dragon window/arena diagnostics and 128-arrow
  baseline.
- `LiveModelChatGameTests`: live dragon/return evidence for visible entities,
  dragon parts, danger signals, revisions, arrows, checkpoint state, and the
  isolated no-ambient-mob End arena; diagnostics tolerate the intentional
  one-tick initial-anchor relogin gap without masking a real failure.
- `AiPlayerManager`: first-human initial anchoring preserves an active body
  across dimensions and retains a validated same-tick retry anchor; it never
  turns a gameplay portal transition into a hidden teleport.
- `LiveModelChatGameTests`: the live combat fixture now creates its embedded
  human at a safe position beside the companion, matching a real client login
  and preventing the test from fabricating an invalid origin anchor.
- `EmergencySurvivalController`: a recent directional damage cue now permits
  one bounded observed-cell separation before the next model round trip,
  closing the rear-hostile "look but do not move" death path without granting
  hidden target coordinates or direct world mutation.
- `EmergencySurvivalControllerTest`: regression coverage verifies the
  directional damage separation is issued on the second 20-TPS tick and uses
  a cautious vanilla movement input.
- `LiveModelChatGameTests`: the bounded horde fixture now reanchors its six
  existing hostile entities after the normal first-human login transaction, so
  fair semantic visibility and damage assertions refer to the same body.
- `LiveModelChatGameTests`: the live golem and horde fixtures now force
  survival mode, clear invulnerability and stale creative abilities, and fail
  closed unless the body is genuinely damageable before the model request.
- `LiveModelChatGameTests`: the multi-hostile fixtures recreate authored
  targets after the normal first-human body relogin, require the combat skill
  to be `RUNNING`, and attribute damage only against a post-start health
  baseline. This prevents speech-only, ambient, and target-death flags from
  becoming false combat passes.
- `MinecraftPlannerInputFactory`: explicit combat goals now receive a compact
  hostile-only target playbook that forbids selecting dropped items or passive
  entities and requires immediate `START_SKILL` when a legal hostile is in the
  current fair frame.
- `EngageObservedEntitySkill`: the fair target gate accepts canonical vanilla
  hostile types even when a derived hostile bit is absent, while continuing to
  reject passive mobs, projectiles, and dropped items.
- `LiveModelHordeFixtureSourceContractTest` and planner/combat unit tests:
  regression coverage for the ten-plus-ten registration, hostile-only prompt,
  canonical hostile validation, and post-start damage attribution.
- `EmbodimentGameTests`: the physical neutral-golem regression now exercises
  the same delayed `NoAI` to vanilla-AI target activation lifecycle.
- `PortalSkillPolicy`: the default observed-portal approach distance now uses
  the full fair 16-block perception budget, while entry still requires a
  current first-person visible face and vanilla reach.
- `EndCrystalStandOffPlanner`: a bounded 20-block observed lateral firing-lane
  search; the live fixture now places the test crystal off the dragon's direct
  centerline so the scenario can verify crystal priority without an impossible
  static occlusion.
- `PrepareFoundationShelterMaterialsSkill`: remembered-table aiming now has a
  short bounded alignment window and reacquires the table through ordinary
  movement instead of remaining in a stale crosshair loop.
- `BuildShelterStepSkill`: roof recovery opens a closed shelter door only via
  the normal first-person use action when it occludes a pending roof face, and
  resets one exhausted exterior fallback marker so a final roof corner can be
  reached from the observed apron without a permanent interior retry loop.
- `SearchObservedStrongholdPortalRoomSkill`: bounded inherited interior
  confidence, fair frontier diagnostics, station yaw derived from actual
  travel, and no walking from a first-person view without observed floor
  support. The live harness keeps the chat sender connected until the initial
  anchor transaction settles and places it in the safe locator's authored
  corridor.
- `ActivateObservedEndPortalSkill`: stale target outcomes are discarded rather
  than replayed, final-eye movement uses the normal station route, and the
  semantic candidate range fails closed below the survival block interaction
  range. This prevents the observed "staring but doing nothing" loop while
  preserving vanilla crosshair, reach, and inventory validation.
- `FairPerceptionSupportSourceContractTest`, `FightEnderDragonSkillTest`, and
  `EmergencySurvivalControllerTest`: regression coverage for collider-center,
  transient shot failures, bounded retreat, and End fall recovery.
- English-only public project documentation and a compact evidence status.

## Recovery turn: live inventory round-trip

- Root cause of the first controlled failure: the provider returned valid
  non-action responses for a visible container handoff, but the final route
  phase filter removed the narrow `use_block`/`transfer_menu_item` skills.
  A second failure exposed that the owned inventory is encoded under the fair
  semantic root `self`, not `body`.
- Source changes in this turn: bounded optional-speech truncation for valid
  action envelopes; final re-application of the current visible-container
  handoff after route filtering; fair recovery for open-container withdrawal;
  fair owned-item drop recovery; final-schema narrowing to `drop_item` when
  the current `self.inventory` proves an explicit item/count; and a real
  round-trip extension of the canonical live item-collection GameTest. The
  standalone duplicate test was removed so the selector has one authoritative
  gate.
- Negative evidence retained: round-trip attempts 1--4 failed because the
  selector did not resolve the duplicate test or the human chat connection
  closed before the second goal was installed; attempt 5 installed the drop
  goal but did not start the action while the recovery parser still read the
  wrong semantic root; attempt 6 installed the goal but remained no-action
  until the controlled recovery path was corrected.
- Real evidence now available: attempt 7 used Forge 65.1.1, the configured
  MiMo `mimo-v2.5` gateway, a headless `ServerPlayer`, and ordinary player
  chat. The model first selected `collect_observed_item`, the body acquired
  exactly three oak logs through vanilla pickup, a second human chat requested
  immediate disposal, and the model selected `START_SKILL drop_item` with
  `minecraft:oak_log` count 3. The normal inventory-menu THROW path produced
  a live `ItemEntity` containing the three logs. Forge reported `All 1
  required tests passed` in 41.79 seconds. This is a controlled live-model
  slice, not a formal M1--M4, random-seed, Hardcore, rendered-client, PVP, or
  speedrun result.
- Last failed gate before the pass: `/tmp/minepilot-live-inventory-roundtrip-6.log`
  (controlled no-action diagnosis). Passing evidence:
  `/tmp/minepilot-live-inventory-roundtrip-8.log` (26.08 seconds, direct
  model `collect_observed_item` followed by `drop_item`). Credentials were
  process only and were not written to source, world data, SQLite, or
  documentation.
- Follow-up player-gift evidence deliberately used a real logged-in
  `ServerPlayer.drop` instead of an authored item entity. Its first run
  failed with a physical diagnostic of `ownedLogs=3` but no `drop_item`
  start: the recovery parser accepted the test fixture spelling `item` while
  production `InventoryItemSummary` encodes `itemId`. The parser now accepts
  the canonical `itemId` field (and retains the bounded legacy spelling).
  The fixed Forge 65.1.1 MiMo run selected collection, verified the three-log
  inventory delta, selected `drop_item`, verified the inventory reached zero,
  and observed a live three-log item entity. This is action evidence, not
  speech evidence.
- The exact Forge 65.0.8 line used by the configured local XMCL instance also
  passed that same real-model player-gift round trip in 16.25 seconds. The
  companion selected `collect_observed_item`, then `drop_item`; the gate
  asserted the resulting live inventory and `ItemEntity` state rather than
  its chat. The release JAR was then installed as the sole active companion
  JAR in the requested instance. The prior 0.1.3 JAR was moved to the
  instance's disabled-mod backup directory. The endpoint/model are in the
  non-secret Forge config and the API credential was replaced in the macOS
  Keychain after a newline-safe digest check. No API value is recorded here.

## Last completed checks

```text
FightEnderDragonSkillTest                         PASS
FairPerception focused tests                      PASS
Offline End/dragon/return physical baseline       PASS (no model)
Live MiMo standalone End victory and return      PASS (controlled real-model chain; Forge 65.1.1, 2.183 min)
Live MiMo movement, follow, surprise defense, food PASS (four controlled live-model slices)
Live MiMo container withdrawal and item collection PASS (latest item recovery rerun; two controlled live-model slices)
Live MiMo inventory pickup then immediate drop PASS (26.08-second direct-model round trip; vanilla THROW entity and inventory delta)
Forge 65.0.0 live inventory round trip                 PASS (25.16-second minimum-line patch slice)
Forge 65.1.2 live inventory round trip                 PASS (30.23-second latest-patch slice)
Live MiMo player-gift pickup then immediate drop    PASS (18.70-second fixed run; vanilla gift, inventory delta, and live ItemEntity)
Live MiMo foundation bootstrap and shelter       PASS (8.912-minute controlled slice; isolated SQLite)
Live MiMo Nether portal build and entry          PASS (52.17-second controlled slice; isolated SQLite)
Live MiMo stronghold search, portal activation, End entry PASS (controlled real-model prefix)
Live MiMo stronghold-room-to-return chain        PASS (6.188-minute controlled real-model chain; not random Hardcore evidence)
Live MiMo End return after anchor/portal fixes   PASS (model fight, vanilla return portal, stable UUID; bounded fixture)
Live MiMo movement causal chain                  PASS (12.26-second real-time slice; START_SKILL travel_to and vanilla move)
Live MiMo follow causal chain                    PASS (12.66-second real-time slice; ordinary unaddressed Chinese chat, START_SKILL follow_entity, vanilla move)
Live MiMo surprise-zombie defense (pre-fix)     FAIL (12.69-second slice; rear hostile killed body while scanning)
Live MiMo surprise-zombie defense (patched)     PASS (15.27-second real-time slice; bounded damage separation and vanilla counterattack)
Live MiMo golden-apple consumption               PASS (15.26-second real-time slice; vanilla item-use consumption)
Live MiMo Chinese combat task                    PASS (18.80-second real-time slice; engage_observed_entity, vanilla attack, Monster Hunter)
Delayed first-human anchor lifecycle             PASS (production zero-human startup GameTest; no rendered-client claim)
Delayed first-human anchor during emergency      PASS (Forge 65.1.1 production GameTest; deferred relogin after survival clears)
Initial-anchor no-safe-placement guard           PASS (focused source contract and patched live movement slice)
Live MiMo directionless-damage shield regression PASS (offline JUnit; real stronghold effectiveness NOT_PROVEN)
Live MiMo directional-damage separation         PASS (real 15.27-second slice; model request plus emergency recovery)
Live MiMo bounded hostile group                  PASS (40.14-second slice; six visible mobs, model engage RUNNING, 4 damaged, body moved/alive)
Live MiMo ten-plus-ten hostile group             PASS (39.70-second slice; 20 visible mobs, model engage RUNNING, 16 damaged, body moved/alive; threshold 10)
Live MiMo iron-golem duel                         PASS (12.15-second bounded slice; 7,736 input/148 output tokens; model engage, vanilla attack, golem damage, verified incoming golem attack, body survival)
Live MiMo water clutch                             PASS (15.26-second bounded slice; HTTP-200 REPLAN plus local emergency water placement, exactly one vanilla bucket use, no fall damage; 11,588 model tokens)
Live MiMo farm work                                PASS (45.65-second bounded slice; two harvest/replant skills plus observed crop-field maintenance, A Seedy Place, 40,964 model tokens)
Live MiMo late End completion handoff              FAIL (137-second bounded slice; three real portal-activation decisions rejected under hostile pressure, SAFE_IDLE, 25,927 model tokens; no End entry)
Roof-jump physical recovery contract             PASS (no-model GameTest)
Focused shelter-material/building JUnit tests    PASS
Exact Forge 65.1.1 dedicated lifecycle smoke      PASS (real dedicated server; no functional claim)
Exact Forge 65.1.1 two-boot persistence smoke     PASS (real restart; no functional claim)
Delayed first-human anchor client smoke           NOT_RUN (macOS lacks Linux/Xvfb; no client claim)
Zero-human same-section cross-dimension simulation PASS (Forge 65.0.0 and 65.1.1; no fixture force-load at destination)
Full current Gradle/JUnit, release jar, compatibility, and Python audit     PASS (63 Python tests; artifact SHA recorded above)
Formal evidence nonce-binding regressions          PASS (65 Python tests; foreign/missing/mixed-run evidence rejected)
Prepared manifest request-nonce binding             PASS (`BINDINGCHECK`; no model credential persisted)
Clean cached Gradle release gate                    PASS after merged-main cache guard
Formal Actor/Observer client gate                 NOT_RUN
Hidden random Hardcore M1/M2/M4 gates             NOT_RUN
M0/M1/M2/M3/M4 product milestones                 NOT_RUN
```

The offline baseline proves only a controlled vanilla physical lower bound. It
does not substitute for a live model, rendered client, natural world, or hidden
seed statistic.

## Immediate next steps

### Current deployment checkpoint

- **Root cause fixed:** the owned-item recovery handoff parsed fixture-only
  `item` while production perception serializes `itemId`; it now reads the
  canonical field with a bounded legacy fallback.
- **Changed files:** `CompanionRuntime`, `LiveModelChatGameTests`, live-run
  records, release notes, goal state, and this checkpoint (commit `815e60c`).
- **Last failed gate:** the formal rendered Actor/Observer client gate remains
  `NOT_RUN`; controlled embedded-player evidence does not satisfy it.
- **Next action:** user launches the installed instance and verifies the first
  real client world; collect its log and reproduce any observed physical
  non-action before declaring the deployment usable.

1. Preserve the closed formal-evidence nonce boundary: the manifest now owns
   the externally expected nonce and both functional and delayed-anchor
   verifiers bind every Actor, Observer, Oracle, and Oracle-result event to it.
   Missing, mixed, and internally consistent foreign bundles fail closed.
2. Preserve all live progression results as controlled evidence; do not
   promote them to a random-seed or speedrun claim. The last formal-client
   preflight remains `NOT_RUN` because this macOS host lacks Linux/Xvfb; the
   next local verification command is the focused Python orchestrator suite.
3. Run the formal Actor/Observer client gate only on an authorized Linux/Xvfb
   worker with the exact frozen JAR, then extend the controlled chain into a
   natural dynamic-dragon world and execute hidden-seed protocols only after
   their exact artifact and worker prerequisites are satisfied.

## Repository and release state

- Latest verified public `main` backup: commit
  `11cdc5c9b1d4d95d03a558ba8d9af38900d8be5c`, tree
  `5338c883c17c0607f5c347d2729c07bfa4b4d1d9`. The nine changed source,
  test, and evidence files in this recovery match their local Git blob hashes
  exactly. The public history includes two repair commits because an initial
  connector upload accidentally included a shell-prefix/base64 payload; the
  current tree is corrected and verified, and no secret was published.

- Public repository: `https://github.com/weidakuang/MinePilot`.
- Public `main` before repair: `66bb2cff9362451fb08daf6fbf330a5d1058d589`.
- Verified repair commit: `5edcdc7f7d37e91b1466fff460b571333c8312db`;
  exact source tree: `e9ec6c1fab68dcbd04b9fa14063e9072bb6e44b1`.
- Forge 65.x backup branch: `mc26.2-forge65`.
- Local branch: `main`.
- Public `main` is again a valid source backup. The two large
  emergency-survival files were restored with full Git blob/tree objects;
  byte counts and blob hashes match the local tree, and a fresh public clone
  passed Gradle test/build/JAR/compatibility verification. Future connector
  publications must keep using blob/tree objects for large files. Generated
  run directories remain disposable and must not be staged.
- API keys are process-only during live tests and are never written here.
- Current local verification commit (`test: verify live inventory round trip`)
  is not yet published. A normal push was attempted after preflight, but
  this worker has no usable GitHub credential: its configured helper points to
  the missing `/opt/homebrew/bin/gh`. The remote `main` was not changed and
  no force push was attempted; publish remains pending authenticated GitHub
  access.
- The latest exact-blob repair snapshot is public `main` commit
  `1d1c75173c475470dde808582e4b07d70f1a7254`; a fresh clone matched all
  seven changed source, test, and evidence files byte-for-byte, parsed the
  goal JSON, and passed Forge 65.1.1 `compileJava`. The immediately
  preceding publication was repaired after a shell-prefix upload corruption;
  the repaired blobs now match local Git object hashes exactly.

## 2026-08-24 Headless Grok 4.5 black-box checkpoint

- Scope: Minecraft 26.2, Forge 65.0.9, a fresh hard-survival dedicated server
  launched with `nogui`, and one ordinary embedded observer submitting a real
  `ServerboundChatPacket`. No Minecraft client window was opened. The external
  pass oracle was restricted to chat packets, companion pose/health,
  inventory/hotbar, and nearby-world state written by
  `HeadlessBlackboxModule`; product audit and SQLite rows were used only after
  a failed run to diagnose it.
- World 5 supplied partial physical evidence: after the Chinese wood request,
  the companion moved from approximately `(49.5,81,78.5)` to
  `(64.5,82,80.5)` and its real inventory contained twelve spruce logs. The
  overall goal still failed because the planner started
  `gather_nearby_wood` repeatedly instead of accepting the first verified
  completion. `MinecraftPlannerInputFactory` now exposes no follow-up skill
  schema after a trusted terminal `COMPLETED` result and requires exact
  inventory-based completion. This boundary is covered by a focused unit
  test but has not yet passed a fresh black-box run.
- World 6 failed. The companion spawned beside several Drowned, remained
  alive at full health, and the visible hostiles eventually disappeared, but
  it never began the requested wood skill before shutdown. Initial inspection
  incorrectly suspected a permanent emergency-controller lease. The actual
  internal timeline shows the alternating recent-damage scan ended after
  roughly sixteen seconds; the high-level Grok 4.5 request remained pending
  for the rest of the run. The server stopped about 89 seconds after
  `model_request_started`, just before the configured 90-second hard deadline,
  so that run does not test timeout recovery. No completion or skill-start row
  exists for the request.
- The existing `BrainOrchestrator` timeout path cancels only the timed-out
  request, leaves the goal running, schedules provider-outage backoff, and
  retries. Its focused regression passes, but this behavior is not yet proven
  in the real background server with Grok 4.5.
- Files changed in the current black-box increment include
  `src/main/java/dev/mcai/companion/blackbox/HeadlessBlackboxModule.java`,
  `src/main/java/dev/mcai/companion/model/ReasoningControl.java`,
  `src/main/java/dev/mcai/companion/model/ModelRequestFactory.java`,
  `src/main/java/dev/mcai/companion/model/JdkProviderCapabilityProbe.java`,
  `src/main/java/dev/mcai/companion/modelsetup/ModelProfileStore.java`,
  `src/main/java/dev/mcai/companion/skills/gathering/GatherNearbyWoodSkill.java`,
  `src/main/java/dev/mcai/companion/skills/gathering/ResourceGatheringSkills.java`,
  `src/main/java/dev/mcai/companion/runtime/MinecraftPlannerInputFactory.java`,
  `src/main/java/dev/mcai/companion/embodiment/SafeCompanionSpawnLocator.java`,
  and `src/main/java/dev/mcai/companion/communication/PlayerTaskIntent.java`,
  together with their focused tests.
- Last gate: focused planner, intent, capability-probe, and profile-store tests
  passed, and the development JAR built. World 6 remains a failed black-box
  gate; it does not promote any M0-M4 status.
- Next action: run a fresh random world for longer than the 90-second hard
  deadline, verify timeout/retry from external behavior if the provider hangs,
  and require the same run to physically gather wood and stop after the first
  verified completion. If it fails, preserve the external trace, diagnose only
  afterward, and fix the concrete causal boundary before expanding scope.
- World 7 crossed the real deadline and remained a failed run. Its first
  generation request was cancelled at 90 seconds, a second request started
  about two seconds later, and the body stayed at `(-147.5,79,-5.5)` with an
  empty inventory until shutdown. Independent minimal probes showed that the
  same valid credential and provider returned `/v1/models` in under one second
  and listed `grok-4.5`, while non-streaming Responses, streaming Responses,
  and Chat Completions generation calls each returned zero bytes before their
  45-second client deadlines. This was provider-generation unavailability at
  that time, not evidence that the product action path worked.
- The brain now gives the model its normal first opportunity, then has one
  narrowly scoped soft-deadline handoff for an explicit player-authored nearby
  wood task. It cancels only the stalled request and starts the no-argument
  `gather_nearby_wood` executor; it cannot invent a target, coordinate, route,
  or hidden observation. A successful compound result now closes that exact
  one-action player goal immediately because the skill's `COMPLETED` boundary
  already verifies a real owned-wood increase. A focused regression verifies
  one handoff, one skill start, server completion, and no second request.
- World 8 passed this one bounded black-box scenario with artifact SHA-256
  `ca87e853e8e6686967e150a63cadaca53b1538f8f18fc5d0a6735070cb778a75`.
  In this run the provider recovered before the soft deadline: Grok 4.5
  returned HTTP 200 in 6,997 ms and selected `gather_nearby_wood`. External
  observations show the body move from `(-0.5,76,-1.5)` to approximately
  `(-4.3,76,5.5)`, stay at 20 health, and change from an empty inventory to
  five birch logs. After the server-emitted completion status, ten consecutive
  one-second observations kept the same position and five-log inventory. The
  saved goal was `COMPLETED` with
  `server_verified_nearby_wood_complete`; no repeated skill or request began.
  This is evidence only for one chat-to-model-to-physical-wood action and its
  terminal boundary. It is not an M1, M2, M3, M4, Hardcore, combat, shelter,
  or two-hour-completion pass.

## 2026-08-24 demand-driven vision checkpoint

- Root cause: the initial screenshot response used one serverbound custom
  payload of up to 2.5 MiB, but Minecraft 26.2 limits serverbound custom
  payloads to 32,767 bytes. The provider probe's fixed 32 by 32 PNG also had
  an invalid CRC. Either defect could make source-level tests look healthy
  while the real path failed.
- The response protocol now uses 24 KiB chunks, bounded total size and chunk
  count, immutable metadata, duplicate/ordering handling, SHA-256 verification,
  timeout cleanup, and secret-buffer clearing. The corrected PNG passed the
  real Grok 4.5 Responses strict-schema plus image-input handshake through the
  configured gateway within the three-request budget.
- A fresh authenticated capture is now attached once to the next eligible
  planner request only when the negotiated model capability includes image
  input. Responses and Chat Completions use their respective documented image
  shapes; text-only models retain semantic perception.
- Changed production areas: model capability/profile/request construction,
  planner input lifecycle, observation request routing, vision capture service,
  chunk wire protocol, runtime wiring, documentation, and version metadata.
- Last gate: 1,236 JVM tests passed with zero failures and two skips; Forge
  65.x compatibility metadata, build, and release-JAR verification passed.
  The installed `0.1.13-dev-mc26.2` JAR has SHA-256
  `00847412895bafc4c55184caf504a1d3f46755c286b1030e1a5bcbd87a365eaa`.
  The XMCL profile selects Grok 4.5 and the credential remains in the OS
  secret store. No visible Minecraft client was launched.
- A real PNG rendered from Minecraft has not passed the isolated
  hidden-renderer gate, so screenshot capture and every M0--M4 milestone
  remain `NOT_RUN`. The next action is that off-screen end-to-end gate on an
  isolated renderer worker; the normal dedicated-server path remains `nogui`.

## 2026-08-24 exact distributed-log-storage checkpoint

- Root causes fixed during the gate: a quantified routed wood task could be
  closed by the old one-cluster shortcut; selected-slot wood was counted
  twice in gather checkpoints; a connected cluster could monopolize the body
  without pickup progress; a rejected last seed could oscillate with survey
  recovery; and the crafting actuator incorrectly treated recipe-book
  visibility as a survival crafting permission check.
- Changed areas include the goal route and terminal milestone, planner schema
  and prompt, conversation routing, foundation storage skill, wood recovery,
  vanilla inventory actuator, physical GameTests, and focused regressions.
  `PrepareDistributedLogStorageSkill` extends the production foundation path;
  it does not inject blocks or inventory contents.
- The recipe regression uses a real crafting-table menu with only eight oak
  planks and a locked chest recipe. Vanilla menu placement consumes the eight
  planks, produces one chest, and awards the recipe after the actual craft.
- The final live gate used Minecraft 26.2, Forge 65.0.9, Java 25, and the real
  configured Grok 4.5 endpoint. One Chinese player-chat request was repaired
  into `FOUNDATION/LOG_STORAGE_DISTRIBUTED`; the human then left. The physical
  oracle passed after 4.118 minutes with exactly 30 oak-log mining and pickup
  statistics, ordinary wood/stone tool recipes, four nonadjacent real chest
  block entities, no raw logs left in the body inventory, and all remaining
  logs balanced across the four chests with a maximum difference of one.
  Model speech and product audit events were not completion evidence.
- Last gate: `real_player_chat_to_live_model_distributed_log_storage` passed
  with `All 1 required tests passed`. The prior seven attempts remain negative
  evidence for the fixed routing, timeout, gathering, retry, and recipe-book
  defects.
- Next action: run the relevant JVM/Forge regression set and release build,
  then publish the exact source snapshot. This controlled fixture does not
  promote any random-world, Hardcore, rendered-client, M1, M2, M3, or M4 gate.
