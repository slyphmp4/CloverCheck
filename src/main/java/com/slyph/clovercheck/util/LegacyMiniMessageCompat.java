package com.slyph.clovercheck.util;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LegacyMiniMessageCompat {
    private static final Pattern HEX = Pattern.compile("(?i)&#?([0-9a-f]{6})");
    private static final Pattern LEGACY = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("0", "black"),
            Map.entry("1", "dark_blue"),
            Map.entry("2", "dark_green"),
            Map.entry("3", "dark_aqua"),
            Map.entry("4", "dark_red"),
            Map.entry("5", "dark_purple"),
            Map.entry("6", "gold"),
            Map.entry("7", "gray"),
            Map.entry("8", "dark_gray"),
            Map.entry("9", "blue"),
            Map.entry("a", "green"),
            Map.entry("b", "aqua"),
            Map.entry("c", "red"),
            Map.entry("d", "light_purple"),
            Map.entry("e", "yellow"),
            Map.entry("f", "white"),
            Map.entry("k", "obfuscated"),
            Map.entry("l", "bold"),
            Map.entry("m", "strikethrough"),
            Map.entry("n", "underlined"),
            Map.entry("o", "italic"),
            Map.entry("r", "reset")
    );

    private LegacyMiniMessageCompat() {
    }

    public static String convert(String input) {
        String hexConverted = HEX.matcher(input == null ? "" : input).replaceAll("<#$1>");
        Matcher matcher = LEGACY.matcher(hexConverted);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1).toLowerCase(Locale.ROOT);
            String tag = TAGS.get(code);
            matcher.appendReplacement(result, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
