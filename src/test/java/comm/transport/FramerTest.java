package comm.transport;

import comm.core.FramingConfig;
import comm.core.Hex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FramerTest {
    /** Akisi tek tek byte'lar halinde besler; parcali okumayi zorlar. */
    private static List<String> feedByteByByte(Framer f, byte[] stream) throws Exception {
        List<String> out = new ArrayList<>();
        Framer.Decoder d = f.decoder();
        for (byte b : stream) d.feed(new byte[]{b}, 0, 1, p -> out.add(Hex.toHex(p)));
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    @Test void lengthPrefixBigEndian() throws Exception {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.LENGTH_PREFIX;
        c.lengthSize = 2;
        Framer f = Framer.create(c);
        assertArrayEquals(Hex.parse("00 03 AA BB CC"), f.encode(Hex.parse("AA BB CC")));
        byte[] stream = concat(f.encode(Hex.parse("01")), f.encode(new byte[0]), f.encode(Hex.parse("02 03")));
        List<String> frames = feedByteByByte(f, stream);
        assertEquals(3, frames.size());
        assertEquals("01", frames.get(0));
        assertEquals("", frames.get(1));
        assertEquals("02 03", frames.get(2));
    }

    @Test void lengthPrefixLittleEndianIncludingHeader() throws Exception {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.LENGTH_PREFIX;
        c.lengthSize = 4;
        c.bigEndian = false;
        c.lengthIncludesHeader = true;
        Framer f = Framer.create(c);
        assertArrayEquals(Hex.parse("06 00 00 00 11 22"), f.encode(Hex.parse("11 22")));
        assertEquals("11 22", feedByteByByte(f, f.encode(Hex.parse("11 22"))).get(0));
    }

    @Test void lengthPrefixRejectsOversize() {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.LENGTH_PREFIX;
        c.lengthSize = 1;
        Framer f = Framer.create(c);
        assertThrows(IllegalArgumentException.class, () -> f.encode(new byte[256]));
        c.lengthSize = 4;
        c.maxFrame = 10;
        Framer g = Framer.create(c);
        assertThrows(Framer.FramingException.class,
                () -> g.decoder().feed(Hex.parse("00 00 00 FF"), 0, 4, p -> { }));
    }

    @Test void delimiterSplitAcrossReads() throws Exception {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.DELIMITER;
        c.delimiterHex = "0D 0A";
        Framer f = Framer.create(c);
        assertArrayEquals(Hex.parse("41 0D 0A"), f.encode(Hex.parse("41")));
        List<String> frames = feedByteByByte(f, Hex.parseText("AB\r\n\r\nC\rD\r\n"));
        assertEquals(3, frames.size());
        assertEquals("41 42", frames.get(0));
        assertEquals("", frames.get(1));
        assertEquals("43 0D 44", frames.get(2));
    }

    @Test void delimiterInSingleChunk() throws Exception {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.DELIMITER;
        c.delimiterHex = "0A";
        List<String> out = new ArrayList<>();
        byte[] chunk = Hex.parseText("x\ny\nz");
        Framer.Decoder d = Framer.create(c).decoder();
        d.feed(chunk, 0, chunk.length, p -> out.add(new String(p, java.nio.charset.StandardCharsets.UTF_8)));
        d.feed(Hex.parseText("\n"), 0, 1, p -> out.add(new String(p, java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(3, out.size());
        assertEquals("z", out.get(2));
    }

    @Test void fixedLength() throws Exception {
        FramingConfig c = new FramingConfig();
        c.type = FramingConfig.Type.FIXED;
        c.fixedLength = 3;
        Framer f = Framer.create(c);
        assertThrows(IllegalArgumentException.class, () -> f.encode(new byte[2]));
        List<String> frames = feedByteByByte(f, Hex.parse("01 02 03 04 05 06 07"));
        assertEquals(2, frames.size());
        assertEquals("04 05 06", frames.get(1));
    }
}
