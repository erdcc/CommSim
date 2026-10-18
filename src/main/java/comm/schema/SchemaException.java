package comm.schema;

/** Sema dosyasi hatasi; mesaj "dosya:satir: aciklama" bicimindedir. */
public final class SchemaException extends Exception {
    private static final long serialVersionUID = 1L;

    public SchemaException(String message) { super(message); }
}
