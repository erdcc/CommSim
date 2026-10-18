package comm.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import comm.core.Hex;
import comm.core.LogEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trafik kaydi (.rec): JSON satirlari. Ilk satir baslik, sonrakiler cerceveler:
 * <pre>{"format":"commsim-rec","version":1,"start":1760000000000}
 * {"t":0,"ep":"Örnek TCP istemci","dir":"TX","peer":"127.0.0.1:5050","hex":"A5 01 ..."}</pre>
 * t, kaydin baslangicina gore milisaniyedir.
 */
public final class Recording {
    public static final String FORMAT = "commsim-rec";

    public static final class Entry {
        public final long t;
        public final String endpoint;
        public final String dir;
        public final String peer;
        public final byte[] data;

        public Entry(long t, String endpoint, String dir, String peer, byte[] data) {
            this.t = t;
            this.endpoint = endpoint;
            this.dir = dir;
            this.peer = peer;
            this.data = data;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public final long start;
    public final List<Entry> entries;

    public Recording(long start, List<Entry> entries) {
        this.start = start;
        this.entries = Collections.unmodifiableList(entries);
    }

    /** Log kayitlarindan (yalnizca cerceveler) kayit olusturur. */
    public static Recording of(List<LogEntry> log) {
        List<Entry> out = new ArrayList<>();
        long start = -1;
        for (LogEntry e : log) {
            if (!e.isFrame()) continue;
            if (start < 0) start = e.time;
            out.add(new Entry(e.time - start, e.endpoint.name(), e.dir.name(), e.peer, e.data));
        }
        return new Recording(Math.max(start, 0), out);
    }

    public long duration() { return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).t; }

    /** Baglanti ve yon gruplari: "ad|YON" -&gt; adet (dosyadaki sirayla). */
    public Map<String, Integer> groups() {
        Map<String, Integer> g = new LinkedHashMap<>();
        for (Entry e : entries) g.merge(e.endpoint + "|" + e.dir, 1, Integer::sum);
        return g;
    }

    public void save(Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            ObjectNode h = JSON.createObjectNode();
            h.put("format", FORMAT);
            h.put("version", 1);
            h.put("start", start);
            w.write(JSON.writeValueAsString(h));
            w.write('\n');
            for (Entry e : entries) {
                ObjectNode n = JSON.createObjectNode();
                n.put("t", e.t);
                n.put("ep", e.endpoint);
                n.put("dir", e.dir);
                n.put("peer", e.peer);
                n.put("hex", Hex.toHex(e.data));
                w.write(JSON.writeValueAsString(n));
                w.write('\n');
            }
        }
    }

    /** @throws IOException "dosya:satir: aciklama" biciminde */
    public static Recording load(Path file) throws IOException {
        List<Entry> out = new ArrayList<>();
        long start = 0;
        int line = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String s;
            while ((s = r.readLine()) != null) {
                line++;
                if (s.trim().isEmpty()) continue;
                try {
                    JsonNode n = JSON.readTree(s);
                    if (line == 1) {
                        if (!FORMAT.equals(n.path("format").asText())) throw new IOException("kayıt dosyası değil (format alanı yok)");
                        start = n.path("start").asLong();
                        continue;
                    }
                    out.add(new Entry(n.path("t").asLong(), n.path("ep").asText(), n.path("dir").asText("TX"),
                            n.path("peer").asText(""), Hex.parse(n.path("hex").asText(""))));
                } catch (IOException | RuntimeException e) {
                    throw new IOException(file.getFileName() + ":" + line + ": " + e.getMessage());
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.t, b.t));
        return new Recording(start, out);
    }
}
