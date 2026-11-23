package clinique.common;

import jade.lang.acl.ACLMessage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contrat de messages (rapport, section B3).
 * Le contenu d'un message est un texte "cle=valeur;cle=valeur".
 * Msg.build(...) le fabrique, Msg.parse(...) le relit.
 */
public final class Msg {
    private Msg() {}

    // Ontologies : elles servent a filtrer ce que chaque agent lit (piege 1 du rapport).
    public static final String ONTO_TRIAGE  = "clinique-triage";   // M1 / M2
    public static final String ONTO_DEMANDE = "clinique-demande";  // M3
    public static final String ONTO_ABSENCE = "clinique-absence";  // M7
    public static final String ONTO_BILAN   = "clinique-bilan";    // commande du scenario
    public static final String ONTO_EVENT   = "clinique-evenement"; // M9 : coordinateur -> Monitor (V3)
    public static final String ONTO_ETAT    = "clinique-etat";     // V3 : "le coordinateur est-il au repos ?"
    public static final String ONTO_FIN     = "clinique-fin";      // V3 : le ScenarioRunner demande au Monitor de conclure

    // Types de service enregistres au DF
    public static final String SVC_MEDECIN = "medecin";
    public static final String SVC_SALLE   = "salle";
    public static final String SVC_TRIAGE  = "triage";
    public static final String SVC_COORD   = "coordination";
    public static final String SVC_MONITOR = "monitor";

    /** build("a","1","b","2") donne "a=1;b=2". */
    public static String build(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append(';');
            sb.append(kv[i]).append('=').append(clean(kv[i + 1]));
        }
        return sb.toString();
    }

    /** "a=1;b=2" donne {a:1, b:2}. */
    public static Map<String, String> parse(String content) {
        Map<String, String> m = new LinkedHashMap<>();
        if (content == null) return m;
        for (String part : content.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return m;
    }

    public static Map<String, String> parse(ACLMessage msg) { return parse(msg.getContent()); }

    public static int getInt(Map<String, String> m, String key, int def) {
        try { return Integer.parseInt(m.get(key)); } catch (Exception e) { return def; }
    }

    // Les caracteres ';' et '=' sont des separateurs : on les retire des valeurs libres.
    private static String clean(String v) {
        return v == null ? "" : v.replace(';', ',').replace('=', ' ');
    }
}
