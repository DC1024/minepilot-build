package dev.mcai.companion.skills.combat;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses a bounded tower column and adjacent water-clutch landing using only
 * cells retained by the fair first-person navigation map.
 */
final class CagedCrystalTraversalPlanner {
    static final int MAXIMUM_TOWER_BLOCKS = 20;
    private static final int MINIMUM_TOWER_BLOCKS = 4;
    private static final double MINIMUM_HORIZONTAL_REACH = 2.5;
    private static final double MAXIMUM_HORIZONTAL_REACH = 4.75;
    private static final double MINING_REACH = 5.65;
    private static final double EYE_HEIGHT = 1.62;
    private static final List<GridPos> CARDINAL_OFFSETS = List.of(
            new GridPos(1, 0, 0),
            new GridPos(-1, 0, 0),
            new GridPos(0, 0, 1),
            new GridPos(0, 0, -1)
    );

    private CagedCrystalTraversalPlanner() {
    }

    static Optional<Plan> plan(
            final SkillContext context,
            final CoreSkillFrame frame,
            final VisibleBlockFace cageBar
    ) {
        final double maximumDanger =
                context.hardcore() ? 0.04 : 0.12;
        final GridPos current = frame.feet();
        return frame.navigation()
                .observedVoxels()
                .keySet()
                .stream()
                .filter(candidate ->
                        Math.abs(candidate.y() - current.y()) <= 1
                )
                .filter(candidate ->
                        safeDryStanding(
                                frame,
                                candidate,
                                maximumDanger
                        )
                )
                .map(candidate -> candidatePlan(
                        frame,
                        candidate,
                        cageBar,
                        maximumDanger
                ))
                .flatMap(Optional::stream)
                .min(Comparator
                        .comparingInt(Plan::towerBlocks)
                        .thenComparingDouble(plan ->
                                squaredHorizontalDistance(
                                        current,
                                        plan.approach()
                                )
                        )
                        .thenComparing(Plan::approach));
    }

    private static Optional<Plan> candidatePlan(
            final CoreSkillFrame frame,
            final GridPos approach,
            final VisibleBlockFace cageBar,
            final double maximumDanger
    ) {
        final double horizontal = horizontalDistance(
                center(approach),
                cageBar.hitPosition()
        );
        if (horizontal < MINIMUM_HORIZONTAL_REACH
                || horizontal > MAXIMUM_HORIZONTAL_REACH
                || horizontal >= MINING_REACH) {
            return Optional.empty();
        }
        final double verticalAllowance = Math.sqrt(
                MINING_REACH * MINING_REACH
                    - horizontal * horizontal
        );
        final double initialEyeY = approach.y() + EYE_HEIGHT;
        final int required = (int) Math.ceil(
                cageBar.hitPosition().y()
                    - verticalAllowance
                    - initialEyeY
        );
        final int towerBlocks = Math.max(
                MINIMUM_TOWER_BLOCKS,
                required
        );
        if (towerBlocks > MAXIMUM_TOWER_BLOCKS) {
            return Optional.empty();
        }
        final double finalVertical = Math.abs(
                cageBar.hitPosition().y()
                    - initialEyeY
                    - towerBlocks
        );
        if (Math.hypot(horizontal, finalVertical)
                > MINING_REACH) {
            return Optional.empty();
        }
        return landing(
                frame,
                approach,
                cageBar.hitPosition(),
                maximumDanger
        ).map(value -> new Plan(
                approach,
                value,
                towerBlocks
        ));
    }

    private static Optional<GridPos> landing(
            final CoreSkillFrame frame,
            final GridPos approach,
            final PerceptionVec3 cageBar,
            final double maximumDanger
    ) {
        return CARDINAL_OFFSETS.stream()
                .map(offset -> new GridPos(
                        approach.x() + offset.x(),
                        approach.y(),
                        approach.z() + offset.z()
                ))
                .filter(candidate ->
                        safeDryStanding(
                                frame,
                                candidate,
                                maximumDanger
                        )
                )
                .max(Comparator
                        .<GridPos>comparingDouble(candidate ->
                                horizontalDistance(
                                        center(candidate),
                                        cageBar
                                )
                        )
                        .thenComparing(
                                Comparator.naturalOrder()
                        ));
    }

    private static boolean safeDryStanding(
            final CoreSkillFrame frame,
            final GridPos feet,
            final double maximumDanger
    ) {
        final Optional<ObservedVoxel> feetVoxel =
                frame.navigation().voxelAt(feet);
        final Optional<ObservedVoxel> headVoxel =
                frame.navigation().voxelAt(feet.above());
        final Optional<ObservedVoxel> supportVoxel =
                frame.navigation().voxelAt(feet.below());
        return feetVoxel.isPresent()
                && headVoxel.isPresent()
                && supportVoxel.isPresent()
                && dryPassable(
                        feetVoxel.orElseThrow(),
                        maximumDanger
                )
                && dryPassable(
                        headVoxel.orElseThrow(),
                        maximumDanger
                )
                && supportVoxel.orElseThrow()
                    .kind().supportsWeight()
                && supportVoxel.orElseThrow()
                    .effectiveDanger() <= maximumDanger;
    }

    private static boolean dryPassable(
            final ObservedVoxel voxel,
            final double maximumDanger
    ) {
        final VoxelKind kind = voxel.kind();
        return kind.isPassable()
                && !kind.isLiquid()
                && kind != VoxelKind.LAVA
                && voxel.effectiveDanger() <= maximumDanger;
    }

    private static PerceptionVec3 center(final GridPos position) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y(),
                position.z() + 0.5
        );
    }

    private static double horizontalDistance(
            final PerceptionVec3 first,
            final PerceptionVec3 second
    ) {
        return Math.hypot(
                first.x() - second.x(),
                first.z() - second.z()
        );
    }

    private static double squaredHorizontalDistance(
            final GridPos first,
            final GridPos second
    ) {
        final double x = first.x() - second.x();
        final double z = first.z() - second.z();
        return x * x + z * z;
    }

    record Plan(
            GridPos approach,
            GridPos landing,
            int towerBlocks
    ) {
        Plan {
            if (towerBlocks < MINIMUM_TOWER_BLOCKS
                    || towerBlocks > MAXIMUM_TOWER_BLOCKS
                    || approach.y() != landing.y()
                    || Math.abs(
                            approach.x() - landing.x()
                    ) + Math.abs(
                            approach.z() - landing.z()
                    ) != 1) {
                throw new IllegalArgumentException(
                        "Invalid caged-crystal traversal plan"
                );
            }
        }

        double targetY() {
            return approach.y() + towerBlocks;
        }
    }
}
