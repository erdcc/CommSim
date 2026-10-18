package comm.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * Basit / Gelismis gorunum. Basit modda ileri ozellikler (kurallar, ureteçler, canli degerler,
 * senaryo, tekrar oynatma, hata enjeksiyonu, sema gecersiz kilma) gizlenir; ayarlar korunur ve calismaya devam eder.
 */
final class UiMode {
    private static final Preferences PREFS = Preferences.userNodeForPackage(UiMode.class);
    private static final String KEY = "uiMode";
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean advanced = "ADVANCED".equals(PREFS.get(KEY, "SIMPLE"));

    private UiMode() { }

    static boolean advanced() { return advanced; }

    static void set(boolean adv) {
        if (adv == advanced) return;
        advanced = adv;
        PREFS.put(KEY, adv ? "ADVANCED" : "SIMPLE");
        for (Runnable r : LISTENERS) r.run();
    }

    /** Mod degisince cagrilir (EDT). */
    static void onChange(Runnable r) { LISTENERS.add(r); }
}
