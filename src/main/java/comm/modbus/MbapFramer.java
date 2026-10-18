package comm.modbus;

import comm.transport.Framer;

import java.util.Arrays;

/** Modbus TCP cerceveleme: 6 byte MBAP + (uzunluk alani kadar) unit id ve PDU. */
public final class MbapFramer extends Framer {
    @Override public byte[] encode(byte[] adu) { return adu; }

    @Override public Decoder decoder() {
        return new Decoder() {
            private byte[] a = new byte[512];
            private int n;

            @Override public void feed(byte[] buf, int off, int len, Sink out) throws FramingException {
                if (n + len > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + len));
                System.arraycopy(buf, off, a, n, len);
                n += len;
                while (n >= 6) {
                    int l = Modbus.u16(a, 4);
                    if (l < 2 || l > 254) throw new FramingException("geçersiz MBAP uzunluğu: " + l);
                    int total = 6 + l;
                    if (n < total) break;
                    out.frame(Arrays.copyOf(a, total));
                    System.arraycopy(a, total, a, 0, n - total);
                    n -= total;
                }
            }
        };
    }
}
