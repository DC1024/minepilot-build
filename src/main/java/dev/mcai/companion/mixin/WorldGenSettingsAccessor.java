package dev.mcai.companion.mixin;

import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Narrow accessor used by release-excluded GameTests to restore vanilla
 * structure lookup inside Mojang's structure-disabled flat test server.
 * Production runtime code never calls it.
 */
@Mixin(WorldGenSettings.class)
public interface WorldGenSettingsAccessor {
    @Mutable
    @Accessor("options")
    void mcai$setOptions(WorldOptions options);
}
