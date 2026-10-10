package dev.mcai.companion.skills.combat;

@FunctionalInterface
public interface DragonVictorySource {
    boolean dragonKilled(long goalRevision);
}
