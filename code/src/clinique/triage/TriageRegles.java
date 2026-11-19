package clinique.triage;

import clinique.common.Urgence;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Triage par mots-cles (V0). Sera remplace par un LLM en V4, et restera le repli (fallback) ensuite. */
public final class TriageRegles {
    private TriageRegles() {}

    public static final class Resultat {
        public final String specialite;
        public final Urgence urgence;
        public final String justification;
        Resultat(String s, Urgence u, String j) { specialite = s; urgence = u; justification = j; }
    }

    private static final Map<String, String[]> MOTS = new LinkedHashMap<>();
    static {
        MOTS.put("cardiologie", new String[]{"coeur", "poitrine", "palpitation", "tension", "cardiaque", "essoufflement"});
        MOTS.put("dermatologie", new String[]{"peau", "eruption", "bouton", "demangeaison", "eczema"});
        MOTS.put("generaliste", new String[]{"fievre", "toux", "grippe", "fatigue", "gorge", "rhume", "mal de tete"});
    }
    private static final String[] ALERTES = {
        "perte de connaissance", "inconscient", "evanoui", "difficulte a respirer",
        "saignement abondant", "paralysie", "douleur dans la poitrine", "douleur a la poitrine", "oppression"
    };

    public static Resultat classer(String texte) {
        String t = normaliser(texte);

        String meilleure = "generaliste";
        int score = 0;
        String motTrouve = "aucun mot-cle (specialite par defaut)";
        for (Map.Entry<String, String[]> e : MOTS.entrySet()) {
            int n = 0;
            String premier = null;
            for (String mot : e.getValue()) if (t.contains(mot)) { n++; if (premier == null) premier = mot; }
            if (n > score) { score = n; meilleure = e.getKey(); motTrouve = "mot-cle " + premier; }
        }

        Urgence urgence = Urgence.NORMALE;
        String raison = motTrouve;
        for (String alerte : ALERTES) {
            if (t.contains(alerte)) {
                urgence = Urgence.HAUTE;
                raison += " ; alerte " + alerte;
                // une alerte thoracique est cardiologique meme si aucun autre mot-cle n'a ete trouve
                if (alerte.contains("poitrine") || alerte.equals("oppression")) meilleure = "cardiologie";
                break;
            }
        }
        return new Resultat(meilleure, urgence, raison);
    }

    static String normaliser(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase();
    }
}
