package comm.core;

/** Akis tabanli (TCP) baglantilarda yukun nasil cercevelenecegi. */
public final class FramingConfig {
    public enum Type {
        RAW("Ham (okunduğu gibi)"),
        LENGTH_PREFIX("Uzunluk önekli"),
        DELIMITER("Ayraçlı"),
        FIXED("Sabit uzunluk");

        public final String label;

        Type(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    public Type type = Type.RAW;
    /** Uzunluk alani boyu: 1, 2 ya da 4 byte. */
    public int lengthSize = 4;
    public boolean bigEndian = true;
    /** Uzunluk degeri, uzunluk alaninin kendisini de sayiyor mu. */
    public boolean lengthIncludesHeader = false;
    /** Ayrac byte'lari (hex), orn. "0A" ya da "0D 0A". */
    public String delimiterHex = "0A";
    public int fixedLength = 16;
    /** Tek cercevenin izin verilen en buyuk boyu. */
    public int maxFrame = 1 << 20;

    public FramingConfig copy() {
        FramingConfig c = new FramingConfig();
        c.type = type;
        c.lengthSize = lengthSize;
        c.bigEndian = bigEndian;
        c.lengthIncludesHeader = lengthIncludesHeader;
        c.delimiterHex = delimiterHex;
        c.fixedLength = fixedLength;
        c.maxFrame = maxFrame;
        return c;
    }

    public String describe() {
        switch (type) {
            case LENGTH_PREFIX:
                return "uzunluk " + lengthSize + "B " + (bigEndian ? "BE" : "LE")
                        + (lengthIncludesHeader ? " (başlık dahil)" : "");
            case DELIMITER:
                return "ayraç " + delimiterHex;
            case FIXED:
                return "sabit " + fixedLength + "B";
            default:
                return "ham";
        }
    }
}
