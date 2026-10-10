package dev.mcai.companion.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AgentNameRulesTest {
    @Test
    void acceptsOnlyConservativeMinecraftProfileNames() {
        assertTrue(AgentNameRules.validateSyntax("Agent_1").accepted());
        assertTrue(AgentNameRules.validateSyntax("ABC").accepted());
        assertTrue(AgentNameRules.validateSyntax("a234567890123456").accepted());

        assertEquals(
            "name_too_short",
            AgentNameRules.validateSyntax("AI").code()
        );
        assertEquals(
            "name_too_long",
            AgentNameRules.validateSyntax("a2345678901234567").code()
        );
        assertEquals(
            "name_invalid_characters",
            AgentNameRules.validateSyntax("智能Agent").code()
        );
        assertEquals(
            "name_invalid_characters",
            AgentNameRules.validateSyntax("Agent-1").code()
        );
        assertFalse(AgentNameRules.validateSyntax(" A B ").accepted());
    }

    @Test
    void requireValidReturnsStrippedNameOrRejects() {
        assertEquals("Agent_2", AgentNameRules.requireValid(" Agent_2 "));
        assertThrows(
            IllegalArgumentException.class,
            () -> AgentNameRules.requireValid("bad!")
        );
    }
}
