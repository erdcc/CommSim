package comm.runtime;

import comm.core.Endpoint;
import comm.core.LiveGenerator;
import comm.modbus.ModbusSlaveEndpoint;
import comm.modbus.ModbusTables;
import comm.snmp.SnmpAgentEndpoint;
import comm.snmp.SnmpValues;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Calisan Modbus slave ve SNMP agent'larin canli degerlerini (LiveGenerator) periyodik gunceller. */
public final class LiveGenerators {
    private static final int TICK_MS = 20;

    private static final class State {
        String text;
        Expr expr;
        long last;
    }

    private final Supplier<List<Endpoint>> endpoints;
    private final Map<LiveGenerator, State> states = new IdentityHashMap<>();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-generators");
        t.setDaemon(true);
        return t;
    });

    /** @param endpoints her cagrida guncel baglanti listesi (thread-safe) */
    public LiveGenerators(Supplier<List<Endpoint>> endpoints) {
        this.endpoints = endpoints;
        exec.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() { exec.shutdownNow(); }

    private void tick() {
        long now = System.currentTimeMillis();
        try {
            for (Endpoint ep : endpoints.get()) {
                if (ep.state() != Endpoint.State.RUNNING) continue;
                for (LiveGenerator g : ep.config().liveGenerators) {
                    if (!g.enabled || g.expr.trim().isEmpty() || g.target.trim().isEmpty()) continue;
                    State s = states.get(g);
                    if (s == null) {
                        s = new State();
                        states.put(g, s);
                    }
                    if (now - s.last < Math.max(TICK_MS, g.intervalMs)) continue;
                    s.last = now;
                    try {
                        if (!g.expr.equals(s.text)) {
                            s.text = g.expr;
                            s.expr = Expr.parse(g.expr);
                        }
                        apply(ep, g.target.trim(), s.expr.eval(Expr.EMPTY));
                    } catch (RuntimeException e) {
                        g.enabled = false;
                        ep.report(null, "Canlı değer kapatıldı (" + g.target + "): " + e.getMessage(), true);
                    }
                }
            }
        } catch (RuntimeException e) {
            // baglanti listesi degisirken; sonraki turda devam
        }
    }

    /** Degeri hedefe yazar. @throws IllegalArgumentException hedef ya da deger gecersizse */
    static void apply(Endpoint ep, String target, String value) {
        if (ep instanceof ModbusSlaveEndpoint) {
            ModbusTarget t = modbusTarget(target);
            long v = (long) Expr.num(value);
            ((ModbusSlaveEndpoint) ep).tables().set(t.table, t.address, (int) (t.table.bit ? (v != 0 ? 1 : 0) : v & 0xFFFF));
        } else if (ep instanceof SnmpAgentEndpoint) {
            SnmpAgentEndpoint a = (SnmpAgentEndpoint) ep;
            OID oid = SnmpValues.oid(target);
            Variable cur = a.table().get(oid);
            if (cur == null) throw new IllegalArgumentException("OID tabloda yok: " + target);
            a.table().set(oid, SnmpValues.parse(SnmpValues.typeOf(cur), value));
        } else {
            throw new IllegalArgumentException("canlı değerler yalnızca Modbus slave ve SNMP agent içindir");
        }
    }

    public static final class ModbusTarget {
        public final ModbusTables.Table table;
        public final int address;

        ModbusTarget(ModbusTables.Table table, int address) {
            this.table = table;
            this.address = address;
        }
    }

    /** "hr:100", "ir:5", "coil:3", "di:2" (adres ondalik ya da 0x). */
    public static ModbusTarget modbusTarget(String target) {
        String t = target.trim().toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("hedef 'hr:100' biçiminde olmalı (hr, ir, coil, di)");
        ModbusTables.Table table;
        switch (t.substring(0, colon)) {
            case "hr": table = ModbusTables.Table.HOLDING; break;
            case "ir": table = ModbusTables.Table.INPUT; break;
            case "coil": table = ModbusTables.Table.COILS; break;
            case "di": table = ModbusTables.Table.DISCRETE; break;
            default: throw new IllegalArgumentException("tablo hr, ir, coil ya da di olmalı: " + target);
        }
        int addr;
        try {
            String a = t.substring(colon + 1).trim();
            addr = a.startsWith("0x") ? Integer.parseInt(a.substring(2), 16) : Integer.parseInt(a);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("geçersiz adres: " + target);
        }
        if (addr < 0 || addr >= ModbusTables.SIZE) throw new IllegalArgumentException("adres 0 … 65535 olmalı: " + target);
        return new ModbusTarget(table, addr);
    }

    /** Arayuz dogrulamasi: hedef bicimi bu baglanti turune uygun mu. */
    public static void checkTarget(Endpoint ep, String target) {
        if (ep instanceof ModbusSlaveEndpoint) modbusTarget(target);
        else if (ep instanceof SnmpAgentEndpoint) SnmpValues.oid(target);
    }
}
