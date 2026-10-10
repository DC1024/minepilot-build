package dev.mcai.companion.skills.loot;

import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fair early-game food acquisition from one visible adult farm animal.
 *
 * <p>The policy deliberately excludes babies, leashed animals and custom
 * named animals. It does not treat this hunt as hostile-combat management, so
 * the ordinary emergency supervisor can still cancel it when a hostile mob or
 * another hardcore danger appears. Attacks, pursuit, drops and pickup all
 * remain on the normal player action path.</p>
 */
public final class HuntObservedFoodAnimalSkill
        implements Skill<HuntObservedFoodAnimalParameters> {
    public static final String NAME = "hunt_observed_food_animal";
    private static final long MAX_BINDING_SAMPLE_LAG = 512L;

    private static final Map<String, Set<String>> FOOD_DROPS = Map.of(
            "minecraft:cow",
            Set.of("minecraft:beef", "minecraft:cooked_beef"),
            "minecraft:mooshroom",
            Set.of("minecraft:beef", "minecraft:cooked_beef"),
            "minecraft:pig",
            Set.of(
                    "minecraft:porkchop",
                    "minecraft:cooked_porkchop"
            ),
            "minecraft:sheep",
            Set.of("minecraft:mutton", "minecraft:cooked_mutton"),
            "minecraft:chicken",
            Set.of("minecraft:chicken", "minecraft:cooked_chicken"),
            "minecraft:rabbit",
            Set.of("minecraft:rabbit", "minecraft:cooked_rabbit")
    );

    private final UUID expectedPlayerId;
    private final CoreSkillFrameSource coreFrames;
    private final EngageAndCollectObservedDropSkill delegate;

    private EngageAndCollectParameters delegateParameters;
    private SkillFailure wrapperFailure;
    private boolean cancelled;

    public HuntObservedFoodAnimalSkill(
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
                ),
                HuntObservedFoodAnimalSkill::legalFoodAnimalTarget,
                false,
                false
        );
    }

    @Override
    public SkillParameterParser<HuntObservedFoodAnimalParameters>
            parameters() {
        return LootSkillParameters::parseFoodAnimalHunt;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final HuntObservedFoodAnimalParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<SkillFailure> binding =
                resourceBindingFailure(parameters);
        if (binding.isPresent()) {
            return binding;
        }
        return delegate.preconditions(
                context,
                delegateParameters(parameters)
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final HuntObservedFoodAnimalParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (resourceBindingFailure(parameters).isPresent()) {
            throw new IllegalStateException(
                    "Food-animal observation changed before start"
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
            final HuntObservedFoodAnimalParameters parameters
    ) {
        if (wrapperFailure != null) {
            return SkillTickResult.failed(wrapperFailure);
        }
        if (delegateParameters == null) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (ownedFrame().isEmpty()) {
            return failAndCancel(context, NAME + ".body_unavailable");
        }
        return delegate.tick(context, delegateParameters);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final HuntObservedFoodAnimalParameters parameters
    ) {
        if (delegateParameters == null) {
            return new SkillCheckpoint(
                    1,
                    "{\"phase\":\"IDLE\",\"resource\":\"food\"}"
            );
        }
        return delegate.checkpoint(context, delegateParameters);
    }

    @Override
    public void cancel(
            final SkillContext context,
            final HuntObservedFoodAnimalParameters parameters
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
            final HuntObservedFoodAnimalParameters parameters
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

    static boolean legalFoodAnimalTarget(
            final VisibleEntity target
    ) {
        Objects.requireNonNull(target, "target");
        final Map<String, String> properties =
                target.visibleProperties();
        return !target.hostile()
                && !target.projectile()
                && FOOD_DROPS.containsKey(target.entityTypeId())
                && "false".equals(properties.get("baby"))
                && "false".equals(properties.get("customNamed"))
                && "false".equals(properties.get("leashed"))
                && !"true".equals(properties.get("tamed"));
    }

    private Optional<SkillFailure> resourceBindingFailure(
            final HuntObservedFoodAnimalParameters parameters
    ) {
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (frame.observationRevision()
                    < parameters.sampleSequence()
                || frame.observationRevision()
                    - parameters.sampleSequence()
                        > MAX_BINDING_SAMPLE_LAG) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stale_observation_id"
            ));
        }
        final Optional<VisibleEntity> authored =
                coreFrames.visibleEntityAtObservation(
                        parameters.sampleSequence(),
                        parameters.observationIndex()
                )
                .filter(binding -> binding.dimension().equals(
                        frame.dimension()
                ))
                .map(
                    CoreSkillFrameSource.VisibleEntityBinding::entity
                );
        if (authored.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    frame.observationRevision()
                            == parameters.sampleSequence()
                            ? NAME + ".invalid_observation_id"
                            : NAME + ".stale_observation_id"
            ));
        }
        final VisibleEntity target = authored.orElseThrow();
        if (!legalFoodAnimalTarget(target)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unowned_adult_food_animal_required"
            ));
        }
        if (!acceptedFoodDrop(
                target.entityTypeId(),
                parameters.expectedItemId()
        )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".expected_food_mismatch"
            ));
        }
        return Optional.empty();
    }

    static boolean acceptedFoodDrop(
            final String entityTypeId,
            final String itemId
    ) {
        final Set<String> drops = FOOD_DROPS.get(entityTypeId);
        return drops != null && drops.contains(itemId);
    }

    static Optional<String> defaultFoodDrop(
            final String entityTypeId
    ) {
        return Optional.ofNullable(switch (entityTypeId) {
            case "minecraft:cow", "minecraft:mooshroom" ->
                    "minecraft:beef";
            case "minecraft:pig" -> "minecraft:porkchop";
            case "minecraft:sheep" -> "minecraft:mutton";
            case "minecraft:chicken" -> "minecraft:chicken";
            case "minecraft:rabbit" -> "minecraft:rabbit";
            default -> null;
        });
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private static EngageAndCollectParameters delegateParameters(
            final HuntObservedFoodAnimalParameters parameters
    ) {
        return new EngageAndCollectParameters(
                parameters.sampleSequence(),
                parameters.observationId(),
                parameters.expectedItemId(),
                parameters.maximumTicks()
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
