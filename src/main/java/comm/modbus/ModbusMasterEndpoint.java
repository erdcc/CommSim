package comm.modbus;

import comm.core.Direction;
import comm.core.EndpointConfig;
import comm.core.ModbusPoll;
import comm.core.TrafficBus;
import comm.schema.Decoded;
import comm.transport.TcpClientEndpoint;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modbus TCP master (istemci): istek gonderir, yanitlari islem numarasiyla eslestirir,
 * okunan degerleri ayna tablolarda tutar; periyodik okumalari ve zaman asimlarini yonetir.
 */
public final class ModbusMasterEndpoint extends TcpClientEndpoint {
    private static final int TICK_MS = 20;

    private static final class Pending {
        final byte[] pdu;
        final long sentAt;
        final ModbusPoll poll;

        Pending(byte[] pdu, ModbusPoll poll) {
            this.pdu = pdu;
            this.sentAt = System.nanoTime();
            this.poll = poll;
        }
    }

    private final ModbusTables mirror = new ModbusTables();
    private final AtomicInteger nextTid = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<ModbusPoll, Long> lastPoll = new IdentityHashMap<>();
    private volatile ScheduledExecutorService timer;
    private volatile long lastLatencyMicros = -1;
    private volatile long okCount;
    private volatile long failCount;

    public ModbusMasterEndpoint(EndpointConfig config, TrafficBus bus) {
        super(config, bus, new MbapFramer());
    }

    /** Okunan degerlerin yansimasi. */
    public ModbusTables tables() { return mirror; }

    public long lastLatencyMicros() { return lastLatencyMicros; }

    public long okCount() { return okCount; }

    public long failCount() { return failCount; }

    public int pendingCount() { return pending.size(); }

    @Override public synchronized void start() {
        super.start();
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, name() + "-modbus");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        pending.clear();
        synchronized (lastPoll) {
            lastPoll.clear();
        }
        super.stop();
    }

    /** Istek PDU'sunu gonderir; islem numarasini dondurur. */
    public int request(byte[] pdu) throws IOException {
        return request(pdu, null);
    }

    private int request(byte[] pdu, ModbusPoll poll) throws IOException {
        if (!isConnected()) throw new IOException("bağlı değil");
        int tid = nextTid.incrementAndGet() & 0xFFFF;
        pending.put(tid, new Pending(pdu, poll));
        try {
            send(Modbus.adu(tid, config.modbusUnitId, pdu), null);
        } catch (IOException e) {
            pending.remove(tid);
            throw e;
        }
        return tid;
    }

    private void tick() {
        try {
            long now = System.nanoTime();
            long timeout = TimeUnit.MILLISECONDS.toNanos(Math.max(1, config.modbusTimeoutMs));
            for (Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Pending> e = it.next();
                if (now - e.getValue().sentAt > timeout) {
                    it.remove();
                    failCount++;
                    error(null, "Zaman aşımı (" + config.modbusTimeoutMs + " ms): " + Modbus.describe(e.getValue().pdu)
                            + ", işlem " + e.getKey());
                }
            }
            if (!isConnected()) return;
            for (ModbusPoll p : config.modbusPolls) {
                if (!p.enabled || p.intervalMs <= 0 || outstanding(p)) continue;
                long last;
                synchronized (lastPoll) {
                    Long l = lastPoll.get(p);
                    last = l == null ? 0 : l;
                }
                if (last != 0 && now - last < TimeUnit.MILLISECONDS.toNanos(p.intervalMs)) continue;
                synchronized (lastPoll) {
                    lastPoll.put(p, now);
                }
                try {
                    request(Modbus.readRequest(p.function, p.address, p.count), p);
                } catch (IllegalArgumentException e) {
                    p.enabled = false;
                    error(null, "Periyodik okuma kapatıldı: " + e.getMessage());
                } catch (IOException ignored) {
                    // baglanti koptu; sonraki turda tekrar denenir
                }
            }
        } catch (RuntimeException e) {
            error(null, "Modbus zamanlayıcı hatası: " + e);
        }
    }

    private boolean outstanding(ModbusPoll p) {
        for (Pending x : pending.values()) if (x.poll == p) return true;
        return false;
    }

    @Override protected void onFrame(String peer, byte[] adu) {
        received(peer, adu); // cozum icin istek henuz bekleyenler arasinda
        if (adu.length < 8) return;
        Pending p = pending.remove(Modbus.tid(adu));
        if (p == null) {
            error(peer, "Beklenmeyen yanıt (işlem " + Modbus.tid(adu) + "); zaman aşımına uğramış olabilir");
            return;
        }
        lastLatencyMicros = (System.nanoTime() - p.sentAt) / 1000;
        String problem = Modbus.apply(p.pdu, Modbus.pdu(adu), mirror);
        if (problem == null) {
            okCount++;
        } else {
            failCount++;
            error(peer, problem);
        }
    }

    @Override protected Decoded decode(byte[] data, Direction dir) {
        if (dir == Direction.TX) return ModbusDecoder.decode(data, true, null);
        Pending p = data.length >= 2 ? pending.get(Modbus.tid(data)) : null;
        return ModbusDecoder.decode(data, false, p == null ? null : p.pdu);
    }
}
