package dev.mcai.companion.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.waypoint.DimensionRef;
import org.junit.jupiter.api.Test;

final class VerifiedFoundationEvidenceTest {
    @Test
    void reopeningSameStorageKeepsDepositButChangingStorageInvalidatesIt() {
        final VerifiedFixtureLocation first =
                fixture(1, 64, 1);
        final VerifiedFixtureLocation second =
                fixture(2, 64, 1);
        final VerifiedFoundationEvidence deposited =
                VerifiedFoundationEvidence.empty(4L)
                        .withFixture(
                                FoundationFixtureKind.STORAGE,
                                first
                        )
                        .withDepositedSupply(
                                "minecraft:cobblestone",
                                16
                        );

        assertTrue(deposited.withFixture(
                FoundationFixtureKind.STORAGE,
                first
        ).suppliesDeposited());
        assertFalse(deposited.withFixture(
                FoundationFixtureKind.STORAGE,
                second
        ).suppliesDeposited());
    }

    @Test
    void depositCannotBeForgedWithoutObservedStorage() {
        assertFalse(
                VerifiedFoundationEvidence.empty(2L)
                        .withDepositedSupply(
                                "minecraft:cobblestone",
                                1
                        )
                        .suppliesDeposited()
        );
    }

    private static VerifiedFixtureLocation fixture(
            final int x,
            final int y,
            final int z
    ) {
        return new VerifiedFixtureLocation(
                DimensionRef.OVERWORLD.id(),
                x,
                y,
                z
        );
    }
}
