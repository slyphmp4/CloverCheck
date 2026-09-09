package com.slyph.clovercheck.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNormalizerTest {
    @Test
    void allowsExactWhitelistedRoot() {
        assertTrue(CommandNormalizer.isAllowed("/msg Player hello", Set.of("msg")));
    }

    @Test
    void doesNotTreatNamespacedCommandAsWhitelistedAlias() {
        assertFalse(CommandNormalizer.isAllowed("/minecraft:msg Player hello", Set.of("msg")));
        assertFalse(CommandNormalizer.isAllowed("/bukkit:msg Player hello", Set.of("msg")));
    }

    @Test
    void allowsNamespaceOnlyWhenExplicitlyWhitelisted() {
        assertTrue(CommandNormalizer.isAllowed("/minecraft:help", Set.of("minecraft:help")));
    }
}
