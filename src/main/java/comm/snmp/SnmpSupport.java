package comm.snmp;

import comm.core.EndpointConfig;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

/** Agent ve manager icin ortak SNMP4J kurulumu (v1, v2c, v3/USM). */
public final class SnmpSupport {
    public static final String[] VERSIONS = {"V1", "V2C", "V3"};
    public static final String[] AUTH = {"NONE", "MD5", "SHA", "SHA256", "SHA512"};
    public static final String[] PRIV = {"NONE", "DES", "3DES", "AES128", "AES192", "AES256"};
    public static final OID SYS_UPTIME = SnmpConstants.sysUpTime;
    public static final OID SNMP_TRAP_OID = SnmpConstants.snmpTrapOID;

    private static boolean protocolsReady;

    private SnmpSupport() { }

    static synchronized void initProtocols() {
        if (protocolsReady) return;
        SecurityProtocols p = SecurityProtocols.getInstance();
        p.addDefaultProtocols();
        p.addAuthenticationProtocol(new AuthHMAC192SHA256());
        p.addAuthenticationProtocol(new AuthHMAC384SHA512());
        p.addPrivacyProtocol(new Priv3DES());
        protocolsReady = true;
    }

    static OID authOid(String name) {
        switch (name) {
            case "MD5": return AuthMD5.ID;
            case "SHA": return AuthSHA.ID;
            case "SHA256": return AuthHMAC192SHA256.ID;
            case "SHA512": return AuthHMAC384SHA512.ID;
            default: return null;
        }
    }

    static OID privOid(String name) {
        switch (name) {
            case "DES": return PrivDES.ID;
            case "3DES": return Priv3DES.ID;
            case "AES128": return PrivAES128.ID;
            case "AES192": return PrivAES192.ID;
            case "AES256": return PrivAES256.ID;
            default: return null;
        }
    }

    public static int version(EndpointConfig c) {
        switch (c.snmpVersion) {
            case "V1": return SnmpConstants.version1;
            case "V3": return SnmpConstants.version3;
            default: return SnmpConstants.version2c;
        }
    }

    static int securityLevel(EndpointConfig c) {
        if (authOid(c.snmpAuthProtocol) == null) return SecurityLevel.NOAUTH_NOPRIV;
        if (privOid(c.snmpPrivProtocol) == null) return SecurityLevel.AUTH_NOPRIV;
        return SecurityLevel.AUTH_PRIV;
    }

    /** v3 ayarlarini dogrular; sorun yoksa null. */
    public static String validateV3(EndpointConfig c) {
        if (c.snmpUser.trim().isEmpty()) return "v3 için kullanıcı adı gerekli.";
        OID auth = authOid(c.snmpAuthProtocol);
        OID priv = privOid(c.snmpPrivProtocol);
        if (auth == null && priv != null) return "Şifreleme (priv) için kimlik doğrulama (auth) da seçilmeli.";
        if (auth != null && c.snmpAuthPassword.length() < 8) return "Auth parolası en az 8 karakter olmalı.";
        if (priv != null && c.snmpPrivPassword.length() < 8) return "Priv parolası en az 8 karakter olmalı.";
        return null;
    }

    /** Verilen tasima uzerinde v1/v2c/v3 destekli bir Snmp olusturur; v3 kullanicisi tanimliysa ekler. */
    static Snmp create(LoggingUdpTransport transport, byte[] engineId, EndpointConfig c) {
        initProtocols();
        USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(engineId), 0);
        MessageDispatcherImpl d = new MessageDispatcherImpl();
        d.addMessageProcessingModel(new MPv1());
        d.addMessageProcessingModel(new MPv2c());
        d.addMessageProcessingModel(new MPv3(usm));
        Snmp snmp = new Snmp(d, transport);
        if (!c.snmpUser.trim().isEmpty()) {
            OID auth = authOid(c.snmpAuthProtocol);
            OID priv = auth == null ? null : privOid(c.snmpPrivProtocol);
            usm.addUser(new OctetString(c.snmpUser.trim()), new UsmUser(new OctetString(c.snmpUser.trim()),
                    auth, auth == null ? null : new OctetString(c.snmpAuthPassword),
                    priv, priv == null ? null : new OctetString(c.snmpPrivPassword)));
        }
        return snmp;
    }

    static byte[] engineId(String seed) {
        return MPv3.createLocalEngineID(new OctetString(seed));
    }
}
