package comm.schema;

import comm.schema.FieldDef.Role;

import java.util.zip.CRC32;

/** Desteklenen checksum algoritmalari; hepsi verinin [from, to) araligi uzerinde hesaplanir. */
final class Checksums {
    private Checksums() { }

    static long compute(Role role, byte[] a, int from, int to) {
        switch (role) {
            case SUM8: {
                int s = 0;
                for (int i = from; i < to; i++) s += a[i] & 0xFF;
                return s & 0xFF;
            }
            case XOR8: {
                int x = 0;
                for (int i = from; i < to; i++) x ^= a[i] & 0xFF;
                return x;
            }
            case CRC16: { // Modbus RTU / CRC-16/IBM: baslangic 0xFFFF, polinom 0xA001 (yansimali)
                int crc = 0xFFFF;
                for (int i = from; i < to; i++) {
                    crc ^= a[i] & 0xFF;
                    for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
                }
                return crc;
            }
            case CRC16_CCITT: { // CRC-16/CCITT-FALSE: baslangic 0xFFFF, polinom 0x1021
                int crc = 0xFFFF;
                for (int i = from; i < to; i++) {
                    crc ^= (a[i] & 0xFF) << 8;
                    for (int b = 0; b < 8; b++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                    crc &= 0xFFFF;
                }
                return crc;
            }
            case CRC32: {
                CRC32 c = new CRC32();
                c.update(a, from, to - from);
                return c.getValue();
            }
            default:
                throw new IllegalArgumentException("checksum değil: " + role);
        }
    }
}
