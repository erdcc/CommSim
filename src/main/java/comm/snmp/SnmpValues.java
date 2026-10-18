package comm.snmp;

import comm.core.Hex;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;

import java.nio.charset.StandardCharsets;

/** SNMP degerlerinin metin <-> Variable donusumleri. */
public final class SnmpValues {
    public static final String[] TYPES = {"INTEGER", "OCTET_STRING", "HEX_STRING", "OID", "IPADDRESS",
            "COUNTER32", "GAUGE32", "TIMETICKS", "COUNTER64", "NULL"};

    private SnmpValues() { }

    /** @throws IllegalArgumentException gecersiz tip ya da deger */
    public static Variable parse(String type, String text) {
        String t = text == null ? "" : text.trim();
        try {
            switch (type) {
                case "INTEGER": return new Integer32(Integer.parseInt(t));
                case "OCTET_STRING": return new OctetString(unquote(text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                case "HEX_STRING": return new OctetString(Hex.parse(t));
                case "OID": return oid(t);
                case "IPADDRESS": {
                    IpAddress ip = new IpAddress();
                    if (!ip.parseAddress(t) || t.isEmpty()) throw new IllegalArgumentException("geçersiz IP adresi: " + t);
                    return ip;
                }
                case "COUNTER32": return new Counter32(unsigned32(t));
                case "GAUGE32": return new Gauge32(unsigned32(t));
                case "TIMETICKS": return new TimeTicks(unsigned32(t));
                case "COUNTER64": return new Counter64(Long.parseUnsignedLong(t));
                case "NULL": return new Null();
                default: throw new IllegalArgumentException("bilinmeyen tip: " + type);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(type + " için geçersiz değer: '" + t + "'");
        }
    }

    private static long unsigned32(String t) {
        long v = Long.parseLong(t);
        if (v < 0 || v > 0xFFFFFFFFL) throw new IllegalArgumentException("0 … 4294967295 olmalı: " + t);
        return v;
    }

    public static OID oid(String s) {
        String t = s.trim();
        if (t.startsWith(".")) t = t.substring(1);
        if (!t.matches("\\d+(\\.\\d+)*")) throw new IllegalArgumentException("geçersiz OID: '" + s.trim() + "'");
        return new OID(t);
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static final String[] ERRORS = {"noError", "tooBig", "noSuchName", "badValue", "readOnly", "genErr",
            "noAccess", "wrongType", "wrongLength", "wrongEncoding", "wrongValue", "noCreation", "inconsistentValue",
            "resourceUnavailable", "commitFailed", "undoFailed", "authorizationError", "notWritable", "inconsistentName"};

    /** RFC 3416 hata durumu adi. */
    public static String errorName(int status) {
        return status >= 0 && status < ERRORS.length ? ERRORS[status] : "hata " + status;
    }

    private static String exceptionName(Variable v) {
        switch (v.getSyntax()) {
            case org.snmp4j.smi.SMIConstants.EXCEPTION_NO_SUCH_OBJECT: return "noSuchObject";
            case org.snmp4j.smi.SMIConstants.EXCEPTION_NO_SUCH_INSTANCE: return "noSuchInstance";
            case org.snmp4j.smi.SMIConstants.EXCEPTION_END_OF_MIB_VIEW: return "endOfMibView";
            default: return v.getSyntaxString();
        }
    }

    /** Degerin tip adi (TYPES'tan biri ya da istisna adi, orn. noSuchObject). */
    public static String typeOf(Variable v) {
        if (v instanceof Counter32) return "COUNTER32";
        if (v instanceof TimeTicks) return "TIMETICKS"; // Gauge32'den once (UnsignedInteger32 alt siniflari)
        if (v instanceof Gauge32) return "GAUGE32";
        if (v instanceof Counter64) return "COUNTER64";
        if (v instanceof Integer32) return "INTEGER";
        if (v instanceof IpAddress) return "IPADDRESS";
        if (v instanceof OctetString) return isText((OctetString) v) ? "OCTET_STRING" : "HEX_STRING";
        if (v instanceof OID) return "OID";
        if (v instanceof Null) return v.isException() ? exceptionName(v) : "NULL";
        return v.getSyntaxString();
    }

    /** Tablo/log gosterimi; OCTET_STRING duz metin, HEX_STRING bosluklu hex. */
    public static String format(Variable v) {
        if (v instanceof OctetString) {
            OctetString o = (OctetString) v;
            return isText(o) ? new String(o.getValue(), StandardCharsets.UTF_8) : Hex.toHex(o.getValue());
        }
        if (v instanceof TimeTicks) return Long.toString(((TimeTicks) v).getValue());
        if (v instanceof Null && v.isException()) return exceptionName(v);
        return v.toString();
    }

    static boolean isText(OctetString o) {
        byte[] b = o.getValue();
        String s = new String(b, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(s.getBytes(StandardCharsets.UTF_8), b)) return false; // gecersiz UTF-8
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\r' && c != '\n') return false;
        }
        return true;
    }
}
