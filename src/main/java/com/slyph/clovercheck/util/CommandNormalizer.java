package com.slyph.clovercheck.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public final class CommandNormalizer {
    private CommandNormalizer() {
    }

    public static String root(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }
        String value = Normalizer.normalize(rawCommand, Normalizer.Form.NFKC).trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        int whitespace = firstWhitespace(value);
        if (whitespace >= 0) {
            value = value.substring(0, whitespace);
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        return value;
    }

    public static boolean isAllowed(String rawCommand, Set<String> whitelist) {
        String root = root(rawCommand);
        return !root.isBlank() && whitelist.contains(root);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
