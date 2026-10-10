package dev.mcai.companion.brain;

/**
 * Local handling result for a model-requested observation.
 *
 * <p>A request never grants new authority. In particular, UNSUPPORTED means
 * the runtime did not manufacture or pretend to have the requested view.</p>
 */
public enum ObservationRequestStatus {
    ACCEPTED,
    UNSUPPORTED,
    REJECTED
}
