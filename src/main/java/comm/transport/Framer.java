package comm.transport;

import comm.core.FramingConfig;
import comm.core.Hex;

import java.io.IOException;
import java.util.Arrays;

/**
 * Akis (TCP) uzerinde cerceveleme. encode yuke baslik/ayrac ekler; Decoder ise gelen
 * parcali byte'lardan tam cerceveleri cikarir (cerceveleme baytlari olmadan).
 */
public abstract class Framer {
    public interface Sink {
        void frame(byte[] payload);
    }

    /** Baglanti basina ayri bir durum tutar; thread-safe degildir. */
    public interface Decoder {
        void feed(byte[] buf, int off, int len, Sink out) throws FramingException;
    }

    public static final class FramingException extends IOException {
        private static final long serialVersionUID = 1L;

        public FramingException(String message) { super(message); }
    }

    public abstract byte[] encode(byte[] payload);

    public abstract Decoder decoder();

    public static Framer create(FramingConfig cfg) {
        switch (cfg.type) {
            case LENGTH_PREFIX: return new LengthPrefix(cfg);
            case DELIMITER: return new Delimiter(cfg);
            case FIXED: return new Fixed(cfg);
            default: return new Raw();
        }
    }

    /** Buyuyen giris tamponu. */
    static final class Buf {
        byte[] a = new byte[4096];
        int n;

        void add(byte[] b, int off, int len) {
            if (n + len > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + len));
            System.arraycopy(b, off, a, n, len);
            n += len;
        }

        byte[] copy(int from, int len) { return Arrays.copyOfRange(a, from, from + len); }

        void drop(int k) {
            System.arraycopy(a, k, a, 0, n - k);
            n -= k;
        }
    }

    static final class Raw extends Framer {
        @Override public byte[] encode(byte[] payload) { return payload; }

        @Override public Decoder decoder() {
            return (buf, off, len, out) -> out.frame(Arrays.copyOfRange(buf, off, off + len));
        }
    }

    static final class LengthPrefix extends Framer {
        private final int size;
        private final boolean bigEndian;
        private final int header;
        private final int max;

        LengthPrefix(FramingConfig cfg) {
            if (cfg.lengthSize != 1 && cfg.lengthSize != 2 && cfg.lengthSize != 4) {
                throw new IllegalArgumentException("uzunluk alanı 1, 2 ya da 4 byte olmalı");
            }
            size = cfg.lengthSize;
            bigEndian = cfg.bigEndian;
            header = cfg.lengthIncludesHeader ? size : 0;
            max = cfg.maxFrame;
        }

        @Override public byte[] encode(byte[] payload) {
            long value = (long) payload.length + header;
            long limit = size == 4 ? 0x7FFFFFFFL : (1L << (8 * size)) - 1;
            if (value > limit) {
                throw new IllegalArgumentException("yük " + size + " byte'lık uzunluk alanına sığmıyor: "
                        + payload.length);
            }
            byte[] out = new byte[size + payload.length];
            for (int i = 0; i < size; i++) {
                int shift = 8 * (bigEndian ? size - 1 - i : i);
                out[i] = (byte) (value >>> shift);
            }
            System.arraycopy(payload, 0, out, size, payload.length);
            return out;
        }

        @Override public Decoder decoder() {
            Buf b = new Buf();
            return (buf, off, len, out) -> {
                b.add(buf, off, len);
                while (b.n >= size) {
                    long value = 0;
                    for (int i = 0; i < size; i++) {
                        int shift = 8 * (bigEndian ? size - 1 - i : i);
                        value |= (long) (b.a[i] & 0xFF) << shift;
                    }
                    long frameLen = value - header;
                    if (frameLen < 0 || frameLen > max) {
                        throw new FramingException("geçersiz uzunluk alanı: " + value);
                    }
                    int total = size + (int) frameLen;
                    if (b.n < total) break;
                    out.frame(b.copy(size, (int) frameLen));
                    b.drop(total);
                }
            };
        }
    }

    static final class Delimiter extends Framer {
        private final byte[] delim;
        private final int max;

        Delimiter(FramingConfig cfg) {
            delim = Hex.parse(cfg.delimiterHex);
            if (delim.length == 0) throw new IllegalArgumentException("ayraç boş olamaz");
            max = cfg.maxFrame;
        }

        @Override public byte[] encode(byte[] payload) {
            byte[] out = Arrays.copyOf(payload, payload.length + delim.length);
            System.arraycopy(delim, 0, out, payload.length, delim.length);
            return out;
        }

        @Override public Decoder decoder() {
            Buf b = new Buf();
            int[] scanFrom = {0};
            return (buf, off, len, out) -> {
                b.add(buf, off, len);
                int start = 0;
                int i = Math.max(0, scanFrom[0]);
                while (i + delim.length <= b.n) {
                    if (matches(b.a, i)) {
                        out.frame(b.copy(start, i - start));
                        i += delim.length;
                        start = i;
                    } else {
                        i++;
                    }
                }
                if (start > 0) b.drop(start);
                // ayrac iki okuma arasinda bolunmus olabilir; son (delim-1) byte'i tekrar tara
                scanFrom[0] = Math.max(0, b.n - delim.length + 1);
                if (b.n > max) throw new FramingException("ayraç bulunamadı, tampon " + b.n + " byte");
            };
        }

        private boolean matches(byte[] a, int at) {
            for (int k = 0; k < delim.length; k++) {
                if (a[at + k] != delim[k]) return false;
            }
            return true;
        }
    }

    static final class Fixed extends Framer {
        private final int size;

        Fixed(FramingConfig cfg) {
            if (cfg.fixedLength <= 0) throw new IllegalArgumentException("sabit uzunluk pozitif olmalı");
            size = cfg.fixedLength;
        }

        @Override public byte[] encode(byte[] payload) {
            if (payload.length != size) {
                throw new IllegalArgumentException("yük " + size + " byte olmalı, " + payload.length + " verildi");
            }
            return payload;
        }

        @Override public Decoder decoder() {
            Buf b = new Buf();
            return (buf, off, len, out) -> {
                b.add(buf, off, len);
                int start = 0;
                while (b.n - start >= size) {
                    out.frame(b.copy(start, size));
                    start += size;
                }
                if (start > 0) b.drop(start);
            };
        }
    }
}
