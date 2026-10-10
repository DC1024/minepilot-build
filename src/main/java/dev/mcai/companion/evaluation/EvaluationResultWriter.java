package dev.mcai.companion.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.progression.SurvivalRouteTracker;
import dev.mcai.companion.world.CompanionWorldData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Emits one seed-free, machine-readable terminal result for an external
 * hidden-seed harness. The world seed and API credential are intentionally
 * absent.
 */
final class EvaluationResultWriter {
    static final String RELATIVE_RESULT =
        "data/mcai_companion/evaluation-result.json";
    private static final Gson JSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private EvaluationResultWriter() {
    }

    static void write(
            final MinecraftServer server,
            final CompanionWorldData data,
            final GoalSnapshot goal
    ) {
        final long currentTick = server.overworld().getGameTime();
        final Result result = new Result(
            2,
            SurvivalRouteTracker.profile(goal)
                .map(Enum::name)
                .orElse("NONE"),
            goal.status().name(),
            goal.detailCode(),
            goal.revision(),
            server.isHardcore(),
            data.evaluationLocked(),
            data.evaluationContaminated(),
            data.hardcoreDead(),
            goal.status()
                == dev.mcai.companion.control.GoalStatus.COMPLETED
                && goal.detailCode().equals(
                    "foundation_route_verified"
                ),
            data.evaluationDragonKilled(),
            data.evaluationReturnedFromEnd(),
            data.evaluationStartedGameTick(),
            data.evaluationFinishedGameTick(),
            data.evaluationElapsedTicks(currentTick),
            currentTick
        );
        final Path destination = server.getWorldPath(LevelResource.ROOT)
            .resolve(RELATIVE_RESULT);
        Thread.startVirtualThread(() -> writeAtomically(
            destination,
            JSON.toJson(result) + "\n"
        ));
    }

    private static void writeAtomically(
            final Path destination,
            final String json
    ) {
        final Path parent = destination.getParent();
        final Path temporary = parent.resolve(
            "." + destination.getFileName() + "." + UUID.randomUUID()
        );
        try {
            Files.createDirectories(parent);
            Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8
            );
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The original safe failure remains the useful signal.
            }
            MinecraftAiCompanion.LOGGER.error(
                "Could not write the seed-free evaluation result"
            );
        }
    }

    private record Result(
        int schemaVersion,
        String routeProfile,
        String outcome,
        String detailCode,
        long goalRevision,
        boolean hardcore,
        boolean evaluationLocked,
        boolean contaminated,
        boolean hardcoreDead,
        boolean foundationVerified,
        boolean dragonKilled,
        boolean returnedFromEnd,
        long startedGameTick,
        long finishedGameTick,
        long elapsedTicks,
        long observedGameTick
    ) {
    }
}
