package clinique.eval;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
import clinique.eval.Metrics.Issue;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;

/**
 * Le Monitor (V3) : il mesure UNE campagne.
 *  - Il recoit les evenements du coordinateur (M9 : rendez-vous confirme, deplacement, echec, absence...)
 *    et garde, pour chaque patient, son issue la plus recente.
 *  - Quand le ScenarioRunner demande de conclure (ontologie "fin"), il interroge les agendas reels des medecins et des
 *    salles pour verifier l'invariant "aucun double rendez-vous", calcule les mesures (classe Metrics), ecrit deux
 *    fichiers CSV (un resume, un detail par patient) et previent le ScenarioRunner.
 * Arguments : strategie, n, graine, dossier, "agent1;agent2;..." (agents a interroger), capaciteMedecins, capaciteSalles.
 */
public class MonitorAgent extends Agent {

    /** Etat courant d'un patient d'apres les evenements. */
    private static final class Etat {
        String urgence = "normale";
        int arrivee = 0;
        String issue = "en_cours";   // en_cours | rdv | echec
        String medecin = "", salle = "", raison = "";
        int creneau = -1;
    }

    private String strategie, dossier;
    private int n, capMedecins, capSalles;
    private long graine;
    private String[] interroges;
    private final Map<String, Etat> patients = new LinkedHashMap<>();
    private int deplacements = 0, replanifications = 0, absences = 0, retours = 0;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        strategie = (String) a[0];
        n = (Integer) a[1];
        graine = ((Number) a[2]).longValue();
        dossier = (String) a[3];
        interroges = ((String) a[4]).split(";");
        capMedecins = (Integer) a[5];
        capSalles = (Integer) a[6];
        Df.inscrire(this, Msg.SVC_MONITOR, "monitor");

        final MessageTemplate tpl = MessageTemplate.or(
                MessageTemplate.MatchOntology(Msg.ONTO_EVENT), MessageTemplate.MatchOntology(Msg.ONTO_FIN));
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = myAgent.receive(tpl);
                if (m == null) { block(); return; }
                if (Msg.ONTO_EVENT.equals(m.getOntology())) enregistrer(Msg.parse(m));
                else conclure(m);
            }
        });
    }

    // ------------------------------------------------------------------ evenements (M9)

    /** Met a jour l'etat du patient concerne par l'evenement. */
    private void enregistrer(Map<String, String> c) {
        String evt = c.get("evt");
        if (evt == null) return;
        switch (evt) {
            case "rdv_confirme": {
                Etat e = patient(c.get("patient"));
                e.urgence = c.get("urgence");
                e.arrivee = Msg.getInt(c, "arrivee", 0);
                e.issue = "rdv";
                e.medecin = c.get("medecin");
                e.salle = c.get("salle");
                e.creneau = Msg.getInt(c, "creneau", -1);
                e.raison = "";
                break;
            }
            case "echec": {
                Etat e = patient(c.get("patient"));
                e.urgence = c.get("urgence");
                e.arrivee = Msg.getInt(c, "arrivee", 0);
                e.issue = "echec";
                e.medecin = ""; e.salle = ""; e.creneau = -1;
                e.raison = c.get("raison");
                break;
            }
            case "deplacement": {           // la victime perd son rendez-vous (elle sera recasee ou en echec)
                deplacements++;
                perdreRdv(c.get("victime"));
                break;
            }
            case "replanification": {       // medecin absent : le patient perd son rendez-vous
                replanifications++;
                perdreRdv(c.get("patient"));
                break;
            }
            case "absence": absences++; break;
            case "retour": retours++; break;
            default: break;
        }
    }

    private Etat patient(String nom) { return patients.computeIfAbsent(nom, k -> new Etat()); }

    private void perdreRdv(String nom) {
        Etat e = patient(nom);
        e.issue = "en_cours"; e.medecin = ""; e.salle = ""; e.creneau = -1;
    }

    // ------------------------------------------------------------------ fin de campagne

    /**
     * Le ScenarioRunner a fini d'envoyer les demandes et le coordinateur est au repos : on interroge les agendas
     * reels (Request "agenda" a chaque medecin et chaque salle), puis on calcule et on ecrit les resultats.
     */
    private void conclure(final ACLMessage fin) {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        for (String nom : interroges) req.addReceiver(new AID(nom, AID.ISLOCALNAME));
        req.setConversationId("monitor-agendas");
        req.setReplyByDate(new java.util.Date(System.currentTimeMillis() + 5000));
        req.setContent(Msg.build("action", "agenda"));

        addBehaviour(new AchieveREInitiator(this, req) {
            @Override
            @SuppressWarnings("rawtypes")
            protected void handleAllResultNotifications(Vector resultNotifications) {
                Map<String, Map<Integer, String>> agendas = new HashMap<>();
                for (Object o : resultNotifications) {
                    ACLMessage r = (ACLMessage) o;
                    if (r.getPerformative() != ACLMessage.INFORM) continue;
                    agendas.put(r.getSender().getLocalName(), lireAgenda(Msg.parse(r).get("agenda")));
                }
                int conflits = verifier(agendas);
                try {
                    ecrire(conflits, agendas.size());
                } catch (IOException e) {
                    Log.toujours(getLocalName(), "ERREUR d'ecriture des resultats : " + e);
                }
                ACLMessage rep = fin.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                rep.setContent(Msg.build("statut", "fini"));
                send(rep);
                Campagne.termine = true;
            }
        });
    }

    private static Map<Integer, String> lireAgenda(String s) {
        Map<Integer, String> m = new TreeMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split(",")) {
            int i = part.indexOf(':');
            if (i > 0) m.put(Integer.parseInt(part.substring(0, i)), part.substring(i + 1));
        }
        return m;
    }

    /**
     * Invariant : aucun double rendez-vous, et les agendas reels recoupent les evenements du coordinateur.
     * Retourne le nombre d'anomalies (0 attendu).
     */
    private int verifier(Map<String, Map<Integer, String>> agendas) {
        int anomalies = 0;
        Map<String, Map<Integer, String>> medecins = new TreeMap<>(), salles = new TreeMap<>();
        for (Map.Entry<String, Map<Integer, String>> e : agendas.entrySet()) {
            (e.getKey().startsWith("salle") ? salles : medecins).put(e.getKey(), e.getValue());
        }
        // un patient n'a qu'un seul rendez-vous chez les medecins
        Map<String, Integer> parPatient = new HashMap<>();
        for (Map<Integer, String> ag : medecins.values())
            for (String p : ag.values()) parPatient.merge(p, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : parPatient.entrySet()) if (e.getValue() > 1) anomalies += e.getValue() - 1;
        // chaque rendez-vous d'un medecin a une salle au meme creneau pour le meme patient, et inversement
        for (Map<Integer, String> ag : medecins.values())
            for (Map.Entry<Integer, String> e : ag.entrySet())
                if (!salleContient(salles, e.getKey(), e.getValue())) anomalies++;
        for (Map<Integer, String> ag : salles.values())
            for (Map.Entry<Integer, String> e : ag.entrySet())
                if (!medecinContient(medecins, e.getKey(), e.getValue())) anomalies++;
        // les agendas recoupent l'etat deduit des evenements
        Set<String> vusAgenda = new HashSet<>();
        for (Map.Entry<String, Map<Integer, String>> me : medecins.entrySet())
            for (Map.Entry<Integer, String> e : me.getValue().entrySet()) {
                vusAgenda.add(e.getValue());
                Etat s = patients.get(e.getValue());
                if (s == null || !"rdv".equals(s.issue) || s.creneau != e.getKey() || !s.medecin.equals(me.getKey())) anomalies++;
            }
        for (Map.Entry<String, Etat> e : patients.entrySet())
            if ("rdv".equals(e.getValue().issue) && !vusAgenda.contains(e.getKey())) anomalies++;
        return anomalies;
    }

    private static boolean salleContient(Map<String, Map<Integer, String>> salles, int creneau, String patient) {
        for (Map<Integer, String> ag : salles.values()) if (patient.equals(ag.get(creneau))) return true;
        return false;
    }

    private static boolean medecinContient(Map<String, Map<Integer, String>> medecins, int creneau, String patient) {
        for (Map<Integer, String> ag : medecins.values()) if (patient.equals(ag.get(creneau))) return true;
        return false;
    }

    // ------------------------------------------------------------------ resultats

    /** Ecrit resume_<strategie>_<n>_<graine>.csv (une ligne) et detail_...csv (une ligne par patient). */
    private void ecrire(int conflits, int agendasLus) throws IOException {
        List<Issue> issues = new ArrayList<>();
        Map<String, Integer> echecs = new TreeMap<>();
        int rdv = 0;
        for (Map.Entry<String, Etat> e : numerote(patients).entrySet()) {
            Etat s = e.getValue();
            int poids = "haute".equals(s.urgence) ? 3 : 1;
            boolean ok = "rdv".equals(s.issue);
            issues.add(new Issue(e.getKey(), poids, s.arrivee, ok ? s.creneau : -1, ok ? "" : s.raison));
            if (ok) rdv++;
            else echecs.merge("en_cours".equals(s.issue) ? "sans_issue" : s.raison, 1, Integer::sum);
        }
        int urg = Metrics.urgences(issues), aTemps = Metrics.urgencesATemps(issues, Slots.DELAI_URGENCE);
        StringBuilder detailEchecs = new StringBuilder();
        for (Map.Entry<String, Integer> e : echecs.entrySet()) {
            if (detailEchecs.length() > 0) detailEchecs.append('|');
            detailEchecs.append(e.getKey()).append(':').append(e.getValue());
        }
        int sansIssue = echecs.getOrDefault("sans_issue", 0);

        Path dir = Paths.get(dossier);
        Files.createDirectories(dir);
        String suffixe = strategie + "_" + n + "_" + graine + ".csv";

        String entete = "strategie,n,graine,patients,rdv,echecs,echecs_detail,attente_moyenne,attente_normale,attente_haute,"
                + "attente_ponderee,urgences,urgences_a_temps,urgences_a_temps_pct,deplacements,replanifications,absences,"
                + "occupation_medecins,occupation_salles,conflits,agendas_lus,sans_issue";
        String ligne = String.join(",",
                strategie, "" + n, "" + graine, "" + patients.size(), "" + rdv, "" + (patients.size() - rdv), detailEchecs.toString(),
                Metrics.fmt(Metrics.attenteMoyenne(issues, 0)), Metrics.fmt(Metrics.attenteMoyenne(issues, 1)),
                Metrics.fmt(Metrics.attenteMoyenne(issues, 3)), Metrics.fmt(Metrics.attentePonderee(issues)),
                "" + urg, "" + aTemps, urg == 0 ? "" : Metrics.fmt(100.0 * aTemps / urg),
                "" + deplacements, "" + replanifications, "" + absences,
                Metrics.fmt(Metrics.occupation(rdv, capMedecins)), Metrics.fmt(Metrics.occupation(rdv, capSalles)),
                "" + conflits, "" + agendasLus, "" + sansIssue);
        Files.write(dir.resolve("resume_" + suffixe), (entete + "\n" + ligne + "\n").getBytes(StandardCharsets.UTF_8));

        StringBuilder detail = new StringBuilder("patient,urgence,arrivee,issue,medecin,salle,creneau,attente,raison\n");
        for (Map.Entry<String, Etat> e : numerote(patients).entrySet()) {
            Etat s = e.getValue();
            boolean ok = "rdv".equals(s.issue);
            detail.append(String.join(",", e.getKey(), s.urgence, "" + s.arrivee, s.issue, s.medecin, s.salle,
                    ok ? "" + s.creneau : "", ok ? "" + Slots.attente(s.arrivee, s.creneau) : "", s.raison)).append('\n');
        }
        Files.write(dir.resolve("detail_" + suffixe), detail.toString().getBytes(StandardCharsets.UTF_8));

        Log.toujours("monitor", String.format("%s : %d patients, %d rdv, %d echecs %s ; attente ponderee %s ; urgences a temps %d/%d ; "
                        + "deplacements %d ; conflits %d", strategie, patients.size(), rdv, patients.size() - rdv,
                echecs.isEmpty() ? "" : "(" + detailEchecs + ")", Metrics.fmt(Metrics.attentePonderee(issues)), aTemps, urg,
                deplacements, conflits));
        Log.toujours("monitor", "resultats : " + dir.resolve("resume_" + suffixe));
    }

    /** Patients tries P1, P2, ..., P10 (ordre numerique) pour des fichiers lisibles. */
    private static Map<String, Etat> numerote(Map<String, Etat> m) {
        Map<String, Etat> out = new TreeMap<>((a, b) -> {
            try { return Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1))); }
            catch (Exception e) { return a.compareTo(b); }
        });
        out.putAll(m);
        return out;
    }
}
