package clinique.eval;

/** Etat partage d'une campagne de mesure (une seule plateforme dans le processus). */
public final class Campagne {
    private Campagne() {}

    /** Passe a true quand le Monitor a ecrit ses resultats : Main peut alors arreter la plateforme. */
    public static volatile boolean termine = false;
}
