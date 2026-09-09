package com.slyph.clovercheck.util;

import java.time.Duration;

public final class DurationFormatter {
    private DurationFormatter() {
    }

    public static String format(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return "%d:%02d:%02d".formatted(hours, minutes, seconds);
        }
        return "%02d:%02d".formatted(minutes, seconds);
    }
}
