package dev.mcai.companion.skill;

import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.model.SkillArgument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSupervisorTest {
    @Test
    void exposesOnlyTheActiveSkillsHostileProximityOwnership() {
        final ScriptedSkill skill = new ScriptedSkill();
        skill.managesVisibleHostileProximity = true;

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertFalse(
                    supervisor
                            .activeSkillManagesVisibleHostileProximity()
            );
            assertTrue(supervisor.start(
                    decision("collect", 7, 11, "count", "1"),
                    context(7, 11, 100, false, true, 0.05)
            ).accepted());
            assertTrue(
                    supervisor
                            .activeSkillManagesVisibleHostileProximity()
            );
            skill.steps.add(SkillTickResult.completed());
            supervisor.tick(
                    context(7, 11, 101, false, true, 0.05)
            );
            assertFalse(
                    supervisor
                            .activeSkillManagesVisibleHostileProximity()
            );
        }
    }

    @Test
    void exposesWorldRevisionTransitionOwnershipOnlyForTheActiveSkill() {
        final ScriptedSkill skill = new ScriptedSkill();
        skill.allowsWorldRevisionTransition = true;

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertFalse(supervisor.activeSkillAllowsWorldRevisionTransition());
            assertTrue(supervisor.start(
                    decision("collect", 7, 11, "count", "1"),
                    context(7, 11, 100, false, true, 0.05)
            ).accepted());
            assertTrue(supervisor.activeSkillAllowsWorldRevisionTransition());
            skill.steps.add(SkillTickResult.completed());
            supervisor.tick(context(7, 11, 101, false, true, 0.05));
            assertFalse(supervisor.activeSkillAllowsWorldRevisionTransition());
        }
    }

    @Test
    void parsesTypedArgumentsAndRunsOneSkillToCompletion() throws Exception {
        ScriptedSkill skill = new ScriptedSkill();
        skill.steps.add(SkillTickResult.running(true, false));
        skill.steps.add(SkillTickResult.completed());
        CountDownLatch checkpointSaved = new CountDownLatch(1);
        AtomicReference<SavedCheckpoint> saved = new AtomicReference<>();
        SkillCheckpointSink sink = (
                name,
                goalRevision,
                worldRevision,
                sequence,
                gameTick,
                checkpoint
        ) -> {
            saved.set(new SavedCheckpoint(
                    name,
                    goalRevision,
                    worldRevision,
                    sequence,
                    gameTick,
                    checkpoint
            ));
            checkpointSaved.countDown();
            return CompletableFuture.completedFuture(null);
        };

        try (SkillSupervisor supervisor = supervisor(skill, sink, permissivePolicy(20))) {
            SkillSupervisor.StartOutcome started = supervisor.start(
                    decision("collect", 7, 11, "count", "128"),
                    context(7, 11, 100, false, true, 0.05)
            );

            assertTrue(started.accepted());
            assertEquals(SkillSupervisor.State.RUNNING, started.snapshot().state());
            assertEquals(128, skill.startedWith);
            assertEquals(1, skill.preconditionCalls);
            assertEquals(1, skill.startCalls);

            SkillSupervisor.Snapshot first = supervisor.tick(
                    context(7, 11, 101, false, true, 0.05)
            );
            assertEquals(SkillSupervisor.State.RUNNING, first.state());
            assertEquals(1, first.executedTicks());

            SkillSupervisor.Snapshot completed = supervisor.tick(
                    context(7, 11, 102, false, true, 0.05)
            );
            assertEquals(SkillSupervisor.State.COMPLETED, completed.state());
            assertEquals(SkillResult.Status.COMPLETED,
                    completed.terminalResult().orElseThrow().status());
            assertEquals(2, completed.executedTicks());
            assertEquals(1, completed.checkpointSequence());
            assertEquals(1, skill.resultCalls);
            assertTrue(checkpointSaved.await(2, TimeUnit.SECONDS));

            SavedCheckpoint persisted = saved.get();
            assertNotNull(persisted);
            assertEquals("collect", persisted.skillName());
            assertEquals(7, persisted.goalRevision());
            assertEquals(11, persisted.worldRevision());
            assertEquals(1, persisted.sequence());
            assertEquals(102, persisted.gameTick());
        }
    }

    @Test
    void validatesTypedArgumentsBeforePreconditionsOrStart() {
        ScriptedSkill skill = new ScriptedSkill();
        SkillRegistry registry = new SkillRegistry().register("collect", skill);

        Optional<String> modelError = registry.modelArgumentValidators()
                .get("collect")
                .validate(List.of(new SkillArgument("count", "not-an-integer")));
        assertEquals(Optional.of("invalid_count"), modelError);

        try (SkillSupervisor supervisor = supervisor(
                registry,
                SkillCheckpointSink.discard(),
                permissivePolicy(20),
                new AtomicLong()
        )) {
            SkillSupervisor.StartOutcome rejected = supervisor.start(
                    decision("collect", 1, 2, "count", "not-an-integer"),
                    context(1, 2, 5, false, true, 0.0)
            );

            assertFalse(rejected.accepted());
            assertEquals(
                    "invalid_count",
                    rejected.failure().orElseThrow().code()
            );
            assertEquals(
                    "invalid_count",
                    rejected.snapshot()
                            .lastStartRejection()
                            .orElseThrow()
                            .code()
            );
            assertEquals(0, skill.preconditionCalls);
            assertEquals(0, skill.startCalls);

            assertTrue(supervisor.start(
                    decision("collect", 1, 2, "count", "1"),
                    context(1, 2, 6, false, true, 0.0)
            ).accepted());
            assertTrue(
                    supervisor.snapshot()
                            .lastStartRejection()
                            .isEmpty()
            );
        }
    }

    @Test
    void rejectsStaleDecisionsAndAllowsOnlyOneActiveSkill() {
        ScriptedSkill skill = new ScriptedSkill();
        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            SkillSupervisor.StartOutcome staleGoal = supervisor.start(
                    decision("collect", 4, 9, "count", "1"),
                    context(5, 9, 20, false, true, 0.0)
            );
            assertEquals(
                    "stale_goal_revision",
                    staleGoal.failure().orElseThrow().code()
            );
            assertEquals(0, skill.startCalls);

            assertTrue(supervisor.start(
                    decision("collect", 5, 9, "count", "1"),
                    context(5, 9, 20, false, true, 0.0)
            ).accepted());
            SkillSupervisor.StartOutcome second = supervisor.start(
                    decision("collect", 5, 9, "count", "2"),
                    context(5, 9, 20, false, true, 0.0)
            );
            assertFalse(second.accepted());
            assertEquals(
                    "skill_already_active",
                    second.failure().orElseThrow().code()
            );

            SkillSupervisor.Snapshot staleWorld = supervisor.tick(
                    context(5, 10, 21, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.FAILED, staleWorld.state());
            assertEquals(
                    "stale_world_revision",
                    failureCode(staleWorld)
            );
            assertEquals(0, skill.tickCalls);
        }
    }

    @Test
    void defersCancellationUntilTheNextSafeCheckpoint() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.steps.add(SkillTickResult.running(true, false));
        skill.steps.add(SkillTickResult.running(true, false));
        skill.steps.add(SkillTickResult.running(true, true));

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 2, 3, "count", "8"),
                    context(2, 3, 40, false, true, 0.0)
            ).accepted());
            supervisor.tick(context(2, 3, 41, false, true, 0.0));

            SkillSupervisor.MutationOutcome cancellation = supervisor.requestCancel(
                    context(2, 3, 41, false, true, 0.0)
            );
            assertTrue(cancellation.accepted());
            assertEquals(SkillSupervisor.State.CANCEL_PENDING,
                    cancellation.snapshot().state());
            assertEquals(0, skill.cancelCalls);

            SkillSupervisor.Snapshot stillPending = supervisor.tick(
                    context(2, 3, 42, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.CANCEL_PENDING, stillPending.state());
            assertEquals(0, skill.cancelCalls);

            SkillSupervisor.Snapshot cancelled = supervisor.tick(
                    context(2, 3, 43, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.CANCELLED, cancelled.state());
            assertEquals(SkillResult.Status.CANCELLED,
                    cancelled.terminalResult().orElseThrow().status());
            assertEquals(1, skill.cancelCalls);
            assertEquals(3, skill.tickCalls);
        }
    }

    @Test
    void abandonsOldExecutionWithoutCallingItOnAReplacementBody() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.defaultStep = SkillTickResult.running(true, false);

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 2, 3, "count", "8"),
                    context(2, 3, 40, false, true, 0.0)
            ).accepted());
            supervisor.tick(context(2, 3, 41, false, true, 0.0));

            final SkillSupervisor.Snapshot abandoned =
                supervisor.abandonForSessionEnd();

            assertEquals(SkillSupervisor.State.SAFE_IDLE, abandoned.state());
            assertEquals("body_session_ended", failureCode(abandoned));
            assertEquals(0, skill.cancelCalls);
            assertTrue(supervisor.start(
                    decision("collect", 3, 4, "count", "1"),
                    context(3, 4, 50, false, true, 0.0)
            ).accepted());
        }
    }

    @Test
    void detachesActiveSkillWhenRuntimeLosesModelWithoutCallingCancel() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.defaultStep = SkillTickResult.running(false, false);

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 8, 9, "count", "1"),
                    context(8, 9, 100, false, true, 0.0)
            ).accepted());

            SkillSupervisor.Snapshot detached =
                    supervisor.abandonForModelDisconnect();

            assertEquals(SkillSupervisor.State.SAFE_IDLE, detached.state());
            assertEquals("model_disconnected", failureCode(detached));
            assertEquals(0, skill.tickCalls);
            assertEquals(0, skill.cancelCalls);
            assertTrue(supervisor.start(
                    decision("collect", 10, 11, "count", "1"),
                    context(10, 11, 101, false, true, 0.0)
            ).accepted());
        }
    }

    @Test
    void detectsRepeatedLackOfProgress() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.defaultStep = SkillTickResult.running(false, false);
        SkillRuntimePolicy policy = permissivePolicy(3);

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                policy
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 10, false, true, 0.0)
            ).accepted());
            supervisor.tick(context(1, 1, 11, false, true, 0.0));
            supervisor.tick(context(1, 1, 12, false, true, 0.0));
            SkillSupervisor.Snapshot stalled = supervisor.tick(
                    context(1, 1, 13, false, true, 0.0)
            );

            assertEquals(SkillSupervisor.State.FAILED, stalled.state());
            assertEquals("skill_stalled", failureCode(stalled));
            assertEquals(3, stalled.consecutiveNoProgressTicks());
            assertEquals(0, skill.cancelCalls);
        }
    }

    @Test
    void convertsSkillExceptionsToBoundedNonSensitiveCodes() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.tickException = new IllegalStateException(
                "sk-do-not-expose-this-placeholder"
        );

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                permissivePolicy(20)
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 2, 2, "count", "1"),
                    context(2, 2, 30, false, true, 0.0)
            ).accepted());
            SkillSupervisor.Snapshot failed = supervisor.tick(
                    context(2, 2, 31, false, true, 0.0)
            );

            assertEquals("skill_tick_exception", failureCode(failed));
            assertFalse(failed.toString().contains("do-not-expose"));
        }

        SkillFailure malformed = SkillFailure.of(
                "SECRET\n" + "x".repeat(100)
        );
        assertEquals("skill_failure", malformed.code());
        assertTrue(malformed.code().length() <= SkillFailure.MAX_CODE_CHARACTERS);
    }

    @Test
    void enforcesTickBudgetAndOverallTimeoutWithAMonotonicClock() {
        AtomicLong clock = new AtomicLong();
        ScriptedSkill slow = new ScriptedSkill();
        slow.tickAction = () -> clock.addAndGet(101);
        slow.defaultStep = SkillTickResult.running(true, true);
        SkillRuntimePolicy policy = new SkillRuntimePolicy(
                Duration.ofNanos(100),
                Duration.ofNanos(1_000),
                20,
                0.5,
                0.2
        );

        try (SkillSupervisor supervisor = supervisor(
                new SkillRegistry().register("collect", slow),
                SkillCheckpointSink.discard(),
                policy,
                clock
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, false, true, 0.0)
            ).accepted());
            SkillSupervisor.Snapshot firstSpike = supervisor.tick(
                    context(1, 1, 2, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.RUNNING, firstSpike.state());
            assertEquals(1, firstSpike.consecutiveTickBudgetBreaches());
            SkillSupervisor.Snapshot secondSpike = supervisor.tick(
                    context(1, 1, 3, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.RUNNING, secondSpike.state());
            assertEquals(2, secondSpike.consecutiveTickBudgetBreaches());
            SkillSupervisor.Snapshot overBudget = supervisor.tick(
                    context(1, 1, 4, false, true, 0.0)
            );
            assertEquals("tick_budget_exceeded", failureCode(overBudget));
            assertEquals(3, overBudget.consecutiveTickBudgetBreaches());
            assertEquals(1, slow.cancelCalls);
        }

        AtomicLong timeoutClock = new AtomicLong();
        ScriptedSkill timedOut = new ScriptedSkill();
        try (SkillSupervisor supervisor = supervisor(
                new SkillRegistry().register("collect", timedOut),
                SkillCheckpointSink.discard(),
                policy,
                timeoutClock
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, false, true, 0.0)
            ).accepted());
            timeoutClock.set(1_000);
            SkillSupervisor.Snapshot timeout = supervisor.tick(
                    context(1, 1, 2, false, true, 0.0)
            );
            assertEquals("skill_timeout", failureCode(timeout));
            assertEquals(0, timedOut.tickCalls);
            assertEquals(1, timedOut.cancelCalls);
        }
    }

    @Test
    void anInBudgetSampleResetsTheSustainedOverrunGate() {
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();
        final ScriptedSkill variable = new ScriptedSkill();
        variable.tickAction = () -> clock.addAndGet(
            calls.incrementAndGet() == 2 ? 100 : 101
        );
        variable.defaultStep = SkillTickResult.running(true, true);
        final SkillRuntimePolicy policy = new SkillRuntimePolicy(
            Duration.ofNanos(100),
            Duration.ofNanos(10_000),
            20,
            0.5,
            0.2,
            3
        );

        try (SkillSupervisor supervisor = supervisor(
                new SkillRegistry().register("collect", variable),
                SkillCheckpointSink.discard(),
                policy,
                clock
        )) {
            assertTrue(supervisor.start(
                decision("collect", 1, 1, "count", "1"),
                context(1, 1, 1, false, true, 0.0)
            ).accepted());
            assertEquals(
                1,
                supervisor.tick(context(
                    1, 1, 2, false, true, 0.0
                )).consecutiveTickBudgetBreaches()
            );
            final SkillSupervisor.Snapshot reset = supervisor.tick(
                context(1, 1, 3, false, true, 0.0)
            );
            assertEquals(SkillSupervisor.State.RUNNING, reset.state());
            assertEquals(0, reset.consecutiveTickBudgetBreaches());
            assertEquals(
                SkillSupervisor.State.RUNNING,
                supervisor.tick(context(
                    1, 1, 4, false, true, 0.0
                )).state()
            );
            assertEquals(
                SkillSupervisor.State.RUNNING,
                supervisor.tick(context(
                    1, 1, 5, false, true, 0.0
                )).state()
            );
            final SkillSupervisor.Snapshot sustained = supervisor.tick(
                context(1, 1, 6, false, true, 0.0)
            );
            assertEquals(
                "tick_budget_exceeded",
                failureCode(sustained)
            );
        }
    }

    @Test
    void appliesHardcoreRiskBeforeStartingOrTakingAnotherAction() {
        ScriptedSkill rejectedSkill = new ScriptedSkill();
        SkillRuntimePolicy policy = new SkillRuntimePolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                20,
                0.4,
                0.2
        );
        try (SkillSupervisor supervisor = supervisor(
                rejectedSkill,
                SkillCheckpointSink.discard(),
                policy
        )) {
            SkillSupervisor.StartOutcome rejected = supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, true, true, 0.41)
            );
            assertFalse(rejected.accepted());
            assertEquals(
                    "hardcore_risk_exceeded",
                    rejected.failure().orElseThrow().code()
            );
            assertEquals(0, rejectedSkill.startCalls);
        }

        ScriptedSkill activeSkill = new ScriptedSkill();
        activeSkill.steps.add(SkillTickResult.running(true, false));
        try (SkillSupervisor supervisor = supervisor(
                activeSkill,
                SkillCheckpointSink.discard(),
                policy
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, true, true, 0.1)
            ).accepted());
            supervisor.tick(context(1, 1, 2, true, true, 0.1));
            SkillSupervisor.Snapshot safeIdle = supervisor.tick(
                    context(1, 1, 3, true, true, 0.8)
            );

            assertEquals(SkillSupervisor.State.SAFE_IDLE, safeIdle.state());
            assertEquals("hardcore_risk_exceeded", failureCode(safeIdle));
            assertEquals(1, activeSkill.tickCalls);
            assertEquals(0, activeSkill.cancelCalls);
        }
    }

    @Test
    void permitsOnlyValidatedTrustedSkillHardcoreRiskOverrides() {
        final SkillRuntimePolicy policy = new SkillRuntimePolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                20,
                0.35,
                0.2
        );
        final ScriptedSkill intentionalCombat = new ScriptedSkill();
        intentionalCombat.riskThresholdOverride =
                OptionalDouble.of(0.75);
        intentionalCombat.defaultStep =
                SkillTickResult.running(true, false);
        try (SkillSupervisor supervisor = supervisor(
                intentionalCombat,
                SkillCheckpointSink.discard(),
                policy
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, true, true, 0.75)
            ).accepted());
            assertEquals(
                    SkillSupervisor.State.RUNNING,
                    supervisor.tick(
                            context(1, 1, 2, true, true, 0.75)
                    ).state()
            );
            final SkillSupervisor.Snapshot stopped =
                    supervisor.tick(
                            context(1, 1, 3, true, true, 0.76)
                    );
            assertEquals(
                    SkillSupervisor.State.SAFE_IDLE,
                    stopped.state()
            );
            assertEquals(
                    "hardcore_risk_exceeded",
                    failureCode(stopped)
            );
        }

        final ScriptedSkill invalidOverride = new ScriptedSkill();
        invalidOverride.riskThresholdOverride =
                OptionalDouble.of(Double.NaN);
        try (SkillSupervisor supervisor = supervisor(
                invalidOverride,
                SkillCheckpointSink.discard(),
                policy
        )) {
            final SkillSupervisor.StartOutcome rejected =
                    supervisor.start(
                            decision(
                                    "collect",
                                    1,
                                    1,
                                    "count",
                                    "1"
                            ),
                            context(
                                    1,
                                    1,
                                    1,
                                    true,
                                    true,
                                    0.50
                            )
                    );
            assertFalse(rejected.accepted());
            assertEquals(
                    "hardcore_risk_exceeded",
                    rejected.failure().orElseThrow().code()
            );
        }
    }

    @Test
    void finishesOnlyALowRiskAtomicSegmentAfterModelDisconnect() {
        ScriptedSkill skill = new ScriptedSkill();
        skill.steps.add(SkillTickResult.running(true, false));
        skill.steps.add(SkillTickResult.running(true, true));
        SkillRuntimePolicy policy = new SkillRuntimePolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                20,
                0.5,
                0.2
        );

        try (SkillSupervisor supervisor = supervisor(
                skill,
                SkillCheckpointSink.discard(),
                policy
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 3, 4, "count", "1"),
                    context(3, 4, 10, false, true, 0.0)
            ).accepted());
            supervisor.tick(context(3, 4, 11, false, true, 0.0));

            SkillSupervisor.Snapshot safeIdle = supervisor.tick(
                    context(3, 4, 12, false, false, 0.1)
            );
            assertEquals(SkillSupervisor.State.SAFE_IDLE, safeIdle.state());
            assertEquals("model_disconnected", failureCode(safeIdle));
            assertEquals(2, skill.tickCalls);
            assertEquals(1, skill.cancelCalls);
        }

        ScriptedSkill risky = new ScriptedSkill();
        risky.steps.add(SkillTickResult.running(true, false));
        try (SkillSupervisor supervisor = supervisor(
                risky,
                SkillCheckpointSink.discard(),
                policy
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 3, 4, "count", "1"),
                    context(3, 4, 20, false, true, 0.0)
            ).accepted());
            supervisor.tick(context(3, 4, 21, false, true, 0.0));
            SkillSupervisor.Snapshot stopped = supervisor.tick(
                    context(3, 4, 22, false, false, 0.3)
            );

            assertEquals(SkillSupervisor.State.SAFE_IDLE, stopped.state());
            assertEquals("disconnected_risk_exceeded", failureCode(stopped));
            assertEquals(1, risky.tickCalls);
            assertEquals(0, risky.cancelCalls);
        }
    }

    @Test
    void neverWaitsForACheckpointSinkOnTheTickThread() throws Exception {
        ScriptedSkill skill = new ScriptedSkill();
        skill.defaultStep = SkillTickResult.running(true, true);
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        SkillCheckpointSink blockingSink = (
                name,
                goalRevision,
                worldRevision,
                sequence,
                gameTick,
                checkpoint
        ) -> {
            sinkEntered.countDown();
            try {
                releaseSink.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        };

        try (SkillSupervisor supervisor = supervisor(
                skill,
                blockingSink,
                permissivePolicy(20)
        )) {
            assertTrue(supervisor.start(
                    decision("collect", 1, 1, "count", "1"),
                    context(1, 1, 1, false, true, 0.0)
            ).accepted());

            SkillSupervisor.Snapshot tick = assertTimeoutPreemptively(
                    Duration.ofMillis(250),
                    () -> supervisor.tick(
                            context(1, 1, 2, false, true, 0.0)
                    )
            );
            assertEquals(SkillSupervisor.State.RUNNING, tick.state());
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS));
            releaseSink.countDown();
        } finally {
            releaseSink.countDown();
        }
    }

    private static SkillSupervisor supervisor(
            ScriptedSkill skill,
            SkillCheckpointSink sink,
            SkillRuntimePolicy policy
    ) {
        return supervisor(
                new SkillRegistry().register("collect", skill),
                sink,
                policy,
                new AtomicLong()
        );
    }

    private static SkillSupervisor supervisor(
            SkillRegistry registry,
            SkillCheckpointSink sink,
            SkillRuntimePolicy policy,
            AtomicLong nanoTime
    ) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return new SkillSupervisor(
                registry,
                sink,
                policy,
                nanoTime::get,
                executor
        );
    }

    private static SkillRuntimePolicy permissivePolicy(int stallTicks) {
        return new SkillRuntimePolicy(
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                stallTicks,
                0.5,
                0.2
        );
    }

    private static DecisionEnvelope decision(
            String skillName,
            long goalRevision,
            long worldRevision,
            String argumentName,
            String argumentValue
    ) {
        return new DecisionEnvelope(
                "request-" + goalRevision + "-" + worldRevision,
                worldRevision,
                goalRevision,
                DecisionKind.START_SKILL,
                skillName,
                List.of(new SkillArgument(argumentName, argumentValue)),
                RequestedObservation.none(),
                "",
                0.9
        );
    }

    private static SkillContext context(
            long goalRevision,
            long worldRevision,
            long gameTick,
            boolean hardcore,
            boolean connected,
            double risk
    ) {
        return new SkillContext(
                goalRevision,
                worldRevision,
                gameTick,
                hardcore,
                connected,
                risk
        );
    }

    private static String failureCode(SkillSupervisor.Snapshot snapshot) {
        return snapshot.terminalResult()
                .flatMap(SkillResult::failure)
                .orElseThrow()
                .code();
    }

    private record SavedCheckpoint(
            String skillName,
            long goalRevision,
            long worldRevision,
            long sequence,
            long gameTick,
            SkillCheckpoint checkpoint
    ) {}

    private static final class ScriptedSkill implements Skill<Integer> {
        private final ArrayDeque<SkillTickResult> steps = new ArrayDeque<>();
        private SkillTickResult defaultStep = SkillTickResult.running(true, false);
        private Optional<SkillFailure> precondition = Optional.empty();
        private RuntimeException tickException;
        private Runnable tickAction = () -> {};
        private SkillResult terminalResult = SkillResult.completed();
        private OptionalDouble riskThresholdOverride =
                OptionalDouble.empty();
        private boolean managesVisibleHostileProximity;
        private boolean allowsWorldRevisionTransition;
        private int preconditionCalls;
        private int startCalls;
        private int tickCalls;
        private int checkpointCalls;
        private int cancelCalls;
        private int resultCalls;
        private int startedWith;

        @Override
        public SkillParameterParser<Integer> parameters() {
            return arguments -> {
                if (arguments.size() != 1
                        || !arguments.getFirst().name().equals("count")) {
                    return SkillParameterResult.invalid("invalid_count");
                }
                try {
                    int count = Integer.parseInt(arguments.getFirst().value());
                    return count > 0 && count <= 4_096
                            ? SkillParameterResult.valid(count)
                            : SkillParameterResult.invalid("invalid_count");
                } catch (NumberFormatException exception) {
                    return SkillParameterResult.invalid("invalid_count");
                }
            };
        }

        @Override
        public OptionalDouble hardcoreRiskThresholdOverride(
                final SkillContext context,
                final Integer parameters
        ) {
            return riskThresholdOverride;
        }

        @Override
        public boolean managesVisibleHostileProximity() {
            return managesVisibleHostileProximity;
        }

        @Override
        public boolean allowsWorldRevisionTransition() {
            return allowsWorldRevisionTransition;
        }

        @Override
        public Optional<SkillFailure> preconditions(
                SkillContext context,
                Integer parameters
        ) {
            preconditionCalls++;
            return precondition;
        }

        @Override
        public void start(SkillContext context, Integer parameters) {
            startCalls++;
            startedWith = parameters;
        }

        @Override
        public SkillTickResult tick(SkillContext context, Integer parameters) {
            tickCalls++;
            tickAction.run();
            if (tickException != null) {
                throw tickException;
            }
            return steps.isEmpty() ? defaultStep : steps.removeFirst();
        }

        @Override
        public SkillCheckpoint checkpoint(
                SkillContext context,
                Integer parameters
        ) {
            checkpointCalls++;
            return new SkillCheckpoint(1, "{\"ticks\":" + tickCalls + "}");
        }

        @Override
        public void cancel(SkillContext context, Integer parameters) {
            cancelCalls++;
        }

        @Override
        public SkillResult result(SkillContext context, Integer parameters) {
            resultCalls++;
            return terminalResult;
        }
    }
}
