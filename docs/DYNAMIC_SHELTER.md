# Dynamic shelter integration

`DynamicShelterSkills` is an independent runtime slice. It generates no fixed
block blueprint and never reads a level, chunk, biome, structure, or hidden
block. Its inputs are limited to the companion's own inventory/body and the
incremental `LocalNavSnapshot` plus block faces derived from first-person fair
semantic rays.

Production wiring:

1. Construct one `ServerShelterFrameSource` for the companion UUID.
2. Publish each fresh `SemanticObservation` into that source from the existing
   semantic-observation callback.
3. Register `DynamicShelterSkills.registerAll(...)` with the same
   `ServerOwnedInteractionSkillActuator` used by fair block interactions.
4. Append `DynamicShelterSkills.plannerGuide()` to the trusted local-skill
   guide.

The model calls `build_shelter_step` with `dimension`, the current
`sampleSequence`, and a stable `scale` (`compact`, `standard`, or `spacious`).
One call performs at most one vanilla placement and waits for a newer fair
observation to confirm it. The model can therefore use `look_at`, `move_to`,
and `equip_item` between construction steps. A cancellation releases active
item use. A dispatched placement becomes a safe checkpoint only after a newer
fair observation confirms the resulting block.

The generated plan searches observed flat, supported, low-danger footprints
around the player, selects a size that fits inventory, points the entrance
toward the player's observed look direction when feasible, and derives walls,
roof, door gap, and interior light from constraints. Every valid plan has an
interior of at least 3×3×2, a complete two-block wall envelope, a roof, one
non-iron wooden door, and one torch or lantern. Unknown cells are never
treated as air. Occupied or changed target cells produce a conflict instead of
being broken or overwritten.

Expected recoverable failures:

- `equip_material`, `equip_door`, `equip_light`: equip the requested phase's
  item using `equip_item`. Structural material prefers the already held valid
  full block; door selection is the lexicographically first safe wooden door;
  light priority is torch, lantern, soul torch, then soul lantern.
- `no_visible_build_step`: turn or move until a support surface for the
  current phase is visible and in reach.
- `insufficient_observation`: inspect the prospective ground and volume.
- `missing_*` or `insufficient_structural_material`: gather/craft resources.
- `no_safe_footprint`: move to safer, flatter terrain.
- `plan_conflict` or `completed_block_missing`: the locally verified build
  state changed; do not overwrite it blindly.
