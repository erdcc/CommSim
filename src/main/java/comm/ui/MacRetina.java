package comm.ui;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Java 8 + macOS Retina duzeltmesi.
 *
 * Java 8'in macOS kodu ekran olcegini okurken ekran kimligini NSNumber.pointerValue ile karsilastirir;
 * Apple Silicon'da (ve yeni macOS'ta) NSNumber "tagged pointer" oldugundan eslesme hic tutmaz ve olcek
 * hep 1 kalir. Uygulama yarim cozunurlukte cizilir, macOS buyuttugu icin yazilar bulanik gorunur.
 * Java 9 ve sonrasinda bu hata yoktur.
 *
 * Duzeltme: pencereler acilmadan once her ekranin gercek olcegi macOS'tan (NSScreen.backingScaleFactor)
 * okunur ve Java 8'in CGraphicsDevice.scale alanina yazilir. Ekran degisince (monitor takilip cikinca)
 * yeniden uygulanir. -Dcommsim.retinaScale=N ile olcek elle verilebilir, 0 kapatir.
 */
final class MacRetina {
    private MacRetina() { }

    private static boolean installed;

    /** Gerekliyse uygular; sonucu (ekran kimligi -&gt; olcek) dondurur, uygulanmadiysa bos. */
    static synchronized Map<Integer, Integer> install() {
        Map<Integer, Integer> applied = new HashMap<>();
        if (!Theme.legacyJava() || !isMac() || GraphicsEnvironment.isHeadless()) return applied;
        String force = System.getProperty("commsim.retinaScale");
        if ("0".equals(force)) return applied;
        apply(force, applied);
        if (!installed) {
            installed = true;
            listenForDisplayChanges(force);
        }
        return applied;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    private static void apply(String force, Map<Integer, Integer> applied) {
        Map<Integer, Integer> scales = new HashMap<>();
        int fallback = 1;
        if (force != null) {
            try {
                fallback = Math.max(1, Integer.parseInt(force.trim()));
            } catch (NumberFormatException e) {
                return;
            }
        } else {
            scales = nativeScales();
            if (scales.isEmpty()) return;
            for (int v : scales.values()) fallback = Math.max(fallback, v);
        }
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            try {
                Field id = d.getClass().getDeclaredField("displayID");
                Field scale = d.getClass().getDeclaredField("scale");
                id.setAccessible(true);
                scale.setAccessible(true);
                int displayId = id.getInt(d);
                Integer s = force != null ? Integer.valueOf(fallback) : scales.get(displayId);
                if (s == null) continue;
                if (scale.getInt(d) != s) scale.setInt(d, s);
                applied.put(displayId, s);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // farkli bir Java 8 surumu: duzeltme uygulanamaz, varsayilanla devam
            }
        }
    }

    /** Ekran degisince Java 8 olcegi yeniden 1 yapar; bizimki ondan sonra calisip duzeltir. */
    private static void listenForDisplayChanges(String force) {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Class<?> listener = Class.forName("sun.awt.DisplayChangedListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(MacRetina.class.getClassLoader(), new Class<?>[]{listener},
                    (p, m, args) -> {
                        if (m.getName().equals("displayChanged")) apply(force, new HashMap<>());
                        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                        if (m.getName().equals("equals")) return p == args[0];
                        return null;
                    });
            ge.getClass().getMethod("addDisplayChangedListener", listener).invoke(ge, proxy);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // dinleyici eklenemezse yalnizca acilistaki olcek gecerli
        }
    }

    /** NSScreen olcekleri (ekran numarasi -&gt; olcek); osascript ile, en fazla 3 sn. */
    static Map<Integer, Integer> nativeScales() {
        Map<Integer, Integer> out = new HashMap<>();
        String js = "ObjC.import('AppKit'); var r=[]; var s=$.NSScreen.screens;"
                + "for (var i=0;i<s.count;i++){var x=s.objectAtIndex(i);"
                + "r.push(ObjC.unwrap(x.deviceDescription.objectForKey('NSScreenNumber'))+':'+x.backingScaleFactor);} r.join(',')";
        try {
            Process p = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", js).redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
            if (line == null) return out;
            for (String part : line.trim().split(",")) {
                String[] kv = part.split(":");
                if (kv.length != 2) continue;
                try {
                    out.put(Integer.parseInt(kv[0].trim()), (int) Math.round(Double.parseDouble(kv[1].trim())));
                } catch (NumberFormatException ignored) {
                    // atla
                }
            }
        } catch (java.io.IOException e) {
            // osascript yok: duzeltme yapilmaz
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }
}
