# Controlled Live-Model Runs

This page records bounded development evidence for the `0.1.16-dev-mc26.2`
line. It is intentionally separate from the formal M0-M4 gates. The tests use
the real Java model gateway, a real Forge server tick loop, a real
`ServerPlayer`, and ordinary vanilla actions, but they do not use a random
Hardcore world or the rendered Actor/Observer protocol.

## Provider and privacy

- Models used by the recorded runs: `mimo-v2.5` and `grok-4.5`.
- Base URL host: `api.slomerex.xyz` (the runtime normalized the API prefix to
  `/v1`).
- Credentials were injected only through the process environment.
- No key, authorization header, prompt secret, or model response body is
  stored in this repository, world data, SQLite, or public evidence.
- Runtime chat and GameTest fixtures may contain Chinese; this public record
  remains English-only.
- The separate 2026-08-24 no-window natural-world follow slice used
  `grok-4.5` through the same normalized host. Its credential came from the
  OS credential store and was never written into the world or evidence file.

## Results

Unless a row records a different Forge patch, runs used Forge `65.1.1`;
all runs used Minecraft `26.2` and Java `25`. Each passing
test ran one case and exited cleanly with `All 1 required tests passed`; the
newest movement, follow, defense, food, combat, and delayed-login results are
recorded in the validated source tree snapshot
`664e99d1532657d10ee20308877c3cf33f30b35d`; later docs-only commits carry
the evidence updates. A fresh clone of the published line passes a Forge
65.1.1 `compileJava` build. They remain controlled
evidence, not release artifacts.

| Scenario | Model decision evidence | Vanilla-world evidence | Result |
| --- | --- | --- | --- |
| `real_player_chat_to_live_model_wood_and_stone_tools` (2026-08-24 relocated bounded gate) | One abstract Chinese player chat reached the real Grok 4.5 gateway and was encoded exactly as `FOUNDATION/STONE_TOOL_OBTAINED`; the player then left. | After every inventory, armor, offhand, and ender-chest slot was cleared and the body was relocated more than 64 blocks, it physically mined and picked up three oak logs and three cobblestone, used normal crafting, and retained a stone pickaxe. Vanilla awarded `Stone Age` and `Getting an Upgrade`. | PASS (36.04 seconds from the bounded live GameTest; Forge 65.0.9; controlled terrain, not a random world or formal M1--M4 evidence) |
| `real_player_chat_to_live_model_distributed_log_storage` (2026-08-24 exact storage gate) | One natural Chinese player-chat instruction reached the real Grok 4.5 gateway. Its first combined route token was rejected; the bounded repair returned canonical `FOUNDATION/LOG_STORAGE_DISTRIBUTED`, after which the human left and issued no further input. | From cleared inventory, equipment, offhand, and ender chest in a fresh controlled area, the body physically mined and picked up exactly 30 oak logs, crafted a wooden pickaxe and stone pickaxe through ordinary menus, mined stone, crafted and placed four nonadjacent chest block entities, and moved every remaining oak log into those four inventories with a maximum count difference of one. The oracle checked vanilla mining, pickup, and recipe statistics plus final inventory and world state; speech and internal completion events were not accepted as evidence. | PASS (4.118-minute controlled live GameTest; Forge 65.0.9; development failures retained as negative evidence). Later reruns exposed livestock-blocked workstation placement, an unbounded exploration child, and rolling-segment center docking. Their current-source physical regressions pass, but a fresh full-chain rerun is still pending because two subsequent Grok 4.5 capability probes timed out with zero inference bytes. This is not a random-world, Hardcore, rendered-client, or formal M1--M4 gate. |
| `real_player_chat_to_live_model_iron_acquisition` (2026-08-24 first attempt) | The same real model encoded an abstract empty-handed request exactly as `FOUNDATION/IRON_OBTAINED`. | The body acquired four logs and a stone pickaxe, then the old route incorrectly entered an unrelated food-reserve stage and stalled after physically collecting six food items. | FAIL; this exposed and removed `FOOD_SECURED` only from the explicitly bounded first-iron dependency chain. Full foundation/toolkit routes still retain food. No iron pass is claimed. |
| `real_player_chat_to_live_model_iron_acquisition` (2026-08-24 replacement attempts) | Two independent production conversation requests received zero provider bytes and reached the 90-second model hard timeout. A separate check reached `/v1/models` with HTTP 200 in 0.83 seconds, while minimal Grok 4.5 generation requests to both supported generation protocols timed out without a first byte. | No gameplay route was authorized and no physical iron action occurred. | FAIL before action because the configured provider generation path was unavailable; retained as negative evidence, not replaced with a scripted decision. |
| `real_player_chat_to_live_model_iron_acquisition` (2026-08-24 recovered endpoint) | Grok 4.5's first response produced a locally rejected combined route field; the bounded repair request returned canonical `FOUNDATION/IRON_OBTAINED`, then the embedded human left. | From cleared inventory, equipment, offhand, and ender chest in a relocated area, the body physically acquired five logs, crafted through the stone-pickaxe progression, mined coal and iron, picked up raw iron, retained iron material, and reached the server-verified `IRON_OBTAINED` milestone through `prepare_iron_toolkit`. The oracle required positive vanilla iron-mining and raw-iron-pickup statistic deltas rather than speech. | PASS (1.789 GameTest minutes, about 107 seconds; Forge 65.0.9; no human after the one abstract Chinese chat; controlled terrain, not a random world or formal M1--M4 evidence) |
| `real_player_task_to_live_model_movement` | `START_SKILL(travel_to)` | The body walked from the fixture start to the requested point; no teleport or command was used. | PASS (controlled live slice) |
| `real_player_task_to_live_model_movement` (2026-08-20 rerun) | SQLite: `conversation_task_accepted` → HTTP-200 model response → schema/revision acceptance → `START_SKILL(travel_to)` → `skill_started` → `low_level_actions_issued(action=move)` | The body reached the requested point through ordinary `ServerPlayer` movement; no teleport or command was used. | PASS (22.86-second current controlled live slice; model request 7.136 seconds) |
| `real_player_task_to_live_model_ender_pearl_reserve` (2026-08-20 rerun) | SQLite: `conversation_task_accepted` → `model_request_started` → HTTP-200 `model_response_received` → schema/revision acceptance → `START_SKILL(secure_ender_pearl_reserve)` → `skill_started` → `low_level_actions_issued` | The body built the observed Enderman roof, fought and picked up Endermen, reached 14 ender pearls with full health, and Forge reported `All 1 required tests passed`. | PASS (3.094-minute controlled live slice after the active-skill epoch fix; not random-world, Hardcore, speedrun, rendered-client, or formal M1-M4 evidence) |
| `real_player_task_to_live_model_follow` | An ordinary, unaddressed Chinese chat reached the real gateway; `START_SKILL(follow_entity)` was schema/revision accepted with an observed player target. | The body survived the initial-anchor relogin and followed the moving course through ordinary player movement. | PASS (12.66-second latest controlled live slice; no `@` prefix required in the one-human fixture) |
| `real_player_task_to_live_model_follow` (2026-08-22 ForgeGradle-pin rerun) | SQLite: `conversation_task_accepted` → HTTP-200 Responses `START_SKILL(follow_entity)` → schema/revision acceptance → `skill_started` → grounded lifecycle status. | The body survived the initial-anchor relogin, followed the moving course, and recorded `low_level_actions_issued(action=move)` through ordinary ServerPlayer movement. | PASS (14.56-second controlled live slice; 7,252 model tokens; no rendered Actor/Observer or formal M3 claim) |
| No-window natural-world moving follow (2026-08-24) | One ordinary player chat produced one HTTP-200 Grok 4.5 `START_SKILL(follow_entity)` in 9.928 seconds; no second decision or replan occurred. | On a fresh random Hard survival world, a background observer moved about 25 blocks through vanilla clientless input with turns and one-block elevation change. Across 32 one-second samples the companion moved continuously, stayed at 20 health, held a median 1.75-block separation, recovered from 5.02 blocks, and settled at 1.88 blocks. Evidence came from chat, coordinates, health, inventory/hotbar, and nearby world state; no Minecraft window was opened. | PASS for this bounded ordinary-terrain slice only (artifact SHA-256 `1a41b0718f0fcf1d97077e6fb735cf1e532be3111f89add4e62496fe31b8c1f3`; cliffs, long-distance pursuit, vehicles, portals, rendered screenshots, and formal M3 remain unverified) |
| `real_player_task_to_live_model_follow_stop_resume` (2026-08-22) | SQLite: first chat → HTTP-200 `START_SKILL(follow_entity)` → `skill_started`; local `stop` → `conversation_task_cancel_requested` → `skill_cancelled.follow_entity`; second chat → HTTP-200 `START_SKILL(follow_entity)` → `skill_started`. Two model chains used 14,648 tokens. | The body reached `SAFE_IDLE/goal_cancelled` at a server-owned checkpoint, remained controlled, then resumed follow through ordinary vanilla movement with `low_level_actions_issued(action=move)`. | PASS (19.80-second controlled live slice; no rendered Actor/Observer or formal M3 claim; `/tmp/minepilot-live-follow-stop-resume-1787344952`) |
| `real_player_task_to_live_model_movement_stop_resume` (2026-08-23) | SQLite: first coordinate chat → HTTP-200 `START_SKILL(travel_to)` → `skill_started`; local `stop` → `conversation_task_cancel_requested` → `skill_cancelled.travel_to`; second coordinate chat → HTTP-200 `START_SKILL(travel_to)` → `skill_started`. Two model chains used 19,095 tokens. | The body reached `SAFE_IDLE/goal_cancelled` at a bounded navigation checkpoint and then resumed ordinary movement, recording `low_level_actions_issued(action=move)` on the second travel skill. | PASS (21.56-second controlled live slice; no rendered Actor/Observer or formal M3 claim; `/tmp/minepilot-live-movement-stop-resume-1787345589`) |
| `real_player_task_to_live_model_zombie_defense_stop_resume` (attempt) | The provider returned repeated `REPLAN` responses while the local emergency lane was guarding; a later `START_SKILL(engage_observed_entity)` response was rejected by fair `stale_world` binding after body movement during the long request. | The stop/resume stage was never entered; no combat damage or victory was counted. | FAIL/NOT_RUN (negative diagnosis only; 32,121 model tokens; no lifecycle claim) |
| `real_player_task_to_live_model_zombie_defense_stop_resume` (2026-08-23 strict rerun) | Two ordinary embedded-player chats reached MiMo through the real gateway. Each planner response was a valid non-action response and was recovered only from the current fair visible-Zombie observation into `engage_observed_entity`; SQLite recorded two HTTP-200 responses, two `skill_started` events, and the stop cancellation chain. | The first vanilla combat skill ran, the player sent `stop`, the body reached the safe checkpoint, retained the same ServerPlayer UUID, and the second chat started a fresh combat skill bound to the new goal revision. The Zombie was killed and `Monster Hunter` was granted. | PASS (1m04s controlled live slice; 15,923 planner tokens, 22,401 total including conversation; not PVP, random Hardcore, rendered Actor/Observer, long-soak, or formal M3/M4 evidence; `/tmp/minepilot-live-combat-stop-resume-lscf7F`) |
| `real_player_task_to_live_model_zombie_defense` (2026-08-23 recovery rerun) | SQLite: conversation task accepted → HTTP-200 MiMo non-action response → bounded `combat_action_recovered_from_no_action` → `skill_started.engage_observed_entity`; the provider used 7,702 input and 172 output tokens for the planner chain. | The same real ServerPlayer used owned equipment, moved and attacked through the vanilla combat path, killed the visible Zombie, and triggered `Monster Hunter`; no teleport or command was used. | PASS (30-second controlled live slice; combat stop/resume, PVP, Hardcore, random-seed, rendered Actor/Observer, and formal M3 remain unverified; `/tmp/minepilot-live-combat-recovery-1787415200`) |
| `real_player_chat_to_surprise_zombie_defense` | `START_SKILL(engage_observed_entity)` after local damage/hostile observations | The emergency lane reacted before model completion and the body killed the visible zombie with normal combat actions. | PASS (controlled live slice) |
| `real_player_chat_to_surprise_zombie_defense` (directional-damage regression) | The real model issued a gameplay replan while the local survival lane remained active. | The first run killed the body while it only scanned; after the fix, a fair directional damage cue caused a bounded sneak separation, the body reacquired the Zombie, attacked through vanilla, survived, and triggered `Monster Hunter`. | PASS after fix (15.27-second controlled live slice; first run retained as FAIL) |
| `real_player_task_to_live_model_zombie_defense` (fresh rerun) | The Chinese chat reached the server, the model returned `START_SKILL(engage_observed_entity)`, and SQLite recorded schema validation, revision acceptance, and skill start. | The body rejoined through the normal first-human anchor lifecycle, used its owned iron equipment, moved/attacked through the vanilla combat lane, and triggered `Monster Hunter`; the exact event chain ended in `low_level_actions_issued`. | PASS (18.80-second controlled live slice) |
| `real_player_task_to_live_model_horde_defense` | The Chinese team request reached the real `mimo-v2.5` gateway; the first response replanned, then HTTP-200 `START_SKILL(engage_observed_entity)` passed schema/revision validation. SQLite recorded skill start and low-level action issuance. | After the normal first-human anchor transaction, the fixture recreated the authored targets after the body relogin and counted only health loss after the skill entered `RUNNING`; four of six Zombies/Skeletons were damaged, the body moved, and body health remained full. | PASS (40.14-second bounded six-mob live slice; earlier stale-anchor, ambient-mob, and target-attribution runs retained) |
| `real_player_task_to_live_model_ten_plus_ten_horde` | The Chinese request to protect the player from ten Zombies and ten Skeletons reached the real gateway; HTTP-200 `START_SKILL(engage_observed_entity)` passed schema/revision validation. SQLite recorded `skill_started` and `low_level_actions_issued(action=move)`. | The bounded arena recreated twenty authored targets after the body relogin; only damage after the model skill entered `RUNNING` counted. Sixteen of twenty targets were damaged, exceeding the fixture threshold of ten; the body moved and body health remained full. | PASS (39.70-second bounded twenty-mob live slice, 2026-08-18 UTC; not human PVP, Hardcore, random-seed, or M4 evidence) |
| `real_player_task_to_live_model_iron_golem_duel` (2026-08-23 clean-target rerun) | SQLite recorded one HTTP-200 MiMo response (`REPLAN` followed by bounded combat recovery), schema/revision acceptance, `skill_started`, and `low_level_actions_issued`; the only fair visible entity was `minecraft:iron_golem`. | The fixture cleared nearby ambient mobs, disabled natural spawning, and enforced survival abilities. The body raised its shield, moved through ordinary `ServerPlayer` actions, damaged the golem, received a real golem attack, and survived at 11.995 health. | PASS (26.35-second controlled live slice; `/tmp/minepilot-live-companion-golem-clean-1787419205`; not PVP, Hardcore, random-seed, rendered Actor/Observer, or formal M3/M4 evidence) |
| `real_player_chat_to_critical_golden_apple` | `START_SKILL(consume_owned_food)` for `golden_apple` | The body consumed the owned item through the ordinary use path; the scenario also verified the follow-up food-decision integrity. | PASS (controlled live slice) |
| `delayed_human_login_after_zero_human_active` | No model request; production zero-human startup and login lifecycle | The body became active before any human joined, then rejoined through the bounded initial-anchor lifecycle beside the later human without a gameplay teleport. | PASS (controlled physical GameTest) |
| `natural_end_island_ingress` (2026-08-20 Forge 65.1.1 rerun) | No model request; the production ingress skill was admitted from a real vanilla End portal and used only fresh fair frames. | Zero human players were present; the body handled the natural End entry wall/ceiling with ordinary observed mining and movement, reached natural End-stone support inside the arena-ready radius, and the server reported `All 1 required tests passed` in 2.518 minutes. | PASS (controlled physical ingress/presence slice; not dynamic combat, random Hardcore, speedrun, rendered-client, or formal M1-M4 evidence) |
| `real_player_task_to_live_model_end_victory_and_return` | `START_SKILL(fight_ender_dragon)` followed by `find_and_enter_observed_portal` | The body destroyed the observed End crystal/dragon, then entered the activated return portal with the same UUID after a first-human cross-dimension anchor guard. | PASS (2.183-minute controlled live slice) |
| `real_player_task_to_live_model_container_withdrawal` | An initial malformed `use_block` was rejected by local argument validation; the model then selected `use_block` and `transfer_menu_item`. | The body opened the real chest menu and transferred three oak planks through the vanilla menu path. | PASS (14.41-second controlled live slice) |
| `real_player_task_to_live_model_item_collection` | `START_SKILL(collect_observed_item)` for an observed item entity. | The body used the normal pickup path and the scenario verified the resulting inventory/stat delta. | PASS (16.86-second controlled live slice) |
| `real_player_task_to_live_model_item_collection` (2026-08-22 recovery rerun) | SQLite: HTTP-200 MiMo response `REPLAN` → one bounded local `survey_surroundings` recovery → HTTP-200 MiMo `START_SKILL(collect_observed_item)` → schema/revision acceptance → skill starts. The two requests recorded 18,400 model tokens. | The body reoriented through the fair four-sector survey, then walked with the vanilla `ServerPlayer` actuator to the observed oak-log drop; `low_level_actions_issued(action=move)` and the verified inventory/stat delta completed the pickup. | PASS (20.06-second controlled live slice; no rendered Actor/Observer or formal M3 claim; `/tmp/minepilot-live-item-collection-fg7034-recovery2`) |
| `real_player_task_to_live_model_item_collection` (2026-08-23 inventory round trip) | Two ordinary player-chat goals reached the live MiMo gateway. The first selected `START_SKILL(collect_observed_item)`; after pickup, the second explicit disposal goal was narrowed by the fair owned-inventory handoff and selected `START_SKILL(drop_item)` with `minecraft:oak_log` count 3. | The body physically approached and acquired exactly three oak logs, then the normal inventory THROW transaction removed the owned stack and produced a live three-log `ItemEntity`. No direct inventory or world write was used. | PASS (26.08-second controlled live slice; Forge 65.1.1; `/tmp/minepilot-live-inventory-roundtrip-8.log`; no rendered Actor/Observer, random Hardcore, PVP, speedrun, or formal M1--M4 claim) |
| `real_player_task_to_live_model_item_collection` (Forge 65.0.0 patch slice) | The minimum supported Forge 65.0.0 server accepted the same two ordinary MiMo chat goals and selected `collect_observed_item` followed by `drop_item` with the observed oak-log count. | Vanilla pickup and inventory THROW produced the expected owned-count decrease and live three-log item entity. | PASS (25.16-second controlled live slice; `/tmp/minepilot-live-inventory-roundtrip-forge6500.log`; patch-level evidence only) |
| `real_player_task_to_live_model_item_collection` (Forge 65.1.2 patch slice) | The latest published Forge 65.1.2 server accepted the same real model/schema/revision chain and selected `collect_observed_item` followed by `drop_item`. | Vanilla pickup and inventory THROW completed with the expected physical item entity and GameTest pass. | PASS (30.23-second controlled live slice; `/tmp/minepilot-live-inventory-roundtrip-forge6512.log`; patch-level evidence only) |
| `real_player_task_to_live_model_player_gift_round_trip` | A real embedded player used the vanilla `ServerPlayer.drop` path to give the companion exactly three oak logs; the live model then accepted the pickup request and a second immediate drop request. | The companion physically approached and picked up the gift, the fair inventory reached 3 logs, the normal inventory THROW removed all 3, and a live `ItemEntity` with 3 logs was observed. The first run exposed the production `item`/`itemId` semantic mismatch; after fixing it, the same real-model test passed. | PASS (18.70-second fixed controlled live slice; Forge 65.1.1; `run-live-player-gift-roundtrip-6511-fix1/logs/latest.log`; earlier mismatch failure retained as negative evidence; no formal M1--M4 claim) |
| `real_player_task_to_live_model_foundation_bootstrap` | Multiple accepted foundation skills, ending with `build_shelter_step`; the model re-planned roof placement after observed occlusion. | The body mined/crafted/organized the M1 materials, physically built the dynamic shelter, survived the configured night, and the SQLite audit recorded `skill_completed.build_shelter_step`, `low_level_actions_issued`, `server_verified_auto_complete`, and `goalStatus=COMPLETED`. | PASS (8.912-minute controlled live slice) |
| `real_player_task_to_live_model_nether_portal_build_and_entry` | `START_SKILL(build_and_light_nether_portal)` followed by `START_SKILL(find_and_enter_observed_portal)`. | The body built and lit the portal using the ordinary placement/use path, entered it, and the portal-entry skill completed. | PASS (52.17-second controlled live slice) |
| `real_player_task_to_live_model_stronghold_portal_room_and_entry` | Four accepted real-model decisions: `survey_surroundings`, `search_stronghold_portal_room`, `activate_observed_end_portal`, and `find_and_enter_observed_portal`. | The body traversed the authored maze, visited a dead end and a second turn, inserted all twelve eyes through ordinary crosshair use and inventory verification, created nine portal blocks, entered the End, and triggered the vanilla `The End?` advancement with the same body UUID. | PASS (`debug15`, 1.627-minute controlled live slice) |
| `real_player_task_to_live_model_stronghold_portal_room_to_victory` | One Chinese player task produced accepted `search_stronghold_portal_room`, `activate_observed_end_portal`, End-entry, `fight_ender_dragon`, and return-portal skill decisions. SQLite records HTTP-200 responses, schema/revision acceptance, skill starts, and low-level movement/use/attack actions. | The body completed the authored maze and twelve-eye transaction, entered the End, received vanilla dragon-kill credit, then physically entered the return portal. Route milestones recorded `DRAGON_KILLED` at tick 4802 and `RETURNED_FROM_END` at tick 7425. | PASS (6.188-minute controlled chain; static bounded End arena, not random Hardcore or a dynamic vanilla dragon fight) |

The explicit provider smoke test also produced negative evidence on the same
credential injection path: capability negotiation succeeded, but a valid
structured response for `secure_ender_pearl_reserve` was `REPLAN` rather than
the test's required `START_SKILL`. The test failed at that assertion after
67.66 seconds. The failure is retained as model-planning evidence; the runtime
did not fabricate a skill or mark the phase complete.

The first rerun of the Ender-pearl reserve slice reached the requested 14
pearls but failed at the final boundary with `stale_world_revision`. The route
milestone changed during the still-active atomic skill and incorrectly released
its frozen decision epoch. The production provider now keeps every active
skill's bound epoch until completion; the corrected rerun passed, and the
failure remains retained here as negative regression evidence.

## Superseded live failures that drove fixes

These runs remain recorded because a green build alone is not evidence of a
working progression route:

- The first real item-collection rerun after the ForgeGradle pin exposed a
  genuine stale-observation path: MiMo returned repeated replans followed by
  an old `collect_observed_item` sample handle, which the local skill
  correctly rejected as `visible_dropped_item_required`. The body did not
  move or claim pickup. The production recovery now performs one bounded
  fair survey before asking the model for the current item handle; the
  passing recovery rerun is recorded in the results table.

- `run-live-foundation-20260814`: remembered workstation aim stayed in an
  unbounded `AIM_TABLE` loop with enough planks but no door/light inventory.
- `run-live-foundation-20260814c`: the table aim fix worked, but a closed
  shelter door repeatedly occluded the roof ray and the route stopped in a
  roof retry loop.
- `run-live-foundation-20260814d`: the door case was no longer dominant; the
  last outer roof corner remained marked as interior-deferred and repeatedly
  re-entered the same fallback cycle. This run used a process compiled before
  the final exterior-recovery patch.
- `run-live-stronghold-victory-debug7-20260814` and
  `run-live-stronghold-victory-diag-20260814`: strict floor-support admission
  correctly refused to walk from a first-person frame that saw stronghold
  faces but no observed floor. The diagnostic showed the fixture floor was one
  block below the visible sample; bounded inherited interior confidence now
  permits only a finite one-block corridor retrace.
- `run-live-stronghold-entry-debug8-20260814` through
  `run-live-stronghold-entry-debug10-20260814`: the production initial-anchor
  relogin placed the body outside the authored maze and the search selected a
  stale scan direction. The harness now places the human one block forward in
  the safe locator ring, and the search derives station yaw from actual travel.
- `run-live-stronghold-entry-debug11-20260814`: search passed, but a final-eye
  fallback stalled after eleven eyes. `debug12` then exposed stale
  `TARGET_OUT_OF_REACH` replay after nine eyes. The final-eye fallback was
  removed, stale targets are discarded, and semantic interaction candidates
  now fail closed at 4.45 blocks. `debug15` is the first passing run after all
  three fixes.
- A later stronghold-to-return run installed its test dragon before vanilla's
  legacy End-fight scan settled, so vanilla correctly retired the fixture.
  Another 90-second gate expired with the still-running skill and the dragon
  at 15.445984 health. A third run exposed a terminal optional cage-safety
  failure. After bounded settling, a 150-second measured fight window, and
  safe target reacquisition, the first credited-kill rerun was then rejected
  only because the harness required destruction of one optional crystal and
  artificial bar. The current full controlled PASS verifies companion kill
  credit, real dragon damage and resource use without requiring that one
  unnecessary tactic; the stricter standalone cage/crystal test remains.

The isolated `20260814e` run is the first foundation run after both fixes and
is the only one counted as a passing controlled foundation result.

The first horde fixture (`run-live-horde-20260814j`) is retained as a harness
failure: the initial-anchor relogin moved the body but the six targets remained
at their old coordinates, so the model correctly selected a survey replan.
The corrected `run-live-horde-20260814k` repositions those same entities after
the login transaction and passes the bounded six-mob assertion, but its model
speech referenced an unrelated ambient slime. The latest rerun recreates the
authored targets after the body relogin and starts damage attribution only at
the accepted `RUNNING` skill edge. It records four of six target entities
damaged, body movement, and full body health, passing in 40.14 seconds. Earlier
`run-live-horde-20260814g` and `run-live-horde-20260814i` stopped before a test
could run because the new Forge test-instance/environment resources had not
yet been registered; they are registration failures, not product evidence.

The ten-plus-ten fixture initially exposed two false-positive paths: target
death flags and ambient/local damage were counted before a model skill had
started, and the first-human relogin could unload the authored mob instances.
The fixture now recreates the twenty targets before chat submission, requires
`engage_observed_entity` to be genuinely `RUNNING`, and compares target health
against a post-start baseline. A previous real MiMo run passed in 38.28 seconds
with all twenty authored Zombies/Skeletons damaged. The latest authorized rerun
passed in 39.70 seconds with sixteen of twenty targets damaged, body movement,
and full health; the fixture requires at least ten damaged targets. This is a
controlled twenty-mob causal slice, not a human PVP or random-world statistic.

The first three live iron-golem attempts are retained as negative evidence:
some inherited creative/invulnerability state invalidated incoming-damage
checks, and one later fixture allowed a naturally spawned slime to become the
model's fair target while separately activating the authored golem. The clean
rerun clears those abilities, removes ambient targets, disables natural
spawning, asserts genuine survival before chat, and passes the full bounded
duel assertion: model-selected golem skill, shielded movement, golem damage,
a real golem attack, and body survival. This is still one controlled
neutral-mob slice, not a human PVP or Hardcore result.

## What this proves

These runs close concrete regressions such as speech-only movement, passive
follow promises, staring at a nearby hostile, refusing to use an owned food
item, and losing the body during first-human anchoring. The directional-damage
rerun additionally proves that a fair rear-hit cue produces a physical
separation before another model round trip. They prove that the
current implementation can carry a real model decision through the local
safety/skill lanes into observable vanilla actions in bounded fixtures. The
delayed-login result is a production lifecycle check, not a model claim.
The horde results add two bounded multi-hostile causal slices: six visible mobs
with four damaged targets, and twenty visible mobs with sixteen damaged targets
in the latest rerun (a previous run damaged all twenty), each after a
model-selected combat skill entered `RUNNING`; both include body movement and
survival. They are not human PVP tests or random-world statistics.
The passing iron-golem slice adds one neutral-mob exchange with verified
incoming damage; it is not a human PVP claim.

The stronghold run specifically proves that portal activation is not treated
as a hidden state mutation: every eye is selected from a current first-person
ray, dispatched through the vanilla server-player actuator, and confirmed by
the inventory/world revision before the next eye. A semantic ray outside the
authoritative survival reach is rejected and causes a bounded station change
rather than an infinite speech-only retry.

The stronghold-room-to-return run adds one complete controlled handoff across
those component skills, including a companion-attributed dragon death and
physical return. Its static dragon, bounded chunk residency, disabled ambient
spawning, authored maze, and test-activated return portal prevent it from
serving as random-world, dynamic-dragon, Hardcore, or speedrun evidence.

They do not prove:

- arbitrary random-seed completion or a two-hour speedrun;
- Hardcore survival statistics;
- a rendered real-client Actor/Observer causal gate;
- the M0 24-hour soak or M3 100-hour world-memory gate;
- the M1, M2, or M4 hidden-seed sample sizes.

The formal statuses therefore remain `NOT_RUN` until an authorized Linux/Xvfb
worker executes the frozen production JAR with the required client and oracle
protocol.
