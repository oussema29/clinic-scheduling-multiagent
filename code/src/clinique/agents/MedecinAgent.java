package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
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
import java.util.Map;

/**
 * Un medecin. Arguments : specialite, capacite (nombre de creneaux ouverts : 0 a capacite-1).
 * Agenda : creneau -> patient confirme ; holds : creneau -> conversation-id (D2).
 * - Contract-Net (M4) : propose son plus proche creneau libre, le met en HOLD ;
 *   ACCEPT -> confirme, REJECT ou expiration -> libere.
 * - Request (M6) : annuler ; commande devenir_absent (M7).
 */
public class MedecinAgent extends Agent {
    private static final long HOLD_MS = 5000;

    private String specialite;
    private int capacite;
    private boolean absent = false;
    private final Map<Integer, String> confirme = new HashMap<>();  // creneau -> patient
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
            // Appel d'offres : propose le plus proche creneau libre et le met en HOLD (reservation provisoire).
            protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {
                if (absent) throw new RefuseException("raison=absent");
                Map<String, String> c = Msg.parse(cfp);
                int arrivee = Msg.getInt(c, "arrivee", 0);
                java.util.Set<Integer> exclus = new java.util.HashSet<>();
                if (c.get("exclu") != null && !c.get("exclu").isEmpty())
                    for (String x : c.get("exclu").split(",")) exclus.add(Integer.parseInt(x.trim()));

                int creneau = -1;
                for (int s = Math.max(0, arrivee); s < capacite; s++) {
                    if (!exclus.contains(s) && !confirme.containsKey(s) && !hold.containsKey(s)) { creneau = s; break; }
                }
                if (creneau < 0) {
                    Log.log(getLocalName(), "refuse " + c.get("patient") + " : planning plein");
                    throw new RefuseException("raison=planning_plein");
                }
                poserHold(creneau, cfp.getConversationId());
                Log.log(getLocalName(), "HOLD creneau " + creneau + " pour " + c.get("patient"));
                ACLMessage p = cfp.createReply();
                p.setPerformative(ACLMessage.PROPOSE);
                p.setContent(Msg.build("creneau", "" + creneau, "medecin", getLocalName()));
                return p;
            }

            @Override
            // Le coordinateur retient notre offre : le HOLD devient un rendez-vous confirme.
            protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {
                Map<String, String> c = Msg.parse(cfp);
                int creneau = Msg.getInt(Msg.parse(propose), "creneau", -1);
                if (!cfp.getConversationId().equals(hold.get(creneau))) {
                    throw new FailureException("raison=hold_expire");
                }
                hold.remove(creneau);
                confirme.put(creneau, c.get("patient"));
                Log.log(getLocalName(), "CONFIRME creneau " + creneau + " pour " + c.get("patient"));
                ACLMessage inf = accept.createReply();
                inf.setPerformative(ACLMessage.INFORM);
                inf.setContent(Msg.build("creneau", "" + creneau, "medecin", getLocalName()));
                return inf;
            }

            @Override
            // Offre non retenue : on libere le creneau mis en HOLD.
            protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
                int creneau = Msg.getInt(Msg.parse(propose), "creneau", -1);
                if (cfp.getConversationId().equals(hold.get(creneau))) {
                    hold.remove(creneau);
                    Log.log(getLocalName(), "HOLD creneau " + creneau + " libere");
                }
            }
        });

        // M6 (annuler) et commande devenir_absent
        addBehaviour(new AchieveREResponder(this, MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST))) {
            @Override
            // Commandes recues : annuler un creneau (M6) ou devenir absent (declenche M7).
            protected ACLMessage handleRequest(ACLMessage req) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(req);
                String action = c.get("action");
                ACLMessage rep = req.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                if ("annuler".equals(action)) {
                    int creneau = Msg.getInt(c, "creneau", -1);
                    if (c.get("patient") != null && c.get("patient").equals(confirme.get(creneau))) {
                        confirme.remove(creneau);
                        Log.log(getLocalName(), "creneau " + creneau + " libere (annulation " + c.get("patient") + ")");
                    }
                    rep.setContent(Msg.build("statut", "annulee"));
                } else if ("devenir_absent".equals(action)) {
                    devenirAbsent();
                    rep.setContent(Msg.build("statut", "absent"));
                } else {
                    throw new NotUnderstoodException("action inconnue : " + action);
                }
                return rep;
            }
        });
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

    /** M7 : se retire du DF, refuse tout CFP et previent directement le coordinateur. */
    private void devenirAbsent() {
        if (absent) return;
        absent = true;
        Df.retirer(this);
        StringBuilder patients = new StringBuilder();
        for (String p : confirme.values()) {
            if (patients.length() > 0) patients.append(',');
            patients.append(p);
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
