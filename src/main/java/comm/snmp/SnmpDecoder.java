package comm.snmp;

import comm.schema.Decoded;
import comm.schema.MessageDef;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.asn1.BER;
import org.snmp4j.asn1.BERInputStream;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import java.io.IOException;
import java.nio.ByteBuffer;

/** SNMP mesajlarini (BER) trafik logu icin cozer. v3'te sifreli govde gosterilmez. */
public final class SnmpDecoder {
    private SnmpDecoder() { }

    public static Decoded decode(byte[] data) {
        Decoded d = new Decoded();
        try {
            BERInputStream in = new BERInputStream(ByteBuffer.wrap(data));
            BER.MutableByte type = new BER.MutableByte();
            BER.decodeHeader(in, type);
            long p = in.getPosition();
            Integer32 ver = new Integer32();
            ver.decodeBER(in);
            int v = ver.getValue();
            String vname = v == 0 ? "v1" : v == 1 ? "v2c" : v == 3 ? "v3" : "v?" + v;
            span(d, "sürüm", p, in.getPosition(), vname);
            if (v == 0 || v == 1) {
                p = in.getPosition();
                OctetString community = new OctetString();
                community.decodeBER(in);
                span(d, "community", p, in.getPosition(), community.toString());
                p = in.getPosition();
                PDU pdu = v == 0 ? new PDUv1() : new PDU();
                pdu.decodeBER(in);
                describe(d, pdu, vname, p, in.getPosition());
            } else if (v == 3) {
                decodeV3(d, in);
            } else {
                d.error = "desteklenmeyen SNMP sürümü: " + v;
            }
        } catch (IOException | RuntimeException e) {
            d.error = "BER çözülemedi: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return d;
    }

    private static void decodeV3(Decoded d, BERInputStream in) throws IOException {
        BER.MutableByte type = new BER.MutableByte();
        long p = in.getPosition();
        BER.decodeHeader(in, type);
        Integer32 msgId = new Integer32();
        msgId.decodeBER(in);
        Integer32 maxSize = new Integer32();
        maxSize.decodeBER(in);
        OctetString flags = new OctetString();
        flags.decodeBER(in);
        Integer32 model = new Integer32();
        model.decodeBER(in);
        int f = flags.length() > 0 ? flags.get(0) & 0xFF : 0;
        boolean auth = (f & 1) != 0;
        boolean priv = (f & 2) != 0;
        span(d, "msgID", p, in.getPosition(), msgId.getValue() + "  (bayraklar: " + (auth ? "auth" : "noAuth")
                + (priv ? "+priv" : "") + ((f & 4) != 0 ? ", reportable" : "") + ")");

        p = in.getPosition();
        OctetString sec = new OctetString();
        sec.decodeBER(in);
        String user = "";
        try {
            BERInputStream us = new BERInputStream(ByteBuffer.wrap(sec.getValue()));
            BER.decodeHeader(us, type);
            OctetString engine = new OctetString();
            engine.decodeBER(us);
            Integer32 boots = new Integer32();
            boots.decodeBER(us);
            Integer32 time = new Integer32();
            time.decodeBER(us);
            OctetString name = new OctetString();
            name.decodeBER(us);
            user = name.toString();
            span(d, "usm", p, in.getPosition(), "kullanıcı '" + user + "', motor " + (engine.length() == 0 ? "(keşif)" : engine.toHexString()));
        } catch (IOException e) {
            span(d, "usm", p, in.getPosition(), "çözülemedi");
        }
        if (priv) {
            d.message = new MessageDef(0, "şifreli (v3)");
            span(d, "scopedPDU", in.getPosition(), in.getPosition(), "şifreli; içerik gösterilemez");
            return;
        }
        p = in.getPosition();
        ScopedPDU pdu = new ScopedPDU();
        pdu.decodeBER(in);
        describe(d, pdu, "v3", p, in.getPosition());
        if (user.isEmpty() && pdu.size() == 0) d.message = new MessageDef(pdu.getType(), PDU.getTypeString(pdu.getType()) + " (v3 keşif)");
    }

    private static void describe(Decoded d, PDU pdu, String vname, long from, long to) {
        String typeName = PDU.getTypeString(pdu.getType());
        d.message = new MessageDef(pdu.getType(), typeName + " (" + vname + ")");
        if (pdu instanceof PDUv1 && pdu.getType() == PDU.V1TRAP) {
            PDUv1 t = (PDUv1) pdu;
            span(d, "pdu", from, to, typeName + ", enterprise " + t.getEnterprise() + ", generic " + t.getGenericTrap()
                    + ", specific " + t.getSpecificTrap() + ", zaman " + t.getTimestamp());
        } else {
            String extra = pdu.getType() == PDU.GETBULK
                    ? ", non-repeaters " + pdu.getNonRepeaters() + ", max-repetitions " + pdu.getMaxRepetitions()
                    : pdu.getErrorStatus() != 0 ? ", hata " + SnmpValues.errorName(pdu.getErrorStatus()) + " (" + pdu.getErrorStatus()
                    + "), indeks " + pdu.getErrorIndex() : "";
            span(d, "pdu", from, to, typeName + ", istek id " + pdu.getRequestID() + extra);
            if (pdu.getType() == PDU.RESPONSE && pdu.getErrorStatus() != 0) {
                d.warnings.add("hata: " + SnmpValues.errorName(pdu.getErrorStatus()) + " (" + pdu.getErrorStatus() + "), indeks " + pdu.getErrorIndex());
            }
        }
        for (int i = 0; i < pdu.size(); i++) {
            VariableBinding vb = pdu.get(i);
            d.spans.add(new Decoded.Span("vb[" + i + "] " + vb.getOid(), (int) from, 0,
                    SnmpValues.format(vb.getVariable()) + "  (" + SnmpValues.typeOf(vb.getVariable()) + ")"));
            d.body.put(vb.getOid().toDottedString(), SnmpValues.format(vb.getVariable()));
        }
    }

    private static void span(Decoded d, String path, long from, long to, String text) {
        d.spans.add(new Decoded.Span(path, (int) from, (int) (to - from), text));
    }
}
