package comm.modbus;

import java.util.Map;
import java.util.TreeMap;

/** Dort Modbus veri tablosu (her biri 65536 adres). Thread-safe; her degisiklikte surum artar. */
public final class ModbusTables {
    public enum Table {
        COILS("Coil (0x)", true, true, 1),
        DISCRETE("Discrete Input (1x)", true, false, 2),
        HOLDING("Holding Register (4x)", false, true, 3),
        INPUT("Input Register (3x)", false, false, 4);

        public final String label;
        public final boolean bit;
        /** Master'in yazabildigi tablo mu. */
        public final boolean writable;
        /** Okuma fonksiyon kodu. */
        public final int readFunction;

        Table(String label, boolean bit, boolean writable, int readFunction) {
            this.label = label;
            this.bit = bit;
            this.writable = writable;
            this.readFunction = readFunction;
        }

        public static Table forReadFunction(int fc) {
            for (Table t : values()) if (t.readFunction == fc) return t;
            return null;
        }

        @Override public String toString() { return label; }
    }

    public static final int SIZE = 65536;

    private final int[][] data = new int[4][SIZE];
    private volatile long version;

    public synchronized int get(Table t, int addr) { return data[t.ordinal()][addr]; }

    public synchronized void set(Table t, int addr, int value) {
        data[t.ordinal()][addr] = normalize(t, value);
        version++;
    }

    public synchronized int[] read(Table t, int addr, int qty) {
        int[] out = new int[qty];
        System.arraycopy(data[t.ordinal()], addr, out, 0, qty);
        return out;
    }

    public synchronized void write(Table t, int addr, int[] values) {
        int[] d = data[t.ordinal()];
        for (int i = 0; i < values.length; i++) d[addr + i] = normalize(t, values[i]);
        version++;
    }

    private static int normalize(Table t, int v) { return t.bit ? (v != 0 ? 1 : 0) : v & 0xFFFF; }

    public long version() { return version; }

    /** Sifir olmayan degerler (proje dosyasina kayit icin). */
    public synchronized Map<Integer, Integer> nonZero(Table t) {
        Map<Integer, Integer> m = new TreeMap<>();
        int[] d = data[t.ordinal()];
        for (int i = 0; i < SIZE; i++) if (d[i] != 0) m.put(i, d[i]);
        return m;
    }

    /** Diger tablonun tum degerlerini kopyalar (baglanti yeniden olusturulunca degerler korunsun). */
    public void copyFrom(ModbusTables other) {
        int[][] snapshot = new int[4][];
        synchronized (other) {
            for (int i = 0; i < 4; i++) snapshot[i] = other.data[i].clone();
        }
        synchronized (this) {
            for (int i = 0; i < 4; i++) System.arraycopy(snapshot[i], 0, data[i], 0, SIZE);
            version++;
        }
    }

    public synchronized void clear() {
        for (int[] d : data) java.util.Arrays.fill(d, 0);
        version++;
    }
}
