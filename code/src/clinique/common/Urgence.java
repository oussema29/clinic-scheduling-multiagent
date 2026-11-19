package clinique.common;

/** Niveau d'urgence et son poids dans l'objectif (normale = 1, haute = 3). */
public enum Urgence {
    NORMALE(1), HAUTE(3);

    public final int poids;
    Urgence(int poids) { this.poids = poids; }

    public String code() { return name().toLowerCase(); }

    public static Urgence de(String s) {
        return "haute".equalsIgnoreCase(s) ? HAUTE : NORMALE;
    }
}
