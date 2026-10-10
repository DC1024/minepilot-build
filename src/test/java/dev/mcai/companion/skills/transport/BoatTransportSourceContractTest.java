package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BoatTransportSourceContractTest {
    @Test
    void productionActuatorSubmitsInputWithoutIntegratingPhysics()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/transport/"
                        + "ServerBoatSkillActuator.java"
        ));

        assertTrue(source.contains("FairPlayerActuator"));
        assertTrue(source.contains("HeadlessBoatAuthority"));
        assertTrue(source.contains("ServerboundPaddleBoatPacket"));
        assertTrue(source.contains(
            "|| !boat.isLocalInstanceAuthoritative())"
        ));
        assertFalse(source.contains("FORWARD_ACCELERATION"));
        assertFalse(source.contains("BACKWARD_ACCELERATION"));
        assertFalse(source.contains("TURN_ONLY_ACCELERATION"));
        assertFalse(source.contains("MAXIMUM_ANGULAR_VELOCITY"));
        assertFalse(source.contains("MAXIMUM_HORIZONTAL_SPEED"));
        assertFalse(source.contains("COLLISION_DRAG"));
        assertFalse(source.contains(".move("));
        assertFalse(source.contains(".setDeltaMovement("));
        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".teleport"));
        assertFalse(source.contains(".absSnapTo("));
        assertFalse(source.contains("ServerboundMoveVehiclePacket"));
        assertFalse(source.contains(".getBlockState("));
        assertFalse(source.contains(".getEntities("));
        assertFalse(source.contains(".setBlock("));
        assertFalse(source.contains(".destroyBlock("));
    }

    @Test
    void exactVanillaAuthorityAndControlHooksAreReleaseRequired()
            throws IOException {
        final String invoker = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/mixin/"
                + "AbstractBoatControlInvoker.java"
        ));
        final String authority = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/mixin/"
                + "HeadlessBoatAuthorityMixin.java"
        ));
        final String scope = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/skills/transport/"
                + "HeadlessBoatAuthority.java"
        ));
        final String control = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/mixin/"
                + "HeadlessBoatControlMixin.java"
        ));
        final String config = Files.readString(Path.of(
            "src/main/resources/mcai_companion.mixins.json"
        ));
        final String build = Files.readString(Path.of("build.gradle"));

        assertTrue(invoker.contains("@Mixin(AbstractBoat.class)"));
        assertTrue(invoker.contains("@Invoker(\"controlBoat\")"));
        assertTrue(authority.contains("@Mixin(Entity.class)"));
        assertTrue(authority.contains(
            "method = \"isLocalInstanceAuthoritative\""
        ));
        assertTrue(authority.contains(
            "entity instanceof AbstractBoat boat"
        ));
        assertTrue(authority.contains(
            "callback.setReturnValue(true)"
        ));
        assertTrue(scope.contains("instanceof ServerPlayer controller"));
        assertTrue(scope.contains("boat.level().isClientSide()"));
        assertTrue(scope.contains("AiProfileMarker.isMarked(profile)"));
        assertTrue(control.contains("@Mixin(AbstractBoat.class)"));
        assertTrue(control.contains("method = \"tick\""));
        assertTrue(control.contains("AbstractBoat;move("));
        assertTrue(control.contains("shift = At.Shift.BEFORE"));
        assertTrue(control.contains(
            "mcaiCompanion$invokeControlBoat()"
        ));
        assertTrue(control.contains(
            "controller.checkMovementStatistics("
        ));
        assertTrue(config.contains(
            "\"AbstractBoatControlInvoker\""
        ));
        assertTrue(config.contains(
            "\"HeadlessBoatAuthorityMixin\""
        ));
        assertTrue(config.contains(
            "\"HeadlessBoatControlMixin\""
        ));
        assertTrue(build.contains(
            "'AbstractBoatControlInvoker.class'"
        ));
        assertTrue(build.contains(
            "'dev/mcai/companion/skills/transport/'"
        ));
        assertTrue(build.contains(
            "+ 'HeadlessBoatAuthority.class'"
        ));
        assertTrue(build.contains(
            "'HeadlessBoatAuthorityMixin.class'"
        ));
        assertTrue(build.contains(
            "'HeadlessBoatControlMixin.class'"
        ));
    }

    @Test
    void frameSourceAddsOnlyOwnVehicleStateToFairObservation()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/transport/"
                        + "ServerBoatSkillFrameSource.java"
        ));

        assertTrue(source.contains("SemanticObservation"));
        assertTrue(source.contains("getControlledVehicle()"));
        assertFalse(source.contains(".getBlockState("));
        assertFalse(source.contains(".getEntities("));
        assertFalse(source.contains(".getChunk("));
        assertFalse(source.contains("structure"));
    }
}
