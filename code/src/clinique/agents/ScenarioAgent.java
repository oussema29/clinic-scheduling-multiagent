package clinique.agents;

import clinique.common.Df;
import clinique.common.Msg;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import java.util.List;

/**
 * Joue un scenario : liste d'etapes (delai, destinataire, contenu). Sert a declencher l'absence d'un medecin
 * et le bilan. (Deviendra le ScenarioRunner de V3.)
 */
public class ScenarioAgent extends Agent {

    public static final class Etape {
        final long delaiMs; final String destinataire; final String ontologie; final String contenu;
        /** destinataire : nom local d'un agent, ou "coordination" pour le coordinateur. */
        public Etape(long delaiMs, String destinataire, String ontologie, String contenu) {
            this.delaiMs = delaiMs; this.destinataire = destinataire; this.ontologie = ontologie; this.contenu = contenu;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        List<Etape> etapes = (List<Etape>) getArguments()[0];
        for (final Etape e : etapes) {
            addBehaviour(new WakerBehaviour(this, e.delaiMs) {
                @Override
                protected void onWake() {
                    AID dest = "coordination".equals(e.destinataire)
                            ? Df.un(myAgent, Msg.SVC_COORD)
                            : new AID(e.destinataire, AID.ISLOCALNAME);
                    if (dest == null) return;
                    ACLMessage m = new ACLMessage(ACLMessage.REQUEST);
                    m.addReceiver(dest);
                    m.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
                    if (e.ontologie != null) m.setOntology(e.ontologie);
                    m.setContent(e.contenu);
                    myAgent.send(m);
                }
            });
        }
    }
}
