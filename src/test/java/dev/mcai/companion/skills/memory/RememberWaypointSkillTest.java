package dev.mcai.companion.skills.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RememberWaypointSkillTest {
    @Test
    void writesOnlyTheObservedCurrentPositionAndNoRawCheckpointLabel() {
        final AtomicReference<Waypoint> captured =
            new AtomicReference<>();
        final CompletableFuture<Void> write = new CompletableFuture<>();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final RememberWaypointSkill skill = new RememberWaypointSkill(
            UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
            ),
            UUID.fromString(
                "00000000-0000-0000-0000-000000000002"
            ),
            () -> Optional.of(new ObservedCurrentPosition(
                DimensionRef.OVERWORLD,
                12.25,
                65,
                -8.75,
                4
            )),
            waypoint -> {
                captured.set(waypoint);
                return write;
            },
            allowed::get
        );
        final RememberWaypointParameters parameters =
            new RememberWaypointParameters("主仓库", "storage");
        final SkillContext context = context(1);

        assertTrue(skill.preconditions(context, parameters).isEmpty());
        skill.start(context, parameters);
        assertEquals(
            SkillTickResult.Status.RUNNING,
            skill.tick(context(2), parameters).status()
        );
        assertEquals(12.25, captured.get().geometry().referencePoint().x());
        assertEquals(-8.75, captured.get().geometry().referencePoint().z());
        assertEquals(
            SkillTickResult.Status.RUNNING,
            skill.tick(context(3), parameters).status()
        );
        write.complete(null);
        assertEquals(
            SkillTickResult.Status.COMPLETED,
            skill.tick(context(4), parameters).status()
        );
        assertFalse(
            skill.checkpoint(context, parameters)
                .payload()
                .contains("主仓库")
        );
    }

    @Test
    void lockedEvaluationRejectsWriteAndParserHasNoCoordinateFields() {
        final RememberWaypointSkill skill = new RememberWaypointSkill(
            UUID.randomUUID(),
            UUID.randomUUID(),
            () -> Optional.of(new ObservedCurrentPosition(
                DimensionRef.OVERWORLD,
                0,
                64,
                0,
                1
            )),
            waypoint -> CompletableFuture.completedFuture(null),
            () -> false
        );
        assertEquals(
            "remember_waypoint.evaluation_locked",
            skill.preconditions(
                context(1),
                new RememberWaypointParameters("home", "base")
            ).orElseThrow().code()
        );
        assertTrue(RememberWaypointSkill.parse(List.of(
            new SkillArgument("name", "home"),
            new SkillArgument("category", "base")
        )).value().isPresent());
        assertTrue(RememberWaypointSkill.parse(List.of(
            new SkillArgument("name", "home"),
            new SkillArgument("category", "base"),
            new SkillArgument("x", "100")
        )).failure().isPresent());
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
