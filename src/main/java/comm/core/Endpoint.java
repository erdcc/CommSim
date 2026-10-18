package comm.core;

import comm.schema.Codec;
import comm.schema.Decoded;
import comm.schema.Schema;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tek bir haberlesme ucu (TCP sunucu/istemci, UDP, ileride Modbus/SNMP).
 * Alinan ve gonderilen her cerceve ile olaylar TrafficBus'a yayinlanir.
 */
public abstract class Endpoint {
    public enum State {
        STOPPED("Durdu"), STARTING("Başlıyor"), RUNNING("Çalışıyor"), ERROR("Hata");

        public final String label;

        State(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    public final int id = IDS.incrementAndGet();
    protected final EndpointConfig config;
    private final TrafficBus bus;
    private final Stats stats = new Stats();
    private volatile State state = State.STOPPED;
    private volatile Schema schema;

    protected Endpoint(EndpointConfig config, TrafficBus bus) {
        this.config = config;
        this.bus = bus;
    }

    /** Baslatir. Sunucu/UDP'de bind hatasi firlatir; istemcide baglanti arka planda kurulur. */
    public abstract void start() throws IOException;

    public abstract void stop();

    /**
     * Yuku gonderir.
     * @param peer hedef es adresi (host:port); null ise tum eslere / varsayilan hedefe
     */
    public final void send(byte[] payload, String peer) throws IOException {
        FaultConfig f = config.faults;
        if (f == null || !f.active()) {
            sendRaw(payload, peer);
            return;
        }
        FaultInjector.send(this, f, payload, peer);
    }

    /** Hata enjeksiyonu uygulanmadan, dogrudan tasimaya gonderir. Alt siniflar uygular. */
    protected abstract void sendRaw(byte[] payload, String peer) throws IOException;

    /** FaultInjector icin. */
    void sendDirect(byte[] payload, String peer) throws IOException { sendRaw(payload, peer); }

    /** Su anki esler (TCP) ya da son gorulen gondericiler (UDP). */
    public abstract List<String> peers();

    public EndpointConfig config() { return config; }

    public String name() { return config.name; }

    public State state() { return state; }

    public Stats stats() { return stats; }

    /** Cercevelerin cozulecegi sema; null olabilir. */
    public Schema schema() { return schema; }

    public void setSchema(Schema s) { schema = s; }

    public boolean isActive() { return state == State.RUNNING || state == State.STARTING; }

    protected void setState(State s) {
        state = s;
        bus.stateChanged(this);
    }

    protected void peersChanged() { bus.stateChanged(this); }

    protected void received(String peer, byte[] data) {
        stats.rx(data.length);
        bus.publish(new LogEntry(this, Direction.RX, peer, data, null, decode(data, Direction.RX)));
    }

    protected void sent(String peer, byte[] data) {
        stats.tx(data.length);
        bus.publish(new LogEntry(this, Direction.TX, peer, data, null, decode(data, Direction.TX)));
    }

    /** Trafik logu icin cerceveyi cozer; protokol uclari (Modbus, SNMP) kendi cozumlerini verir. */
    protected Decoded decode(byte[] data, Direction dir) {
        Schema s = schema;
        return s == null ? null : Codec.decode(s, data);
    }

    /** Dis bilesenlerin (kurallar, ureteçler, hata enjeksiyonu) bu baglantinin loguna yazmasi icin. */
    public void report(String peer, String message, boolean isError) {
        if (isError) error(peer, message);
        else info(peer, message);
    }

    protected void info(String peer, String message) {
        bus.publish(new LogEntry(this, Direction.INFO, peer, null, message));
    }

    protected void error(String peer, String message) {
        stats.error(message);
        bus.publish(new LogEntry(this, Direction.ERROR, peer, null, message));
    }

    protected static String reason(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public String toString() { return config.name; }
}
