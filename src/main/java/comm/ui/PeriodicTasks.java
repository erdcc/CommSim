package comm.ui;

import comm.core.Endpoint;

import javax.swing.Timer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Baglanti basina periyodik gonderimler. Yuk her turda EDT'de uretilir (parametre degisiklikleri aninda
 * yansir), gonderim arka planda yapilir; onceki gonderim bitmediyse tur atlanir.
 */
final class PeriodicTasks {
    static final class Task {
        final Endpoint endpoint;
        final String label;
        final int intervalMs;
        final String peer;
        private final Supplier<byte[]> payload;
        private final AtomicBoolean busy = new AtomicBoolean();
        private Timer timer;
        volatile long count;
        volatile String lastError = "";

        Task(Endpoint endpoint, String label, int intervalMs, String peer, Supplier<byte[]> payload) {
            this.endpoint = endpoint;
            this.label = label;
            this.intervalMs = intervalMs;
            this.peer = peer;
            this.payload = payload;
        }

        String summary() {
            return label + " / " + intervalMs + " ms (" + count + ")" + (lastError.isEmpty() ? "" : " ⚠");
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "periodic-send");
        t.setDaemon(true);
        return t;
    });

    Task start(Endpoint ep, String label, int intervalMs, String peer, Supplier<byte[]> payload) {
        Task t = new Task(ep, label, intervalMs, peer, payload);
        t.timer = new Timer(intervalMs, e -> fire(t));
        t.timer.setInitialDelay(0);
        t.timer.start();
        tasks.add(t);
        return t;
    }

    private void fire(Task t) {
        if (t.endpoint.state() != Endpoint.State.RUNNING || !t.busy.compareAndSet(false, true)) return;
        byte[] data;
        try {
            data = t.payload.get();
        } catch (RuntimeException e) {
            t.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            t.busy.set(false);
            return;
        }
        sender.execute(() -> {
            try {
                t.endpoint.send(data, t.peer);
                t.count++;
                t.lastError = "";
            } catch (IOException | RuntimeException e) {
                t.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                t.busy.set(false);
            }
        });
    }

    List<Task> of(Endpoint ep) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks) if (t.endpoint == ep) out.add(t);
        return out;
    }

    void stop(Endpoint ep) {
        for (Task t : of(ep)) {
            t.timer.stop();
            tasks.remove(t);
        }
    }

    void stopAll() {
        for (Task t : tasks) t.timer.stop();
        tasks.clear();
    }
}
