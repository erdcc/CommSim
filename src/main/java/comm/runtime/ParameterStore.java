package comm.runtime;

import comm.core.Direction;
import comm.core.LogEntry;
import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.MessageDef;
import comm.schema.Schema;
import comm.schema.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir baglantinin mesaj parametreleri: her mesaj icin gonderilecek degerler, ureteçler ve son alinan/gonderilen
 * cozum. Baslik degerleri ve baslik ureteçleri tum mesajlarca paylasilir. Tek thread'den (arayuzde EDT) kullanilir.
 */
public final class ParameterStore {
    /** Baslik alanlari icin ureteç anahtari. */
    public static final String HEADER = "başlık.";

    public static final class MsgState {
        public final MessageDef def;
        public final Map<String, Object> txBody;
        /** Yol -&gt; ureteç ifadesi; her gonderimde hesaplanip degere yazilir. */
        final Map<String, Expr> generators = new LinkedHashMap<>();
        public Decoded lastRx;
        public long rxTime;
        public int rxCount;
        public String rxPeer = "";
        public Decoded lastTx;
        public long txTime;
        public int txCount;

        MsgState(MessageDef def, Map<String, Object> txBody) {
            this.def = def;
            this.txBody = txBody;
        }
    }

    private Schema schema;
    private Map<String, Object> txHeader = new LinkedHashMap<>();
    private final Map<String, Expr> headerGenerators = new LinkedHashMap<>();
    private final Map<String, MsgState> states = new LinkedHashMap<>();
    /** Son alinan herhangi bir mesajin basligi (Parametreler'de gosterilir). */
    public Decoded lastRxAny;
    private long version;

    public Schema schema() { return schema; }

    /** Sema degisti: ayni adli mesajlarin gonderilecek degerleri ve ureteçleri korunur. */
    public void setSchema(Schema s) {
        if (s == schema) return;
        Map<String, MsgState> old = new LinkedHashMap<>(states);
        states.clear();
        schema = s;
        if (s != null) {
            txHeader = Values.complete(s.header, txHeader);
            for (MessageDef m : s.messages()) {
                MsgState prev = old.get(m.name);
                Map<String, Object> body = prev != null ? prev.txBody : new LinkedHashMap<>();
                MsgState st = new MsgState(m, Values.complete(m.body, body));
                if (prev != null) {
                    st.rxCount = prev.rxCount;
                    st.txCount = prev.txCount;
                    st.generators.putAll(prev.generators);
                }
                states.put(m.name, st);
            }
        }
        lastRxAny = null;
        changed();
    }

    public Map<String, Object> txHeader() { return txHeader; }

    public MsgState state(MessageDef m) { return m == null ? null : states.get(m.name); }

    public MsgState state(String name) { return states.get(name); }

    public Iterable<MsgState> states() { return states.values(); }

    // ---- ureteçler ----

    /** Yolun ureteç metni ("başlık." ile baslayan yollar baslik ureteçleridir); yoksa "". */
    public String generator(MessageDef m, String path) {
        Expr e = path.startsWith(HEADER) ? headerGenerators.get(path) : m == null || state(m) == null ? null : state(m).generators.get(path);
        return e == null ? "" : e.text();
    }

    /**
     * Ureteci ayarlar; bos metin kaldirir.
     * @throws IllegalArgumentException ifade gecersizse
     */
    public void setGenerator(MessageDef m, String path, String text) {
        Map<String, Expr> target = path.startsWith(HEADER) ? headerGenerators : state(m).generators;
        if (text == null || text.trim().isEmpty()) target.remove(path);
        else target.put(path, Expr.parse(text));
        changed();
    }

    /** Tum ureteçler: anahtar "" baslik, digerleri mesaj adi; deger yol -&gt; ifade metni (proje kaydi icin). */
    public Map<String, Map<String, String>> generatorTexts() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!headerGenerators.isEmpty()) out.put("", texts(headerGenerators));
        for (MsgState st : states.values()) if (!st.generators.isEmpty()) out.put(st.def.name, texts(st.generators));
        return out;
    }

    private static Map<String, String> texts(Map<String, Expr> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> e : m.entrySet()) out.put(e.getKey(), e.getValue().text());
        return out;
    }

    /** Ureteçlerin sayisi (mesaj basina; baslik ureteçleri dahil degil). */
    public int generatorCount(MessageDef m) {
        MsgState st = state(m);
        return st == null ? 0 : st.generators.size();
    }

    /**
     * Ureteçleri uygular (degerler tabloda da gorunur) ve mesaji kodlar.
     * @throws IllegalArgumentException deger/ureteç hatasi (alan adiyla)
     */
    public byte[] encode(MessageDef m) {
        MsgState st = state(m);
        if (st == null) throw new IllegalArgumentException("şemada mesaj yok: " + (m == null ? "?" : m.name));
        List<String> problems = new ArrayList<>();
        apply(headerGenerators, schema.header, txHeader, HEADER, problems);
        apply(st.generators, m.body, st.txBody, "", problems);
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        if (!headerGenerators.isEmpty() || !st.generators.isEmpty()) changed();
        return Codec.encode(schema, m, txHeader, st.txBody);
    }

    private static void apply(Map<String, Expr> gens, comm.schema.StructDef s, Map<String, Object> values, String prefix,
                              List<String> problems) {
        if (gens.isEmpty()) return;
        Map<String, String> texts = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> g : gens.entrySet()) {
            try {
                texts.put(g.getKey(), g.getValue().eval(Expr.EMPTY));
            } catch (IllegalArgumentException e) {
                problems.add(g.getKey() + " üreteci: " + e.getMessage());
            }
        }
        problems.addAll(Values.applyTexts(s, values, prefix, texts));
    }

    /** Ureteçleri calistirmadan (durumlarini ilerletmeden) mevcut degerlerle kodlar; onizleme icin. */
    public byte[] preview(MessageDef m) {
        MsgState st = state(m);
        if (st == null) throw new IllegalArgumentException("şemada mesaj yok: " + (m == null ? "?" : m.name));
        return Codec.encode(schema, m, txHeader, st.txBody);
    }

    // ---- trafik ----

    /** Trafik kaydini isler; cozulmus ve bu semaya ait bir mesajsa durumu gunceller. */
    public void record(LogEntry e) {
        Decoded d = e.decoded;
        if (d == null || d.message == null || schema == null) return;
        MsgState st = states.get(d.message.name);
        if (st == null || st.def != d.message) return; // eski semadan kalma kayit
        if (e.dir == Direction.RX) {
            st.lastRx = d;
            st.rxTime = e.time;
            st.rxCount++;
            st.rxPeer = e.peer;
            lastRxAny = d;
        } else if (e.dir == Direction.TX) {
            st.lastTx = d;
            st.txTime = e.time;
            st.txCount++;
        }
        changed();
    }

    /** Son alinan degerleri gonderilecek degerlere kopyalar. */
    public boolean copyLastRx(MessageDef m) {
        MsgState st = state(m);
        if (st == null || st.lastRx == null) return false;
        copyFrom(m, st.lastRx);
        return true;
    }

    /** Verilen cozumun govdesini (ve basligini) gonderilecek degerlere kopyalar. */
    public void copyFrom(MessageDef m, Decoded d) {
        MsgState st = state(m);
        if (st == null) return;
        st.txBody.clear();
        st.txBody.putAll(Values.copyMap(d.body));
        Values.complete(m.body, st.txBody);
        changed();
    }

    public void resetDefaults(MessageDef m) {
        MsgState st = state(m);
        if (st == null) return;
        st.txBody.clear();
        Values.complete(m.body, st.txBody);
        changed();
    }

    /** Mesajin su anki gonderilecek degerlerini adlandirilmis bir hazir mesaj olarak dondurur. */
    public comm.core.MessagePreset snapshot(MessageDef m, String name) {
        MsgState st = state(m);
        if (st == null) throw new IllegalArgumentException(m == null ? "mesaj seçilmedi" : m.name + " şemada yok");
        comm.core.MessagePreset p = new comm.core.MessagePreset();
        p.name = name;
        p.message = m.name;
        p.values = Values.toTexts(m.body, st.txBody, "");
        return p;
    }

    /**
     * Hazir mesaji gonderilecek degerlere uygular (once varsayilanlar, sonra kayitli metinler).
     * @return uygulanamayan alanlar icin uyarilar
     * @throws IllegalArgumentException mesaj semada yoksa
     */
    public List<String> apply(comm.core.MessagePreset p) {
        MessageDef m = schema == null ? null : schema.byName(p.message);
        MsgState st = state(m);
        if (m == null || st == null) throw new IllegalArgumentException("'" + p.message + "' mesajı şemada yok");
        st.txBody.clear();
        Values.complete(m.body, st.txBody);
        List<String> problems = Values.applyTexts(m.body, st.txBody, "", p.values);
        changed();
        return problems;
    }

    public void changed() { version++; }

    public long version() { return version; }

}
