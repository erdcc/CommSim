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
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * SNMP agent: OID tablosundan GET/GETNEXT/GETBULK/SET isteklerine v1, v2c ve v3 ile yanit verir;
 * trap gonderir. Ham datagramlar trafik loguna yazilir.
 */
public final class SnmpAgentEndpoint extends Endpoint implements CommandResponder {
    private static final int MAX_BULK = 2000;

    private OidTable table = OidTable.defaults();
    private final LinkedHashSet<String> managers = new LinkedHashSet<>();
    private volatile Snmp snmp;
    private volatile LoggingUdpTransport transport;
    private final byte[] engineId;

    public SnmpAgentEndpoint(EndpointConfig config, TrafficBus bus) {
        super(config, bus);
        engineId = SnmpSupport.engineId("commsim-agent-" + id);
    }

    public OidTable table() { return table; }

    /** Baglantinin OID dosyasini yukler (bos ise varsayilan tablo). */
    public void loadOidFile() throws IOException {
        table = config.oidFile.trim().isEmpty() ? OidTable.defaults()
                : OidTable.load(java.nio.file.Paths.get(config.oidFile.trim()));
    }

    public String engineIdHex() { return new OctetString(engineId).toHexString(); }

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
            error(null, "SNMP agent açılamadı " + config.localAddress + ":" + config.localPort + ": " + reason(e));
            setState(State.ERROR);
            throw e instanceof IOException ? (IOException) e : new IOException(reason(e), e);
        }
        table.resetUptime();
        setState(State.RUNNING);
        info(null, "Dinleniyor: " + config.localAddress + ":" + transport.getListenAddress().getPort()
                + ", motor id " + engineIdHex());
    }

    /** Gercekte dinlenen port. */
    public int boundPort() {
        LoggingUdpTransport t = transport;
        return t == null ? -1 : t.getListenAddress().getPort();
    }

    private void packet(boolean incoming, String peer, byte[] data) {
        if (incoming) {
            synchronized (managers) {
                managers.remove(peer);
                managers.add(peer);
                if (managers.size() > 32) {
                    Iterator<String> it = managers.iterator();
                    it.next();
                    it.remove();
                }
            }
            received(peer, data);
        } else {
            sent(peer, data);
        }
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
        String to = peer != null ? peer : config.trapHost + ":" + config.trapPort;
        t.sendMessage(udp(to), payload, null);
    }

    static UdpAddress udp(String hostPort) throws IOException {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0) throw new IOException("adres host:port biçiminde olmalı: " + hostPort);
        try {
            return new UdpAddress(InetAddress.getByName(hostPort.substring(0, i)), Integer.parseInt(hostPort.substring(i + 1)));
        } catch (NumberFormatException e) {
            throw new IOException("geçersiz port: " + hostPort);
        }
    }

    @Override public List<String> peers() {
        synchronized (managers) {
            return new ArrayList<>(managers);
        }
    }

    @Override protected Decoded decode(byte[] data, Direction dir) { return SnmpDecoder.decode(data); }

    // ---- istek isleme ----

    @Override public void processPdu(CommandResponderEvent e) {
        PDU req = e.getPDU();
        if (req == null) return;
        int type = req.getType();
        if (type != PDU.GET && type != PDU.GETNEXT && type != PDU.GETBULK && type != PDU.SET) return;
        String peer = LoggingUdpTransport.peer(e.getPeerAddress());
        boolean v1 = e.getMessageProcessingModel() == org.snmp4j.mp.MessageProcessingModel.MPv1;
        if (e.getSecurityModel() != org.snmp4j.security.SecurityModel.SECURITY_MODEL_USM) {
            String community = new String(e.getSecurityName(), StandardCharsets.UTF_8);
            boolean ok = type == PDU.SET ? community.equals(config.snmpWriteCommunity)
                    : community.equals(config.snmpReadCommunity) || community.equals(config.snmpWriteCommunity);
            if (!ok) {
                error(peer, "Yetkisiz community '" + community + "' (" + PDU.getTypeString(type) + "); yanıtlanmadı");
                e.setProcessed(true);
                return;
            }
        }
        PDU resp = (PDU) req.clone();
        resp.setType(PDU.RESPONSE);
        resp.setErrorStatus(0);
        resp.setErrorIndex(0);
        switch (type) {
            case PDU.GET: get(resp, v1); break;
            case PDU.GETNEXT: getNext(resp, v1); break;
            case PDU.GETBULK: getBulk(req, resp); break;
            default: set(resp, v1, peer);
        }
        try {
            e.getMessageDispatcher().returnResponsePdu(e.getMessageProcessingModel(), e.getSecurityModel(),
                    e.getSecurityName(), e.getSecurityLevel(), resp, e.getMaxSizeResponsePDU(), e.getStateReference(),
                    new StatusInformation());
        } catch (MessageException ex) {
            error(peer, "Yanıt gönderilemedi: " + reason(ex));
        }
        e.setProcessed(true);
    }

    private void get(PDU resp, boolean v1) {
        for (int i = 0; i < resp.size(); i++) {
            VariableBinding vb = resp.get(i);
            Variable v = table.get(vb.getOid());
            if (v != null) {
                vb.setVariable(v);
            } else if (v1) {
                fail(resp, PDU.noSuchName, i);
                return;
            } else {
                vb.setVariable(table.hasSubtree(vb.getOid().trim()) ? Null.noSuchInstance : Null.noSuchObject);
            }
        }
    }

    private void getNext(PDU resp, boolean v1) {
        for (int i = 0; i < resp.size(); i++) {
            VariableBinding vb = resp.get(i);
            Map.Entry<OID, Variable> n = table.next(vb.getOid());
            if (n != null) {
                vb.setOid(n.getKey());
                vb.setVariable(n.getValue());
            } else if (v1) {
                fail(resp, PDU.noSuchName, i);
                return;
            } else {
                vb.setVariable(Null.endOfMibView);
            }
        }
    }

    /** RFC 3416 4.2.3: ilk N degisken GETNEXT, kalanlar max-repetitions kez tekrar. */
    private void getBulk(PDU req, PDU resp) {
        resp.clear(); // istek kimligini de sifirlar; yanit eslesmesi icin geri yazilir
        resp.setRequestID(req.getRequestID());
        resp.setErrorStatus(0);
        resp.setErrorIndex(0);
        int n = Math.max(0, Math.min(req.getNonRepeaters(), req.size()));
        int m = Math.max(0, req.getMaxRepetitions());
        for (int i = 0; i < n; i++) resp.add(nextOrEnd(req.get(i).getOid()));
        List<OID> cursors = new ArrayList<>();
        for (int i = n; i < req.size(); i++) cursors.add(req.get(i).getOid());
        for (int r = 0; r < m && !cursors.isEmpty() && resp.size() < MAX_BULK; r++) {
            boolean any = false;
            for (int i = 0; i < cursors.size(); i++) {
                VariableBinding vb = nextOrEnd(cursors.get(i));
                resp.add(vb);
                if (!Null.isExceptionSyntax(vb.getVariable().getSyntax())) any = true;
                cursors.set(i, vb.getOid());
            }
            if (!any) break;
        }
    }

    private VariableBinding nextOrEnd(OID oid) {
        Map.Entry<OID, Variable> n = table.next(oid);
        return n == null ? new VariableBinding(oid, Null.endOfMibView) : new VariableBinding(n.getKey(), n.getValue());
    }

    /** Once tum degiskenler dogrulanir, sonra hepsi birden yazilir. */
    private void set(PDU resp, boolean v1, String peer) {
        for (int i = 0; i < resp.size(); i++) {
            VariableBinding vb = resp.get(i);
            Variable cur = table.get(vb.getOid());
            if (cur == null) {
                fail(resp, v1 ? PDU.noSuchName : PDU.noCreation, i);
                return;
            }
            if (!table.isWritable(vb.getOid())) {
                fail(resp, v1 ? PDU.noSuchName : PDU.notWritable, i);
                return;
            }
            if (cur.getSyntax() != vb.getVariable().getSyntax()) {
                fail(resp, v1 ? PDU.badValue : PDU.wrongType, i);
                return;
            }
        }
        for (int i = 0; i < resp.size(); i++) {
            VariableBinding vb = resp.get(i);
            table.set(vb.getOid(), vb.getVariable());
            info(peer, "SET " + vb.getOid() + " = " + SnmpValues.format(vb.getVariable()));
        }
    }

    private static void fail(PDU resp, int status, int index) {
        resp.setErrorStatus(status);
        resp.setErrorIndex(index + 1);
    }

    // ---- trap ----

    /** Trap (v1/v2c/v3, ayardaki surume gore) gonderir; degiskenler trap OID'sinden sonra eklenir. */
    public void sendTrap(OID trapOid, List<VariableBinding> vbs) throws IOException {
        Snmp s = snmp;
        if (s == null) throw new IOException("agent çalışmıyor");
        UdpAddress target = udp(config.trapHost + ":" + config.trapPort);
        int version = SnmpSupport.version(config);
        Variable uptime = table.get(SnmpSupport.SYS_UPTIME);
        TimeTicks ticks = uptime instanceof TimeTicks ? (TimeTicks) uptime : new TimeTicks(0);
        PDU pdu;
        Target t;
        if (version == SnmpConstants.version1) {
            PDUv1 p = new PDUv1();
            p.setType(PDU.V1TRAP);
            // ent.0.N -> enterprise=ent, specific=N
            OID ent = new OID(trapOid);
            int specific = ent.size() > 1 ? ent.removeLast() : 0;
            if (ent.size() > 1 && ent.last() == 0) ent.removeLast();
            p.setEnterprise(ent);
            p.setGenericTrap(PDUv1.ENTERPRISE_SPECIFIC);
            p.setSpecificTrap(specific);
            p.setTimestamp(ticks.getValue());
            p.setAgentAddress(new IpAddress(InetAddress.getLoopbackAddress()));
            pdu = p;
        } else {
            pdu = version == SnmpConstants.version3 ? new ScopedPDU() : new PDU();
            pdu.setType(PDU.TRAP);
            pdu.add(new VariableBinding(SnmpSupport.SYS_UPTIME, ticks));
            pdu.add(new VariableBinding(SnmpSupport.SNMP_TRAP_OID, trapOid));
        }
        for (VariableBinding vb : vbs) pdu.add(vb);
        if (version == SnmpConstants.version3) {
            UserTarget ut = new UserTarget();
            ut.setSecurityName(new OctetString(config.snmpUser.trim()));
            ut.setSecurityLevel(SnmpSupport.securityLevel(config));
            ut.setAuthoritativeEngineID(engineId);
            t = ut;
        } else {
            CommunityTarget ct = new CommunityTarget();
            ct.setCommunity(new OctetString(config.snmpReadCommunity));
            t = ct;
        }
        t.setVersion(version);
        t.setAddress(target);
        s.send(pdu, t);
        info(config.trapHost + ":" + config.trapPort, "Trap gönderildi: " + trapOid + " (" + vbs.size() + " değişken)");
    }
}
