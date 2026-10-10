package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locks the conservative support affordance to the already ray-hit block.
 *
 * <p>A sturdy face alone is not proof of a full standing surface: slabs,
 * stairs, fences, and other partial collision shapes must remain fail-closed
 * for mining movement.</p>
 */
final class FairPerceptionSupportSourceContractTest {
    private static final Pattern FULL_STURDY_TOP = Pattern.compile(
            "if\\s*\\(\\s*state\\.isCollisionShapeFullBlock"
                + "\\(\\s*level,\\s*position\\)\\s*"
                + "&&\\s*state\\.isFaceSturdy\\s*"
                + "\\(\\s*level,\\s*position,\\s*Direction\\.UP\\s*\\)",
            Pattern.DOTALL
    );

    @Test
    void supportRequiresVisibleUpFaceFullCollisionAndSturdiness()
            throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "FairPerceptionSampler.java"
        ));
        final String affordanceSource = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "BlockShapeAffordances.java"
        ));

        assertTrue(source.contains("BlockShapeAffordances.topSupport"));
        assertTrue(affordanceSource.contains("if (hitFace != Direction.UP)"));
        assertTrue(
                FULL_STURDY_TOP.matcher(affordanceSource).find(),
                "Standing support must require both a full collision shape "
                    + "and a sturdy UP face"
        );
    }

    @Test
    void everyLoadedBlockHitPreservesItsClearSegmentBeforeFaceDeduplication()
            throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "FairPerceptionSampler.java"
        ));
        final int validBlockHit = source.indexOf(
            "if (hit.getType() != HitResult.Type.BLOCK"
        );
        final int clearSegment = source.indexOf(
            "clearSegmentBeforeHit(",
            validBlockHit
        );
        final int faceDeduplication = source.indexOf(
            "if (uniqueFaces.containsKey(key))",
            validBlockHit
        );

        assertTrue(validBlockHit >= 0);
        assertTrue(clearSegment > validBlockHit);
        assertTrue(
            clearSegment < faceDeduplication,
            "A duplicate visible face must not discard the independently "
                + "cast ray's clear segment"
        );
    }

    @Test
    void dragonPartsPublishOneStableParentIdentity()
            throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "FairPerceptionSampler.java"
        ));

        assertTrue(source.contains(
                "candidate instanceof EnderDragonPart part"
        ));
        assertTrue(source.contains("part.parentMob != null"));
        assertTrue(source.contains(
                "emittedEntityIds.add(perceived.getUUID())"
        ));
        assertTrue(
                source.contains("for (EnderDragonPart part : level.dragonParts())"),
                "Fair entity candidates must include Level multipart parts; "
                    + "the generic EntityTypeTest query omits them"
        );
        assertTrue(
                source.contains("for (EnderDragon dragon : level.getDragons())"),
                "Loaded dragon roots must repopulate multipart candidates "
                    + "when the auxiliary section map is briefly stale"
        );
        assertTrue(
                source.contains("EntityTypeTest.forClass(EnderDragon.class)")
                    && source.contains("loadedDragonRoots"),
                "The ordinary loaded dragon-root query must cover a fight "
                    + "manager whose convenience collection is not refreshed"
        );
        assertTrue(
                source.contains("candidates.removeIf(existing ->")
                    && source.contains("existing.getUUID().equals(part.getUUID())"),
                "Fresh root-owned dragon parts must replace stale same-UUID "
                    + "multipart index entries"
        );
        assertTrue(
                source.contains("level.getDragonFight().dragonUUID()"),
                "The End fight UUID must be a final loaded-root fallback"
        );
        assertTrue(
                source.contains("level.isLoaded(candidate.blockPosition())"),
                "Broad multipart indexing must still apply loaded-section "
                    + "gates before publication"
        );
        assertTrue(source.contains("candidates.subList("));
        assertTrue(source.contains("\"interactionAimX\""));
        assertTrue(source.contains("\"interactionAimY\""));
        assertTrue(source.contains("\"interactionAimZ\""));
        assertTrue(source.contains(
                "visibleEntityProperties("
        ));
        assertTrue(source.contains("projectileThreat"));
        assertTrue(source.contains("isCurrentThreat(player, entity)"));
        assertTrue(source.contains(
                "canonicalPerceivedEntity(candidate).getUUID()"
        ));
        assertTrue(source.contains(
                "candidate instanceof EnderDragonPart part"
        ));
        assertTrue(
                source.contains("part.parentMob.isAlive()")
                    && source.contains("part.parentMob.isRemoved()"),
                "Dragon-part threat identity must follow its live parent"
        );
        assertTrue(
                source.contains("anyVisualPointInView"),
                "Entity FOV must test bounded collider points rather than "
                    + "requiring only a tall entity's eye to be in frame"
        );
        assertTrue(
                source.contains("emittedEntityIds.contains(part.parentMob.getUUID())"),
                "Multipart siblings must not consume the finite LOS budget "
                    + "after their semantic parent was published"
        );
        assertTrue(
                source.contains("isCurrentThreat(player, entity)"),
                "Finite entity perception must prioritize bounded hostile "
                    + "candidates before neutral aftermath entities"
        );
        assertTrue(
                source.contains("candidate instanceof EnderDragon dragon")
                    && source.contains("isHostilePerceivedEntity(perceived)"),
                "The canonical dragon root must remain a hostile semantic "
                    + "target even though vanilla does not mark it Enemy"
        );
        assertTrue(
                source.contains("instanceof EnderDragon ? 0 : 1"),
                "Dragon roots and multipart colliders must not be starved "
                    + "by breath-cloud LOS checks"
        );
        assertTrue(
                source.contains("candidate instanceof AreaEffectCloud cloud")
                    && source.contains("!cloud.isWaiting()")
                    && source.contains("cloud.getRadius() > 0.1F"),
                "Active visible dragon-breath clouds must publish a fair "
                    + "proximity threat instead of remaining neutral"
        );
    }

    @Test
    void dragonPartAimStartsInsideItsColliderBeforeEyeFallback()
            throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/perception/"
                    + "FairPerceptionSampler.java"
        ));
        final int dragonPartBranch = source.indexOf(
                "if (entity instanceof EnderDragonPart)"
        );
        final int centerPoint = source.indexOf(
                "new Vec3(centerX, centerY, centerZ)",
                dragonPartBranch
        );
        final int eyeFallback = source.indexOf(
                "entity.getEyePosition()",
                dragonPartBranch
        );

        assertTrue(dragonPartBranch >= 0);
        assertTrue(centerPoint > dragonPartBranch);
        assertTrue(
                eyeFallback > centerPoint,
                "Dragon-part eye position must remain a fallback; "
                    + "a collider-center point is the first legal aim hint"
        );
    }
}
