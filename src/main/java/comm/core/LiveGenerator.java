package comm.core;

/**
 * Bir slave/agent degerini periyodik olarak bir ifadeyle gunceller.
 * Hedef: Modbus'ta "hr:100", "ir:5", "coil:3", "di:2"; SNMP'de OID (1.3.6.1.4.1.99999.1.1.0).
 */
public final class LiveGenerator {
    public volatile String target = "";
    /** Ifade: counter(0,1), random(10,20), sine(0,100,5000) ... (comm.runtime.Expr). */
    public volatile String expr = "";
    public volatile int intervalMs = 1000;
    public volatile boolean enabled = true;

    public LiveGenerator copy() {
        LiveGenerator g = new LiveGenerator();
        g.target = target;
        g.expr = expr;
        g.intervalMs = intervalMs;
        g.enabled = enabled;
        return g;
    }
}
