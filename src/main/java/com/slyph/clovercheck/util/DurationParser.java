package com.slyph.clovercheck.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    public static Optional<Duration> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String value = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) {
            return Optional.empty();
        }

        Matcher matcher = TOKEN.matcher(value);
        int cursor = 0;
        long seconds = 0L;
        boolean found = false;

        try {
            while (matcher.find()) {
                if (matcher.start() != cursor) {
                    return Optional.empty();
                }
                found = true;
                long amount = Long.parseLong(matcher.group(1));
                long multiplier = switch (matcher.group(2).charAt(0)) {
                    case 's' -> 1L;
                    case 'm' -> 60L;
                    case 'h' -> 3_600L;
                    case 'd' -> 86_400L;
                    default -> throw new IllegalStateException("Unexpected duration unit");
                };
                seconds = Math.addExact(seconds, Math.multiplyExact(amount, multiplier));
                cursor = matcher.end();
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }

        if (!found || cursor != value.length() || seconds <= 0L) {
            return Optional.empty();
        }

        return Optional.of(Duration.ofSeconds(seconds));
    }

    public static String format(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder result = new StringBuilder();
        append(result, days, "д.");
        append(result, hours, "ч.");
        append(result, minutes, "мин.");
        if (result.isEmpty() || seconds > 0L) {
            append(result, seconds, "сек.");
        }
        return result.toString();
    }

    private static void append(StringBuilder builder, long value, String suffix) {
        if (value <= 0L) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value).append(' ').append(suffix);
    }
}
