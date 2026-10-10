# Professional Companion Capability Audit

Audit date: 2026-08-23

This document is the detailed audit requested by sections 15.1--15.4. It
separates four claims that are often accidentally conflated:

* **Implemented**: production code has the capability boundary.
* **Controlled**: a bounded Forge fixture verified a physical result.
* **Live slice**: a real player chat, real configured model, real server tick,
  and ordinary `ServerPlayer` actions produced the result.
* **Formal**: the frozen external-client, natural-world, statistical protocol
  passed. Formal M1--M4 status remains `NOT_RUN` unless that exact protocol
  passes.

An acknowledgement, a planner response, a registry entry, or a unit test is
not evidence that a companion actually completed the requested Minecraft
work.

## 15.1 Professional companion

| Capability expected from a real play companion | Current implementation | Strongest evidence | Gap before a product claim |
| --- | --- | --- | --- |
| Visible account-free teammate with stable identity | Implemented | `ServerPlayer`, stable UUID, player-list lifecycle, TAB identity marker | Rendered two-client archive and M0 release gate |
| Ordinary chat without mandatory `@` in single-player | Implemented | Live unaddressed follow chat reached the real model | External Actor client and multilingual regression archive |
| Understand a request and create an executable goal | Implemented | Live movement, follow, combat, farm, container, portal and End-chain slices | Natural-world task distribution and formal M1/M3 cases |
| Do something physical after speaking | Implemented | Live slices record `skill_started` and `low_level_actions_issued`; movement/combat/farm results were server-verified | Actor/Observer causal gate; no speech-only false positives in every required scenario |
| Follow, stop, resume, and reacquire a moving player | Partial | Controlled stop/resume plus one 25-block no-window natural-world moving-target slice | Cliffs, external clients, chunk unload, long-distance and 100-hour cases |
| Give grounded status and accept cancellation | Implemented | Status derives from accepted skill transitions; local `stop`/`hold`/`pause` reaches a safe checkpoint | Rendered UX and interruption matrix under model latency |
| Local safety during model delay | Implemented | Emergency food, shield, separation, fall/water-clutch and parkour slices | Formal damage/death-rate and adversarial latency tests |
| Combat teammate behavior (PVE) | Implemented in bounded scope | Real MiMo Zombie/horde/golem slices; shield warmup and vanilla damage | Dynamic natural-world combat, projectile/PVE diversity, Hardcore statistics |
| Human-like PVP | Not implemented as a product claim | A controlled iron-golem duel is not PVP | Human opponent Actor/Observer tournament, fair-target attribution and ruleset matrix |
| Shared waypoints, ownership, and protected builds | Partial | Xaero parser/authorization/persistence and fair same-dimension routing code | Real Xaero client, protection/consent scenarios, grief/ownership audit |
| Exploration and travel planning | Partial-to-implemented | Walk, boat, minecart, portal and stronghold component slices | Long routes, route memory at scale, unknown terrain and failure recovery |
| Resource, inventory, and container accountability | Implemented for tested paths | Chest withdrawal, item pickup, live MiMo pickup-then-drop round trip, crafting, smelting and equipment transactions | All workstation/container families and multi-session ownership cases |
| Long-term memory and named places | Implemented in architecture | SQLite WAL/FTS5/R*Tree, waypoint and portal repositories | 10,000-waypoint/100,000-asset/100-hour formal gate |
| Coaching and companion social behavior | Partial | Chat/status, task handoffs and player-language responses | Voice/proximity integration, session preferences, teaching feedback and social usability study |
| Identity disclosure and secure communication | Implemented | `[AI]` labeling, no fake secure signature, MCP host/origin/token checks | Rendered multiplayer review and server policy matrix |
| Failure honesty | Implemented by policy | Stale decisions, rejected actions and incomplete End routes remain failures | Formal evidence archive must bind every terminal verdict to the exact jar |

### Professional-companion acceptance gaps

Real services marketed as Minecraft coaching or co-play consistently combine
survival, building, exploration, redstone, combat/PvP, mod/server support,
tailored live sessions, and feedback rather than only answering chat. MinePilot
therefore needs a real-client scenario matrix for: “play beside me”, “teach me
while we build”, “protect me while exploring”, “organize my base”, “repair a
failed farm”, “stop now”, and “resume from the saved checkpoint”. Those cases
are not equivalent to one successful model call.

## 15.2 Workstations and transport

### Workstation and container matrix

| Vanilla surface | Current state | Evidence/limitation |
| --- | --- | --- |
| Crafting table | Implemented and controlled/live | Foundation crafting and live bootstrap use normal menu/crafting paths |
| Furnace | Implemented and controlled/live | Furnace batch, fuel, cook ticks and result-slot verification; live foundation slice |
| Blast furnace and smoker | Implemented at generic menu level | Controlled menu contracts exist; no dedicated live-model task for each |
| Chest/double chest and barrel | Implemented at generic menu level | Live chest withdrawal; barrel/container contracts are bounded |
| Hopper and dispenser inventory | Implemented at transaction/interaction level | Hopper menu and dispenser/button contracts; no complete item-sorter commissioning |
| Loom, cartography table, smithing table, grindstone, stonecutter | Observed-slot transaction contracts are implemented and covered by Forge menu fixtures | No model-driven live slice for each family; recipe/XP/material selection still requires the model to bind the current frame |
| Anvil, enchanting table, brewing stand, cauldron | Observed-slot transactions exist for anvil/enchanting/brewing; cauldron remains interaction-only | No model-driven live slice for each family; no potion recipe/XP workflow claim |
| Lectern, fletching table, composter | Recognition/placement boundary exists | Villager workstation use and librarian/trade workflows are not complete |
| Ender chest and shulker box | Partial recognition | No formal cross-session storage/ownership transaction |
| Respawn anchor and bed | Survival/portal-related partial support | No complete Nether respawn safety commissioning matrix |

The placement guard was audited and expanded to treat brewing stands,
cauldrons, fletching tables, hoppers, dispensers, droppers, observers,
pistons, repeaters, comparators, and redstone lamps as interactive blocks.
This prevents a foundation material routine from trying to place a block on a
surface whose normal use should win. It does **not** claim that the companion
can build or operate a complete machine from those blocks.

Open-menu observations now also attach a bounded, server-derived `role` to
each visible slot (for example `FURNACE_FUEL`, `SMITHING_TEMPLATE`,
`ANVIL_OUTPUT`, or `PLAYER_INVENTORY`). The role is only a hint for the
currently open menu; every action still binds `sampleSequence`, `containerId`,
`stateId`, and the exact slot before vanilla validation. This removes a major
source of model confusion without reading closed containers or bypassing
Minecraft's recipe, fuel, XP, durability, or ownership rules.

### Transport matrix

| Transport mode | Current state | Evidence/limitation |
| --- | --- | --- |
| Walking, sprinting, jumping, swimming, climbing, doors and safe drops | Implemented | Local 20-TPS movement and controlled/live movement/parkour slices |
| Boat/raft entry and travel | Implemented and controlled | Vanilla mount, paddle, turn, brake and safe dismount contracts |
| Rideable minecart and powered-rail travel | Implemented and controlled | Vanilla rail motion and rider input contracts |
| Existing rail switching and station use | Partial | Travel follows observed/verified rails; no station construction/dispatch skill |
| Chest/hopper/furnace/TNT minecart logistics | Not implemented as a system | Entity observation exists, but cargo loading and route commissioning are absent |
| Nether portal traversal | Implemented in bounded routes | Portal build/entry and verified portal-edge memory; formal random routes absent |
| Strider, horse, pig, camel, elytra, ice-boat highways | Not implemented | No fair control/landing/ownership skills |
| Cross-dimension coordinate scaling | Heuristic only | Scaling is a route hint; only verified portal edges may be used |

## 15.3 Farm capability matrix

| Farm family | Current state | What is actually verified |
| --- | --- | --- |
| Manual wheat/carrot/potato/beetroot plot | Implemented | Visible till/plant/harvest/replant and dynamic hydrated-field planner |
| Sugar cane planting | Implemented atomic skill | Visible support plus water precondition; no automatic harvester |
| Dynamic field maintenance | Implemented in bounded scope | Real MiMo wheat slice harvested/replanted and maintained a crop field |
| Melon/pumpkin, bamboo, cactus, cocoa, berries, kelp, nether wart, chorus | Not implemented | No registered production farm skill and no causal run |
| Tree, mushroom, flower, fishing, honey | Not implemented | Gathering primitives exist, but no farm commissioning/maintenance workflow |
| Animal breeding and slaughter | Not implemented as a farm system | Food hunting exists; breeding, pens and ownership are absent |
| Villager breeder | Mechanism knowledge only | No villager transport, bed/food/willingness commissioning skill |
| Trading hall | Mechanism knowledge only | No trade reroll, locking, restock, pricing or ownership workflow |
| Iron golem farm | Mechanism knowledge only | A golem combat slice is not an iron-farm build or rate result |
| Hostile mob farm/tower | Mechanism knowledge only | Redstone/dispenser and combat primitives exist; no spawn/kill/collection builder |
| Item collection and storage | Partial | Chest/hopper transactions and item pickup; no complete sorter/shulker loader |
| Chunk loading/AFK/rate measurement | Not implemented | Formal farms must measure output over an observed window and loaded chunks |
| Repair and commissioning | Crop planner only | Generic `MechanismSpec` supports invariants, probes, rates and repairs; only crop field has a production planner |

Farm construction must be generated from a current first-person survey and
mechanism constraints. A remembered block blueprint, structure locator, hidden
spawn scan, direct block mutation, or creative inventory would violate the
fair-play contract. The missing farm families remain explicit backlog, not
implied by the generic mechanism schema.

## 15.4 Machine capability matrix

| Machine/system | Current state | Evidence/limitation |
| --- | --- | --- |
| Button/door/dispenser interaction | Implemented and controlled | Ordinary first-person use, powered state, item consumption and projectile result |
| Hopper inventory transaction | Implemented and controlled | Normal menu transfer and container state verification |
| Dropper/dispenser/observer/piston/repeater/comparator/redstone lamp recognition | Placement guard and perception boundary | No complete machine construction or timing verifier |
| Redstone clock and item sorter | Not implemented | No planner/commissioner/repair loop |
| Cobblestone/basalt generator | Not implemented | No fluid/lava safety and output-rate contract |
| Furnace array/super-smelter | Not implemented as a system | Single furnace-family batch works; routing, fuel balancing and throughput do not |
| Sugar-cane or crop automation | Not implemented | Manual field work only |
| Mob farm and iron farm | Not implemented | Mechanism facts are documented but no fair builder/commissioner |
| Villager transport/curing/trading machine | Not implemented | Requires entity ownership, beds, workstations, transport and price state |
| Rail station/item minecart logistics | Not implemented | Rideable travel is not cargo automation |
| Create adapter | SPI only | No exact Forge 65.x build contract or menu/world regression |
| MTR adapter | SPI only | No exact build contract or vehicle/route regression |
| Farmer's Delight adapter | SPI only | No exact build contract or cooking/food regression |
| Machine safety and repair | Partial architecture | Site survey, provenance, clearances and repair fields exist; only crop planning consumes them |

### Machine acceptance rule

A machine is not “supported” until all of these are recorded for three
unseen, site-varied cases: material acquisition, ordinary player construction,
activation, output collection, rate observation, interruption/restart, and a
fair repair after one induced failure. The current repository has not met that
bar for any machine beyond the bounded dispenser/button and hopper contracts.

## External validation: what real Minecraft companion work includes

The official Minecraft introduction describes the core survival loop as
gathering resources, crafting, building a shelter, exploring, and fighting;
its server guide describes planning large builds and collaborating on difficult
bosses. The official controls guide also treats the player list/TAB status as a
normal multiplayer affordance. These are the minimum “play beside me” duties,
not optional polish:

* [How to Minecraft](https://www.minecraft.net/en-us/article/how-minecraft)
* [How to play on a Minecraft server](https://www.minecraft.net/en-us/article/how-play-minecraft-server)
* [Minecraft controls](https://www.minecraft.net/article/minecraft-controls)

Current public coaching/co-play service descriptions add the service
expectations that are easy to miss in a technical mod: tailored live sessions,
survival and base-building help, PvP/combat, redstone/farms, exploration,
modded/server support, patient teaching, feedback, and flexible hourly or
multi-session continuity:

* [ReinwinBoost Minecraft coaching](https://reinwinboost.com/collection/minecraft/product/minecraft-coaching)
* [Fiverr SMP survival/base/PvP coaching](https://www.fiverr.com/not_epsilon/coach-you-to-dominate-in-minecraft-smp)
* [EB24 shared Minecraft co-op sessions](https://eloboost24.eu/ggirls/minecraft)
* [Netherite Academy live coaching/community](https://whop.com/joined/netherite-academy/)

The technical farm audit was cross-checked against the Minecraft Wiki's
current Java tutorials: crop farms have villager and collection constraints,
iron farms depend on beds/villager panic or gossip, and mob farms require
spawn, transport, kill, and collection stages. Minecart tutorials likewise
separate rideable travel from powered-rail placement and cargo behavior:

* [Crop farming](https://minecraft.wiki/w/Tutorial%3ACrop_farming)
* [Villager farming](https://minecraft.wiki/w/Tutorial%3AVillager_farming)
* [Iron golem farming](https://minecraft.wiki/w/Tutorial%3AIron_Golem_farming)
* [Mob farming](https://minecraft.wiki/w/Tutorial%3AMob_farm)
* [Minecarts](https://minecraft.wiki/w/Tutorial%3AMinecarts)

These sources validate the matrix split: a professional companion must be able
to participate in the whole activity loop and communicate progress, while a
technical machine claim requires commissioning and output evidence. They do
not justify claiming that MinePilot already meets those formal gates.

## Result of this audit

The placement-guard gap is fixed and covered by a focused regression test.
The matrices now make the remaining work explicit:

1. exercise the role-labeled workstation transactions with real model-driven
   live slices instead of treating Forge fixtures as companion evidence;
2. add fair, site-generated farm commissioners one family at a time;
3. add machine activation/rate/repair evidence rather than only block
   interaction;
4. run the professional-companion scenario archive with a real Actor client,
   Observer, dedicated server, model, restart/chunk-unload cases, and long soak.

No M1, M2, M3, or M4 formal status is promoted by this audit.
