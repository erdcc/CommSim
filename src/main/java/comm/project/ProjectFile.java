package comm.project;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import comm.core.EndpointConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proje dosyasi (JSON): baglanti ayarlari, gonderilecek mesaj degerleri, Modbus slave tablolari,
 * SNMP agent OID tablolari ve senaryo metni. v3 parolalari duz metin olarak saklanir.
 */
public final class ProjectFile {
    public static final int FORMAT = 1;

    public static final class EndpointEntry {
        public EndpointConfig config;
        /** Paylasilan baslik degerleri: yol -&gt; metin. */
        public Map<String, String> txHeader = new LinkedHashMap<>();
        /** Mesaj adi -&gt; (yol -&gt; metin). */
        public Map<String, Map<String, String>> txMessages = new LinkedHashMap<>();
        /** Ureteçler: anahtar "" baslik, digerleri mesaj adi; deger yol -&gt; ifade. */
        public Map<String, Map<String, String>> generators = new LinkedHashMap<>();
        /** Modbus slave: tablo adi (COILS, DISCRETE, HOLDING, INPUT) -&gt; adres -&gt; deger (sifirlar yazilmaz). */
        public Map<String, Map<Integer, Integer>> modbusTables;
        /** SNMP agent: OID tablosu satirlari (.oid dosya bicimi). */
        public List<String> oidTable;
    }

    public int format = FORMAT;
    public List<EndpointEntry> endpoints = new ArrayList<>();
    public String scenario = "";
    /** Istege bagli pencere yerlesimi. */
    public LayoutState layout;

    /** Yerlesimi tercihlerde saklamak icin JSON. */
    public static String toJson(Object o) throws IOException { return MAPPER.writeValueAsString(o); }

    public static <T> T fromJson(String s, Class<T> type) throws IOException { return MAPPER.readValue(s, type); }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.PUBLIC_ONLY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false)
            .build();

    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), this);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProjectFile load(Path file) throws IOException {
        ProjectFile p = MAPPER.readValue(file.toFile(), ProjectFile.class);
        if (p.format > FORMAT) throw new IOException("proje dosyası daha yeni bir sürümle kaydedilmiş (biçim " + p.format + ")");
        for (EndpointEntry e : p.endpoints) {
            if (e.config == null) throw new IOException("bağlantı ayarı eksik");
            if (e.txHeader == null) e.txHeader = new LinkedHashMap<>();
            if (e.txMessages == null) e.txMessages = new LinkedHashMap<>();
            if (e.generators == null) e.generators = new LinkedHashMap<>();
            if (e.config.modbusPolls == null) e.config.modbusPolls = new java.util.concurrent.CopyOnWriteArrayList<>();
            else e.config.modbusPolls = new java.util.concurrent.CopyOnWriteArrayList<>(e.config.modbusPolls);
            if (e.config.framing == null) e.config.framing = new comm.core.FramingConfig();
            if (e.config.faults == null) e.config.faults = new comm.core.FaultConfig();
            e.config.rules = e.config.rules == null ? new java.util.concurrent.CopyOnWriteArrayList<>()
                    : new java.util.concurrent.CopyOnWriteArrayList<>(e.config.rules);
            e.config.liveGenerators = e.config.liveGenerators == null ? new java.util.concurrent.CopyOnWriteArrayList<>()
                    : new java.util.concurrent.CopyOnWriteArrayList<>(e.config.liveGenerators);
            if (e.config.schemaOverrides == null) e.config.schemaOverrides = new comm.schema.SchemaOverrides();
        }
        if (p.scenario == null) p.scenario = "";
        return p;
    }
}
