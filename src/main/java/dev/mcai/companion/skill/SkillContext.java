package dev.mcai.companion.skill;

/**
 * Server-authoritative facts supplied to one local skill invocation.
 *
 * <p>The risk score describes the next locally contemplated atomic segment,
 * from {@code 0.0} (known safe) to {@code 1.0} (certainly unacceptable).
 * Callers construct a fresh context from the companion body on each game
 * tick. Skills receive their world services through ordinary constructor
 * injection, keeping this runtime independent of Minecraft classes.</p>
 */
public record SkillContext(
        long goalRevision,
        long worldRevision,
        long gameTick,
        boolean hardcore,
        boolean modelConnected,
        double riskScore
) {
    public SkillContext {
        if (goalRevision < 0 || worldRevision < 0 || gameTick < 0) {
            throw new IllegalArgumentException("Revisions and gameTick must be non-negative");
        }
        if (!Double.isFinite(riskScore) || riskScore < 0.0 || riskScore > 1.0) {
            throw new IllegalArgumentException("riskScore must be a finite value from zero to one");
        }
    }
}
