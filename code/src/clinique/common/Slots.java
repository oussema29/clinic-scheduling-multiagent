package clinique.common;

/** D8 : le temps est un numero de creneau (0 a NB-1). Pas d'horloge globale. */
public final class Slots {
    private Slots() {}
    public static final int NB = 20;

    /**
     * V1 : une urgence haute doit etre vue au plus tard DELAI_URGENCE creneaux apres son arrivee.
     * Au-dela, un creneau libre est juge "trop tard" et la preemption devient possible.
     */
    public static final int DELAI_URGENCE = 1;

    /** Attente d'un patient = creneau attribue - creneau d'arrivee (jamais negative). */
    public static int attente(int arrivee, int creneau) {
        return Math.max(0, creneau - arrivee);
    }
}
