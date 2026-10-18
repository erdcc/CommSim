package comm.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bir baglantinin ayarlari. Alanlar turune gore kullanilir:
 * TCP sunucu: localAddress, localPort. TCP istemci: remoteHost, remotePort, reconnectMs.
 * UDP: localAddress, localPort (dinleme), remoteHost, remotePort (varsayilan hedef),
 * multicastGroup, networkInterface, broadcast.
 */
public final class EndpointConfig {
    public enum Kind {
        TCP_SERVER("TCP Sunucu"),
        TCP_CLIENT("TCP İstemci"),
        UDP("UDP"),
        MODBUS_SLAVE("Modbus TCP Slave"),
        MODBUS_MASTER("Modbus TCP Master"),
        SNMP_AGENT("SNMP Agent"),
        SNMP_MANAGER("SNMP Manager"),
        WS_SERVER("WebSocket Sunucu"),
        WS_CLIENT("WebSocket İstemci");

        public final String label;

        Kind(String label) { this.label = label; }

        @Override public String toString() { return label; }

        public boolean isModbus() { return this == MODBUS_SLAVE || this == MODBUS_MASTER; }

        public boolean isSnmp() { return this == SNMP_AGENT || this == SNMP_MANAGER; }

        /** Dinleyen (sunucu) taraf mi. */
        public boolean isServer() { return this == TCP_SERVER || this == MODBUS_SLAVE || this == WS_SERVER; }

        public boolean isWebSocket() { return this == WS_SERVER || this == WS_CLIENT; }

        /** Baglanan (istemci) taraf mi. */
        public boolean isClient() { return this == TCP_CLIENT || this == MODBUS_MASTER || this == WS_CLIENT; }
    }

    public String name = "Bağlantı";
    public Kind kind = Kind.TCP_SERVER;
    public String localAddress = "0.0.0.0";
    public int localPort = 5050;
    public String remoteHost = "127.0.0.1";
    public int remotePort = 5050;
    /** >0 ise TCP istemci baglanti koparsa bu kadar ms sonra yeniden dener. */
    public int reconnectMs = 0;
    /** Bos degilse UDP bu multicast grubuna katilir. */
    public String multicastGroup = "";
    /** Multicast icin ag arayuzu adi (orn. en0); bos ise sistem secer. */
    public String networkInterface = "";
    public int multicastTtl = 1;
    public boolean broadcast = false;
    public FramingConfig framing = new FramingConfig();
    /** Mesaj semasi dosyasi (.def); bos ise cerceveler cozulmez. */
    public String schemaFile = "";
    /** Sema dosyasini degistirmeden bu baglantiya ozel byte sirasi / checksum / uzunluk ayarlari. */
    public comm.schema.SchemaOverrides schemaOverrides = new comm.schema.SchemaOverrides();

    // ---- Modbus ----
    /** Master: isteklerdeki unit id; slave: yanit verilen unit id. */
    public int modbusUnitId = 1;
    /** Slave: her unit id'ye yanit ver. */
    public boolean modbusAnyUnit = true;
    /** Slave: yanit gecikmesi (yavas cihaz benzetimi). */
    public int modbusDelayMs = 0;
    /** Master: yanit bekleme suresi. */
    public int modbusTimeoutMs = 1000;
    /** Master: periyodik okumalar. */
    public List<ModbusPoll> modbusPolls = new CopyOnWriteArrayList<>();
    /** Modbus slave / SNMP agent: periyodik degisen degerler. */
    public List<LiveGenerator> liveGenerators = new CopyOnWriteArrayList<>();
    /** TCP/UDP/WebSocket: otomatik yanit kurallari (sirayla; ilk eslesen calisir). */
    public List<ResponseRule> rules = new CopyOnWriteArrayList<>();
    /** Test amacli hata enjeksiyonu (gonderilen cercevelere). */
    public FaultConfig faults = new FaultConfig();

    // ---- WebSocket ----
    /** Sunucu: kabul edilen yol (bos = hepsi); istemci: istek yolu. */
    public String wsPath = "/";
    /** Bos degilse el sikismada istenen/kabul edilen alt protokol. */
    public String wsSubprotocol = "";
    /** Gecerli UTF-8 yukleri metin cercevesiyle gonder (aksi halde binary). */
    public boolean wsText = true;
    /** &gt;0 ise bu aralikla ping gonderilir. */
    public int wsPingMs = 0;

    // ---- SNMP ----
    /** V1, V2C ya da V3: manager'in istek surumu, agent'in trap surumu (agent her surume yanit verir). */
    public String snmpVersion = "V2C";
    public String snmpReadCommunity = "public";
    public String snmpWriteCommunity = "private";
    /** v3 USM kullanicisi. */
    public String snmpUser = "";
    /** NONE, MD5, SHA, SHA256, SHA512 */
    public String snmpAuthProtocol = "SHA";
    public String snmpAuthPassword = "";
    /** NONE, DES, 3DES, AES128, AES192, AES256 */
    public String snmpPrivProtocol = "AES128";
    public String snmpPrivPassword = "";
    public int snmpTimeoutMs = 1500;
    public int snmpRetries = 1;
    /** Agent: OID tablosu dosyasi (.oid); bos ise varsayilan system grubu. */
    public String oidFile = "";
    /** Agent: trap hedefi. */
    public String trapHost = "127.0.0.1";
    public int trapPort = 1162;

    public EndpointConfig copy() {
        EndpointConfig c = new EndpointConfig();
        c.name = name;
        c.kind = kind;
        c.localAddress = localAddress;
        c.localPort = localPort;
        c.remoteHost = remoteHost;
        c.remotePort = remotePort;
        c.reconnectMs = reconnectMs;
        c.multicastGroup = multicastGroup;
        c.networkInterface = networkInterface;
        c.multicastTtl = multicastTtl;
        c.broadcast = broadcast;
        c.framing = framing.copy();
        c.schemaFile = schemaFile;
        c.schemaOverrides = schemaOverrides == null ? new comm.schema.SchemaOverrides() : schemaOverrides.copy();
        c.modbusUnitId = modbusUnitId;
        c.modbusAnyUnit = modbusAnyUnit;
        c.modbusDelayMs = modbusDelayMs;
        c.modbusTimeoutMs = modbusTimeoutMs;
        c.wsPath = wsPath;
        c.wsSubprotocol = wsSubprotocol;
        c.wsText = wsText;
        c.wsPingMs = wsPingMs;
        c.snmpVersion = snmpVersion;
        c.snmpReadCommunity = snmpReadCommunity;
        c.snmpWriteCommunity = snmpWriteCommunity;
        c.snmpUser = snmpUser;
        c.snmpAuthProtocol = snmpAuthProtocol;
        c.snmpAuthPassword = snmpAuthPassword;
        c.snmpPrivProtocol = snmpPrivProtocol;
        c.snmpPrivPassword = snmpPrivPassword;
        c.snmpTimeoutMs = snmpTimeoutMs;
        c.snmpRetries = snmpRetries;
        c.oidFile = oidFile;
        c.trapHost = trapHost;
        c.trapPort = trapPort;
        c.modbusPolls = new CopyOnWriteArrayList<>();
        for (ModbusPoll p : modbusPolls) c.modbusPolls.add(p.copy());
        c.faults = faults == null ? new FaultConfig() : faults.copy();
        c.rules = new CopyOnWriteArrayList<>();
        if (rules != null) for (ResponseRule r : rules) c.rules.add(r.copy());
        c.liveGenerators = new CopyOnWriteArrayList<>();
        if (liveGenerators != null) for (LiveGenerator g : liveGenerators) c.liveGenerators.add(g.copy());
        return c;
    }

    private String overrideNote() {
        String o = schemaOverrides == null || schemaFile.isEmpty() ? "" : schemaOverrides.describe();
        return o.isEmpty() ? "" : ", şema: " + o;
    }

    /** Listede gosterilen kisa ozet; hata enjeksiyonu acikca belirtilir. */
    public String describe() {
        String fault = faults != null && faults.active() && !kind.isSnmp() ? "  ⚡ hata enjeksiyonu" : "";
        int activeRules = 0;
        if (rules != null && !kind.isModbus() && !kind.isSnmp()) for (ResponseRule r : rules) if (r.enabled) activeRules++;
        String rule = activeRules > 0 ? "  ↩ " + activeRules + " kural" : "";
        return baseDescribe() + rule + fault;
    }

    private String baseDescribe() {
        switch (kind) {
            case TCP_SERVER:
                return "TCP sunucu " + localAddress + ":" + localPort + ", " + framing.describe() + overrideNote();
            case TCP_CLIENT:
                return "TCP istemci → " + remoteHost + ":" + remotePort + ", " + framing.describe() + overrideNote();
            case MODBUS_SLAVE:
                return "Modbus slave " + localAddress + ":" + localPort + ", unit " + (modbusAnyUnit ? "hepsi" : modbusUnitId);
            case WS_SERVER:
                return "WebSocket sunucu ws://" + localAddress + ":" + localPort + (wsPath.isEmpty() ? "/" : wsPath) + overrideNote();
            case WS_CLIENT:
                return "WebSocket istemci → ws://" + remoteHost + ":" + remotePort + (wsPath.isEmpty() ? "/" : wsPath) + overrideNote();
            case SNMP_AGENT:
                return "SNMP agent " + localAddress + ":" + localPort + ", trap → " + trapHost + ":" + trapPort
                        + " (" + snmpVersion + ")";
            case SNMP_MANAGER:
                return "SNMP manager → " + remoteHost + ":" + remotePort + " (" + snmpVersion + "), trap portu "
                        + (localPort == 0 ? "yok" : String.valueOf(localPort));
            case MODBUS_MASTER:
                return "Modbus master → " + remoteHost + ":" + remotePort + ", unit " + modbusUnitId
                        + (modbusPolls.isEmpty() ? "" : ", " + modbusPolls.size() + " periyodik okuma");
            default:
                String s = "UDP " + localAddress + ":" + localPort + " → " + remoteHost + ":" + remotePort;
                if (!multicastGroup.isEmpty()) s += ", grup " + multicastGroup;
                if (broadcast) s += ", broadcast";
                return s + overrideNote();
        }
    }
}
