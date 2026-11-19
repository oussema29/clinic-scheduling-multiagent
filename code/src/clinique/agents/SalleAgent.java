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
            protected ACLMessage handleRequest(ACLMessage req) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(req);
                String action = c.get("action");
                int creneau = Msg.getInt(c, "creneau", -1);
                String patient = c.get("patient");

                ACLMessage rep = req.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                if ("reserver".equals(action)) {
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
