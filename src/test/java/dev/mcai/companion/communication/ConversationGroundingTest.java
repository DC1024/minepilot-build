package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ConversationGroundingTest {
    @Test
    void rejectsChineseOwnedGoldenAppleClaimWhenInventoryIsEmpty() {
        final Optional<String> correction =
                CompanionConversationCoordinator
                        .correctGoldenAppleInventoryClaim(
                                "你给的金苹果快吃吧",
                                "不用了，我已经有一个金苹果了，这个留给你。",
                                0
                        );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("没有金苹果"));
    }

    @Test
    void rejectsEnglishOwnedGoldenAppleClaimWhenInventoryIsEmpty() {
        final Optional<String> correction =
                CompanionConversationCoordinator
                        .correctGoldenAppleInventoryClaim(
                                "Please eat the golden apple.",
                                "I already have a golden apple, so I will keep it for you.",
                                0
                        );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("currently have no"));
    }

    @Test
    void correctsFalseNoAppleClaimWhenInventoryContainsOne() {
        final Optional<String> correction =
                CompanionConversationCoordinator
                        .correctGoldenAppleInventoryClaim(
                                "你看看背包",
                                "我没有金苹果。",
                                1
                        );

        assertEquals(
                "我刚核对过背包，当前有 1 个金苹果；不能说自己没有。",
                correction.orElseThrow()
        );
    }

    @Test
    void leavesUnrelatedGoldenAppleSpeechUntouched() {
        assertTrue(
                CompanionConversationCoordinator
                        .correctGoldenAppleInventoryClaim(
                                "你快吃",
                                "金苹果可以在危急时救命。",
                                0
                        )
                        .isEmpty()
        );
    }

    @Test
    void correctsGenericChineseThreatDenialFromRecentDamage() {
        final Optional<String> correction =
                CompanionConversationCoordinator.correctNearbyThreatClaim(
                        "你安全吗？",
                        "这里没有怪，很安全。",
                        false,
                        true,
                        true
                );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("受到伤害"));
        assertTrue(correction.orElseThrow().contains("不能断言"));
    }

    @Test
    void correctsEnglishGenericThreatDenialWhenZombieIsVisible() {
        final Optional<String> correction =
                CompanionConversationCoordinator.correctNearbyThreatClaim(
                        "Are you safe?",
                        "No mobs here; I am safe.",
                        true,
                        true,
                        false
                );

        assertEquals(
                "A zombie is currently visible in my own view; "
                        + "saying there is none would be false.",
                correction.orElseThrow()
        );
    }

    @Test
    void doesNotInventThreatWhenTheFairFrameHasNoSignal() {
        assertTrue(
                CompanionConversationCoordinator.correctNearbyThreatClaim(
                        "有怪吗？",
                        "我没有看到怪物。",
                        false,
                        false,
                        false
                ).isEmpty()
        );
    }

    @Test
    void correctsColloquialHitQuestionAndIHaveNotSeenDenial() {
        final var correction =
                CompanionConversationCoordinator.correctNearbyThreatClaim(
                        "你看到木头在打你了吗？",
                        "我没看到，木头在哪里呢？",
                        false,
                        true,
                        false
                );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("攻击者类型"));
    }

    @Test
    void correctsEnglishHitQuestionWithoutInventingAttackerIdentity() {
        final var correction =
                CompanionConversationCoordinator.correctNearbyThreatClaim(
                        "Can you see what is hitting you?",
                        "I don't see anything.",
                        false,
                        false,
                        true
                );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("cannot identify"));
        assertTrue(correction.orElseThrow().contains("attacker"));
    }

    @Test
    void correctsStonePileClaimWhenCurrentSurfaceCuesSuggestACliffOrRavine() {
        final var correction = CompanionConversationCoordinator
                .correctTerrainClaim(
                        "你是不是在峡谷旁边？",
                        "看来我们只是被困在一堆石头里。",
                        true,
                        false,
                        false
                );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("峡谷/崖壁"));
        assertTrue(correction.orElseThrow().contains("不能诚实地说"));
    }

    @Test
    void correctsEnglishStonePileClaimWithoutPretendingToKnowTheFullTerrain() {
        final var correction = CompanionConversationCoordinator
                .correctTerrainClaim(
                        "Are you in a ravine?",
                        "It is just a pile of stones.",
                        false,
                        true,
                        true
                );

        assertTrue(correction.isPresent());
        assertTrue(correction.orElseThrow().contains("large-cave-like"));
        assertTrue(correction.orElseThrow().contains("cannot prove"));
    }

    @Test
    void doesNotInventTerrainClassificationWithoutAFairCue() {
        assertTrue(
                CompanionConversationCoordinator.correctTerrainClaim(
                        "这里是峡谷吗？",
                        "这里就是一堆石头。",
                        false,
                        false,
                        false
                ).isEmpty()
        );
    }
}
