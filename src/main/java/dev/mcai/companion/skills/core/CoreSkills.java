package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;
import java.util.UUID;

public final class CoreSkills {
    public static final String MOVE_TO = "move_to";
    public static final String LOOK_AT = "look_at";
    public static final String SAFE_IDLE = "safe_idle";
    public static final String FOLLOW_ENTITY = "follow_entity";

    private CoreSkills() {
    }

    /**
     * Registers the production core slice.
     *
     * <p>Integration order for each server tick is: publish a new fair
     * semantic observation when due, tick the skill supervisor, then call
     * {@link ServerOwnedCoreSkillActuator#postServerTick()}. On any terminal
     * supervisor/body/runtime transition, call
     * {@link ServerOwnedCoreSkillActuator#quiesceNow()} before that normal
     * post-tick call. The action owner dynamically rebinds after respawn and
     * enforces its one-tick fail-safe lease.</p>
     */
    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            ServerOwnedCoreSkillActuator actuator,
            CoreSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LocalAStarPlanner planner,
            CoreSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(policy, "policy");
        return registry
                .register(MOVE_TO, new MoveToSkill(
                        playerId,
                        actuator,
                        frames,
                        planner,
                        policy
                ))
                .register(LOOK_AT, new LookAtSkill(
                        playerId,
                        actuator,
                        frames
                ))
                .register(FOLLOW_ENTITY, new FollowEntitySkill(
                        playerId,
                        actuator,
                        frames,
                        planner,
                        policy
                ))
                .register(SAFE_IDLE, new SafeIdleSkill(
                        playerId,
                        actuator,
                        frames
                ));
    }
}
