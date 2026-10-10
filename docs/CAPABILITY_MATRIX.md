# Capability matrix

`Implemented` means production code exists. `Inner-loop` means a bounded JVM or
Forge fixture has verified it. `Formal` requires the external client, natural
world, and statistical protocol named by the plan.

The detailed 15.1--15.4 audit, including the distinction between interaction,
construction, commissioning, and formal companion evidence, is in
[`PROFESSIONAL_COMPANION_MATRIX.md`](PROFESSIONAL_COMPANION_MATRIX.md).

| Capability | Implemented | Inner-loop evidence | Formal status |
|---|---:|---:|---:|
| Stable visible ServerPlayer body | yes | Forge lifecycle and zero-human tests; current-source latest/floor checks include Forge 65.1.1 and 65.0.0 | NOT_RUN |
| Main/off hand, armor, hunger, death/respawn | yes | lifecycle/menu fixtures | NOT_RUN |
| Simulated chunks/view distance | yes | moving-chunk fixture plus zero-human same-section Overworld-to-Nether entity/block simulation on Forge 65.0.0 and 65.1.1 | NOT_RUN |
| Fair first-person semantic observation | yes | observation/revision tests | NOT_RUN |
| On-demand first-person screenshot capture and model input | partial | authenticated renderer protocol; 24 KiB bounded serverbound chunk/reassembly tests; PNG bounds and camera exclusion; production Responses/Chat image composition tests; real Grok 4.5 strict-output plus image-input handshake passed; no live background-renderer Minecraft frame yet | NOT_RUN |
| Auditory hostile sound cue | yes | bounded Forge entity-sound event, actual 4–16 block distance filter, 20-tick identity-free cue, semantic refresh and local safety wiring | NOT_RUN |
| Model decision envelope/revision invalidation | yes | JVM/model protocol tests | NOT_RUN |
| Movement/follow/search/replan | yes | controlled fixtures plus one 25-block no-window natural-world follow slice | NOT_RUN |
| Boat and minecart transport | yes | integrated Forge lifecycle fixture drives vanilla mount, boat travel, powered-rail minecart travel | NOT_RUN |
| Parkour and water-clutch safety | yes | controlled physical fixtures | NOT_RUN |
| Combat emergency lane | yes | Enderman/Slime Forge fixtures | NOT_RUN |
| Workstations, containers, crafting, smelting | yes | 65.1.0 lifecycle/menu transactions across furnace, brewing, cartography, stonecutter, merchant, enchanting, loom, smithing, grindstone, anvil, chest/barrel/hopper/dispenser/ender chest/shulker plus role-labeled open-menu observations | NOT_RUN |
| Shelter/foundation construction | yes | controlled dynamic shelter fixtures | NOT_RUN |
| Nether/portal/stronghold/End components | partial | bounded component fixtures plus one controlled live-model stronghold-room-to-return chain; static End arena, not random/dynamic-dragon evidence | NOT_RUN |
| Random Hardcore completion ≤2h | no claim | none | NOT_RUN |
| Multiplayer chat identity/TAB status | implemented | `[AI]` identity plus live `● online`/`○ offline` body-session marker; source/JVM coverage | NOT_RUN |
| Skin import/render synchronization | implemented | source/UI tests | NOT_RUN |
| Xaero shared waypoint routing | adapter + bounded action playbook | parser/authorization/persistence and same-dimension `move_to` planner coverage | NOT_RUN |
| Codex loopback MCP | implemented | protocol/security tests | NOT_RUN |
| Forge 66 adapter | no | not released/observed | NOT_APPLICABLE |
| Create/MTR/Farmer's Delight expert adapters | SPI only | no contract runs | NOT_RUN |

## 15.x audit summary

| Section | Current honest result | Main missing proof |
| --- | --- | --- |
| 15.1 Professional companion | Bounded live slices for chat-to-action, follow, stop/resume, combat, farm work, containers, and portals; not a formal companion claim | Real Actor/Observer, social/ownership matrix, long-world memory, 100-hour soak |
| 15.2 Workstations and transport | Crafting, furnace-family, chest/hopper/dispenser transactions, role-labeled vanilla workstation menus, and boat/minecart travel are bounded; cargo logistics are not complete | Per-workstation live-model slices, rail construction/stations, cargo vehicles, cross-dimension natural routes |
| 15.3 Farm capability matrix | Manual crop/sugar-cane field work and dynamic crop planning; no complete animal, villager, iron, hostile-mob, or automatic crop farms | Site-generated builders, commissioning/rate/repair evidence across unseen variants |
| 15.4 Machine capability matrix | Fair button/dispenser/door/hopper primitives and generic mechanism schema; no complete machine system | Redstone construction, activation, output/rate measurement, repair, and versioned mod adapters |
