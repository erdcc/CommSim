package comm.core;

/**
 * Test amacli hata enjeksiyonu: gonderilen cercevelere olasiliklarla bozulma uygular.
 * Yuzdeler 0..100; her gonderimde bagimsiz olarak denenir.
 */
public final class FaultConfig {
    public volatile boolean enabled;
    /** Cerceveyi hic gondermeme. */
    public volatile int dropPct;
    /** Semadaki checksum alanini bozma. */
    public volatile int checksumPct;
    /** Semadaki uzunluk alanini 1 kaydirma. */
    public volatile int lengthPct;
    /** Rastgele bir byte'i degistirme. */
    public volatile int flipPct;
    /** Sondan 1..yarisi kadar byte kesme. */
    public volatile int truncatePct;
    /** Iki kez gonderme. */
    public volatile int duplicatePct;
    /** Sabit gecikme ve uzerine rastgele 0..jitter ms. */
    public volatile int delayMs;
    public volatile int jitterMs;

    public boolean active() {
        return enabled && (dropPct > 0 || checksumPct > 0 || lengthPct > 0 || flipPct > 0 || truncatePct > 0
                || duplicatePct > 0 || delayMs > 0 || jitterMs > 0);
    }

    public FaultConfig copy() {
        FaultConfig f = new FaultConfig();
        f.enabled = enabled;
        f.dropPct = dropPct;
        f.checksumPct = checksumPct;
        f.lengthPct = lengthPct;
        f.flipPct = flipPct;
        f.truncatePct = truncatePct;
        f.duplicatePct = duplicatePct;
        f.delayMs = delayMs;
        f.jitterMs = jitterMs;
        return f;
    }

    public String describe() {
        if (!active()) return "";
        StringBuilder sb = new StringBuilder("⚡");
        if (dropPct > 0) sb.append(" düşür %").append(dropPct);
        if (checksumPct > 0) sb.append(" checksum %").append(checksumPct);
        if (lengthPct > 0) sb.append(" uzunluk %").append(lengthPct);
        if (flipPct > 0) sb.append(" byte %").append(flipPct);
        if (truncatePct > 0) sb.append(" kes %").append(truncatePct);
        if (duplicatePct > 0) sb.append(" çift %").append(duplicatePct);
        if (delayMs > 0 || jitterMs > 0) sb.append(" gecikme ").append(delayMs).append(jitterMs > 0 ? "±" + jitterMs : "").append(" ms");
        return sb.toString();
    }
}
