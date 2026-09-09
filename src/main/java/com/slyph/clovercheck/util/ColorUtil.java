package com.slyph.clovercheck.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {
    private static final Pattern ANGLE_HEX = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern HASH_HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern BARE_HEX = Pattern.compile("(?i)&(?!#)([0-9a-f]{6})");

    private ColorUtil() {
    }

    public static Component deserialize(String input) {
        return SerializerHolder.LEGACY.deserialize(normalizeHex(input == null ? "" : input));
    }

    static String normalizeHex(String input) {
        String normalized = ANGLE_HEX.matcher(input).replaceAll("&#$1");
        normalized = BARE_HEX.matcher(normalized).replaceAll("&#$1");

        Matcher matcher = HASH_HEX.matcher(normalized);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char character : hex.toCharArray()) {
                replacement.append('&').append(character);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static final class SerializerHolder {
        private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();

        private SerializerHolder() {
        }
    }
}
