package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ModelBootstrapSourceContractTest {
    @Test
    void evaluationCommandDelegatesToServerThreadBootstrapTransaction()
            throws IOException {
        final String communication = source(
            "communication/CommunicationModule.java"
        );
        final String bootstrap = source(
            "runtime/ModelBootstrapCoordinator.java"
        );
        final String runtime = source(
            "runtime/CompanionRuntime.java"
        );

        assertTrue(communication.contains(
            ".requestEvaluationStart(route)"
        ));
        assertFalse(communication.contains("probeExplicitly"));
        assertTrue(bootstrap.contains(
            "mailbox.compareAndSet("
        ));
        assertTrue(bootstrap.contains(
            "requireServerThread();"
        ));
        final int bootstrapTick = runtime.indexOf(
            "runtime.modelBootstrap().tick()"
        );
        assertTrue(bootstrapTick >= 0);
        assertTrue(runtime.indexOf(
            "goal = runtime.goals().snapshot();",
            bootstrapTick
        ) > bootstrapTick);
    }

    @Test
    void freshEvaluationOrdersFreezeBodyGoalTimerAndCommit()
            throws IOException {
        final String source = source(
            "runtime/ModelBootstrapCoordinator.java"
        );
        final int transaction = source.indexOf(
            "public MutationResult startFreshEvaluation("
        );
        final int freeze = source.indexOf(
            "model.freezeForEvaluation()",
            transaction
        );
        final int profile = source.indexOf(
            "model.profileForEvaluation()",
            freeze
        );
        final int body = source.indexOf(
            "AiPlayerManager.requestSpawn(server)",
            profile
        );
        final int goal = source.indexOf(
            "goals.startHardcoreEvaluation(",
            body
        );
        final int timer = source.indexOf(
            "worldData.beginEvaluation(",
            goal
        );
        final int commit = source.indexOf(
            "freeze.commit();",
            timer
        );

        assertTrue(transaction >= 0);
        assertTrue(freeze > transaction);
        assertTrue(profile > freeze);
        assertTrue(body > profile);
        assertTrue(goal > body);
        assertTrue(timer > goal);
        assertTrue(commit > timer);
    }

    @Test
    void ordinaryGoalsNeedTheExplicitOneShotWorldStartupRestore()
            throws IOException {
        final String source = source(
            "runtime/ModelBootstrapCoordinator.java"
        );
        final String runtime = source(
            "runtime/CompanionRuntime.java"
        );

        assertTrue(source.contains(
            "eligibleOrdinaryRestoreRevision"
        ));
        assertTrue(source.contains(
            "goal.revision()"
                + "\n                    == eligibleOrdinaryRestoreRevision"
        ));
        assertTrue(source.contains(
            "if (evaluationRestore || ordinaryRestartRestore)"
        ));
        assertTrue(source.contains(
            "!ordinaryStartupRestoreRequested"
        ));
        assertTrue(source.contains(
            "ordinaryStartupRestoreAttempted"
        ));
        assertTrue(source.contains(
            "return model.prepareConfiguredProfile();"
        ));
        assertTrue(runtime.contains(
            "modelBootstrap.requestOrdinaryStartupRestore()"
        ));
        assertFalse(runtime.contains(
            "model.probeExplicitly()"
        ));
    }

    @Test
    void missingVerifiedGatewayKeepsOnlyOfflineSafetyLane()
            throws IOException {
        final String runtime = source(
            "runtime/CompanionRuntime.java"
        );
        final int controlGate = runtime.indexOf(
            "modelControlEnabled ="
        );
        final int offlineArbiter = runtime.indexOf(
            "arbitrateBehavior(\n                runtime,\n                currentTick,\n                modelControlEnabled"
        );
        final int offlineLane = runtime.indexOf(
            "if (!modelControlEnabled && !survivalIntervened)"
        );
        final int emergencyGate = runtime.indexOf(
            "EMERGENCY_SURVIVAL",
            offlineArbiter
        );

        assertTrue(controlGate >= 0);
        assertTrue(offlineArbiter > controlGate);
        assertTrue(offlineLane > offlineArbiter);
        assertTrue(emergencyGate > offlineArbiter);
        assertTrue(runtime.contains(
            "if (modelControlEnabled\n"
                + "                || isActive(runtime.skillSupervisor().snapshot()))"
        ));
        assertTrue(runtime.contains(
            "Wearing an already-owned armor upgrade"
        ));
        assertTrue(runtime.contains(
            "BehaviorArbiter.Lane.IDLE_EQUIPMENT"
        ));
        assertTrue(runtime.contains(
            "activeSkillManagesVisibleHostileProximity()"
        ));
        assertTrue(runtime.contains(
            "activeSkillManagesPhysicalContactThreats()"
        ));
        assertTrue(runtime.contains(
            "runtime.interactionActions().quiesceNow()"
        ));
        assertTrue(runtime.contains(
            "runtime.boatActions().quiesceNow()"
        ));
        assertTrue(runtime.contains(
            "runtime.minecartActions().quiesceNow()"
        ));
    }

    private static String source(final String relative)
            throws IOException {
        return Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/" + relative
        ));
    }
}
