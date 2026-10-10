package dev.mcai.companion.skills.transport;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.skin.AiProfileMarker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Single scope predicate for the server-local headless boat physics path.
 */
public final class HeadlessBoatAuthority {
    private HeadlessBoatAuthority() {
    }

    public static boolean usesServerLocalControl(
            final AbstractBoat boat
    ) {
        if (boat == null
                || !(boat.getControllingPassenger()
                    instanceof ServerPlayer controller)) {
            return false;
        }
        return eligibleProfile(
                boat.level().isClientSide(),
                controller.getGameProfile()
        );
    }

    /**
     * Loader-independent core of the authority boundary, exposed for a
     * deterministic JVM test.
     */
    static boolean eligibleProfile(
            final boolean clientSide,
            final GameProfile profile
    ) {
        return !clientSide
                && profile != null
                && AiProfileMarker.isMarked(profile);
    }
}
