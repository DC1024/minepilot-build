package dev.mcai.companion.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BehaviorArbiterTest {
    @Test
    void emergencyClaimSuppressesSkillAndIdle() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger emergency = new AtomicInteger();
        final AtomicInteger skill = new AtomicInteger();
        final AtomicInteger idle = new AtomicInteger();

        final var resolution = arbiter.arbitrate(
                20L,
                List.of(
                        candidate(
                                BehaviorArbiter.Lane.IDLE_EQUIPMENT,
                                idle,
                                false
                        ),
                        candidate(
                                BehaviorArbiter.Lane.ACTIVE_SKILL,
                                skill,
                                true
                        ),
                        candidate(
                                BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                                emergency,
                                true
                        )
                )
        );

        assertTrue(resolution.claimedBy(
                BehaviorArbiter.Lane.EMERGENCY_SURVIVAL
        ));
        assertEquals(
                List.of(BehaviorArbiter.Lane.EMERGENCY_SURVIVAL),
                resolution.attempted()
        );
        assertEquals(1, emergency.get());
        assertEquals(0, skill.get());
        assertEquals(0, idle.get());
    }

    @Test
    void passingEmergencyAllowsSkillButStillSuppressesIdle() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger emergency = new AtomicInteger();
        final AtomicInteger skill = new AtomicInteger();
        final AtomicInteger idle = new AtomicInteger();

        final var resolution = arbiter.arbitrate(
                21L,
                List.of(
                        candidate(
                                BehaviorArbiter.Lane.IDLE_EQUIPMENT,
                                idle,
                                true
                        ),
                        candidate(
                                BehaviorArbiter.Lane.ACTIVE_SKILL,
                                skill,
                                true
                        ),
                        candidate(
                                BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                                emergency,
                                false
                        )
                )
        );

        assertTrue(resolution.claimedBy(
                BehaviorArbiter.Lane.ACTIVE_SKILL
        ));
        assertEquals(1, emergency.get());
        assertEquals(1, skill.get());
        assertEquals(0, idle.get());
    }

    @Test
    void sameTickReplayNeverRunsCandidatesTwice() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger calls = new AtomicInteger();
        final List<BehaviorArbiter.Candidate> candidates =
                List.of(candidate(
                        BehaviorArbiter.Lane.ACTIVE_SKILL,
                        calls,
                        true
                ));

        final var first = arbiter.arbitrate(30L, candidates);
        final var replay = arbiter.arbitrate(30L, candidates);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.status(), replay.status());
        assertEquals(first.lane(), replay.lane());
        assertEquals(1, calls.get());
    }

    @Test
    void regressedTickFailsClosedWithoutRunningCandidate() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger calls = new AtomicInteger();
        arbiter.arbitrate(
                40L,
                List.of(candidate(
                        BehaviorArbiter.Lane.ACTIVE_SKILL,
                        calls,
                        false
                ))
        );

        final var regressed = arbiter.arbitrate(
                39L,
                List.of(candidate(
                        BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                        calls,
                        true
                ))
        );

        assertTrue(regressed.failedClosed());
        assertEquals("non_monotonic_server_tick", regressed.reason());
        assertEquals(1, calls.get());
    }

    @Test
    void candidateExceptionFailsClosedBeforeLowerPriorityLane() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger idle = new AtomicInteger();

        final var failed = arbiter.arbitrate(
                50L,
                List.of(
                        new BehaviorArbiter.Candidate(
                                BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                                () -> {
                                    throw new IllegalStateException("boom");
                                }
                        ),
                        candidate(
                                BehaviorArbiter.Lane.IDLE_EQUIPMENT,
                                idle,
                                true
                        )
                )
        );

        assertTrue(failed.failedClosed());
        assertEquals(
                BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                failed.lane().orElseThrow()
        );
        assertEquals(0, idle.get());
    }

    @Test
    void duplicateLaneFailsClosedBeforeAnyCandidateRuns() {
        final BehaviorArbiter arbiter = new BehaviorArbiter();
        final AtomicInteger calls = new AtomicInteger();

        final var failed = arbiter.arbitrate(
                60L,
                List.of(
                        candidate(
                                BehaviorArbiter.Lane.ACTIVE_SKILL,
                                calls,
                                false
                        ),
                        candidate(
                                BehaviorArbiter.Lane.ACTIVE_SKILL,
                                calls,
                                true
                        )
                )
        );

        assertTrue(failed.failedClosed());
        assertEquals("duplicate_behavior_lane", failed.reason());
        assertEquals(0, calls.get());
    }

    private static BehaviorArbiter.Candidate candidate(
            final BehaviorArbiter.Lane lane,
            final AtomicInteger calls,
            final boolean claim
    ) {
        return new BehaviorArbiter.Candidate(
                lane,
                () -> {
                    calls.incrementAndGet();
                    return claim
                            ? BehaviorArbiter.Attempt.claim(
                                    lane.name().toLowerCase()
                            )
                            : BehaviorArbiter.Attempt.pass();
                }
        );
    }
}
