package comm.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tum baglantilarin trafik ve durum olaylarini toplayan dagitici.
 * Dinleyiciler G/C thread'lerinden cagrilir; hizli donmeli, Swing'e kendileri aktarmali.
 */
public final class TrafficBus {
    public interface Listener {
        void onEntry(LogEntry entry);

        /** Durum ya da es listesi degisti. */
        void onStateChanged(Endpoint endpoint);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    void publish(LogEntry e) {
        for (Listener l : listeners) l.onEntry(e);
    }

    void stateChanged(Endpoint ep) {
        for (Listener l : listeners) l.onStateChanged(ep);
    }
}
