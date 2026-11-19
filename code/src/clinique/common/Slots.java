package clinique.common;

/** D8 : le temps est un numero de creneau (0 a NB-1). Pas d'horloge globale. */
public final class Slots {
    private Slots() {}
    public static final int NB = 20;

    /** Attente d'un patient = creneau attribue - creneau d'arrivee (jamais negative). */
    public static int attente(int arrivee, int creneau) {
        return Math.max(0, creneau - arrivee);
    }
}
