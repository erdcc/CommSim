package comm.snmp;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.LogEntry;
import comm.core.TrafficBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnmpTest {
    private static final OID SYS_NAME = new OID("1.3.6.1.2.1.1.5.0");
    private static final OID SYS_DESCR = new OID("1.3.6.1.2.1.1.1.0");

    private final TrafficBus bus = new TrafficBus();
    private final List<LogEntry> log = new CopyOnWriteArrayList<>();
    private final List<Endpoint> open = new ArrayList<>();

    SnmpTest() {
        bus.addListener(new TrafficBus.Listener() {
            @Override public void onEntry(LogEntry e) { log.add(e); }

            @Override public void onStateChanged(Endpoint endpoint) { }
        });
    }

    @AfterEach void tearDown() {
        for (Endpoint e : open) e.stop();
    }

    private static EndpointConfig v3(EndpointConfig c) {
        c.snmpVersion = "V3";
        c.snmpUser = "testuser";
        c.snmpAuthProtocol = "SHA";
        c.snmpAuthPassword = "authpass-123";
        c.snmpPrivProtocol = "AES128";
        c.snmpPrivPassword = "privpass-123";
        return c;
    }

    private SnmpManagerEndpoint manager(String version, boolean useV3) throws Exception {
        EndpointConfig mc = new EndpointConfig();
        mc.kind = EndpointConfig.Kind.SNMP_MANAGER;
        mc.localAddress = "127.0.0.1";
        mc.localPort = 0;
        mc.snmpVersion = version;
        mc.snmpTimeoutMs = 700;
        mc.snmpRetries = 0;
        if (useV3) v3(mc);
        SnmpManagerEndpoint m = new SnmpManagerEndpoint(mc, bus);
        open.add(m);
        m.start();
        return m;
    }

    private SnmpAgentEndpoint agent(int trapPort, boolean useV3) throws Exception {
        EndpointConfig ac = new EndpointConfig();
        ac.kind = EndpointConfig.Kind.SNMP_AGENT;
        ac.localAddress = "127.0.0.1";
        ac.localPort = 0;
        ac.trapPort = trapPort;
        if (useV3) v3(ac);
        SnmpAgentEndpoint a = new SnmpAgentEndpoint(ac, bus);
        open.add(a);
        a.start();
        return a;
    }

    private static void point(SnmpManagerEndpoint m, SnmpAgentEndpoint a) {
        m.config().remoteHost = "127.0.0.1";
        m.config().remotePort = a.boundPort();
    }

    private static SnmpManagerEndpoint.Result await(SnmpManagerEndpoint m, Predicate<SnmpManagerEndpoint.Result> p)
            throws InterruptedException {
        long end = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < end) {
            for (SnmpManagerEndpoint.Result r : m.results()) if (p.test(r)) return r;
            Thread.sleep(10);
        }
        throw new AssertionError("sonuç gelmedi; mevcut: " + describe(m.results()));
    }

    private static String describe(List<SnmpManagerEndpoint.Result> rs) {
        StringBuilder sb = new StringBuilder();
        for (SnmpManagerEndpoint.Result r : rs) sb.append(r.op).append(' ').append(r.oid).append('=').append(r.value).append("; ");
        return sb.toString();
    }

    @Test void oidTableFileRoundTrip() throws Exception {
        OidTable t = OidTable.parse("1.3.6.1.4.1.1.1.0 INTEGER rw -5\n"
                + "1.3.6.1.4.1.1.2.0 OCTET_STRING ro \"a \\\"b\\\" c\"\n"
                + "1.3.6.1.4.1.1.3.0 HEX_STRING ro 00 FF\n"
                + "1.3.6.1.4.1.1.4.0 IPADDRESS ro 10.0.0.1\n"
                + "1.3.6.1.4.1.1.5.0 TIMETICKS ro @uptime\n", "t.oid");
        assertEquals(5, t.size());
        StringBuilder sb = new StringBuilder();
        for (OidTable.Entry e : t.snapshot()) sb.append(OidTable.line(e)).append('\n');
        OidTable again = OidTable.parse(sb.toString(), "again");
        assertEquals("a \"b\" c", SnmpValues.format(again.get(new OID("1.3.6.1.4.1.1.2.0"))));
        assertEquals("HEX_STRING", SnmpValues.typeOf(again.get(new OID("1.3.6.1.4.1.1.3.0"))));
        assertTrue(again.snapshot().get(4).uptime);
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> OidTable.parse("1.3.6 INTEGER rw abc\n", "bad.oid"));
        assertTrue(e.getMessage().startsWith("bad.oid:1:"), e.getMessage());
    }

    @Test void v2cGetNextBulkWalkAndSet() throws Exception {
        SnmpManagerEndpoint m = manager("V2C", false);
        SnmpAgentEndpoint a = agent(m.boundPort(), false);
        point(m, a);

        m.get(Arrays.asList(SYS_DESCR, new OID("1.3.6.1.2.1.1.99.0")));
        await(m, r -> r.op.equals("GET") && r.oid.equals(SYS_DESCR.toDottedString()) && r.value.contains("simülatörü"));
        await(m, r -> r.op.equals("GET") && r.value.equals("noSuchObject"));

        m.getNext(Collections.singletonList(SYS_DESCR));
        await(m, r -> r.op.equals("GETNEXT") && r.oid.equals("1.3.6.1.2.1.1.2.0"));

        m.getBulk(Collections.singletonList(new OID("1.3.6.1.2.1.1")), 0, 3);
        await(m, r -> r.op.equals("GETBULK") && r.oid.equals("1.3.6.1.2.1.1.3.0") && r.type.equals("TIMETICKS"));

        m.walk(new OID("1.3.6.1.2.1.1"));
        await(m, r -> r.op.equals("WALK") && r.value.equals("6 değişken"));

        m.set(Collections.singletonList(new VariableBinding(SYS_NAME, new OctetString("yeni-ad"))));
        await(m, r -> r.op.equals("SET") && r.value.equals("yeni-ad"));
        assertEquals("yeni-ad", a.table().get(SYS_NAME).toString());

        m.set(Collections.singletonList(new VariableBinding(SYS_DESCR, new OctetString("x"))));
        await(m, r -> r.op.equals("SET") && r.error && r.value.contains("notWritable"));
        m.set(Collections.singletonList(new VariableBinding(SYS_NAME, new Integer32(1))));
        await(m, r -> r.op.equals("SET") && r.error && r.value.contains("wrongType"));

        // trafik logu ham BER'i cozer
        assertTrue(log.stream().anyMatch(e -> e.endpoint == a && e.dir == Direction.RX && e.decoded != null
                && e.decoded.message != null && e.decoded.message.name.equals("GET (v2c)")));
        assertTrue(log.stream().anyMatch(e -> e.endpoint == m && e.dir == Direction.RX && e.decoded != null
                && e.decoded.spans.stream().anyMatch(s -> s.path.contains(SYS_NAME.toDottedString()))));
    }

    @Test void wrongCommunityIsIgnoredAndTimesOut() throws Exception {
        SnmpManagerEndpoint m = manager("V2C", false);
        SnmpAgentEndpoint a = agent(m.boundPort(), false);
        point(m, a);
        m.config().snmpReadCommunity = "yanlis";
        m.get(Collections.singletonList(SYS_DESCR));
        await(m, r -> r.error && r.value.startsWith("zaman aşımı"));
        assertTrue(log.stream().anyMatch(e -> e.endpoint == a && e.dir == Direction.ERROR && e.text.contains("Yetkisiz")));
    }

    @Test void v1ReportsNoSuchName() throws Exception {
        SnmpManagerEndpoint m = manager("V1", false);
        SnmpAgentEndpoint a = agent(m.boundPort(), false);
        point(m, a);
        m.get(Collections.singletonList(new OID("1.3.6.1.9.9.9.0")));
        await(m, r -> r.error && r.value.contains("noSuchName"));
        m.walk(new OID("1.3.6.1.2.1.1"));
        await(m, r -> r.op.equals("WALK") && r.value.equals("6 değişken"));
    }

    @Test void v3AuthPrivGetSetAndTrap() throws Exception {
        SnmpManagerEndpoint m = manager("V3", true);
        SnmpAgentEndpoint a = agent(m.boundPort(), true);
        point(m, a);
        m.get(Collections.singletonList(SYS_NAME));
        await(m, r -> r.op.equals("GET") && r.value.equals("sim-agent"));
        m.set(Collections.singletonList(new VariableBinding(SYS_NAME, new OctetString("v3-ad"))));
        await(m, r -> r.op.equals("SET") && r.value.equals("v3-ad"));
        assertTrue(log.stream().anyMatch(e -> e.decoded != null && e.decoded.message != null
                && e.decoded.message.name.equals("şifreli (v3)")));

        a.config().snmpVersion = "V3";
        a.sendTrap(new OID("1.3.6.1.4.1.99999.0.1"), Collections.singletonList(new VariableBinding(SYS_NAME, new OctetString("t"))));
        await(m, r -> r.op.startsWith("TRAP") && r.oid.equals(SYS_NAME.toDottedString()));
    }

    @Test void wrongV3PasswordIsReported() throws Exception {
        SnmpManagerEndpoint m = manager("V3", true);
        SnmpAgentEndpoint a = agent(m.boundPort(), true);
        point(m, a);
        m.stop();
        m.config().snmpAuthPassword = "baska-parola";
        m.start();
        m.get(Collections.singletonList(SYS_NAME));
        SnmpManagerEndpoint.Result r = await(m, x -> x.error);
        assertTrue(r.value.contains("auth") || r.value.contains("zaman"), r.value);
    }

    @Test void v1AndV2cTrapsReachManager() throws Exception {
        SnmpManagerEndpoint m = manager("V2C", false);
        SnmpAgentEndpoint a = agent(m.boundPort(), false);
        a.sendTrap(new OID("1.3.6.1.4.1.99999.0.7"), Collections.<VariableBinding>emptyList());
        await(m, r -> r.op.startsWith("TRAP") && r.value.equals("1.3.6.1.4.1.99999.0.7"));
        a.config().snmpVersion = "V1";
        a.sendTrap(new OID("1.3.6.1.4.1.99999.0.8"), Collections.<VariableBinding>emptyList());
        await(m, r -> r.op.startsWith("V1TRAP") && r.value.contains("specific 8"));
    }

    @Test void parsesLowercaseTypesUnderTurkishLocale() throws Exception {
        java.util.Locale old = java.util.Locale.getDefault();
        java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
        try {
            OidTable t = OidTable.parse("1.3.6.1.4.1.1.0 integer RW 5\n1.3.6.1.4.1.2.0 octet_string ro \"i\"\n", "tr.oid");
            assertEquals("INTEGER", SnmpValues.typeOf(t.get(new OID("1.3.6.1.4.1.1.0"))));
            assertTrue(t.isWritable(new OID("1.3.6.1.4.1.1.0")));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
