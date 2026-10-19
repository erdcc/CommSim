package comm.ui.editor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Senaryo dili komutlarinin ozeti: tamamlama, renklendirme ve ipuclari icin. */
final class DslInfo {
    /** Arguman anlami: tamamlama hangi listeyi onersin. */
    enum Arg { EP, MSG, FIELDS, MBOP, MBTABLE, SNMPOP, ONOFF, FUNC, FILE, TEXT }

    static final class Cmd {
        final String name;
        final String syntax;
        final String doc;
        final Arg[] args;
        /** Tamamlamada eklenecek sablon (TemplateCompletion bicimi). */
        final String template;

        Cmd(String name, String syntax, String doc, String template, Arg... args) {
            this.name = name;
            this.syntax = syntax;
            this.doc = doc;
            this.template = template;
            this.args = args;
        }

        Arg arg(int i) { return i < args.length ? args[i] : args.length > 0 && args[args.length - 1] == Arg.FIELDS ? Arg.FIELDS : null; }
    }

    static final Map<String, Cmd> COMMANDS = new LinkedHashMap<>();
    static final Set<String> BLOCK_WORDS = new HashSet<>(Arrays.asList(
            "repeat", "while", "if", "else", "end", "function", "call", "return", "break", "include", "var"));
    static final Set<String> OPENERS = new HashSet<>(Arrays.asList("repeat", "while", "if", "else", "function"));

    private static void add(String name, String syntax, String doc, String template, Arg... args) {
        COMMANDS.put(name, new Cmd(name, syntax, doc, template, args));
    }

    static {
        add("start", "start <bağlantı>", "Bağlantıyı başlatır ve çalışana dek bekler.", "start ${bağlantı}", Arg.EP);
        add("stop", "stop <bağlantı>", "Bağlantıyı durdurur.", "stop ${bağlantı}", Arg.EP);
        add("wait", "wait <ms>", "Verilen süre kadar bekler.", "wait ${500}");
        add("log", "log <metin>", "Çıktıya metin yazar; ${değişken} kullanılabilir.", "log ${metin}");
        add("send", "send <bağlantı> <MESAJ> [alan=değer ...]", "Değerleri atar ve şema mesajını gönderir.",
                "send ${bağlantı} ${MESAJ} ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("set", "set <bağlantı> <MESAJ> alan=değer ...", "Yalnızca gönderilecek değerleri değiştirir.",
                "set ${bağlantı} ${MESAJ} ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("hex", "hex <bağlantı> <hex ...>", "Ham byte gönderir: hex istemci A5 01 02", "hex ${bağlantı} ${A5 01}", Arg.EP);
        add("text", "text <bağlantı> <metin>", "Metin gönderir (\\r \\n \\xHH kaçışları).", "text ${bağlantı} ${OK\\r\\n}", Arg.EP);
        add("waitfor", "waitfor <bağlantı> <MESAJ> [ms]", "Mesaj gelene dek bekler (varsayılan 5000 ms).",
                "waitfor ${bağlantı} ${MESAJ} ${2000}", Arg.EP, Arg.MSG);
        add("expect", "expect <bağlantı> <MESAJ> alan=değer ...", "Son alınan değeri kontrol eder; sonuç sayılır.",
                "expect ${bağlantı} ${MESAJ} ${cursor}", Arg.EP, Arg.MSG, Arg.FIELDS);
        add("modbus", "modbus <master> read <fc> <adres> <adet> | write <adres> <değer,...> | coil <adres> <0|1>",
                "Modbus master isteği gönderir.", "modbus ${master} ${read} ${3} ${0} ${10}", Arg.EP, Arg.MBOP);
        add("mbset", "mbset <slave> coil|di|hr|ir <adres> <değer[,değer...]>", "Modbus slave tablosuna yazar.",
                "mbset ${slave} ${hr} ${0} ${1,2,3}", Arg.EP, Arg.MBTABLE);
        add("snmp", "snmp <manager> get|getnext|walk <oid ...> | set <oid> <TİP> <değer>", "SNMP isteği gönderir.",
                "snmp ${manager} ${get} ${1.3.6.1.2.1.1.5.0}", Arg.EP, Arg.SNMPOP);
        add("trap", "trap <agent> <trapOID> [oid ...]", "SNMP agent'tan trap gönderir.", "trap ${agent} ${1.3.6.1.4.1.99999.0.1}", Arg.EP);
        add("var", "var <ad> = <ifade>", "Değişken tanımlar: var n = counter(1,1). Sonra ${ad} ile kullanılır.", "var ${ad} = ${ifade}");
        add("assert", "assert <ifade> <op> <ifade>", "Kontrol (== != < > <= >=); sonuç sayılır.", "assert ${a} == ${b}");
        add("fail", "fail [mesaj]", "Senaryoyu başarısız sayıp bitirir.", "fail ${mesaj}");
        add("exit", "exit [mesaj]", "Senaryoyu başarıyla bitirir.", "exit ${mesaj}");
        add("continue_on_error", "continue_on_error on|off", "Açıkken hatalı komut atlanır ve başarısız sayılır.",
                "continue_on_error ${on}", Arg.ONOFF);
        add("repeat", "repeat <N> … end", "Bloğu N kez tekrarlar; N bir ${değişken} olabilir.", "repeat ${3}\n  ${cursor}\nend");
        add("while", "while <ifade> <op> <ifade> … end", "Koşul doğru oldukça tekrarlar.", "while ${koşul} < ${10}\n  ${cursor}\nend");
        add("if", "if <ifade> <op> <ifade> … [else …] end", "Koşullu blok.", "if ${koşul} == ${değer}\n  ${cursor}\nend");
        add("else", "else", "if bloğunun diğer kolu.", "else");
        add("end", "end", "repeat / while / if / function bloğunu kapatır.", "end");
        add("break", "break", "En içteki repeat ya da while döngüsünden çıkar.", "break");
        add("function", "function <ad> [param ...] … end", "Komut grubu tanımlar (en dış seviyede).",
                "function ${ad} ${param}\n  ${cursor}\nend");
        add("call", "call <ad> [arg ...]", "Fonksiyonu çağırır; argümanlar ${param} değişkenlerine yazılır.", "call ${ad}", Arg.FUNC);
        add("return", "return", "Fonksiyondan çıkar.", "return");
        add("include", "include <dosya.scn>", "Başka bir senaryo dosyasını buraya ekler.", "include ${ortak.scn}", Arg.FILE);
    }

    static boolean isOperator(String w) {
        switch (w) {
            case "==": case "!=": case "<": case ">": case "<=": case ">=": case "=":
            case "+": case "-": case "*": case "/": case "%":
                return true;
            default:
                return false;
        }
    }

    /** Ifadelerde kullanilabilen islevler. */
    static final String[][] FUNCTIONS = {
            {"counter", "counter(başlangıç, adım)", "Her değerlendirmede artan sayaç"},
            {"random", "random(min, max)", "Rastgele tam sayı"},
            {"randomf", "randomf(min, max)", "Rastgele ondalık sayı"},
            {"sine", "sine(min, max, periyot_ms)", "Sinüs dalgası"},
            {"ramp", "ramp(min, max, periyot_ms)", "Testere dişi"},
            {"square", "square(min, max, periyot_ms)", "Kare dalga"},
            {"list", "list(A|B|C)", "Sırayla değerler"},
            {"pick", "pick(A|B|C)", "Rastgele değer"},
            {"toggle", "toggle()", "true / false sırayla"},
            {"now", "now()", "Şu anki zaman (ms)"},
            {"last", "last(bağlantı, MESAJ, alan)", "Mesajın son alınan alan değeri"},
    };

    private DslInfo() { }
}
