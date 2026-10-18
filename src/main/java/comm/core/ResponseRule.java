package comm.core;

/**
 * Otomatik yanit kurali: gelen cerceve eslesirse (istege bagli kosulla) gecikmeyle yanit gonderir.
 * Alanlar arayuzden degisebilir; kural motoru anlik degeri kullanir.
 */
public final class ResponseRule {
    public volatile boolean enabled = true;
    /** Sema mesaj adi, "*" (her cerceve) ya da "hex:01 02" (bu byte'lari iceren cerceve). */
    public volatile String when = "";
    /** Bos ya da "alan=deger; alan2>5" (= != > < >= <=). Yalnizca sema mesajlarinda. */
    public volatile String condition = "";
    public volatile int delayMs = 0;
    /** Sema mesaj adi, "hex:A5 01" ya da "text:OK\r\n"; bos ise yanit yok (yalnizca sayilir). */
    public volatile String reply = "";
    /** "alan=ifade; baslik.seq=$rx.başlık.seq" — ifadeler comm.runtime.Expr. */
    public volatile String assignments = "";

    public ResponseRule copy() {
        ResponseRule r = new ResponseRule();
        r.enabled = enabled;
        r.when = when;
        r.condition = condition;
        r.delayMs = delayMs;
        r.reply = reply;
        r.assignments = assignments;
        return r;
    }
}
