package comm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bir sema mesajinin adlandirilmis deger kumesi ("hazir mesaj"); tek tikla forma yuklenir ya da gonderilir. */
public final class MessagePreset {
    public String name = "";
    /** Sema mesaj adi. */
    public String message = "";
    /** Govde alanlari: yol -&gt; metin (Values.toTexts bicimi). */
    public Map<String, String> values = new LinkedHashMap<>();

    public MessagePreset copy() {
        MessagePreset p = new MessagePreset();
        p.name = name;
        p.message = message;
        p.values = new LinkedHashMap<>(values);
        return p;
    }

    @Override public String toString() { return name; }
}
