package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
import clinique.common.Urgence;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREResponder;
import jade.proto.ContractNetResponder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Un medecin. Arguments : specialite, capacite (nombre de creneaux ouverts : 0 a capacite-1).
 * Agenda : creneau -> rendez-vous (patient, urgence, deja deplace ?) ; holds : creneau -> conversation-id (D2).
 * - Contract-Net (M4) : propose son plus proche creneau libre et le met en HOLD ;
 *   V1 : pour une urgence haute sans creneau libre assez proche, propose de PREEMPTER un patient normal.
 *   ACCEPT -> confirme, REJECT ou expiration -> libere.
 * - Request (M6) : annuler, restaurer (retour arriere V1), agenda (diagnostic) ; commandes devenir_absent (M7)
 *   et revenir (V2, M7 statut=disponible).
 */
public class MedecinAgent extends Agent {
    private static final long HOLD_MS = 5000;

    /** Un rendez-vous dans l'agenda. */
    private static final class Rdv {
        final String patient;
        final Urgence urgence;
        final boolean deplace;   // deja deplace une fois : ne peut plus etre une victime
        final long seq;          // ordre de confirmation (le plus recent = seq le plus grand)
        Rdv(String patient, Urgence urgence, boolean deplace, long seq) {
            this.patient = patient; this.urgence = urgence; this.deplace = deplace; this.seq = seq;
        }
    }

    private String specialite;
    private int capacite;
    private boolean absent = false;
    private long compteur = 0;
    private final Map<Integer, Rdv> confirme = new HashMap<>();     // creneau -> rendez-vous
    private final Map<Integer, String> hold = new HashMap<>();      // creneau -> conversation-id

    @Override
    protected void setup() {
        Object[] a = getArguments();
        specialite = a != null && a.length > 0 ? (String) a[0] : "generaliste";
        capacite = a != null && a.length > 1 ? (Integer) a[1] : Slots.NB;
        Df.inscrire(this, Msg.SVC_MEDECIN, specialite);
        Log.log(getLocalName(), "pret : " + specialite + ", creneaux 0.." + (capacite - 1));

        addBehaviour(new ContractNetResponder(this, MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                MessageTemplate.MatchPerformative(ACLMessage.CFP))) {

            @Override
            // Appel d'offres : propose un creneau libre (HOLD) ou, pour une urgence haute, une preemption.
            protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {
                if (absent) throw new RefuseException("raison=absent");
                Map<String, String> c = Msg.parse(cfp);
                Urgence urgence = Urgence.de(c.get("urgence"));
                int arrivee = Msg.getInt(c, "arrivee", 0);
                Set<Integer> exclus = new HashSet<>();
                if (c.get("exclu") != null && !c.get("exclu").isEmpty())
                    for (String x : c.get("exclu").split(",")) exclus.add(Integer.parseInt(x.trim()));

                int libre = -1;
                for (int s = Math.max(0, arrivee); s < capacite; s++) {
                    if (!exclus.contains(s) && !confirme.containsKey(s) && !hold.containsKey(s)) { libre = s; break; }
                }

                // V1 : une urgence haute doit etre vue vite. Pas de creneau libre assez proche -> preemption.
                boolean tropTard = urgence == Urgence.HAUTE && (libre < 0 || libre > arrivee + Slots.DELAI_URGENCE);
                if (tropTard) {
                    int victime = choisirVictime(arrivee, libre, exclus);
                    if (victime >= 0) {
                        String nomVictime = confirme.get(victime).patient;
                        poserHold(victime, cfp.getConversationId());
                        Log.log(getLocalName(), "HOLD creneau " + victime + " : PREEMPTION possible de " + nomVictime
                                + " pour " + c.get("patient"));
                        ACLMessage p = cfp.createReply();
                        p.setPerformative(ACLMessage.PROPOSE);
                        p.setContent(Msg.build("creneau", "" + victime, "medecin", getLocalName(),
                                "preemption", "oui", "victime", nomVictime));
                        testLenteur();
                        return p;
                    }
                }

                if (libre < 0) {
                    String raison = urgence == Urgence.HAUTE ? "planning_sature" : "planning_plein";
                    Log.log(getLocalName(), "refuse " + c.get("patient") + " : " + raison.replace('_', ' '));
                    throw new RefuseException("raison=" + raison);
                }
                poserHold(libre, cfp.getConversationId());
                Log.log(getLocalName(), "HOLD creneau " + libre + " pour " + c.get("patient"));
                ACLMessage p = cfp.createReply();
                p.setPerformative(ACLMessage.PROPOSE);
                p.setContent(Msg.build("creneau", "" + libre, "medecin", getLocalName()));
                testLenteur();
                return p;
            }

            @Override
            // Le coordinateur retient notre offre : le HOLD devient un rendez-vous confirme
            // (en cas de preemption, le patient urgent remplace la victime).
            protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {
                Map<String, String> c = Msg.parse(cfp);
                Map<String, String> p = Msg.parse(propose);
                int creneau = Msg.getInt(p, "creneau", -1);
                if (!cfp.getConversationId().equals(hold.get(creneau))) {
                    throw new FailureException("raison=hold_expire");
                }
                hold.remove(creneau);

                boolean preemption = "oui".equals(p.get("preemption"));
                String victime = null;
                if (preemption) {
                    Rdv ancien = confirme.get(creneau);
                    if (ancien == null || !ancien.patient.equals(p.get("victime"))) {
                        throw new FailureException("raison=victime_changee");
                    }
                    victime = ancien.patient;
                }
                confirme.put(creneau, new Rdv(c.get("patient"), Urgence.de(c.get("urgence")),
                        "oui".equals(c.get("deplace")), compteur++));
                Log.log(getLocalName(), (preemption ? "CONFIRME (preemption de " + victime + ") creneau " : "CONFIRME creneau ")
                        + creneau + " pour " + c.get("patient"));

                ACLMessage inf = accept.createReply();
                inf.setPerformative(ACLMessage.INFORM);
                inf.setContent(preemption
                        ? Msg.build("creneau", "" + creneau, "medecin", getLocalName(), "victime", victime)
                        : Msg.build("creneau", "" + creneau, "medecin", getLocalName()));
                return inf;
            }

            @Override
            // Offre non retenue : on libere le HOLD (le rendez-vous de la victime, lui, n'a jamais bouge).
            protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
                int creneau = Msg.getInt(Msg.parse(propose), "creneau", -1);
                if (cfp.getConversationId().equals(hold.get(creneau))) {
                    hold.remove(creneau);
                    Log.log(getLocalName(), "HOLD creneau " + creneau + " libere");
                }
            }
        });

        // M6 (annuler), restaurer, agenda, et commande devenir_absent
        addBehaviour(new AchieveREResponder(this, MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST))) {
            @Override
            protected ACLMessage handleRequest(ACLMessage req) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(req);
                String action = c.get("action");
                ACLMessage rep = req.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                if ("annuler".equals(action)) {
                    int creneau = Msg.getInt(c, "creneau", -1);
                    Rdv r = confirme.get(creneau);
                    if (r != null && r.patient.equals(c.get("patient"))) {
                        confirme.remove(creneau);
                        Log.log(getLocalName(), "creneau " + creneau + " libere (annulation " + c.get("patient") + ")");
                    }
                    rep.setContent(Msg.build("statut", "annulee"));
                } else if ("restaurer".equals(action)) {
                    // retour arriere d'une preemption : le patient deplace retrouve son creneau
                    int creneau = Msg.getInt(c, "creneau", -1);
                    confirme.put(creneau, new Rdv(c.get("patient"), Urgence.de(c.get("urgence")), false, compteur++));
                    Log.log(getLocalName(), "creneau " + creneau + " restaure pour " + c.get("patient"));
                    rep.setContent(Msg.build("statut", "restaure"));
                } else if ("agenda".equals(action)) {
                    afficherAgenda();
                    rep.setContent(Msg.build("statut", "ok"));
                } else if ("devenir_absent".equals(action)) {
                    devenirAbsent();
                    rep.setContent(Msg.build("statut", "absent"));
                } else if ("revenir".equals(action)) {
                    revenir();
                    rep.setContent(Msg.build("statut", "disponible"));
                } else {
                    throw new NotUnderstoodException("action inconnue : " + action);
                }
                return rep;
            }
        });
    }

    /**
     * Choisit la victime d'une preemption : un patient d'urgence normale, jamais deja deplace,
     * dont le creneau est assez proche (et meilleur que le creneau libre eventuel), non retenu par un HOLD.
     * Parmi eux : le rendez-vous le plus recent.
     */
    private int choisirVictime(int arrivee, int libre, Set<Integer> exclus) {
        int meilleur = -1;
        long seqMax = -1;
        for (Map.Entry<Integer, Rdv> e : confirme.entrySet()) {
            int s = e.getKey();
            Rdv r = e.getValue();
            if (s < arrivee || s > arrivee + Slots.DELAI_URGENCE) continue;
            if (libre >= 0 && s >= libre) continue;
            if (exclus.contains(s) || hold.containsKey(s)) continue;
            if (r.urgence != Urgence.NORMALE || r.deplace) continue;
            if (r.seq > seqMax) { seqMax = r.seq; meilleur = s; }
        }
        return meilleur;
    }

    /** HOLD temporaire : libere automatiquement s'il n'est pas confirme (D2). */
    private void poserHold(final int creneau, final String conv) {
        hold.put(creneau, conv);
        addBehaviour(new WakerBehaviour(this, HOLD_MS) {
            @Override
            protected void onWake() {
                if (conv.equals(hold.get(creneau))) {
                    hold.remove(creneau);
                    Log.log(getLocalName(), "HOLD creneau " + creneau + " expire");
                }
            }
        });
    }

    /** Crochet de test : un medecin "lent" met 3 s a repondre a un CFP (-Dclinique.test.medecinLent=dr_leroy). */
    private void testLenteur() {
        if (getLocalName().equals(System.getProperty("clinique.test.medecinLent")))
            try { Thread.sleep(3000); } catch (InterruptedException e) { /* ignore */ }
    }

    /** Ligne de diagnostic lue par les tests : AGENDA nom : creneau=patient, ... */
    private void afficherAgenda() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Rdv> e : new TreeMap<>(confirme).entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue().patient);
        }
        Log.log(getLocalName(), "AGENDA " + getLocalName() + " : " + sb);
    }

    /**
     * V2 : retour du medecin. Il se reinscrit au DF (donc redevient candidat pour les NOUVELLES demandes) et previent
     * le coordinateur (M7, statut=disponible). Il ne reprend pas ses anciens patients : ils ont ete replanifies.
     */
    private void revenir() {
        if (!absent) return;
        absent = false;
        Df.inscrire(this, Msg.SVC_MEDECIN, specialite);
        Log.log(getLocalName(), "REVIENT : de nouveau disponible (agenda vide)");
        AID coord = Df.un(this, Msg.SVC_COORD);
        if (coord != null) {
            ACLMessage m = new ACLMessage(ACLMessage.INFORM);
            m.addReceiver(coord);
            m.setOntology(Msg.ONTO_ABSENCE);
            m.setContent(Msg.build("medecin", getLocalName(), "statut", "disponible", "patients", ""));
            send(m);
        }
    }

    /** M7 : se retire du DF, refuse tout CFP et previent directement le coordinateur. */
    private void devenirAbsent() {
        if (absent) return;
        absent = true;
        Df.retirer(this);
        StringBuilder patients = new StringBuilder();
        for (Rdv r : confirme.values()) {
            if (patients.length() > 0) patients.append(',');
            patients.append(r.patient);
        }
        Log.log(getLocalName(), "DEVIENT ABSENT ; patients concernes : " + (patients.length() == 0 ? "aucun" : patients));
        AID coord = Df.un(this, Msg.SVC_COORD);
        if (coord != null) {
            ACLMessage m = new ACLMessage(ACLMessage.INFORM);
            m.addReceiver(coord);
            m.setOntology(Msg.ONTO_ABSENCE);
            m.setContent(Msg.build("medecin", getLocalName(), "statut", "absent", "patients", patients.toString()));
            send(m);
        }
        confirme.clear();
        hold.clear();
    }

    @Override
    protected void takeDown() { if (!absent) Df.retirer(this); }
}
