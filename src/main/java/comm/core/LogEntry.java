package comm.core;

import comm.schema.Decoded;

/** Trafik kaydinin tek satiri: bir cerceve (RX/TX) ya da bir olay (INFO/ERROR). */
public final class LogEntry {
    public final long time;
    public final Endpoint endpoint;
    public final Direction dir;
    /** Karsi uc adresi; olaylarda bos olabilir. */
    public final String peer;
    /** Cerceve yuku (cerceveleme baslik/ayraclari haric); olaylarda null. */
    public final byte[] data;
    /** Olay metni; cercevelerde null. */
    public final String text;
    /** Baglantiya sema atanmissa cercevenin cozumu; aksi halde null. */
    public final Decoded decoded;

    public LogEntry(Endpoint endpoint, Direction dir, String peer, byte[] data, String text) {
        this(endpoint, dir, peer, data, text, null);
    }

    public LogEntry(Endpoint endpoint, Direction dir, String peer, byte[] data, String text, Decoded decoded) {
        this.time = System.currentTimeMillis();
        this.endpoint = endpoint;
        this.dir = dir;
        this.peer = peer == null ? "" : peer;
        this.data = data;
        this.text = text;
        this.decoded = decoded;
    }

    public boolean isFrame() { return data != null; }
}
