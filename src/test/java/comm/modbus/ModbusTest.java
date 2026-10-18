package comm.modbus;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.Hex;
import comm.core.LogEntry;
import comm.core.ModbusPoll;
import comm.core.TrafficBus;
import comm.modbus.ModbusTables.Table;
import comm.schema.Decoded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusTest {
    private final TrafficBus bus = new TrafficBus();
    private final List<LogEntry> log = new CopyOnWriteArrayList<>();
    private final List<Endpoint> open = new ArrayList<>();

    ModbusTest() {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    // ---- protokol ----

    @Test void slaveProcessesStandardFunctions() {
        ModbusTables t = new ModbusTables();
        t.write(Table.HOLDING, 100, new int[]{1, 0xFFFF, 3});
        assertEquals("03 06 00 01 FF FF 00 03", Hex.toHex(Modbus.process(Modbus.readRequest(3, 100, 3), t)));

        t.write(Table.COILS, 0, new int[]{1, 0, 1, 1, 0, 0, 0, 0, 1});
        assertEquals("01 02 0D 01", Hex.toHex(Modbus.process(Modbus.readRequest(1, 0, 9), t)));

        byte[] wr = Modbus.writeRegister(7, 1234);
        assertArrayEquals(wr, Modbus.process(wr, t)); // yanki
        assertEquals(1234, t.get(Table.HOLDING, 7));

        Modbus.process(Modbus.writeCoils(20, new int[]{1, 1, 0, 1}), t);
        assertEquals(1, t.get(Table.COILS, 23));
        assertEquals(0, t.get(Table.COILS, 22));

        assertEquals("10 00 32 00 02", Hex.toHex(Modbus.process(Modbus.writeRegisters(50, new int[]{5, 6}), t)));
        assertEquals(6, t.get(Table.HOLDING, 51));

        // FC23: once yazar, sonra okur
        byte[] rw = Modbus.process(Modbus.readWriteRegisters(50, 2, 50, new int[]{9}), t);
        assertEquals("17 04 00 09 00 06", Hex.toHex(rw));
    }

    @Test void slaveReturnsExceptions() {
        ModbusTables t = new ModbusTables();
        assertEquals("88 01", Hex.toHex(Modbus.process(new byte[]{8, 0, 0}, t)));               // illegal function
        assertEquals("83 02", Hex.toHex(Modbus.process(Hex.parse("03 FF FF 00 02"), t)));      // adres tasiyor
        assertEquals("83 03", Hex.toHex(Modbus.process(Hex.parse("03 00 00 00 7E"), t)));      // 126 register
        assertEquals("85 03", Hex.toHex(Modbus.process(Hex.parse("05 00 00 12 34"), t)));      // gecersiz coil
        assertThrows(IllegalArgumentException.class, () -> Modbus.readRequest(3, 0, 200));
        assertThrows(IllegalArgumentException.class, () -> Modbus.writeRegister(0, 70000));
    }

    @Test void masterAppliesResponsesToMirror() {
        ModbusTables slave = new ModbusTables();
        ModbusTables mirror = new ModbusTables();
        slave.write(Table.INPUT, 10, new int[]{42, 43});
        byte[] req = Modbus.readRequest(4, 10, 2);
        assertNull(Modbus.apply(req, Modbus.process(req, slave), mirror));
        assertEquals(43, mirror.get(Table.INPUT, 11));
        String err = Modbus.apply(Modbus.readRequest(3, 0, 1), Hex.parse("83 02"), mirror);
        assertTrue(err.contains("Illegal Data Address"), err);
    }

    @Test void framerHandlesSplitAndBackToBackFrames() throws Exception {
        byte[] a = Modbus.adu(1, 1, Modbus.readRequest(3, 0, 10));
        byte[] b = Modbus.adu(2, 1, Modbus.writeRegister(5, 9));
        byte[] stream = new byte[a.length + b.length];
        System.arraycopy(a, 0, stream, 0, a.length);
        System.arraycopy(b, 0, stream, a.length, b.length);
        List<String> out = new ArrayList<>();
        comm.transport.Framer.Decoder d = new MbapFramer().decoder();
        d.feed(stream, 0, 3, f -> out.add(Hex.toHex(f)));
        d.feed(stream, 3, stream.length - 3, f -> out.add(Hex.toHex(f)));
        assertEquals(2, out.size());
        assertEquals(Hex.toHex(b), out.get(1));
    }

    @Test void decoderShowsAddressesInResponse() {
        byte[] req = Modbus.readRequest(3, 100, 2);
        byte[] resp = Modbus.adu(5, 1, Hex.parse("03 04 00 0A FF FF"));
        Decoded d = ModbusDecoder.decode(resp, false, req);
        assertTrue(d.ok(), d.error);
        assertTrue(d.spans.stream().anyMatch(s -> s.path.equals("hr[101]") && s.text.startsWith("65535")));
        Decoded ex = ModbusDecoder.decode(Modbus.adu(5, 1, Hex.parse("83 02")), false, req);
        assertTrue(ex.label().startsWith("İstisna"), ex.label());
    }

    // ---- uctan uca ----

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(cond.getAsBoolean(), "zaman aşımı: " + what);
    }

    private ModbusSlaveEndpoint slave() throws Exception {
        EndpointConfig sc = new EndpointConfig();
        sc.kind = EndpointConfig.Kind.MODBUS_SLAVE;
        sc.localAddress = "127.0.0.1";
        sc.localPort = 0;
        ModbusSlaveEndpoint s = new ModbusSlaveEndpoint(sc, bus);
        open.add(s);
        s.start();
        return s;
    }

    private ModbusMasterEndpoint master(int port) throws Exception {
        EndpointConfig mc = new EndpointConfig();
        mc.kind = EndpointConfig.Kind.MODBUS_MASTER;
        mc.remotePort = port;
        mc.modbusTimeoutMs = 300;
        ModbusMasterEndpoint m = new ModbusMasterEndpoint(mc, bus);
        open.add(m);
        m.start();
        await(m::isConnected, "master bağlantısı");
        return m;
    }

    @Test void masterReadsAndWritesOverTcp() throws Exception {
        ModbusSlaveEndpoint s = slave();
        s.tables().write(Table.HOLDING, 0, new int[]{11, 22, 33});
        ModbusMasterEndpoint m = master(s.boundPort());

        m.request(Modbus.readRequest(3, 0, 3));
        await(() -> m.tables().get(Table.HOLDING, 2) == 33, "okuma");
        m.request(Modbus.writeCoil(5, true));
        await(() -> s.tables().get(Table.COILS, 5) == 1, "coil yazma");
        await(() -> m.okCount() == 2, "yanıt sayısı");
        assertTrue(m.lastLatencyMicros() >= 0);

        // trafik cozumu: slave RX istek, master RX yanit (adresli)
        assertTrue(log.stream().anyMatch(e -> e.endpoint == m && e.dir == Direction.RX && e.decoded != null
                && e.decoded.spans.stream().anyMatch(x -> x.path.equals("hr[2]"))));
    }

    @Test void pollingAndExceptionsAndTimeouts() throws Exception {
        ModbusSlaveEndpoint s = slave();
        s.config().modbusAnyUnit = false;
        s.config().modbusUnitId = 1;
        ModbusMasterEndpoint m = master(s.boundPort());
        ModbusPoll p = new ModbusPoll();
        p.function = 4;
        p.address = 0;
        p.count = 2;
        p.intervalMs = 50;
        m.config().modbusPolls.add(p);
        s.tables().set(Table.INPUT, 1, 77);
        await(() -> m.tables().get(Table.INPUT, 1) == 77, "periyodik okuma");
        s.tables().set(Table.INPUT, 1, 78);
        await(() -> m.tables().get(Table.INPUT, 1) == 78, "periyodik okuma güncellemesi");
        p.enabled = false;

        long fails = m.failCount();
        m.request(Hex.parse("03 FF FF 00 02")); // adres tasiyor -> istisna 02
        await(() -> m.failCount() == fails + 1, "istisna");
        assertTrue(log.stream().anyMatch(e -> e.dir == Direction.ERROR && e.text.contains("Illegal Data Address")));

        m.config().modbusUnitId = 9; // slave yanit vermez -> zaman asimi
        m.request(Modbus.readRequest(3, 0, 1));
        await(() -> log.stream().anyMatch(e -> e.dir == Direction.ERROR && e.text.startsWith("Zaman aşımı")), "zaman aşımı");
        assertEquals(0, m.pendingCount());
    }
}
