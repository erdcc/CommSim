package comm.scenario;

/** Senaryo sozdizimi ya da calisma hatasi; mesaj "satir N: aciklama" bicimindedir. */
public final class ScenarioException extends Exception {
    private static final long serialVersionUID = 1L;

    public ScenarioException(String message) { super(message); }
}
