package com.slyph.clovercheck.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ColorUtil {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private ColorUtil() {
    }

    public static Component deserialize(String input) {
        String value = input == null ? "" : input;
        return LEGACY.deserialize(HexColorNormalizer.normalize(value));
    }
}
