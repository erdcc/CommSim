package comm.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexTest {
    @Test void parsesCommonForms() {
        byte[] expected = {0x01, 0x02, (byte) 0xAB};
        assertArrayEquals(expected, Hex.parse("01 02 ab"));
        assertArrayEquals(expected, Hex.parse("0102AB"));
        assertArrayEquals(expected, Hex.parse("0x01,0x02, 0xAB"));
        assertArrayEquals(expected, Hex.parse(" 01\n02\tAB "));
        assertArrayEquals(new byte[0], Hex.parse("   "));
    }

    @Test void rejectsBadHex() {
        assertThrows(IllegalArgumentException.class, () -> Hex.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> Hex.parse("zz"));
    }

    @Test void parsesEscapedText() {
        assertArrayEquals(new byte[]{'A', '\r', '\n', 0, (byte) 0xFF, '\\'}, Hex.parseText("A\\r\\n\\0\\xFF\\\\"));
        assertArrayEquals(new byte[]{(byte) 0xC3, (byte) 0xA7}, Hex.parseText("ç"));
        assertThrows(IllegalArgumentException.class, () -> Hex.parseText("\\x4"));
        assertThrows(IllegalArgumentException.class, () -> Hex.parseText("\\q"));
    }

    @Test void dumpsSixteenPerLine() {
        byte[] b = new byte[20];
        b[0] = 'H';
        String[] lines = Hex.dump(b).split("\n");
        assertEquals(2, lines.length);
        assertEquals("0000  48 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00  H...............", lines[0]);
        assertEquals("0010", lines[1].substring(0, 4));
    }
}
