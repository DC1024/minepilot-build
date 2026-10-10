package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.PerceptionNavMapper;
import dev.mcai.companion.perception.SemanticObservation;
import java.util.Objects;
import java.util.Optional;

/**
 * Observation-stage adapter. Call {@link #publish} when fair semantic
 * perception is produced, outside the skill's two-millisecond tick budget.
 */
public final class MappedCoreSkillFrameSource implements CoreSkillFrameSource {
    private final PerceptionNavMapper mapper;
    private CoreSkillFrame current;

    public MappedCoreSkillFrameSource() {
        this(new PerceptionNavMapper());
    }

    public MappedCoreSkillFrameSource(PerceptionNavMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public synchronized CoreSkillFrame publish(SemanticObservation observation) {
        Objects.requireNonNull(observation, "observation");
        LocalNavSnapshot navigation = mapper.ingest(observation);
        current = CoreSkillFrame.from(observation, navigation);
        return current;
    }

    @Override
    public synchronized Optional<CoreSkillFrame> current() {
        return Optional.ofNullable(current);
    }

    public synchronized void reset() {
        mapper.reset();
        current = null;
    }
}
