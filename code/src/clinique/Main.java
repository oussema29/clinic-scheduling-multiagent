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
 *   java -cp "lib/jade.jar;out" clinique.Main aleatoire 20 42 [5] 20 demandes aleatoires, graine 42, [capacite des cardiologues]
 *   java -cp "lib/jade.jar;out" clinique.Main urgence              V1 : urgence sur planning plein, le patient deplace accepte
 *   java -cp "lib/jade.jar;out" clinique.Main urgence refus        V1 : variante, le patient deplace refuse la compensation
 *   java -cp "lib/jade.jar;out" clinique.Main sature               V1 : urgence sans victime possible (planning sature)
 *   java -cp "lib/jade.jar;out" clinique.Main absence-comp       V1 (test) : un medecin s'absente pendant une compensation
 *   java -cp "lib/jade.jar;out" clinique.Main absence            V2 : un medecin s'absente (urgent replanifie d'abord, capacite insuffisante), puis revient
 *   java -cp "lib/jade.jar;out" clinique.Main absence-negociation  V2 (test) : absence en pleine negociation ; utiliser -Dclinique.test.salleLente=salle1
 *   java -cp "lib/jade.jar;out" clinique.Main double              V1 : une 2e urgence ne peut pas deplacer un patient deja deplace
 *   option --gui : ouvre l'interface RMA de JADE ; option --sniffer : ouvre aussi le Sniffer sur les agents principaux
 */
public class Main {
    static AgentContainer c;
    static boolean sniffer;

    static void agent(String nom, Class<?> classe, Object... args) throws Exception {
        c.createNewAgent(nom, classe.getName(), args).start();
    }

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        sniffer = false;
        List<String> a = new ArrayList<>();
        for (String s : args) { if (s.equals("--gui")) gui = true; else if (s.equals("--sniffer")) sniffer = true; else a.add(s); }

        // JADE est bavard en INFO : on ne garde que les avertissements.
        Logger.getLogger("").setLevel(Level.WARNING);
        for (Handler h : Logger.getLogger("").getHandlers()) h.setLevel(Level.WARNING);

        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, String.valueOf(gui));
        c = Runtime.instance().createMainContainer(p);

        String mode = a.isEmpty() ? "" : a.get(0);
        long duree;
        if (mode.equals("urgence")) {
            duree = urgence(a.size() > 1 && a.get(1).equals("refus"), false);
        } else if (mode.equals("absence-comp")) {
            duree = urgence(false, true);
        } else if (mode.equals("absence-negociation")) {
            duree = journeeType("dr_leroy", 1300);   // test : dr_leroy s'absente pendant la reservation de la salle (salle ralentie)
        } else if (mode.equals("absence")) {
            duree = absenceEtRetour();
        } else if (mode.equals("double")) {
            duree = doubleUrgence();
        } else if (mode.equals("sature")) {
            duree = sature();
        } else if (mode.equals("aleatoire")) {
            int n = a.size() > 1 ? Integer.parseInt(a.get(1)) : 20;
            long graine = a.size() > 2 ? Long.parseLong(a.get(2)) : 42;
            int cap = a.size() > 3 ? Integer.parseInt(a.get(3)) : 5;
            duree = aleatoire(n, graine, cap);
        } else {
            duree = journeeType();
        }

        Thread.sleep(duree);
        if (sniffer) {
            // on ferme le Sniffer avant la plateforme : sinon il tente de se detacher d'agents deja arretes (warning de l'AMS)
            try { c.getAgent("sniffer").kill(); Thread.sleep(500); } catch (Exception e) { /* deja arrete */ }
        }
        c.kill();
        System.exit(0);
    }

    /** Laisse les agents s'inscrire au DF ; avec --sniffer, ouvre le Sniffer JADE sur les agents principaux. */
    static void attendreDF() throws Exception {
        Thread.sleep(1000);
        if (sniffer) {
            agent("sniffer", jade.tools.sniffer.Sniffer.class,
                    "coordinateur;triage;dr_martin;dr_leroy;dr_dupont;salle1;salle2;P3;P5");
            Thread.sleep(2500);   // le temps d'ouvrir la fenetre avant les premiers messages
        }
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

    /** Patient avec une tolerance (en creneaux) : il accepte un report s'il ne la depasse pas. */
    static void patient(String nom, String texte, int arrivee, long delaiMs, int tolerance) throws Exception {
        agent(nom, clinique.agents.PatientAgent.class, texte, arrivee, delaiMs, tolerance);
    }

    /** Fin de scenario : bilan du coordinateur, puis relevé des agendas (sert aux tests de coherence). */
    static void bilanEtAgendas(List<ScenarioAgent.Etape> etapes, long t) {
        etapes.add(new ScenarioAgent.Etape(t, "coordination", Msg.ONTO_BILAN, "bilan"));
        for (String nom : new String[]{"dr_martin", "dr_leroy", "dr_dupont", "salle1", "salle2"})
            etapes.add(new ScenarioAgent.Etape(t + 500, nom, null, Msg.build("action", "agenda")));
    }

    /**
     * V1 : le planning des cardiologues est plein quand l'urgence arrive.
     * P1 a P4 (normaux) occupent les creneaux 0 et 1 des deux cardiologues ; P5 (douleur thoracique) arrive ensuite :
     * le prochain creneau libre (2) est trop tard pour une urgence, donc un patient normal lui cede sa place.
     * refus = true : les patients P3 et P4 ont une tolerance de 0 et declineront toute compensation.
     */
    static long urgence(boolean refus, boolean absenceCompensation) throws Exception {
        Log.log("main", "=== Urgence sur planning plein (V1)" + (refus ? ", variante : le patient deplace refuse" : "") + " ===");
        infrastructure();
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 6);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 6);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        attendreDF();

        int tol34 = refus ? 0 : 3;
        patient("P1", "J'ai des palpitations depuis hier", 0, 0, 3);
        patient("P2", "Ma tension est trop elevee", 0, 100, 3);
        patient("P3", "Mal au coeur depuis ce matin", 0, 200, tol34);
        patient("P4", "Essoufflement a l'effort", 0, 300, tol34);
        patient("P5", "Douleur dans la poitrine et difficulte a respirer", 0, 3000, 3);

        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        // test : dr_leroy (qui porte le nouveau creneau du patient deplace) s'absente pendant la compensation
        if (absenceCompensation) etapes.add(new ScenarioAgent.Etape(5000, "dr_leroy", null, Msg.build("action", "devenir_absent")));
        bilanEtAgendas(etapes, 9000);
        agent("scenario", ScenarioAgent.class, etapes);
        return 11000;
    }

    /**
     * V2 : absence d'un medecin puis retour.
     * dr_leroy porte P1, P3 (normaux) et P5 (urgent, arrive au creneau 2) ; dr_martin a un seul creneau libre (le 2).
     * dr_leroy s'absente : P5 (urgent) est replanifie en premier et prend le dernier creneau de dr_martin ;
     * P1 et P3 n'ont plus de place : echec "capacite". Plus tard dr_leroy revient et P6, nouvelle demande, passe chez lui.
     */
    static long absenceEtRetour() throws Exception {
        Log.log("main", "=== Absence d'un medecin, replanification, retour (V2) ===");
        infrastructure();
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        attendreDF();
        patient("P1", "J'ai des palpitations depuis hier", 0, 0);
        patient("P2", "Ma tension est trop elevee", 0, 100);
        patient("P3", "Mal au coeur depuis ce matin", 0, 200);
        patient("P4", "Essoufflement a l'effort", 0, 300);
        patient("P5", "Douleur dans la poitrine et difficulte a respirer", 2, 400);
        patient("P6", "Palpitations et tension elevee", 0, 9000);   // arrive apres le retour de dr_leroy
        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        etapes.add(new ScenarioAgent.Etape(5000, "dr_leroy", null, Msg.build("action", "devenir_absent")));
        etapes.add(new ScenarioAgent.Etape(8000, "dr_leroy", null, Msg.build("action", "revenir")));
        bilanEtAgendas(etapes, 12500);
        agent("scenario", ScenarioAgent.class, etapes);
        return 15000;
    }

    /**
     * V1 : regle d'equite. Une 1re urgence (P5) deplace P3, qui est recase au creneau 2 chez dr_leroy.
     * Une 2e urgence (P6, arrivee au creneau 2) ne trouve alors comme seule victime possible que P3 : deja deplace,
     * donc protege. Resultat attendu : echec planning_sature, et P3 garde son creneau.
     */
    static long doubleUrgence() throws Exception {
        Log.log("main", "=== Deux urgences : un patient deja deplace est protege (V1) ===");
        infrastructure();
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 2);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        attendreDF();
        patient("P1", "J'ai des palpitations depuis hier", 0, 0);
        patient("P2", "Ma tension est trop elevee", 0, 100);
        patient("P3", "Mal au coeur depuis ce matin", 0, 200);
        patient("P4", "Essoufflement a l'effort", 0, 300);
        patient("P5", "Douleur dans la poitrine et difficulte a respirer", 0, 3000);
        patient("P6", "Douleur dans la poitrine", 2, 6500);
        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        bilanEtAgendas(etapes, 11000);
        agent("scenario", ScenarioAgent.class, etapes);
        return 13000;
    }

    /** V1 : tous les rendez-vous sont des urgences hautes : aucune victime possible, echec explicite pour la 5e urgence. */
    static long sature() throws Exception {
        Log.log("main", "=== Planning sature (V1) : aucune victime possible ===");
        infrastructure();
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 2);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 2);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        attendreDF();
        for (int i = 1; i <= 4; i++) patient("P" + i, "Douleur dans la poitrine", 0, 100L * i);
        patient("P5", "Douleur dans la poitrine", 0, 3000);          // urgence : tout est plein, personne a deplacer
        patient("P6", "Ma tension est trop elevee", 0, 3100);        // normal : simple planning plein
        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        bilanEtAgendas(etapes, 8000);
        agent("scenario", ScenarioAgent.class, etapes);
        return 10000;
    }

    /** La journee type du rapport : 5 patients, une urgence, un medecin qui s'absente, un bilan. */
    static long journeeType() throws Exception { return journeeType("dr_martin", 5000); }

    /** Journee type ; "medecinAbsent" s'absente apres "quand" ms (un delai tres court provoque l'absence en pleine negociation). */
    static long journeeType(String medecinAbsent, long quand) throws Exception {
        Log.log("main", "=== Journee type ===");
        infrastructure();
        // Planning des cardiologues volontairement petit : 3 creneaux chacun.
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", 3);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", 6);
        attendreDF();   // laisse les agents s'inscrire au DF avant les patients

        patient("P1", "J'ai des palpitations depuis hier", 0, 0);
        patient("P2", "Ma tension est trop elevee", 0, 0);
        patient("P3", "Douleur dans la poitrine et difficulte a respirer", 0, 300);
        patient("P4", "Forte fievre et toux depuis trois jours", 1, 0);
        patient("P5", "Fatigue et mal de gorge", 1, 0);

        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        etapes.add(new ScenarioAgent.Etape(quand, medecinAbsent, null, Msg.build("action", "devenir_absent")));
        bilanEtAgendas(etapes, 9000);
        agent("scenario", ScenarioAgent.class, etapes);
        return 11000;
    }

    /** N demandes aleatoires : sert au test d'acceptation (aucune double attribution). */
    static long aleatoire(int n, long graine, int cap) throws Exception {
        Log.log("main", "=== " + n + " demandes aleatoires, graine " + graine + " ===");
        infrastructure();
        // capacites serrees : les urgences declenchent des preemptions
        agent("dr_martin", clinique.agents.MedecinAgent.class, "cardiologie", cap);
        agent("dr_leroy", clinique.agents.MedecinAgent.class, "cardiologie", cap);
        agent("dr_dupont", clinique.agents.MedecinAgent.class, "generaliste", cap + 3);
        attendreDF();

        String[] textes = {
            "Palpitations et tension elevee", "Douleur dans la poitrine", "Forte fievre et toux",
            "Fatigue et mal de gorge", "Eruption sur la peau", "Perte de connaissance ce matin",
            "Mal de tete et rhume", "Essoufflement a l'effort"
        };
        Random r = new Random(graine);
        for (int i = 1; i <= n; i++) {
            String texte = textes[r.nextInt(textes.length)];
            int arrivee = r.nextInt(4);
            long delai = r.nextInt(1500);
            int tolerance = r.nextInt(5);
            // les urgences arrivent apres les demandes normales : elles trouvent un planning deja rempli (preemptions)
            if (texte.contains("poitrine") || texte.contains("connaissance")) delai += 3000;
            patient("P" + i, texte, arrivee, delai, tolerance);
        }
        List<ScenarioAgent.Etape> etapes = new ArrayList<>();
        etapes.add(new ScenarioAgent.Etape(4000 + 100L * n, "dr_martin", null, Msg.build("action", "devenir_absent")));
        etapes.add(new ScenarioAgent.Etape(6500 + 100L * n, "dr_martin", null, Msg.build("action", "revenir")));   // V2 : il revient
        bilanEtAgendas(etapes, 8000 + 200L * n);
        agent("scenario", ScenarioAgent.class, etapes);
        return 10000 + 200L * n;
    }
}
