package clinique.eval;

import clinique.common.Slots;
import java.util.List;
import java.util.Locale;

/**
 * Les formules de mesure (rapport, V3). Fonctions pures : aucune dependance a JADE, donc testables seules.
 * Le MonitorAgent leur passe la liste des issues de la campagne (une par patient).
 */
public final class Metrics {
    private Metrics() {}

    /** Issue finale d'un patient : un rendez-vous (creneau >= 0) ou un echec. */
    public static final class Issue {
        public final String patient;
        public final int poids;        // 1 = urgence normale, 3 = urgence haute
        public final int arrivee;
        public final int creneau;      // -1 si echec
        public final String raison;    // raison de l'echec, sinon vide
        public Issue(String patient, int poids, int arrivee, int creneau, String raison) {
            this.patient = patient; this.poids = poids; this.arrivee = arrivee; this.creneau = creneau; this.raison = raison;
        }
        public boolean servi() { return creneau >= 0; }
        public int attente() { return Slots.attente(arrivee, creneau); }
    }

    /** Attente moyenne des patients servis. poids = 1 ou 3 pour un niveau d'urgence, 0 pour tous. NaN s'il n'y en a pas. */
    public static double attenteMoyenne(List<Issue> issues, int poids) {
        int n = 0, somme = 0;
        for (Issue i : issues) {
            if (!i.servi() || (poids != 0 && i.poids != poids)) continue;
            n++; somme += i.attente();
        }
        return n == 0 ? Double.NaN : somme / (double) n;
    }

    /** Le critere du projet : somme(attente x poids) / somme(poids), sur les patients servis. */
    public static double attentePonderee(List<Issue> issues) {
        int num = 0, den = 0;
        for (Issue i : issues) {
            if (!i.servi()) continue;
            num += i.attente() * i.poids; den += i.poids;
        }
        return den == 0 ? Double.NaN : num / (double) den;
    }

    /** Nombre d'urgences hautes (servies ou non). */
    public static int urgences(List<Issue> issues) {
        int n = 0;
        for (Issue i : issues) if (i.poids == 3) n++;
        return n;
    }

    /** Urgences hautes servies en au plus "delai" creneaux. Un echec compte comme "pas servi a temps". */
    public static int urgencesATemps(List<Issue> issues, int delai) {
        int n = 0;
        for (Issue i : issues) if (i.poids == 3 && i.servi() && i.attente() <= delai) n++;
        return n;
    }

    public static int servis(List<Issue> issues) {
        int n = 0;
        for (Issue i : issues) if (i.servi()) n++;
        return n;
    }

    /** Part de la capacite utilisee (rendez-vous / creneaux ouverts). */
    public static double occupation(int rendezVous, int capacite) {
        return capacite == 0 ? Double.NaN : rendezVous / (double) capacite;
    }

    /** Nombre au format CSV : point decimal, 3 decimales, vide si NaN. */
    public static String fmt(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v);
    }
}
