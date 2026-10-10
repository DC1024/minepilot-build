package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class CoreSkillFramePoseTest {
    @Test
    void livePoseRefreshPreservesFairSemanticSnapshot() {
        CoreSkillFrame semantic = frame(
                7,
                0.5,
                1.0,
                0.5,
                new PerceptionVec3(1.0, 0.0, 0.0),
                corridor(7, 2),
                0.08
        );
        CoreSkillPose live = new CoreSkillPose(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                11,
                new PerceptionVec3(1.25, 1.0, 0.5),
                new PerceptionVec3(1.25, 2.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                false,
                true
        );

        CoreSkillFrame refreshed = semantic.withPose(live);

        assertEquals(11, refreshed.gameTime());
        assertEquals(live.position(), refreshed.position());
        assertEquals(live.lookDirection(), refreshed.lookDirection());
        assertEquals(7, refreshed.observationRevision());
        assertEquals(0.08, refreshed.danger());
        assertSame(semantic.navigation(), refreshed.navigation());
    }

    @Test
    void crossDimensionPoseCannotReuseOldNavigation() {
        CoreSkillFrame semantic = frame(
                1,
                0.5,
                1.0,
                0.5,
                new PerceptionVec3(1.0, 0.0, 0.0),
                corridor(1, 0),
                0.0
        );
        CoreSkillPose nether = new CoreSkillPose(
                PLAYER_ID,
                DimensionRef.NETHER,
                2,
                semantic.position(),
                semantic.eyePosition(),
                semantic.lookDirection(),
                true,
                false
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> semantic.withPose(nether)
        );
    }
}
