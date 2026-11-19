package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ProposeResponder;
import java.util.Map;

/**
 * Un patient. Arguments : texte libre, creneau d'arrivee, delai d'envoi (ms), [tolerance en creneaux, defaut 3].
 * V1 : si son rendez-vous est cede a une urgence, il recoit une proposition (FIPA Propose) et l'evalue lui-meme :
 * il accepte si le report (nouveau - ancien) ne depasse pas sa tolerance.
 * Deroulement : M1 vers le triage, M2 recu, M3 vers le coordinateur, puis attend INFORM ou FAILURE.
 * Un WakerBehaviour declenche l'envoi, un CyclicBehaviour lit les reponses (receive + block).
 */
public class PatientAgent extends Agent {
    private String texte;
    private int arrivee;
    private String convTriage;
    private String convRdv;
    private int tolerance = 3;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        texte = (String) a[0];
        arrivee = (Integer) a[1];
        long delai = (Long) a[2];
        if (a.length > 3) tolerance = (Integer) a[3];
        convTriage = "triage-" + getLocalName();
        convRdv = "rdv-" + getLocalName();

        addBehaviour(new WakerBehaviour(this, delai) {
            @Override
            protected void onWake() { envoyerAuTriage(); }
        });

        // V1 : proposition de compensation (protocole Propose). Conversation distincte ("comp-..."), donc
        // le CyclicBehaviour ci-dessous ne la voit pas : seul ce responder la lit.
        // crochet de test : un patient "muet" ne repond jamais aux propositions (-Dclinique.test.patientMuet=P3)
        if (!getLocalName().equals(System.getProperty("clinique.test.patientMuet")))
        addBehaviour(new ProposeResponder(this, MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE))) {
            @Override
            protected ACLMessage prepareResponse(ACLMessage propose) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(propose);
                int nouveau = Msg.getInt(c, "nouveau", -1), ancien = Msg.getInt(c, "ancien", -1);
                // crochet de test : un patient "lent" met 1,5 s a repondre (-Dclinique.test.patientLent=P3)
                if (getLocalName().equals(System.getProperty("clinique.test.patientLent")))
                    try { Thread.sleep(1500); } catch (InterruptedException e) { /* ignore */ }
                boolean accepte = decider(ancien, nouveau);
                ACLMessage rep = propose.createReply();
                rep.setPerformative(accepte ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                Log.log(getLocalName(), "evalue la proposition : creneau " + ancien + " -> " + nouveau
                        + " (report " + (nouveau - ancien) + ", tolerance " + tolerance + ") : " + (accepte ? "ACCEPTE" : "REFUSE"));
                return rep;
            }
        });

        // On ne lit que ce qui nous concerne : nos deux conversations.
        final MessageTemplate tpl = MessageTemplate.or(
                MessageTemplate.MatchConversationId(convTriage),
                MessageTemplate.MatchConversationId(convRdv));
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage m = myAgent.receive(tpl);
                if (m == null) { block(); return; }
                traiter(m);
            }
        });
    }

    /** Decision du patient face a un report : il accepte si le report ne depasse pas sa tolerance. */
    private boolean decider(int ancien, int nouveau) {
        return nouveau >= 0 && nouveau - ancien <= tolerance;
    }

    /** M1 : envoie le texte libre au triage (trouve via le DF). C'est le debut de toute la chaine. */
    private void envoyerAuTriage() {
        AID triage = Df.un(this, Msg.SVC_TRIAGE);
        if (triage == null) { Log.log(getLocalName(), "aucun agent de triage"); return; }
        ACLMessage m = new ACLMessage(ACLMessage.REQUEST);
        m.addReceiver(triage);
        m.setOntology(Msg.ONTO_TRIAGE);
        m.setConversationId(convTriage);
        m.setContent(Msg.build("texte", texte));
        send(m);
        Log.log(getLocalName(), "dit : \"" + texte + "\"");
    }

    /**
     * Traite un message recu :
     *  - reponse du triage (M2) -> envoie la demande de rendez-vous au coordinateur (M3) ;
     *  - reponse du coordinateur -> affiche le rendez-vous, l'echec ou la demande de replanification.
     */
    private void traiter(ACLMessage m) {
        Map<String, String> c = Msg.parse(m);

        if (convTriage.equals(m.getConversationId())) {               // M2
            AID coord = Df.un(this, Msg.SVC_COORD);
            if (coord == null) { Log.log(getLocalName(), "aucun coordinateur"); return; }
            ACLMessage d = new ACLMessage(ACLMessage.REQUEST);        // M3
            d.addReceiver(coord);
            d.setOntology(Msg.ONTO_DEMANDE);
            d.setConversationId(convRdv);
            d.setContent(Msg.build("patient", getLocalName(),
                    "specialite", c.get("specialite"), "urgence", c.get("urgence"),
                    "arrivee", "" + arrivee));
            send(d);
            return;
        }

        if (m.getPerformative() == ACLMessage.FAILURE) {
            Log.log(getLocalName(), "PAS DE RENDEZ-VOUS (" + c.get("raison") + ")");
        } else if ("a_replanifier".equals(c.get("statut"))) {
            Log.log(getLocalName(), "son rendez-vous saute (" + c.get("raison") + "), en attente d'un nouveau");
        } else if ("deplace".equals(c.get("statut"))) {
            Log.log(getLocalName(), "DEPLACE : son creneau " + c.get("ancien") + " est cede a une urgence, il attend une proposition");
        } else if ("a_rappeler".equals(c.get("statut"))) {
            Log.log(getLocalName(), "A RAPPELER : il a decline la compensation (" + c.get("raison") + "), la clinique le recontactera");
        } else {
            Log.log(getLocalName(), "RENDEZ-VOUS : " + c.get("medecin") + ", " + c.get("salle")
                    + ", creneau " + c.get("creneau") + " (attente " + Slots.attente(arrivee, Msg.getInt(c, "creneau", 0)) + ")");
        }
    }
}
