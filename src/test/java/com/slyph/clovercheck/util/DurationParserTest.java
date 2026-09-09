package com.slyph.clovercheck.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {
    @Test
    void parsesCombinedDurations() {
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h30m").orElseThrow());
        assertEquals(Duration.ofSeconds(65), DurationParser.parse("1m5s").orElseThrow());
    }

    @Test
    void rejectsMalformedDurations() {
        assertTrue(DurationParser.parse("10").isEmpty());
        assertTrue(DurationParser.parse("5x").isEmpty());
        assertTrue(DurationParser.parse("0s").isEmpty());
        assertTrue(DurationParser.parse("1h-nope").isEmpty());
    }

    @Test
    void formatsDurationsForMessages() {
        assertEquals("1 ч. 30 мин.", DurationParser.format(Duration.ofMinutes(90)));
        assertEquals("45 сек.", DurationParser.format(Duration.ofSeconds(45)));
    }
}
