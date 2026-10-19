package comm.ui.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Groovy senaryo API'sinin ozeti (comm.scenario.SimScript ile ayni). */
final class GroovyInfo {
    enum Arg { EP, MSG, FIELDS, MBTABLE, OIDTYPE, OTHER }

    static final class Method {
        final String name;
        final String signature;
        final String doc;
        final String template;
        final Arg[] args;

        Method(String name, String signature, String doc, String template, Arg... args) {
            this.name = name;
            this.signature = signature;
            this.doc = doc;
            this.template = template;
            this.args = args;
        }

        Arg arg(int i) {
            if (i < args.length) return args[i];
            return args.length > 0 && args[args.length - 1] == Arg.FIELDS ? Arg.FIELDS : null;
        }
    }

    static final Map<String, Method> METHODS = new LinkedHashMap<>();

    private static void add(String name, String sig, String doc, String template, Arg... args) {
        METHODS.put(name, new Method(name, sig, doc, template, args));
    }

    static {
        add("start", "start(bağlantı)", "Bağlantıyı başlatır ve çalışana dek bekler.", "start \"${bağlantı}\"", Arg.EP);
        add("stop", "stop(bağlantı)", "Bağlantıyı durdurur.", "stop \"${bağlantı}\"", Arg.EP);
        add("sleep", "sleep(ms)", "Bekler; Durdur düğmesine hemen yanıt verir.", "sleep ${500}");
        add("log", "log(metin)", "Senaryo çıktısına yazar.", "log \"${metin}\"");
        add("send", "send(bağlantı, MESAJ, alan: değer, …)", "Değerleri atar ve şema mesajını gönderir.",
                "send \"${bağlantı}\", \"${MESAJ}\", ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("set", "set(bağlantı, MESAJ, alan: değer, …)", "Yalnızca gönderilecek değerleri değiştirir.",
                "set \"${bağlantı}\", \"${MESAJ}\", ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("sendHex", "sendHex(bağlantı, hex)", "Ham byte gönderir: sendHex \"istemci\", \"A5 01 02\"",
                "sendHex \"${bağlantı}\", \"${A5 01}\"", Arg.EP);
        add("sendText", "sendText(bağlantı, metin)", "Metin gönderir (\\r \\n \\xHH kaçışları).",
                "sendText \"${bağlantı}\", \"${metin}\"", Arg.EP);
        add("sendBytes", "sendBytes(bağlantı, byte[])", "Byte dizisi gönderir.", "sendBytes \"${bağlantı}\", ${veri}", Arg.EP);
        add("waitFor", "Map waitFor(bağlantı, MESAJ, ms = 5000)",
                "Mesaj gelene dek bekler ve alanlarını döndürür: def m = waitFor(...); m.speed",
                "waitFor(\"${bağlantı}\", \"${MESAJ}\", ${2000})", Arg.EP, Arg.MSG);
        add("last", "Map last(bağlantı, MESAJ)", "Mesajın son alınan değerleri; hiç alınmadıysa null.",
                "last(\"${bağlantı}\", \"${MESAJ}\")", Arg.EP, Arg.MSG);
        add("expect", "boolean expect(bağlantı, MESAJ, alan: değer, …)", "Son alınan değerleri kontrol eder; her alan sayılır.",
                "expect \"${bağlantı}\", \"${MESAJ}\", ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("check", "boolean check(koşul, açıklama)", "Genel kontrol; sonuç sayılır ve loga yazılır.",
                "check ${koşul}, \"${açıklama}\"");
        add("fail", "fail(mesaj)", "Senaryoyu başarısız sayıp bitirir.", "fail \"${mesaj}\"");
        add("exit", "exit(mesaj)", "Senaryoyu başarıyla bitirir.", "exit \"${mesaj}\"");
        add("modbusRead", "modbusRead(master, fc, adres, adet)", "Modbus okuma isteği (fc 1-4).",
                "modbusRead \"${master}\", ${3}, ${0}, ${10}", Arg.EP);
        add("modbusWrite", "modbusWrite(master, adres, [değerler])", "Holding register yazar (tek değer ya da liste).",
                "modbusWrite \"${master}\", ${0}, [${1}]", Arg.EP);
        add("modbusCoil", "modbusCoil(master, adres, açık)", "Tek coil yazar.", "modbusCoil \"${master}\", ${0}, ${true}", Arg.EP);
        add("mbset", "mbset(slave, tablo, adres, [değerler])", "Slave tablosuna yazar; tablo: coil | di | hr | ir.",
                "mbset \"${slave}\", \"${hr}\", ${0}, [${1}]", Arg.EP, Arg.MBTABLE);
        add("snmpGet", "snmpGet(manager, oid, …)", "SNMP GET isteği.", "snmpGet \"${manager}\", \"${1.3.6.1.2.1.1.5.0}\"", Arg.EP);
        add("snmpWalk", "snmpWalk(manager, oid)", "SNMP WALK.", "snmpWalk \"${manager}\", \"${1.3.6.1.2.1.1}\"", Arg.EP);
        add("snmpSet", "snmpSet(manager, oid, TİP, değer)", "SNMP SET; TİP: INTEGER, OCTET_STRING, …",
                "snmpSet \"${manager}\", \"${oid}\", \"${INTEGER}\", ${değer}", Arg.EP, Arg.OTHER, Arg.OIDTYPE);
        add("trap", "trap(agent, trapOid, oid, …)", "SNMP agent'tan trap gönderir.", "trap \"${agent}\", \"${1.3.6.1.4.1.99999.0.1}\"", Arg.EP);
        add("dsl", "dsl(metin)", "Senaryo dilinde satırlar çalıştırır; değişkenler ortaktır.", "dsl '''\n${cursor}\n'''");
        add("endpoint", "Endpoint endpoint(bağlantı)", "Alt düzey bağlantı nesnesi.", "endpoint(\"${bağlantı}\")", Arg.EP);
    }

    /** Groovy anahtar sozcukleri ve hazir kaliplar: ad, sablon, aciklama. */
    static final String[][] TEMPLATES = {
            {"for", "for (${x} in ${liste}) {\n    ${cursor}\n}", "for döngüsü"},
            {"fori", "for (int ${i} = 0; ${i} < ${10}; ${i}++) {\n    ${cursor}\n}", "sayaçlı for döngüsü"},
            {"each", "${liste}.each { ${it} ->\n    ${cursor}\n}", "her eleman için"},
            {"eachWithIndex", "${liste}.eachWithIndex { ${v}, ${i} ->\n    ${cursor}\n}", "sıra numarasıyla her eleman"},
            {"if", "if (${koşul}) {\n    ${cursor}\n}", "koşul"},
            {"ife", "if (${koşul}) {\n    ${cursor}\n} else {\n    \n}", "koşul / değilse"},
            {"while", "while (${koşul}) {\n    ${cursor}\n}", "döngü"},
            {"def", "def ${ad} = ${cursor}", "değişken"},
            {"try", "try {\n    ${cursor}\n} catch (Exception e) {\n    log \"Hata: \" + e.message\n}", "hata yakalama"},
            {"repeat", "${3}.times { ${i} ->\n    ${cursor}\n}", "N kez tekrar"},
    };

    static final String[] KEYWORDS = {
            "def", "if", "else", "for", "in", "while", "return", "break", "continue", "true", "false", "null",
            "try", "catch", "finally", "throw", "new", "as", "instanceof", "switch", "case", "default",
    };

    static final String[] OBJECTS = {"sim", "vars"};

    private GroovyInfo() { }
}
