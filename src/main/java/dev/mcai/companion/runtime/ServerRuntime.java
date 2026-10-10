package dev.mcai.companion.runtime;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.control.BehaviorArbiter;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.brain.BrainOrchestrator;
import dev.mcai.companion.communication.CompanionConversationCoordinator;
import dev.mcai.companion.credential.ApiKeyManager;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.mcp.LoopbackMcpServer;
import dev.mcai.companion.mechanism.AsyncHydratedCropFieldPlanService;
import dev.mcai.companion.modelsetup.ModelSetupModule;
import dev.mcai.companion.world.CompanionWorldData;
import dev.mcai.companion.progression.FoundationActionAudit;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.EmergencySurvivalController;
import dev.mcai.companion.skills.core.IdleEquipmentController;
import dev.mcai.companion.skills.interaction.ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.skills.stronghold.EyeTraceResultBuffer;
import dev.mcai.companion.skills.transport.ServerBoatSkillActuator;
import dev.mcai.companion.skills.transport.ServerMinecartSkillActuator;
import net.minecraft.server.MinecraftServer;
import dev.mcai.companion.vision.VisionCaptureService;

public record ServerRuntime(
    MinecraftServer server,
    CompanionWorldData worldData,
    MemoryDatabase memory,
    GoalCoordinator goals,
    ApiKeyManager apiKeys,
    ModelRuntime model,
    ModelSetupModule.RuntimeAttachment modelSetup,
    ModelBootstrapCoordinator modelBootstrap,
    SkillRegistry skills,
    SkillSupervisor skillSupervisor,
    MinecraftObservationProvider observations,
    BrainOrchestrator brain,
    CompanionConversationCoordinator conversation,
    ServerOwnedCoreSkillActuator coreActions,
    ServerCoreSkillFrameSource coreFrames,
    EyeTraceResultBuffer eyeTraceResults,
    EmergencySurvivalController survival,
    IdleEquipmentController idleEquipment,
    ServerOwnedInteractionSkillActuator interactionActions,
    FoundationActionAudit foundationAudit,
    ServerBoatSkillActuator boatActions,
    ServerMinecartSkillActuator minecartActions,
    AsyncHydratedCropFieldPlanService mechanismPlans,
    BehaviorArbiter behaviorArbiter,
    RuntimeTickMetrics tickMetrics,
    AtomicLong observedBodySessionGeneration,
    AtomicLong lastTransportAuditTick,
    VisionCaptureService visionCapture,
    Optional<LoopbackMcpServer> mcp
) implements AutoCloseable {
    @Override
    public void close() {
        closeSafely("survival controls", survival::reset);
        closeSafely(
            "interaction controls",
            interactionActions::quiesceNow
        );
        closeSafely("boat controls", boatActions::quiesceNow);
        closeSafely(
            "minecart controls",
            minecartActions::quiesceNow
        );
        closeSafely("core controls", coreActions::quiesceNow);
        closeSafely("mechanism planner", mechanismPlans::close);
        closeSafely("conversation", conversation::close);
        closeSafely("brain", brain::close);
        closeSafely("skill supervisor", skillSupervisor::close);
        closeSafely("MCP", () -> mcp.ifPresent(LoopbackMcpServer::close));
        closeSafely("vision capture", visionCapture::close);
        closeSafely("model bootstrap", modelBootstrap::close);
        closeSafely("model setup", modelSetup::close);
        closeSafely("model", model::close);
        closeSafely("API credential", apiKeys::close);
        closeSafely("memory database", memory::close);
    }

    private static void closeSafely(
        final String resource,
        final Runnable operation
    ) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.error(
                "Failed to close companion {} safely; continuing cleanup",
                resource
            );
        }
    }
}
