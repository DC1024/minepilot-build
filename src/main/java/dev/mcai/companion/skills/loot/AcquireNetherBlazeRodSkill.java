package dev.mcai.companion.skills.loot;

import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.combat.CombatHardcoreRisk;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Fair, bounded Nether progression macro for one observed Blaze.
 *
 * <p>This is deliberately a policy-constrained facade over the generic local
 * combat-to-pickup controller. It can start only from the companion's own
 * current first-person observation in the Nether, and only when the selected
 * observation is a hostile Blaze. Combat still uses normal cooldown, reach,
 * line-of-sight, movement and shield handling; collection still requires an
 * ordinary visible {@code minecraft:item} entity and confirmation in the
 * companion's owned inventory. The fixed expected item prevents a model from
 * claiming arbitrary loot.</p>
 */
public final class AcquireNetherBlazeRodSkill
        implements Skill<AcquireNetherBlazeRodParameters> {
    public static final String NAME = "acquire_nether_blaze_rod";

    private static final String BLAZE = "minecraft:blaze";
    private static final String BLAZE_ROD = "minecraft:blaze_rod";

    private final UUID expectedPlayerId;
    private final CoreSkillFrameSource coreFrames;
    private final EngageAndCollectObservedDropSkill delegate;

    private EngageAndCollectParameters delegateParameters;
    private SkillFailure wrapperFailure;
    private boolean cancelled;

    public AcquireNetherBlazeRodSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        delegate = new EngageAndCollectObservedDropSkill(
                expectedPlayerId,
                Objects.requireNonNull(core, "core"),
                coreFrames,
                Objects.requireNonNull(
                        interactions,
                        "interactions"
                ),
                Objects.requireNonNull(
                        interactionFrames,
                        "interactionFrames"
                )
        );
    }

    @Override
    public SkillParameterParser<AcquireNetherBlazeRodParameters>
            parameters() {
        return LootSkillParameters::parseNetherBlazeRod;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        if (delegateParameters == null) {
            return false;
        }
        if (!delegate.awaitingObservedDrop()) {
            return true;
        }
        /*
         * The combat child has ended and the collection child owns the
         * next first-person movement frame.  A recently killed Blaze is
         * intentionally retained by EmergencySurvivalController for a
         * bounded reacquisition sweep; that stale lease used to cancel every
         * forward pickup frame and strand a visible rod 1--3 blocks away.
         * Suppress only that stale proximity lease while no unrelated hostile
         * or projectile is currently visible.  A fresh threat still returns
         * false and remains emergency-owned on this tick.
         */
        return ownedFrame()
                .map(frame -> frame.visibleEntities().stream()
                        .noneMatch(entity -> entity.hostile()
                                || entity.projectile()))
                .orElse(false);
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return managesVisibleHostileProximity();
    }

    /**
     * Exposes only the child phase needed by the bounded resource-macro
     * hand-off. It does not expose an entity handle or any hidden world
     * state.
     */
    boolean awaitingObservedDrop() {
        return delegateParameters != null
                && delegate.awaitingObservedDrop();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()
                || delegateParameters != null
                    && delegate.awaitingObservedDrop()) {
            return OptionalDouble.empty();
        }
        return CombatHardcoreRisk.threshold(
                context,
                frame.orElseThrow(),
                1.0
        );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<SkillFailure> resourceBinding =
                resourceBindingFailure(parameters);
        if (resourceBinding.isPresent()) {
            return resourceBinding;
        }
        return delegate.preconditions(
                context,
                delegateParameters(parameters)
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (resourceBindingFailure(parameters).isPresent()) {
            throw new IllegalStateException(
                    "Nether Blaze observation changed before start"
            );
        }
        delegateParameters = delegateParameters(parameters);
        wrapperFailure = null;
        cancelled = false;
        delegate.start(context, delegateParameters);
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        if (wrapperFailure != null) {
            return SkillTickResult.failed(wrapperFailure);
        }
        if (delegateParameters == null) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return failAndCancel(
                    context,
                    NAME + ".body_unavailable"
            );
        }
        if (!DimensionRef.NETHER.equals(
                current.orElseThrow().dimension()
        )) {
            return failAndCancel(
                    context,
                    NAME + ".dimension_changed"
            );
        }
        return delegate.tick(context, delegateParameters);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        if (delegateParameters == null) {
            return new SkillCheckpoint(
                    1,
                    "{\"phase\":\"IDLE\",\"resource\":\"blaze_rod\"}"
            );
        }
        return delegate.checkpoint(context, delegateParameters);
    }

    @Override
    public void cancel(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        if (delegateParameters != null) {
            delegate.cancel(context, delegateParameters);
        }
        delegateParameters = null;
        wrapperFailure = null;
        cancelled = true;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final AcquireNetherBlazeRodParameters parameters
    ) {
        if (wrapperFailure != null) {
            return SkillResult.failed(wrapperFailure);
        }
        if (cancelled) {
            return SkillResult.cancelled();
        }
        if (delegateParameters == null) {
            return SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        }
        return delegate.result(context, delegateParameters);
    }

    private Optional<SkillFailure> resourceBindingFailure(
            final AcquireNetherBlazeRodParameters parameters
    ) {
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!DimensionRef.NETHER.equals(frame.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".nether_required"
            ));
        }
        if (frame.observationRevision()
                != parameters.sampleSequence()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stale_observation_id"
            ));
        }
        final int index = parameters.observationIndex();
        if (index < 0 || index >= frame.visibleEntities().size()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".invalid_observation_id"
            ));
        }
        final VisibleEntity target =
                frame.visibleEntities().get(index);
        if (!BLAZE.equals(target.entityTypeId())
                || !target.hostile()
                || target.projectile()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".visible_blaze_required"
            ));
        }
        return Optional.empty();
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private static EngageAndCollectParameters delegateParameters(
            final AcquireNetherBlazeRodParameters parameters
    ) {
        return new EngageAndCollectParameters(
                parameters.sampleSequence(),
                parameters.observationId(),
                BLAZE_ROD,
                parameters.maximumTicks()
        );
    }

    static boolean isNoDropFailure(final SkillFailure failure) {
        return failure != null
                && (EngageAndCollectObservedDropSkill.NAME
                        + ".expected_drop_not_observed").equals(
                                failure.code()
                        );
    }

    private SkillTickResult failAndCancel(
            final SkillContext context,
            final String code
    ) {
        final SkillFailure failure = SkillFailure.of(code);
        if (delegateParameters != null) {
            delegate.cancel(context, delegateParameters);
        }
        wrapperFailure = failure;
        cancelled = false;
        return SkillTickResult.failed(failure);
    }
}
