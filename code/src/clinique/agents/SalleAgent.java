package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import jade.core.Agent;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREResponder;
import java.util.HashMap;
import java.util.Map;

/**
 * Une salle de consultation. Agenda : creneau -> patient.
 * Actions (M5, M6) : reserver, annuler. (transferer arrive en V1.)
 * Un agent n'a qu'un thread : test-et-reservation est atomique sans verrou.
 */
public class SalleAgent extends Agent {
    private final Map<Integer, String> agenda = new HashMap<>();

    @Override
    protected void setup() {
        Df.inscrire(this, Msg.SVC_SALLE, "salle");
        MessageTemplate tpl = MessageTemplate.and(
                MessageTemplate.MatchProtocol(jade.domain.FIPANames.InteractionProtocol.FIPA_REQUEST),
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST));

        addBehaviour(new AchieveREResponder(this, tpl) {
            @Override
            // reserver : refuse si le creneau est pris, sinon le reserve. annuler : libere le creneau.
            // transferer (V1) : donne le creneau d'un patient a un autre. agenda : ligne de diagnostic.
            protected ACLMessage handleRequest(ACLMessage req) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(req);
                String action = c.get("action");
                int creneau = Msg.getInt(c, "creneau", -1);
                String patient = c.get("patient");

                ACLMessage rep = req.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                if ("reserver".equals(action)) {
                    // crochet de test : une salle "lente" met 1,5 s a repondre (-Dclinique.test.salleLente=salle1)
                    if (getLocalName().equals(System.getProperty("clinique.test.salleLente")))
                        try { Thread.sleep(1500); } catch (InterruptedException e) { /* ignore */ }
                    if (agenda.containsKey(creneau)) {
                        Log.log(getLocalName(), "refuse " + patient + " au creneau " + creneau + " (occupee)");
                        throw new RefuseException("raison=occupee");
                    }
                    agenda.put(creneau, patient);
                    Log.log(getLocalName(), "reservee au creneau " + creneau + " pour " + patient);
                    rep.setContent(Msg.build("statut", "reservee", "salle", getLocalName(), "creneau", "" + creneau));
                } else if ("annuler".equals(action)) {
                    if (patient != null && patient.equals(agenda.get(creneau))) {
                        agenda.remove(creneau);
                        Log.log(getLocalName(), "creneau " + creneau + " libere (annulation " + patient + ")");
                    }
                    rep.setContent(Msg.build("statut", "annulee"));
                } else if ("transferer".equals(action)) {
                    // V1 (preemption) : le creneau passe du patient deplace (de) au patient urgent (vers).
                    // Atomique : on verifie que le creneau appartient bien a "de" avant de le donner.
                    String de = c.get("de"), vers = c.get("vers");
                    if (Boolean.getBoolean("clinique.test.refuseTransfert")) {   // crochet de test : simule une panne de transfert
                        Log.log(getLocalName(), "(test) transfert refuse volontairement");
                        throw new RefuseException("raison=panne_simulee");
                    }
                    if (de == null || vers == null || !de.equals(agenda.get(creneau))) {
                        Log.log(getLocalName(), "refuse le transfert du creneau " + creneau + " (" + de + " -> " + vers + ")");
                        throw new RefuseException("raison=pas_au_bon_patient");
                    }
                    agenda.put(creneau, vers);
                    Log.log(getLocalName(), "creneau " + creneau + " transfere de " + de + " a " + vers);
                    rep.setContent(Msg.build("statut", "transferee", "salle", getLocalName(), "creneau", "" + creneau));
                } else if ("agenda".equals(action)) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(agenda).entrySet()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                    Log.log(getLocalName(), "AGENDA " + getLocalName() + " : " + sb);
                    rep.setContent(Msg.build("statut", "ok", "agenda", sb.toString().replace(" ", "").replace('=', ':')));
                } else {
                    throw new NotUnderstoodException("action inconnue : " + action);
                }
                return rep; // un INFORM ici est la reponse finale : le protocole se termine
            }
        });
    }

    @Override
    protected void takeDown() { Df.retirer(this); }
}
