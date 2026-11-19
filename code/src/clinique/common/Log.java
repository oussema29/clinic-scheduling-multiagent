package clinique.common;

/** Journal console : [temps ecoule][agent] evenement. Synchronise pour que les lignes ne se melangent pas. */
public final class Log {
    private Log() {}
    private static final long T0 = System.currentTimeMillis();

    public static synchronized void log(String agent, String evenement) {
        long ms = System.currentTimeMillis() - T0;
        System.out.printf("[%5.2fs][%-12s] %s%n", ms / 1000.0, agent, evenement);
    }
}
