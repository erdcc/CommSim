package comm.runtime;

import comm.core.Endpoint;

import java.util.Map;

/** Kaydi zamanlamasini koruyarak (hiz carpaniyla) eslenen baglantilardan yeniden gonderir. */
public final class Replayer {
    public interface Listener {
        void progress(int sent, int total, long elapsedMs, int errors);

        void finished(String summary);
    }

    private volatile boolean stop;
    private volatile Thread thread;

    public boolean isRunning() { return thread != null; }

    public void stop() {
        stop = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /**
     * @param mapping   "baglanti|YON" grubu -&gt; gonderecek baglanti (eslenmeyen gruplar atlanir)
     * @param speed     hiz carpani (2 = iki kat hizli); 0 ya da negatif = beklemeden
     * @param keepPeer  true ise kayittaki ese gonderilir (sunucu/UDP), degilse tum eslere / varsayilan hedefe
     */
    public synchronized void start(Recording rec, Map<String, Endpoint> mapping, double speed, boolean loop,
                                   boolean keepPeer, Listener listener) {
        if (thread != null) throw new IllegalStateException("tekrar oynatma zaten çalışıyor");
        stop = false;
        Thread t = new Thread(() -> run(rec, mapping, speed, loop, keepPeer, listener), "replay");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void run(Recording rec, Map<String, Endpoint> mapping, double speed, boolean loop, boolean keepPeer, Listener l) {
        int sent = 0;
        int errors = 0;
        int rounds = 0;
        long t0 = System.currentTimeMillis();
        int total = 0;
        for (Recording.Entry e : rec.entries) if (mapping.containsKey(e.endpoint + "|" + e.dir)) total++;
        String reason = "bitti";
        try {
            outer:
            do {
                rounds++;
                long roundStart = System.currentTimeMillis();
                for (Recording.Entry e : rec.entries) {
                    if (stop) {
                        reason = "durduruldu";
                        break outer;
                    }
                    Endpoint ep = mapping.get(e.endpoint + "|" + e.dir);
                    if (ep == null) continue;
                    if (speed > 0) {
                        long due = roundStart + (long) (e.t / speed);
                        long wait;
                        while ((wait = due - System.currentTimeMillis()) > 0) {
                            if (stop) {
                                reason = "durduruldu";
                                break outer;
                            }
                            Thread.sleep(Math.min(wait, 50));
                        }
                    }
                    String peer = keepPeer && !ep.config().kind.isClient() && !e.peer.isEmpty() ? e.peer : null;
                    try {
                        ep.send(e.data, peer);
                        sent++;
                    } catch (Exception ex) {
                        errors++;
                    }
                    if (l != null) l.progress(sent, total, System.currentTimeMillis() - t0, errors);
                }
            } while (loop && !stop && total > 0);
        } catch (InterruptedException ex) {
            reason = "durduruldu";
        } finally {
            thread = null;
            if (l != null) {
                l.finished("Tekrar oynatma " + reason + ": " + sent + " çerçeve gönderildi" + (errors > 0 ? ", " + errors + " hata" : "")
                        + (rounds > 1 ? ", " + rounds + " tur" : ""));
            }
        }
    }

}
