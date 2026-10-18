package sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Haritadaki tek node nesnesi. Tum mesajlar bu nesnenin ozelliklerinden turetilir.
 * Haritaya iki tur eklenir: PLATFORM ve RADAR.
 */
public final class Node {
    public enum Type { PLATFORM, RADAR }

    /** Platformun TCP gate (istemci baglantisi) durumu. Radarlar icin hep OFF. */
    public enum GateState { OFF, CONNECTING, CONNECTED, FAILED }

    public static final List<String> IDENTITIES = List.of("PENDING", "FRIEND", "HOSTILE", "NEUTRAL", "UNKNOWN");

    public final Type type;
    public int nodeId;
    public String name;
    public String identity = "UNKNOWN";
    public double lat;
    public double lon;
    public double altitude;
    public double speed;
    public double heading;
    public GateState gate = GateState.OFF;

    public Node(Type type, int nodeId, String name, double lat, double lon) {
        this.type = type;
        this.nodeId = nodeId;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
    }

    /** Mesaj alanlariyla eslesen ozellikler (anahtar = alan adi veya @bind adi). */
    public Map<String, Object> attributes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId);
        m.put("nodeType", type.name());
        m.put("name", name);
        m.put("identity", identity);
        m.put("lat", lat);
        m.put("lon", lon);
        m.put("altitude", altitude);
        m.put("speed", speed);
        m.put("heading", heading);
        return m;
    }
}
