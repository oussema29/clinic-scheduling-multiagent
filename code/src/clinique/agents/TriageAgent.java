package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.triage.TriageRegles;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.Map;

/** M1 -> M2. Recoit un texte libre, repond specialite + urgence (V0 : mots-cles). */
public class TriageAgent extends Agent {

    @Override
    protected void setup() {
        Df.inscrire(this, Msg.SVC_TRIAGE, "triage");
        final MessageTemplate tpl = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchOntology(Msg.ONTO_TRIAGE));

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            // Pour chaque M1 recu : classe le texte avec les regles, puis repond M2 au patient.
            public void action() {
                ACLMessage m = myAgent.receive(tpl);
                if (m == null) { block(); return; }

                Map<String, String> c = Msg.parse(m);
                TriageRegles.Resultat r = TriageRegles.classer(c.get("texte"));

                ACLMessage rep = m.createReply();
                rep.setPerformative(ACLMessage.INFORM);
                rep.setContent(Msg.build(
                        "specialite", r.specialite,
                        "urgence", r.urgence.code(),
                        "justification", r.justification,
                        "source", "regles"));
                myAgent.send(rep);
                Log.log(getLocalName(), m.getSender().getLocalName() + " -> " + r.specialite
                        + " / urgence " + r.urgence.code() + " (" + r.justification + ")");
            }
        });
    }

    @Override
    protected void takeDown() { Df.retirer(this); }
}
