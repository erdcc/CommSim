package comm.core;

/** Kayit yonu: alinan, gonderilen ya da baglanti olayi. */
public enum Direction {
    RX("←"), TX("→"), INFO("•"), ERROR("!");

    public final String symbol;

    Direction(String symbol) { this.symbol = symbol; }
}
