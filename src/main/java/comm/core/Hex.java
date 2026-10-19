package comm.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Hex ve kacisli metin donusumleri, hex dump. */
public final class Hex {
    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() { }

    /** Tek byte'in iki haneli buyuk harf hex'i. */
    public static String byteHex(int v) {
        return new String(new char[]{DIGITS[(v >> 4) & 0xF], DIGITS[v & 0xF]});
    }

    public static String toHex(byte[] b) {
        return toHex(b, 0, b.length, " ");
    }

    public static String toHex(byte[] b, int off, int len, String sep) {
        StringBuilder sb = new StringBuilder(len * (2 + sep.length()));
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(sep);
            int v = b[off + i] & 0xFF;
            sb.append(DIGITS[v >>> 4]).append(DIGITS[v & 0x0F]);
        }
        return sb.toString();
    }

    /** En fazla max byte'in hex onizlemesi; fazlasi "…" ile kisaltilir. */
    public static String preview(byte[] b, int max) {
        if (b.length <= max) return toHex(b);
        return toHex(b, 0, max, " ") + " …";
    }

    /**
     * Hex metni byte'a cevirir. "01 02 0a", "01020A", "0x01,0x02" ve satir sonlu yazimlari kabul eder.
     * @throws IllegalArgumentException gecersiz hane ya da tek sayida hane varsa
     */
    public static byte[] parse(String s) {
        StringBuilder digits = new StringBuilder(s.length());
        for (String tok : s.trim().split("[\\s,;]+")) {
            if (tok.startsWith("0x") || tok.startsWith("0X")) tok = tok.substring(2);
            digits.append(tok);
        }
        if (digits.length() % 2 != 0) throw new IllegalArgumentException("tek sayıda hex hane");
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(digits.charAt(2 * i), 16);
            int lo = Character.digit(digits.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("geçersiz hex: " + digits.substring(2 * i, 2 * i + 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** UTF-8 metni byte'a cevirir; \r \n \t \0 \\ ve \xHH kacislarini cozer. */
    public static byte[] parseText(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                plain.append(c);
                continue;
            }
            char n = s.charAt(++i);
            int b;
            switch (n) {
                case 'r': b = '\r'; break;
                case 'n': b = '\n'; break;
                case 't': b = '\t'; break;
                case '0': b = 0; break;
                case '\\': b = '\\'; break;
                case 'x':
                    if (i + 2 >= s.length()) throw new IllegalArgumentException("eksik \\x kaçışı");
                    try {
                        b = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("geçersiz \\x kaçışı: " + s.substring(i + 1, i + 3));
                    }
                    i += 2;
                    break;
                default:
                    throw new IllegalArgumentException("bilinmeyen kaçış: \\" + n);
            }
            flush(plain, out);
            out.write(b);
        }
        flush(plain, out);
        return out.toByteArray();
    }

    private static void flush(StringBuilder plain, ByteArrayOutputStream out) {
        if (plain.length() == 0) return;
        byte[] b = plain.toString().getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
        plain.setLength(0);
    }

    /** Yazdirilabilir ASCII; digerleri '.' olur. */
    public static String ascii(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            sb.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
        }
        return sb.toString();
    }

    /** Klasik 16 byte'lik satirlarla hex dump: ofset, hex, ASCII. */
    public static String dump(byte[] b) {
        StringBuilder sb = new StringBuilder((b.length / 16 + 1) * 78);
        for (int off = 0; off < b.length; off += 16) {
            int n = Math.min(16, b.length - off);
            sb.append(String.format("%04X  ", off));
            for (int i = 0; i < 16; i++) {
                if (i < n) {
                    int v = b[off + i] & 0xFF;
                    sb.append(DIGITS[v >>> 4]).append(DIGITS[v & 0x0F]).append(' ');
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii(b, off, n)).append('\n');
        }
        return sb.toString();
    }
}
