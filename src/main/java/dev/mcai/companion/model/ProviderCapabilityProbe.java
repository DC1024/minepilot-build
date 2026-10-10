package dev.mcai.companion.model;

import java.util.concurrent.CompletionStage;

/**
 * One-shot probe for a single configured provider/model pair.
 *
 * <p>Construction performs no I/O. The first explicit call to {@link #probe()}
 * starts probing; later calls return the same result and never create another
 * billable request. Create a new probe instance for an intentional re-check.</p>
 */
public interface ProviderCapabilityProbe extends AutoCloseable {
    CompletionStage<CapabilityProbeOutcome> probe();

    @Override
    void close();
}
