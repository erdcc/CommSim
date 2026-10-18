package comm.modbus;

import comm.schema.Decoded;
import comm.schema.MessageDef;

import java.util.ArrayList;
import java.util.List;

/** Modbus TCP cercevelerini trafik logu icin alan alan cozer. */
public final class ModbusDecoder {
    private ModbusDecoder() { }

    /**
     * @param request true ise istek (master -&gt; slave), false ise yanit
     * @param reqPdu  yanitlar icin eslesen istek PDU'su (adresleri gostermek icin); bilinmiyorsa null
     */
    public static Decoded decode(byte[] adu, boolean request, byte[] reqPdu) {
        Decoded d = new Decoded();
        if (adu.length < 8) {
            d.error = "Modbus çerçevesi çok kısa (" + adu.length + " byte)";
            return d;
        }
        span(d, "mbap.işlem", 0, 2, String.valueOf(Modbus.u16(adu, 0)));
        span(d, "mbap.protokol", 2, 2, String.valueOf(Modbus.u16(adu, 2)));
        span(d, "mbap.uzunluk", 4, 2, String.valueOf(Modbus.u16(adu, 4)));
        span(d, "mbap.unit", 6, 1, String.valueOf(adu[6] & 0xFF));
        if (Modbus.u16(adu, 2) != 0) d.warnings.add("protokol id 0 değil");
        if (Modbus.u16(adu, 4) != adu.length - 6) d.warnings.add("MBAP uzunluğu çerçeveyle uyuşmuyor");
        int fc = adu[7] & 0xFF;
        boolean ex = (fc & 0x80) != 0;
        int base = fc & 0x7F;
        String name = String.format("FC%02d %s", base, Modbus.functionName(base));
        d.message = new MessageDef(fc, ex ? "İstisna " + name : name + (request ? " ?" : " ✓"));
        span(d, "fonksiyon", 7, 1, String.format("%d (%s)", base, Modbus.functionName(base)));
        try {
            if (ex) {
                int code = adu[8] & 0xFF;
                span(d, "istisna", 8, 1, code + " (" + Modbus.exceptionName(code) + ")");
                d.warnings.add("istisna " + code + ": " + Modbus.exceptionName(code));
            } else if (request) {
                decodeRequest(d, adu, base);
            } else {
                decodeResponse(d, adu, base, reqPdu);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            d.error = "PDU eksik";
        }
        return d;
    }

    private static void decodeRequest(Decoded d, byte[] a, int fc) {
        switch (fc) {
            case Modbus.READ_COILS:
            case Modbus.READ_DISCRETE:
            case Modbus.READ_HOLDING:
            case Modbus.READ_INPUT:
                u16(d, a, "adres", 8);
                u16(d, a, "adet", 10);
                break;
            case Modbus.WRITE_COIL:
                u16(d, a, "adres", 8);
                span(d, "değer", 10, 2, Modbus.u16(a, 10) == 0xFF00 ? "ON" : Modbus.u16(a, 10) == 0 ? "OFF" : "geçersiz");
                break;
            case Modbus.WRITE_REGISTER:
                u16(d, a, "adres", 8);
                register(d, a, "değer", 10);
                break;
            case Modbus.WRITE_COILS: {
                int addr = u16(d, a, "adres", 8);
                int qty = u16(d, a, "adet", 10);
                span(d, "byte sayısı", 12, 1, String.valueOf(a[12] & 0xFF));
                bits(d, a, 13, addr, qty, "coil");
                break;
            }
            case Modbus.WRITE_REGISTERS: {
                int addr = u16(d, a, "adres", 8);
                int qty = u16(d, a, "adet", 10);
                span(d, "byte sayısı", 12, 1, String.valueOf(a[12] & 0xFF));
                for (int i = 0; i < qty; i++) register(d, a, "hr[" + (addr + i) + "]", 13 + 2 * i);
                break;
            }
            case Modbus.READ_WRITE_REGISTERS: {
                u16(d, a, "okuma adresi", 8);
                u16(d, a, "okuma adedi", 10);
                int wa = u16(d, a, "yazma adresi", 12);
                int wq = u16(d, a, "yazma adedi", 14);
                span(d, "byte sayısı", 16, 1, String.valueOf(a[16] & 0xFF));
                for (int i = 0; i < wq; i++) register(d, a, "hr[" + (wa + i) + "]", 17 + 2 * i);
                break;
            }
            default:
                break;
        }
    }

    private static void decodeResponse(Decoded d, byte[] a, int fc, byte[] req) {
        int addr = req != null && req.length >= 3 ? Modbus.u16(req, 1) : 0;
        String prefix = req == null ? "[" : null;
        switch (fc) {
            case Modbus.READ_COILS:
            case Modbus.READ_DISCRETE: {
                int bc = a[8] & 0xFF;
                span(d, "byte sayısı", 8, 1, String.valueOf(bc));
                int qty = req != null ? Modbus.u16(req, 3) : bc * 8;
                bits(d, a, 9, req == null ? 0 : addr, Math.min(qty, bc * 8), fc == Modbus.READ_COILS ? "coil" : "di");
                break;
            }
            case Modbus.READ_HOLDING:
            case Modbus.READ_INPUT:
            case Modbus.READ_WRITE_REGISTERS: {
                int bc = a[8] & 0xFF;
                span(d, "byte sayısı", 8, 1, String.valueOf(bc));
                String t = fc == Modbus.READ_INPUT ? "ir" : "hr";
                for (int i = 0; i < bc / 2; i++) {
                    register(d, a, prefix != null ? "değer[" + i + "]" : t + "[" + (addr + i) + "]", 9 + 2 * i);
                }
                break;
            }
            case Modbus.WRITE_COIL:
            case Modbus.WRITE_REGISTER:
                decodeRequest(d, a, fc);
                break;
            case Modbus.WRITE_COILS:
            case Modbus.WRITE_REGISTERS:
                u16(d, a, "adres", 8);
                u16(d, a, "adet", 10);
                break;
            default:
                break;
        }
    }

    private static int u16(Decoded d, byte[] a, String path, int off) {
        int v = Modbus.u16(a, off);
        span(d, path, off, 2, String.valueOf(v));
        d.body.put(path, (long) v);
        return v;
    }

    private static void register(Decoded d, byte[] a, String path, int off) {
        int v = Modbus.u16(a, off);
        span(d, path, off, 2, v + "  (" + (short) v + ", 0x" + String.format("%04X", v) + ")");
        d.body.put(path, (long) v);
    }

    private static void bits(Decoded d, byte[] a, int off, int addr, int qty, String name) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < qty; i++) {
            int bit = (a[off + i / 8] >>> (i % 8)) & 1;
            values.add((long) bit);
            if (i < 64) span(d, name + "[" + (addr + i) + "]", off + i / 8, 1, bit == 1 ? "1 (ON)" : "0");
        }
        d.body.put(name, values);
    }

    private static void span(Decoded d, String path, int off, int len, String text) {
        d.spans.add(new Decoded.Span(path, off, len, text));
    }
}
