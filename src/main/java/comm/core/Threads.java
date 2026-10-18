package comm.core;

/** Kucuk thread yardimcilari. */
public final class Threads {
    private Threads() { }

    public static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Kesilirse false doner (kesme bayragi geri yuklenir). */
    public static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
