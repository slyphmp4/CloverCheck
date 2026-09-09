package com.slyph.clovercheck.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorUtilTest {
    @Test
    void normalizesSupportedHexFormats() {
        assertEquals("&x&F&F&0&0&0&0Text", HexColorNormalizer.normalize("&FF0000Text"));
        assertEquals("&x&F&F&0&0&0&0Text", HexColorNormalizer.normalize("&#FF0000Text"));
        assertEquals("&x&F&F&0&0&0&0Text", HexColorNormalizer.normalize("<#FF0000>Text"));
    }

    @Test
    void leavesMalformedHexUntouched() {
        assertEquals("&GG0000Text", HexColorNormalizer.normalize("&GG0000Text"));
    }
}
