package com.slyph.clovercheck.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HexColorNormalizer {
    private static final Pattern ANGLE_HEX = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern HASH_HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern BARE_HEX = Pattern.compile("(?i)&(?!#)([0-9a-f]{6})");

    private HexColorNormalizer() {
    }

    static String normalize(String input) {
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
}
