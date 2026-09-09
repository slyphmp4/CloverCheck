package com.slyph.clovercheck.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyMiniMessageCompatTest {
    @Test
    void convertsSupportedLegacyAndHexFormats() {
        assertEquals("<#FF0000>text", LegacyMiniMessageCompat.convert("&FF0000text"));
        assertEquals("<#00FF00>text", LegacyMiniMessageCompat.convert("&#00FF00text"));
        assertEquals("<red>text", LegacyMiniMessageCompat.convert("&ctext"));
    }

    @Test
    void leavesMiniMessageHexUntouched() {
        assertEquals("<#ABCDEF>text", LegacyMiniMessageCompat.convert("<#ABCDEF>text"));
    }
}
