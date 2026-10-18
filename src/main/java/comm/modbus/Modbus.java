package comm.modbus;

import comm.modbus.ModbusTables.Table;

/**
 * Modbus TCP protokolu: MBAP basligi, istek PDU'lari, slave tarafinda istek isleme ve
 * master tarafinda yanitin tabloya yansitilmasi.
 */
public final class Modbus {
    public static final int READ_COILS = 1;
    public static final int READ_DISCRETE = 2;
    public static final int READ_HOLDING = 3;
    public static final int READ_INPUT = 4;
    public static final int WRITE_COIL = 5;
    public static final int WRITE_REGISTER = 6;
    public static final int WRITE_COILS = 15;
    public static final int WRITE_REGISTERS = 16;
    public static final int READ_WRITE_REGISTERS = 23;

    public static final int EX_ILLEGAL_FUNCTION = 1;
    public static final int EX_ILLEGAL_ADDRESS = 2;
    public static final int EX_ILLEGAL_VALUE = 3;

    /** Arayuzdeki fonksiyon secimi icin desteklenen kodlar. */
    public static final int[] FUNCTIONS = {READ_COILS, READ_DISCRETE, READ_HOLDING, READ_INPUT, WRITE_COIL,
            WRITE_REGISTER, WRITE_COILS, WRITE_REGISTERS, READ_WRITE_REGISTERS};

    private Modbus() { }

    public static String functionName(int fc) {
        switch (fc) {
            case READ_COILS: return "Read Coils";
            case READ_DISCRETE: return "Read Discrete Inputs";
            case READ_HOLDING: return "Read Holding Registers";
            case READ_INPUT: return "Read Input Registers";
            case WRITE_COIL: return "Write Single Coil";
            case WRITE_REGISTER: return "Write Single Register";
            case WRITE_COILS: return "Write Multiple Coils";
            case WRITE_REGISTERS: return "Write Multiple Registers";
            case READ_WRITE_REGISTERS: return "Read/Write Multiple Registers";
            default: return "Fonksiyon " + fc;
        }
    }

    public static String exceptionName(int code) {
        switch (code) {
            case 1: return "Illegal Function";
            case 2: return "Illegal Data Address";
            case 3: return "Illegal Data Value";
            case 4: return "Slave Device Failure";
            case 5: return "Acknowledge";
            case 6: return "Slave Device Busy";
            case 10: return "Gateway Path Unavailable";
            case 11: return "Gateway Target Failed To Respond";
            default: return "Bilinmeyen istisna";
        }
    }

    // ---- MBAP ----

    public static byte[] adu(int tid, int unit, byte[] pdu) {
        byte[] a = new byte[7 + pdu.length];
        a[0] = (byte) (tid >>> 8);
        a[1] = (byte) tid;
        // protokol id 0
        int len = pdu.length + 1;
        a[4] = (byte) (len >>> 8);
        a[5] = (byte) len;
        a[6] = (byte) unit;
        System.arraycopy(pdu, 0, a, 7, pdu.length);
        return a;
    }

    public static int tid(byte[] adu) { return u16(adu, 0); }

    public static int protocolId(byte[] adu) { return u16(adu, 2); }

    public static int unit(byte[] adu) { return adu[6] & 0xFF; }

    public static byte[] pdu(byte[] adu) {
        byte[] p = new byte[Math.max(0, adu.length - 7)];
        System.arraycopy(adu, 7, p, 0, p.length);
        return p;
    }

    static int u16(byte[] b, int off) { return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF); }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    // ---- istek PDU'lari (master) ----

    /** Okuma istegi (FC 1-4). @throws IllegalArgumentException adet/adres sinir disindaysa */
    public static byte[] readRequest(int fc, int addr, int qty) {
        int max = fc == READ_COILS || fc == READ_DISCRETE ? 2000 : 125;
        checkRange(addr, qty, max);
        byte[] p = new byte[5];
        p[0] = (byte) fc;
        put16(p, 1, addr);
        put16(p, 3, qty);
        return p;
    }

    public static byte[] writeCoil(int addr, boolean on) {
        checkRange(addr, 1, 1);
        byte[] p = new byte[5];
        p[0] = WRITE_COIL;
        put16(p, 1, addr);
        put16(p, 3, on ? 0xFF00 : 0);
        return p;
    }

    public static byte[] writeRegister(int addr, int value) {
        checkRange(addr, 1, 1);
        checkRegister(value);
        byte[] p = new byte[5];
        p[0] = WRITE_REGISTER;
        put16(p, 1, addr);
        put16(p, 3, value);
        return p;
    }

    public static byte[] writeCoils(int addr, int[] bits) {
        checkRange(addr, bits.length, 1968);
        int bc = (bits.length + 7) / 8;
        byte[] p = new byte[6 + bc];
        p[0] = WRITE_COILS;
        put16(p, 1, addr);
        put16(p, 3, bits.length);
        p[5] = (byte) bc;
        packBits(bits, p, 6);
        return p;
    }

    public static byte[] writeRegisters(int addr, int[] regs) {
        checkRange(addr, regs.length, 123);
        byte[] p = new byte[6 + 2 * regs.length];
        p[0] = WRITE_REGISTERS;
        put16(p, 1, addr);
        put16(p, 3, regs.length);
        p[5] = (byte) (2 * regs.length);
        for (int i = 0; i < regs.length; i++) {
            checkRegister(regs[i]);
            put16(p, 6 + 2 * i, regs[i]);
        }
        return p;
    }

    public static byte[] readWriteRegisters(int readAddr, int readQty, int writeAddr, int[] regs) {
        checkRange(readAddr, readQty, 125);
        checkRange(writeAddr, regs.length, 121);
        byte[] p = new byte[10 + 2 * regs.length];
        p[0] = READ_WRITE_REGISTERS;
        put16(p, 1, readAddr);
        put16(p, 3, readQty);
        put16(p, 5, writeAddr);
        put16(p, 7, regs.length);
        p[9] = (byte) (2 * regs.length);
        for (int i = 0; i < regs.length; i++) {
            checkRegister(regs[i]);
            put16(p, 10 + 2 * i, regs[i]);
        }
        return p;
    }

    private static void checkRange(int addr, int qty, int max) {
        if (qty < 1 || qty > max) throw new IllegalArgumentException("adet 1 … " + max + " olmalı: " + qty);
        if (addr < 0 || addr + qty > ModbusTables.SIZE) throw new IllegalArgumentException("adres aralığı 0 … 65535 dışında");
    }

    private static void checkRegister(int v) {
        if (v < -32768 || v > 65535) throw new IllegalArgumentException("register değeri 16 bit olmalı: " + v);
    }

    private static void packBits(int[] bits, byte[] out, int off) {
        for (int i = 0; i < bits.length; i++) {
            if (bits[i] != 0) out[off + i / 8] |= (byte) (1 << (i % 8));
        }
    }

    static int[] unpackBits(byte[] in, int off, int qty) {
        int[] bits = new int[qty];
        for (int i = 0; i < qty; i++) bits[i] = (in[off + i / 8] >>> (i % 8)) & 1;
        return bits;
    }

    // ---- slave: istegi isle ----

    /** Istek PDU'sunu tablolara uygular ve yanit PDU'sunu (normal ya da istisna) dondurur. */
    public static byte[] process(byte[] req, ModbusTables t) {
        if (req.length < 1) return exception(0, EX_ILLEGAL_VALUE);
        int fc = req[0] & 0xFF;
        switch (fc) {
            case READ_COILS:
            case READ_DISCRETE:
            case READ_HOLDING:
            case READ_INPUT: {
                if (req.length != 5) return exception(fc, EX_ILLEGAL_VALUE);
                int addr = u16(req, 1);
                int qty = u16(req, 3);
                boolean bit = fc <= READ_DISCRETE;
                if (qty < 1 || qty > (bit ? 2000 : 125)) return exception(fc, EX_ILLEGAL_VALUE);
                if (addr + qty > ModbusTables.SIZE) return exception(fc, EX_ILLEGAL_ADDRESS);
                int[] v = t.read(Table.forReadFunction(fc), addr, qty);
                return bit ? bitsResponse(fc, v) : registersResponse(fc, v);
            }
            case WRITE_COIL: {
                if (req.length != 5) return exception(fc, EX_ILLEGAL_VALUE);
                int value = u16(req, 3);
                if (value != 0xFF00 && value != 0) return exception(fc, EX_ILLEGAL_VALUE);
                t.set(Table.COILS, u16(req, 1), value == 0xFF00 ? 1 : 0);
                return req.clone();
            }
            case WRITE_REGISTER:
                if (req.length != 5) return exception(fc, EX_ILLEGAL_VALUE);
                t.set(Table.HOLDING, u16(req, 1), u16(req, 3));
                return req.clone();
            case WRITE_COILS: {
                if (req.length < 6) return exception(fc, EX_ILLEGAL_VALUE);
                int addr = u16(req, 1);
                int qty = u16(req, 3);
                int bc = req[5] & 0xFF;
                if (qty < 1 || qty > 1968 || bc != (qty + 7) / 8 || req.length != 6 + bc) return exception(fc, EX_ILLEGAL_VALUE);
                if (addr + qty > ModbusTables.SIZE) return exception(fc, EX_ILLEGAL_ADDRESS);
                t.write(Table.COILS, addr, unpackBits(req, 6, qty));
                return writeEcho(fc, addr, qty);
            }
            case WRITE_REGISTERS: {
                if (req.length < 6) return exception(fc, EX_ILLEGAL_VALUE);
                int addr = u16(req, 1);
                int qty = u16(req, 3);
                int bc = req[5] & 0xFF;
                if (qty < 1 || qty > 123 || bc != 2 * qty || req.length != 6 + bc) return exception(fc, EX_ILLEGAL_VALUE);
                if (addr + qty > ModbusTables.SIZE) return exception(fc, EX_ILLEGAL_ADDRESS);
                t.write(Table.HOLDING, addr, regs(req, 6, qty));
                return writeEcho(fc, addr, qty);
            }
            case READ_WRITE_REGISTERS: {
                if (req.length < 10) return exception(fc, EX_ILLEGAL_VALUE);
                int ra = u16(req, 1);
                int rq = u16(req, 3);
                int wa = u16(req, 5);
                int wq = u16(req, 7);
                int bc = req[9] & 0xFF;
                if (rq < 1 || rq > 125 || wq < 1 || wq > 121 || bc != 2 * wq || req.length != 10 + bc) {
                    return exception(fc, EX_ILLEGAL_VALUE);
                }
                if (ra + rq > ModbusTables.SIZE || wa + wq > ModbusTables.SIZE) return exception(fc, EX_ILLEGAL_ADDRESS);
                t.write(Table.HOLDING, wa, regs(req, 10, wq)); // standart: once yazma, sonra okuma
                return registersResponse(fc, t.read(Table.HOLDING, ra, rq));
            }
            default:
                return exception(fc, EX_ILLEGAL_FUNCTION);
        }
    }

    static int[] regs(byte[] b, int off, int qty) {
        int[] v = new int[qty];
        for (int i = 0; i < qty; i++) v[i] = u16(b, off + 2 * i);
        return v;
    }

    public static byte[] exception(int fc, int code) {
        return new byte[]{(byte) (fc | 0x80), (byte) code};
    }

    private static byte[] bitsResponse(int fc, int[] bits) {
        int bc = (bits.length + 7) / 8;
        byte[] p = new byte[2 + bc];
        p[0] = (byte) fc;
        p[1] = (byte) bc;
        packBits(bits, p, 2);
        return p;
    }

    private static byte[] registersResponse(int fc, int[] regs) {
        byte[] p = new byte[2 + 2 * regs.length];
        p[0] = (byte) fc;
        p[1] = (byte) (2 * regs.length);
        for (int i = 0; i < regs.length; i++) put16(p, 2 + 2 * i, regs[i]);
        return p;
    }

    private static byte[] writeEcho(int fc, int addr, int qty) {
        byte[] p = new byte[5];
        p[0] = (byte) fc;
        put16(p, 1, addr);
        put16(p, 3, qty);
        return p;
    }

    // ---- master: yaniti yansit ----

    /**
     * Yaniti istege gore dogrular; basariliysa okunan/yazilan degerleri ayna tabloya yazar.
     * @return null basarili; aksi halde hata metni (istisna ya da bozuk yanit)
     */
    public static String apply(byte[] req, byte[] resp, ModbusTables mirror) {
        if (resp.length < 1 || req.length < 1) return "boş PDU";
        int fc = req[0] & 0xFF;
        int rfc = resp[0] & 0xFF;
        String where = describe(req);
        if (rfc == (fc | 0x80)) {
            int code = resp.length > 1 ? resp[1] & 0xFF : 0;
            return "İstisna " + code + " (" + exceptionName(code) + "): " + where;
        }
        if (rfc != fc) return "yanıt fonksiyonu " + rfc + ", istek " + fc + ": " + where;
        try {
            switch (fc) {
                case READ_COILS:
                case READ_DISCRETE: {
                    int qty = u16(req, 3);
                    if (resp.length != 2 + (qty + 7) / 8 || (resp[1] & 0xFF) != (qty + 7) / 8) return "yanıt boyu hatalı: " + where;
                    mirror.write(Table.forReadFunction(fc), u16(req, 1), unpackBits(resp, 2, qty));
                    return null;
                }
                case READ_HOLDING:
                case READ_INPUT:
                case READ_WRITE_REGISTERS: {
                    int qty = u16(req, 3);
                    if (resp.length != 2 + 2 * qty || (resp[1] & 0xFF) != 2 * qty) return "yanıt boyu hatalı: " + where;
                    if (fc == READ_WRITE_REGISTERS) mirror.write(Table.HOLDING, u16(req, 5), regs(req, 10, u16(req, 7)));
                    mirror.write(fc == READ_INPUT ? Table.INPUT : Table.HOLDING, u16(req, 1), regs(resp, 2, qty));
                    return null;
                }
                case WRITE_COIL:
                    mirror.set(Table.COILS, u16(req, 1), u16(req, 3) == 0xFF00 ? 1 : 0);
                    return null;
                case WRITE_REGISTER:
                    mirror.set(Table.HOLDING, u16(req, 1), u16(req, 3));
                    return null;
                case WRITE_COILS:
                    mirror.write(Table.COILS, u16(req, 1), unpackBits(req, 6, u16(req, 3)));
                    return null;
                case WRITE_REGISTERS:
                    mirror.write(Table.HOLDING, u16(req, 1), regs(req, 6, u16(req, 3)));
                    return null;
                default:
                    return null;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return "yanıt eksik: " + where;
        }
    }

    /** "FC03 Read Holding Registers @100 x10" gibi kisa tanim. */
    public static String describe(byte[] req) {
        int fc = req[0] & 0xFF;
        StringBuilder sb = new StringBuilder(String.format("FC%02d %s", fc, functionName(fc)));
        if (req.length >= 5) {
            sb.append(" @").append(u16(req, 1));
            if (fc != WRITE_COIL && fc != WRITE_REGISTER) sb.append(" x").append(u16(req, 3));
        }
        return sb.toString();
    }
}
