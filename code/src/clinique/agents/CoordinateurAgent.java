package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
import clinique.common.Urgence;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import jade.proto.ContractNetInitiator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;

/**
 * Le coordinateur (V0).
 *  - Un CyclicBehaviour recoit les demandes (M3), les absences (M7) et la commande de bilan.
 *  - Les demandes vont dans une file prioritaire : urgence haute d'abord, puis ordre d'arrivee (D6).
 *  - Une seule negociation a la fois : ContractNetInitiator vers les medecins (M4),
 *    puis AchieveREInitiator vers les salles (M5), l'une apres l'autre (D4).
 *  - La reponse finale au patient (INFORM / FAILURE) est envoyee ici meme (D1).
 *  - Si aucune salle n'est libre : annulation chez le medecin (M6) et une nouvelle tentative qui exclut ce creneau (exclusions cumulees, 5 essais au plus) (D3).
 */
public class CoordinateurAgent extends Agent {
    private static final long GUICHET_MS = 1000;   // fenetre de regroupement avant de traiter la file
    private static final long REPONSE_CFP_MS = 2000;
    private static final int  TENTATIVES_MAX = 5;

    /** Une demande de rendez-vous. */
    private static final class Demande {
        final String patient, conv, specialite;
        final Urgence urgence;
        final int arrivee;
        final long seq;
        int tentative = 0;
        final java.util.TreeSet<Integer> exclus = new java.util.TreeSet<>();   // creneaux sans salle deja essayes
        boolean termine = false;
        Demande(String patient, String conv, String specialite, Urgence urgence, int arrivee, long seq) {
            this.patient = patient; this.conv = conv; this.specialite = specialite;
            this.urgence = urgence; this.arrivee = arrivee; this.seq = seq;
        }
        AID aid() { return new AID(patient, AID.ISLOCALNAME); }
    }

    /** Un rendez-vous confirme. */
    private static final class Rdv {
        final Demande demande; final String medecin, salle; final int creneau;
        Rdv(Demande d, String m, String s, int c) { demande = d; medecin = m; salle = s; creneau = c; }
    }

    private final PriorityQueue<Demande> file = new PriorityQueue<>(
            Comparator.<Demande>comparingInt(d -> -d.urgence.poids).thenComparingLong(d -> d.seq));
    private final Map<String, Rdv> registre = new TreeMap<>();     // patient -> rdv
    private final Map<String, Integer> echecs = new TreeMap<>();   // raison -> nombre
    private long compteur = 0;
    private boolean occupe = false;
    private boolean guichetPlanifie = false;

    @Override
    protected void setup() {
        Df.inscrire(this, Msg.SVC_COORD, "coordination");
        Log.log(getLocalName(), "pret");

        // Seuls les messages "de service" nous interessent : les reponses des protocoles
        // (CFP, REQUEST...) sont lues par les initiateurs, pas par ce behaviour.
        final MessageTemplate tpl = MessageTemplate.or(
                MessageTemplate.MatchOntology(Msg.ONTO_DEMANDE),
                MessageTemplate.or(MessageTemplate.MatchOntology(Msg.ONTO_ABSENCE),
                                   MessageTemplate.MatchOntology(Msg.ONTO_BILAN)));

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = myAgent.receive(tpl);
                if (m == null) { block(); return; }
                if (Msg.ONTO_DEMANDE.equals(m.getOntology())) recevoirDemande(m);
                else if (Msg.ONTO_ABSENCE.equals(m.getOntology())) recevoirAbsence(m);
                else bilan();
            }
        });
    }

    // ------------------------------------------------------------------ reception

    /**
     * M3 : un patient demande un rendez-vous.
     * On range la demande dans la file prioritaire, puis on programme le guichet
     * (la negociation ne demarre pas tout de suite, voir planifierGuichet).
     */
    private void recevoirDemande(ACLMessage m) {
        Map<String, String> c = Msg.parse(m);
        Demande d = new Demande(c.get("patient"), m.getConversationId(), c.get("specialite"),
                Urgence.de(c.get("urgence")), Msg.getInt(c, "arrivee", 0), compteur++);
        file.add(d);
        Log.log(getLocalName(), "demande de " + d.patient + " : " + d.specialite + ", urgence " + d.urgence.code()
                + " (file : " + file.size() + ")");
        planifierGuichet(GUICHET_MS);
    }

    /**
     * M7 : un medecin est absent. Pour chaque patient concerne : on supprime son rendez-vous,
     * on libere sa salle, on le prevent, puis on le remet dans la file avec son urgence d'origine.
     * Il repasse ensuite par la negociation normale ; le medecin absent n'est plus au DF, donc plus consulte.
     */
    private void recevoirAbsence(ACLMessage m) {
        Map<String, String> c = Msg.parse(m);
        String medecin = c.get("medecin");
        String liste = c.get("patients");
        Log.log(getLocalName(), "ABSENCE de " + medecin + " (patients : " + (liste == null || liste.isEmpty() ? "aucun" : liste) + ")");
        if (liste != null && !liste.isEmpty()) {
            for (String p : liste.split(",")) {
                Rdv r = registre.get(p);
                if (r == null || !r.medecin.equals(medecin)) continue;
                registre.remove(p);
                annulerChezSalle(r.salle, r.creneau, p);
                Demande d = new Demande(p, r.demande.conv, r.demande.specialite, r.demande.urgence,
                        r.demande.arrivee, compteur++);
                file.add(d);
                ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
                inf.addReceiver(d.aid());
                inf.setConversationId(d.conv);
                inf.setContent(Msg.build("statut", "a_replanifier", "raison", "medecin_absent"));
                send(inf);
                Log.log(getLocalName(), p + " remis dans la file (urgence " + d.urgence.code() + ")");
            }
        }
        planifierGuichet(GUICHET_MS);
    }

    // ------------------------------------------------------------------ file d'attente

    /**
     * Programme (une seule fois) le reveil de la file. Le delai laisse les demandes proches
     * s'accumuler, pour que la priorite d'urgence puisse jouer avant la premiere negociation.
     */
    private void planifierGuichet(long delai) {
        if (occupe || guichetPlanifie) return;
        guichetPlanifie = true;
        addBehaviour(new WakerBehaviour(this, delai) {
            @Override
            protected void onWake() {
                guichetPlanifie = false;
                suivante();
            }
        });
    }

    /** Prend la meilleure demande de la file et la negocie. Ne fait rien si une negociation est deja en cours. */
    private void suivante() {
        if (occupe) return;
        Demande d = file.poll();
        if (d == null) return;
        occupe = true;
        negocier(d);
    }

    /** Appelee a la fin de chaque demande (succes ou echec) : libere le verrou et enchaine la suivante. */
    private void fin() {
        occupe = false;
        suivante();
    }

    // ------------------------------------------------------------------ negociation : Contract-Net (M4)

    /**
     * Etape 1 d'une demande : appel d'offres (Contract-Net) a tous les medecins de la specialite.
     * Enchainement : handleAllResponses (choix de l'offre) -> handleInform (medecin confirme)
     * -> reserverSalle -> confirmer ou echec.
     */
    private void negocier(final Demande d) {
        d.tentative++;
        AID[] medecins = Df.chercher(this, Msg.SVC_MEDECIN, d.specialite);
        Log.log(getLocalName(), "NEGOCIATION " + d.patient + " (" + d.specialite + ", " + d.urgence.code()
                + ", essai " + d.tentative + ") : " + medecins.length + " medecin(s)");
        if (medecins.length == 0) { echec(d, "aucun_medecin"); return; }

        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setConversationId(d.conv + "-" + d.tentative);
        for (AID med : medecins) cfp.addReceiver(med);
        cfp.setReplyByDate(new java.util.Date(System.currentTimeMillis() + REPONSE_CFP_MS));
        cfp.setContent(Msg.build("patient", d.patient, "specialite", d.specialite, "urgence", d.urgence.code(),
                "arrivee", "" + d.arrivee, "exclu", d.exclus.toString().replaceAll("[\\[\\] ]", "")));

        addBehaviour(new ContractNetInitiator(this, cfp) {
            private AID gagnant;
            private int creneau;

            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            // Appelee quand tous les medecins ont repondu (ou delai ecoule) : on retient le creneau le plus proche.
            protected void handleAllResponses(Vector responses, Vector acceptances) {
                ACLMessage meilleure = null;
                int meilleurCreneau = Integer.MAX_VALUE;
                boolean plein = false;
                for (Object o : responses) {
                    ACLMessage r = (ACLMessage) o;
                    if (r.getPerformative() == ACLMessage.PROPOSE) {
                        int cr = Msg.getInt(Msg.parse(r), "creneau", Integer.MAX_VALUE);
                        // le plus proche ; a egalite, le premier dans l'ordre alphabetique des medecins
                        if (cr < meilleurCreneau || (cr == meilleurCreneau
                                && r.getSender().compareTo(meilleure.getSender()) < 0)) {
                            meilleure = r; meilleurCreneau = cr;
                        }
                    } else if ("planning_plein".equals(Msg.parse(r).get("raison"))) {
                        plein = true;
                    }
                }
                if (meilleure == null) {
                    echec(d, plein ? "planning_plein" : "aucun_medecin_disponible");
                    return;
                }
                for (Object o : responses) {
                    ACLMessage r = (ACLMessage) o;
                    if (r.getPerformative() != ACLMessage.PROPOSE) continue;
                    ACLMessage rep = r.createReply();
                    rep.setPerformative(r == meilleure ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                    acceptances.add(rep);
                }
                gagnant = meilleure.getSender();
                creneau = meilleurCreneau;
            }

            @Override
            protected void handleInform(ACLMessage inform) {
                reserverSalle(d, gagnant, creneau, Df.chercher(CoordinateurAgent.this, Msg.SVC_SALLE, null), 0);
            }

            @Override
            protected void handleFailure(ACLMessage failure) {
                echec(d, "echec_medecin");
            }
        });
    }

    // ------------------------------------------------------------------ salle : Request (M5)

    /**
     * Etape 2 : reserver une salle au creneau obtenu. i = position de la salle essayee dans la liste du DF.
     * A chaque refus on se rappelle avec i + 1 (pas de boucle : un agent ne doit pas bloquer).
     * Quand i depasse la liste, aucune salle n'est libre : on rend le creneau au medecin et on retente.
     */
    private void reserverSalle(final Demande d, final AID medecin, final int creneau, final AID[] salles, final int i) {
        if (i >= salles.length) {
            // D3 : aucune salle, on rend le creneau au medecin puis on retente une fois avec exclu=creneau
            Log.log(getLocalName(), "aucune salle libre au creneau " + creneau + " pour " + d.patient + " : annulation chez " + medecin.getLocalName());
            annulerChezMedecin(d, medecin, creneau);
            return;
        }
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        req.addReceiver(salles[i]);
        req.setConversationId(d.conv + "-salle-" + d.tentative + "-" + i);
        req.setContent(Msg.build("action", "reserver", "creneau", "" + creneau, "patient", d.patient));

        addBehaviour(new AchieveREInitiator(this, req) {
            @Override
            protected void handleInform(ACLMessage inform) {
                confirmer(d, medecin.getLocalName(), salles[i].getLocalName(), creneau);
            }
            @Override
            protected void handleRefuse(ACLMessage refuse) {
                reserverSalle(d, medecin, creneau, salles, i + 1);     // salle suivante
            }
            @Override
            protected void handleFailure(ACLMessage failure) {
                reserverSalle(d, medecin, creneau, salles, i + 1);
            }
            @Override
            protected void handleNotUnderstood(ACLMessage nu) {
                reserverSalle(d, medecin, creneau, salles, i + 1);
            }
        });
    }

    /** M6 vers un medecin, puis nouvel essai en excluant ce creneau (exclusions cumulees). */
    private void annulerChezMedecin(final Demande d, AID medecin, final int creneau) {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        req.addReceiver(medecin);
        req.setConversationId(d.conv + "-annul-" + d.tentative);
        req.setContent(Msg.build("action", "annuler", "creneau", "" + creneau, "patient", d.patient));
        addBehaviour(new AchieveREInitiator(this, req) {
            private boolean suite = false;
            private void suite() {
                if (suite) return;
                suite = true;
                if (d.tentative < TENTATIVES_MAX) { d.exclus.add(creneau); negocier(d); }
                else echec(d, "aucune_salle");
            }
            @Override protected void handleInform(ACLMessage m) { suite(); }
            @Override protected void handleFailure(ACLMessage m) { suite(); }
            @Override protected void handleRefuse(ACLMessage m) { suite(); }
            @Override protected void handleNotUnderstood(ACLMessage m) { suite(); }
        });
    }

    /** M6 vers une salle (pas de suite a donner a la reponse). */
    private void annulerChezSalle(String salle, int creneau, String patient) {
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        req.addReceiver(new AID(salle, AID.ISLOCALNAME));
        req.setConversationId("annul-" + salle + "-" + patient + "-" + compteur++);
        req.setContent(Msg.build("action", "annuler", "creneau", "" + creneau, "patient", patient));
        addBehaviour(new AchieveREInitiator(this, req));
    }

    // ------------------------------------------------------------------ issues

    /** Issue positive : enregistre le rendez-vous, previent le patient (INFORM), puis passe a la demande suivante. */
    private void confirmer(Demande d, String medecin, String salle, int creneau) {
        if (d.termine) return;
        d.termine = true;
        registre.put(d.patient, new Rdv(d, medecin, salle, creneau));
        int attente = Slots.attente(d.arrivee, creneau);
        Log.log(getLocalName(), "RDV CONFIRME " + d.patient + " : " + medecin + ", " + salle + ", creneau " + creneau + " (attente " + attente + ")");
        ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
        inf.addReceiver(d.aid());
        inf.setConversationId(d.conv);
        inf.setContent(Msg.build("statut", "confirme", "medecin", medecin, "salle", salle,
                "creneau", "" + creneau, "attente", "" + attente));
        send(inf);
        fin();
    }

    /** Issue negative : compte l'echec, previent le patient (FAILURE), puis passe a la demande suivante. */
    private void echec(Demande d, String raison) {
        if (d.termine) return;
        d.termine = true;
        echecs.merge(raison, 1, Integer::sum);
        Log.log(getLocalName(), "ECHEC " + d.patient + " : " + raison);
        ACLMessage f = new ACLMessage(ACLMessage.FAILURE);
        f.addReceiver(d.aid());
        f.setConversationId(d.conv);
        f.setContent(Msg.build("raison", raison));
        send(f);
        fin();
    }

    // ------------------------------------------------------------------ mesures

    /** Affiche les rendez-vous, l'attente moyenne et ponderee par l'urgence, et verifie l'absence de doubles attributions. */
    private void bilan() {
        Log.log(getLocalName(), "================ BILAN ================");
        int n = 0, somme = 0, sommePond = 0, poids = 0;
        Set<String> vus = new HashSet<>();
        List<String> doublons = new ArrayList<>();
        List<Rdv> tous = new ArrayList<>(registre.values());
        tous.sort(Comparator.comparingInt((Rdv r) -> r.creneau).thenComparing(r -> r.medecin));
        for (Rdv r : tous) {
            int att = Slots.attente(r.demande.arrivee, r.creneau);
            n++; somme += att; sommePond += att * r.demande.urgence.poids; poids += r.demande.urgence.poids;
            Log.log(getLocalName(), String.format("  %-4s %-7s %-8s %-7s creneau %2d  attente %d",
                    r.demande.patient, r.demande.urgence.code(), r.medecin, r.salle, r.creneau, att));
            if (!vus.add("M|" + r.medecin + "|" + r.creneau)) doublons.add("medecin " + r.medecin + " creneau " + r.creneau);
            if (!vus.add("S|" + r.salle + "|" + r.creneau)) doublons.add("salle " + r.salle + " creneau " + r.creneau);
        }
        Log.log(getLocalName(), "rendez-vous confirmes : " + n + " ; echecs : " + echecs);
        if (n > 0) {
            Log.log(getLocalName(), String.format("attente moyenne : %.2f creneau(x) ; attente ponderee par l'urgence : %.2f",
                    somme / (double) n, sommePond / (double) poids));
        }
        Log.log(getLocalName(), "doubles attributions : " + doublons.size() + (doublons.isEmpty() ? "" : " " + doublons));
        Log.log(getLocalName(), "en file : " + file.size() + " ; negociation en cours : " + occupe);
    }
}
