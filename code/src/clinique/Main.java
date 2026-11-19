package clinique;

import clinique.agents.ScenarioAgent;
import clinique.common.Log;
import clinique.common.Msg;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * D5 : lance la plateforme JADE et tous les agents dans un seul processus.
 *
 *   java -cp "lib/jade.jar;out" clinique.Main                      journee type (5 patients)
 *   java -cp "lib/jade.jar;out" clinique.Main aleatoire 20 42      20 demandes aleatoires, graine 42
 *   option --gui : ouvre l'interface RMA de JADE
 */
public class Main {
    static AgentContainer c;

    static void agent(String nom, Class<?> classe, Object... args) throws Exception {
        c.createNewAgent(nom, classe.getName(), args).start();
    }

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        List<String> a = new ArrayList<>();
        for (String s : args) { if (s.equals("--gui")) gui = true; else a.add(s); }

        // JADE est bavard en INFO : on ne garde que les avertissements.
        Logger.getLogger("").setLevel(Level.WARNING);
        for (Handler h : Logger.getLogger("").getHandlers()) h.setLevel(Level.WARNING);

        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, String.valueOf(gui));
        c = Runtime.instance().createMainContainer(p);

        boolean aleatoire = !a.isEmpty() && a.get(0).equals("aleatoire");
        long duree;
        if (aleatoire) {
            int n = a.size() > 1 ? Integer.parseInt(a.get(1)) : 20;
            long graine = a.size() > 2 ? Long.parseLong(a.get(2)) : 42;
            duree = aleatoire(n, graine);
        } else {
            duree = journeeType();
        }

        Thread.sleep(duree);
        c.kill();
        System.exit(0);
    }

    /** Infrastructure commune : coordinateur, triage, salles. */
    static void infrastructure() throws Exception {
        agent("coordinateur", clinique.agents.CoordinateurAgent.class);
        agent("triage", clinique.agents.TriageAgent.class);
        agent("salle1", clinique.agents.SalleAgent.class);
        agent("salle2", clinique.agents.SalleAgent.class);
    }

    static void patient(String nom, String texte, int arrivee, long delaiMs) throws Exception {
        agent(nom, clinique.agents.PatientAgent.class, texte, arrivee, delaiMs);
    }

    /** La journee type du rapport : 5 patients, une urgence, un medecin qui s'absente, un bilan. */
    static long journeeType() throws Exception {
        Log.log("main", "=== Journee type (V0) ===");
        infrastructure();
        // Planning des cardiologues volontairement petit : 3 creneaux chacun.
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        Thread.sleep(1000);   // laisse les agents s'inscrire au DF avant les patients

        patient("P1", "J'ai des palpitations depuis hier", 0, 0);
        patient("P2", "Ma tension est trop elevee", 0, 0);
        patient("P3", "Douleur dans la poitrine et difficulte a respirer", 0, 300);
        patient("P4", "Forte fievre et toux depuis trois jours", 1, 0);
        patient("P5", "Fatigue et mal de gorge", 1, 0);

        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        etapes.add(new ScenarioAgent.Etape(5000, "dr_martin", null, Msg.build("action", "devenir_absent")));
        etapes.add(new ScenarioAgent.Etape(9000, "coordination", Msg.ONTO_BILAN, "bilan"));
        agent("scenario", ScenarioAgent.class, etapes);
        return 11000;
    }

    /** N demandes aleatoires : sert au test d'acceptation (aucune double attribution). */
    static long aleatoire(int n, long graine) throws Exception {
        Log.log("main", "=== " + n + " demandes aleatoires, graine " + graine + " ===");
        infrastructure();
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 8);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 8);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 12);
        Thread.sleep(1000);

        String[] textes = {
            "Palpitations et tension elevee", "Douleur dans la poitrine", "Forte fievre et toux",
            "Fatigue et mal de gorge", "Eruption sur la peau", "Perte de connaissance ce matin",
            "Mal de tete et rhume", "Essoufflement a l'effort"
        };
        Random r = new Random(graine);
        for (int i = 1; i <= n; i++) {
            patient("P" + i, textes[r.nextInt(textes.length)], r.nextInt(4), r.nextInt(1500));
        }
        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        etapes.add(new ScenarioAgent.Etape(4000 + 100L * n, "dr_martin", null, Msg.build("action", "devenir_absent")));
        etapes.add(new ScenarioAgent.Etape(8000 + 200L * n, "coordination", Msg.ONTO_BILAN, "bilan"));
        agent("scenario", ScenarioAgent.class, etapes);
        return 10000 + 200L * n;
    }
}
