package comm.snmp;

import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agent'in OID agaci. Dosya bicimi (satir basina bir OID):
 * <pre>  1.3.6.1.2.1.1.5.0  OCTET_STRING  rw  "cihaz-1"</pre>
 * TIMETICKS degeri {@code @uptime} ise agent calistigindan beri gecen sure verilir.
 */
public final class OidTable {
    public static final String UPTIME = "@uptime";

    public static final class Entry {
        public final OID oid;
        public Variable value;
        public boolean writable;
        /** Deger sysUpTime gibi agent baslangicindan hesaplanir. */
        public boolean uptime;

        public Entry(OID oid, Variable value, boolean writable, boolean uptime) {
            this.oid = oid;
            this.value = value;
            this.writable = writable;
            this.uptime = uptime;
        }

        Entry copy() { return new Entry(oid, (Variable) value.clone(), writable, uptime); }
    }

    private final TreeMap<OID, Entry> map = new TreeMap<>();
    private volatile long startMillis = System.currentTimeMillis();
    private volatile long version;

    public long version() { return version; }

    public void resetUptime() { startMillis = System.currentTimeMillis(); }

    private Variable current(Entry e) {
        return e.uptime ? new TimeTicks((System.currentTimeMillis() - startMillis) / 10) : e.value;
    }

    public synchronized Variable get(OID oid) {
        Entry e = map.get(oid);
        return e == null ? null : current(e);
    }

    public synchronized boolean contains(OID oid) { return map.containsKey(oid); }

    public synchronized boolean isWritable(OID oid) {
        Entry e = map.get(oid);
        return e != null && e.writable && !e.uptime;
    }

    /** Sozluk sirasinda sonraki OID ve degeri; yoksa null. */
    public synchronized Map.Entry<OID, Variable> next(OID oid) {
        Map.Entry<OID, Entry> e = map.higherEntry(oid);
        return e == null ? null : new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), current(e.getValue()));
    }

    /** Verilen OID'nin altinda (onu onek olarak alan) bir giris var mi. */
    public synchronized boolean hasSubtree(OID oid) {
        Map.Entry<OID, Entry> e = map.ceilingEntry(oid);
        return e != null && e.getKey().startsWith(oid);
    }

    public synchronized void set(OID oid, Variable v) {
        Entry e = map.get(oid);
        if (e != null) {
            e.value = v;
            version++;
        }
    }

    public synchronized void put(Entry e) {
        map.put(e.oid, e);
        version++;
    }

    public synchronized void remove(OID oid) {
        map.remove(oid);
        version++;
    }

    /** Arayuz ve kayit icin kopya (uptime girisleri anlik degerle). */
    public synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(map.size());
        for (Entry e : map.values()) {
            Entry c = e.copy();
            c.value = current(e);
            out.add(c);
        }
        return out;
    }

    public synchronized void copyFrom(OidTable other) {
        List<Entry> src;
        synchronized (other) {
            src = new ArrayList<>();
            for (Entry e : other.map.values()) src.add(e.copy());
        }
        map.clear();
        for (Entry e : src) map.put(e.oid, e);
        version++;
    }

    public synchronized int size() { return map.size(); }

    // ---- dosya ----

    public static OidTable load(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parse(text, file.getFileName().toString());
    }

    /** @throws IOException "dosya:satir: aciklama" biciminde */
    public static OidTable parse(String text, String source) throws IOException {
        OidTable t = new OidTable();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+", 4);
            try {
                if (p.length < 3) throw new IllegalArgumentException("'<oid> <tip> <ro|rw> [değer]' bekleniyor");
                String access = p[2].toLowerCase(java.util.Locale.ROOT);
                if (!access.equals("ro") && !access.equals("rw")) throw new IllegalArgumentException("erişim 'ro' ya da 'rw' olmalı");
                t.put(entry(p[0], p[1].toUpperCase(java.util.Locale.ROOT), access.equals("rw"), p.length > 3 ? p[3] : ""));
            } catch (IllegalArgumentException e) {
                throw new IOException(source + ":" + (i + 1) + ": " + e.getMessage());
            }
        }
        return t;
    }

    public static Entry entry(String oid, String type, boolean writable, String value) {
        OID o = SnmpValues.oid(oid);
        if (type.equals("TIMETICKS") && value.trim().equals(UPTIME)) return new Entry(o, new TimeTicks(0), false, true);
        return new Entry(o, SnmpValues.parse(type, value), writable, false);
    }

    public synchronized void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("# OID  TİP  ERİŞİM(ro|rw)  DEĞER\n");
        for (Entry e : map.values()) sb.append(line(e)).append('\n');
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Dosyadaki satir bicimi. */
    public static String line(Entry e) {
        String type = SnmpValues.typeOf(e.value);
        String value = e.uptime ? UPTIME : SnmpValues.format(e.value);
        if (type.equals("OCTET_STRING")) value = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return e.oid + "  " + type + "  " + (e.writable ? "rw" : "ro") + "  " + value;
    }

    /** Dosya verilmediginde kullanilan kucuk system grubu. */
    public static OidTable defaults() {
        try {
            return parse(String.join("\n",
                    "1.3.6.1.2.1.1.1.0 OCTET_STRING ro \"Haberleşme simülatörü SNMP agent\"",
                    "1.3.6.1.2.1.1.2.0 OID ro 1.3.6.1.4.1.99999",
                    "1.3.6.1.2.1.1.3.0 TIMETICKS ro @uptime",
                    "1.3.6.1.2.1.1.4.0 OCTET_STRING rw \"admin@example.com\"",
                    "1.3.6.1.2.1.1.5.0 OCTET_STRING rw \"sim-agent\"",
                    "1.3.6.1.2.1.1.6.0 OCTET_STRING rw \"Laboratuvar\""), "varsayılan");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
