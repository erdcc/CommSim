package comm.snmp;

import comm.core.Direction;
import comm.core.Endpoint;
import comm.core.EndpointConfig;
import comm.core.TrafficBus;
import comm.schema.Decoded;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SNMP manager: agent'a GET/GETNEXT/GETBULK/SET/WALK istekleri gonderir; trap ve inform alir.
 * Sonuclar {@link #results()} listesinde birikir (arayuz surum numarasiyla yeniler).
 */
public final class SnmpManagerEndpoint extends Endpoint implements CommandResponder {
    public static final int MAX_RESULTS = 5000;
    private static final int WALK_LIMIT = 10_000;

    /** Sonuc listesinin bir satiri. */
    public static final class Result {
        public final long time = System.currentTimeMillis();
        public final String op;
        public final String oid;
        public final String type;
        public final String value;
        public final boolean error;

        Result(String op, String oid, String type, String value, boolean error) {
            this.op = op;
            this.oid = oid;
            this.type = type;
            this.value = value;
            this.error = error;
        }
    }

    private final List<Result> results = new ArrayList<>();
    private volatile long resultsVersion;
    private volatile long lastLatencyMs = -1;
    private volatile Snmp snmp;
    private volatile LoggingUdpTransport transport;
    private final byte[] engineId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snmp-manager");
        t.setDaemon(true);
        return t;
    });

    public SnmpManagerEndpoint(EndpointConfig config, TrafficBus bus) {
        super(config, bus);
        engineId = SnmpSupport.engineId("commsim-manager-" + id);
    }

    @Override public synchronized void start() throws IOException {
        if (snmp != null) return;
        setState(State.STARTING);
        try {
            UdpAddress local = new UdpAddress(InetAddress.getByName(config.localAddress), config.localPort);
            transport = new LoggingUdpTransport(local, this::packet);
            snmp = SnmpSupport.create(transport, engineId, config);
            snmp.addCommandResponder(this);
            snmp.listen();
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            error(null, "SNMP manager açılamadı: " + reason(e));
            setState(State.ERROR);
            throw e instanceof IOException ? (IOException) e : new IOException(reason(e), e);
        }
        setState(State.RUNNING);
        info(null, "Hazır; trap dinleme " + config.localAddress + ":" + transport.getListenAddress().getPort()
                + ", hedef " + config.remoteHost + ":" + config.remotePort);
    }

    public int boundPort() {
        LoggingUdpTransport t = transport;
        return t == null ? -1 : t.getListenAddress().getPort();
    }

    private void packet(boolean incoming, String peer, byte[] data) {
        if (incoming) received(peer, data);
        else sent(peer, data);
    }

    @Override public synchronized void stop() {
        boolean was = snmp != null;
        closeQuietly();
        if (was) info(null, "Durduruldu");
        setState(State.STOPPED);
    }

    private void closeQuietly() {
        try {
            if (snmp != null) snmp.close();
            else if (transport != null) transport.close();
        } catch (IOException ignored) {
            // kapaniyor
        }
        snmp = null;
        transport = null;
    }

    @Override protected void sendRaw(byte[] payload, String peer) throws IOException {
        LoggingUdpTransport t = transport;
        if (t == null) throw new IOException("çalışmıyor");
        t.sendMessage(SnmpAgentEndpoint.udp(peer != null ? peer : config.remoteHost + ":" + config.remotePort), payload, null);
    }

    @Override public List<String> peers() { return Collections.emptyList(); }

    @Override protected Decoded decode(byte[] data, Direction dir) { return SnmpDecoder.decode(data); }

    // ---- sonuclar ----

    public List<Result> results() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }

    public long resultsVersion() { return resultsVersion; }

    public long lastLatencyMs() { return lastLatencyMs; }

    public void clearResults() {
        synchronized (results) {
            results.clear();
        }
        resultsVersion++;
    }

    private void add(Result r) {
        synchronized (results) {
            results.add(r);
            if (results.size() > MAX_RESULTS) results.subList(0, results.size() - MAX_RESULTS).clear();
        }
        resultsVersion++;
    }

    private void addAll(String op, PDU pdu) {
        for (int i = 0; i < pdu.size(); i++) {
            VariableBinding vb = pdu.get(i);
            boolean ex = vb.getVariable().isException();
            add(new Result(op, vb.getOid().toDottedString(), SnmpValues.typeOf(vb.getVariable()),
                    SnmpValues.format(vb.getVariable()), ex));
        }
    }

    // ---- istekler (arka plan thread'inde) ----

    public void get(List<OID> oids) { submit(PDU.GET, oids, 0, 0, null); }

    public void getNext(List<OID> oids) { submit(PDU.GETNEXT, oids, 0, 0, null); }

    public void getBulk(List<OID> oids, int nonRepeaters, int maxRepetitions) {
        submit(PDU.GETBULK, oids, nonRepeaters, maxRepetitions, null);
    }

    public void set(List<VariableBinding> vbs) { submit(PDU.SET, null, 0, 0, vbs); }

    private void submit(int type, List<OID> oids, int nonRep, int maxRep, List<VariableBinding> vbs) {
        worker.execute(() -> {
            String op = PDU.getTypeString(type);
            try {
                PDU pdu = newPdu();
                pdu.setType(type);
                if (vbs != null) {
                    for (VariableBinding vb : vbs) pdu.add(vb);
                } else {
                    for (OID o : oids) pdu.add(new VariableBinding(o));
                }
                if (type == PDU.GETBULK) {
                    if (SnmpSupport.version(config) == SnmpConstants.version1) throw new IOException("GETBULK v1'de yok");
                    pdu.setNonRepeaters(nonRep);
                    pdu.setMaxRepetitions(maxRep);
                }
                PDU resp = request(pdu, type == PDU.SET);
                if (resp.getErrorStatus() != 0) {
                    add(new Result(op, "", "", "hata: " + SnmpValues.errorName(resp.getErrorStatus()) + " (" + resp.getErrorStatus()
                            + "), indeks " + resp.getErrorIndex(), true));
                } else {
                    addAll(op, resp);
                }
            } catch (IOException e) {
                add(new Result(op, oids == null || oids.isEmpty() ? "" : oids.get(0).toDottedString(), "", e.getMessage(), true));
                error(config.remoteHost + ":" + config.remotePort, op + ": " + e.getMessage());
            }
        });
    }

    /** Alt agaci gezer: v1'de GETNEXT, v2c/v3'te GETBULK (20'ser). */
    public void walk(OID root) {
        worker.execute(() -> {
            int count = 0;
            OID cur = root;
            boolean bulk = SnmpSupport.version(config) != SnmpConstants.version1;
            try {
                outer:
                while (count < WALK_LIMIT) {
                    PDU pdu = newPdu();
                    pdu.add(new VariableBinding(cur));
                    if (bulk) {
                        pdu.setType(PDU.GETBULK);
                        pdu.setMaxRepetitions(20);
                    } else {
                        pdu.setType(PDU.GETNEXT);
                    }
                    PDU resp = request(pdu, false);
                    if (resp.getErrorStatus() != 0) {
                        if (resp.getErrorStatus() != PDU.noSuchName) {
                            add(new Result("WALK", cur.toDottedString(), "", "hata: " + SnmpValues.errorName(resp.getErrorStatus()), true));
                        }
                        break;
                    }
                    if (resp.size() == 0) break;
                    for (int i = 0; i < resp.size(); i++) {
                        VariableBinding vb = resp.get(i);
                        if (vb.getVariable().isException() || !vb.getOid().startsWith(root) || vb.getOid().compareTo(cur) <= 0) {
                            break outer;
                        }
                        add(new Result("WALK", vb.getOid().toDottedString(), SnmpValues.typeOf(vb.getVariable()),
                                SnmpValues.format(vb.getVariable()), false));
                        cur = vb.getOid();
                        count++;
                    }
                }
                add(new Result("WALK", root.toDottedString(), "", count + " değişken" + (count >= WALK_LIMIT ? " (sınır)" : ""), false));
            } catch (IOException e) {
                add(new Result("WALK", cur.toDottedString(), "", e.getMessage(), true));
                error(config.remoteHost + ":" + config.remotePort, "WALK: " + e.getMessage());
            }
        });
    }

    private PDU newPdu() {
        return SnmpSupport.version(config) == SnmpConstants.version3 ? new ScopedPDU() : new PDU();
    }

    private PDU request(PDU pdu, boolean write) throws IOException {
        Snmp s = snmp;
        if (s == null) throw new IOException("manager çalışmıyor");
        long t0 = System.nanoTime();
        ResponseEvent ev = s.send(pdu, target(write));
        if (ev == null || ev.getResponse() == null) {
            if (ev != null && ev.getError() != null) throw new IOException(reason(ev.getError()));
            throw new IOException("zaman aşımı (" + config.snmpTimeoutMs + " ms × " + (config.snmpRetries + 1) + ")");
        }
        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000;
        PDU resp = ev.getResponse();
        if (resp.getType() == PDU.REPORT) throw new IOException("v3 rapor: " + reportText(resp));
        return resp;
    }

    private static String reportText(PDU report) {
        if (report.size() == 0) return "bilinmeyen";
        OID o = report.get(0).getOid();
        if (o.startsWith(SnmpConstants.usmStatsUnknownUserNames)) return "bilinmeyen kullanıcı";
        if (o.startsWith(SnmpConstants.usmStatsWrongDigests)) return "yanlış auth parolası";
        if (o.startsWith(SnmpConstants.usmStatsDecryptionErrors)) return "çözme hatası (priv parolası/protokolü)";
        if (o.startsWith(SnmpConstants.usmStatsUnsupportedSecLevels)) return "desteklenmeyen güvenlik seviyesi";
        if (o.startsWith(SnmpConstants.usmStatsNotInTimeWindows)) return "zaman penceresi dışında";
        return o.toDottedString();
    }

    private Target target(boolean write) throws IOException {
        UdpAddress addr = SnmpAgentEndpoint.udp(config.remoteHost + ":" + config.remotePort);
        int version = SnmpSupport.version(config);
        Target t;
        if (version == SnmpConstants.version3) {
            UserTarget ut = new UserTarget();
            ut.setSecurityName(new OctetString(config.snmpUser.trim()));
            ut.setSecurityLevel(SnmpSupport.securityLevel(config));
            t = ut;
        } else {
            CommunityTarget ct = new CommunityTarget();
            ct.setCommunity(new OctetString(write ? config.snmpWriteCommunity : config.snmpReadCommunity));
            t = ct;
        }
        t.setVersion(version);
        t.setAddress(addr);
        t.setTimeout(config.snmpTimeoutMs);
        t.setRetries(config.snmpRetries);
        return t;
    }

    // ---- trap / inform alimi ----

    @Override public void processPdu(CommandResponderEvent e) {
        PDU pdu = e.getPDU();
        if (pdu == null) return;
        int type = pdu.getType();
        if (type != PDU.TRAP && type != PDU.V1TRAP && type != PDU.INFORM) return;
        String peer = LoggingUdpTransport.peer(e.getPeerAddress());
        String op = PDU.getTypeString(type) + " ← " + peer;
        if (pdu instanceof PDUv1 && type == PDU.V1TRAP) {
            PDUv1 v1 = (PDUv1) pdu;
            add(new Result(op, v1.getEnterprise().toDottedString(), "v1", "generic " + v1.getGenericTrap()
                    + ", specific " + v1.getSpecificTrap(), false));
        }
        addAll(op, pdu);
        String trapOid = "";
        for (int i = 0; i < pdu.size(); i++) {
            if (pdu.get(i).getOid().equals(SnmpSupport.SNMP_TRAP_OID)) trapOid = " " + pdu.get(i).getVariable();
        }
        info(peer, PDU.getTypeString(type) + " alındı" + trapOid);
        if (type == PDU.INFORM) {
            PDU resp = (PDU) pdu.clone();
            resp.setType(PDU.RESPONSE);
            resp.setErrorStatus(0);
            resp.setErrorIndex(0);
            try {
                e.getMessageDispatcher().returnResponsePdu(e.getMessageProcessingModel(), e.getSecurityModel(),
                        e.getSecurityName(), e.getSecurityLevel(), resp, e.getMaxSizeResponsePDU(),
                        e.getStateReference(), new StatusInformation());
            } catch (MessageException ex) {
                error(peer, "Inform yanıtlanamadı: " + reason(ex));
            }
        }
        e.setProcessed(true);
    }
}
