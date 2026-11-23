package clinique.common;

/** Journal console : [temps ecoule][agent] evenement. Synchronise pour que les lignes ne se melangent pas. */
public final class Log {
    private Log() {}
    private static final long T0 = System.currentTimeMillis();

    /** Mis a false pour les campagnes de mesure (des centaines de lignes inutiles) ; option --verbeux pour les reactiver. */
    public static volatile boolean actif = true;

    public static synchronized void log(String agent, String evenement) {
        if (!actif) return;
        affiche(agent, evenement);
    }

    /** Affiche meme quand le journal detaille est coupe (resumes de campagne). */
    public static synchronized void toujours(String agent, String evenement) {
        affiche(agent, evenement);
    }

    private static void affiche(String agent, String evenement) {
        long ms = System.currentTimeMillis() - T0;
        System.out.printf("[%5.2fs][%-12s] %s%n", ms / 1000.0, agent, evenement);
    }
}
