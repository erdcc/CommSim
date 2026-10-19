package comm.core;

import java.util.concurrent.atomic.AtomicLong;

/** Baglanti basina sayaclar. */
public final class Stats {
    private final AtomicLong rxFrames = new AtomicLong();
    private final AtomicLong txFrames = new AtomicLong();
    private final AtomicLong rxBytes = new AtomicLong();
    private final AtomicLong txBytes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile String lastError = "";
    private volatile long lastRx;
    private volatile long lastTx;

    void rx(int bytes) {
        rxFrames.incrementAndGet();
        rxBytes.addAndGet(bytes);
        lastRx = System.currentTimeMillis();
    }

    void tx(int bytes) {
        txFrames.incrementAndGet();
        txBytes.addAndGet(bytes);
        lastTx = System.currentTimeMillis();
    }

    void error(String message) {
        errors.incrementAndGet();
        lastError = message;
    }

    public long rxFrames() { return rxFrames.get(); }
    public long txFrames() { return txFrames.get(); }
    public long rxBytes() { return rxBytes.get(); }
    public long txBytes() { return txBytes.get(); }
    public long errors() { return errors.get(); }
    public String lastError() { return lastError; }
    /** Son alinan / gonderilen cercevenin zamani (ms); hic yoksa 0. */
    public long lastRx() { return lastRx; }
    public long lastTx() { return lastTx; }

    public void reset() {
        rxFrames.set(0);
        txFrames.set(0);
        rxBytes.set(0);
        txBytes.set(0);
        errors.set(0);
        lastError = "";
        lastRx = 0;
        lastTx = 0;
    }
}
