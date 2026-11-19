package clinique.agents;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Slots;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ProposeResponder;
import java.util.Map;

/**
 * Un patient. Arguments : texte libre, creneau d'arrivee, delai d'envoi (ms), [tolerance en creneaux, defaut 3].
 *
 * V2 : sa conversation est un FSMBehaviour (machine a etats). Chaque etat attend un message et la TRANSITION
 * est decidee par le type du message recu (receive + block : jamais d'attente bloquante).
 *
 *   DEMANDE --> ATTENTE --> CONFIRME <--> A_REPLANIFIER   (medecin absent)
 *                  |           |  ^
 *                  |           v  |
 *                  |        DEPLACE ---> A_RAPPELER      (V1 : cede a une urgence, compensation refusee)
 *                  v
 *                ECHEC       (aussi depuis A_REPLANIFIER et DEPLACE)
 *
 * V1 : si son rendez-vous est cede a une urgence, il recoit une proposition (FIPA Propose) et l'evalue lui-meme :
 * il accepte si le report (nouveau - ancien) ne depasse pas sa tolerance. Le ProposeResponder tourne en parallele
 * de la machine a etats (conversation distincte "comp-...").
 */
public class PatientAgent extends Agent {
    // evenements = transitions de la machine a etats
    private static final int SUITE = 0, VERS_CONFIRME = 1, VERS_ECHEC = 2, VERS_REPLANIFIER = 3, VERS_DEPLACE = 4, VERS_RAPPELER = 5;

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

        // V1 : proposition de compensation (protocole Propose). Conversation distincte ("comp-..."), donc
        // la machine a etats ne la voit pas : seul ce responder la lit.
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

        // ---- V2 : la machine a etats
        FSMBehaviour fsm = new FSMBehaviour(this);
        fsm.registerFirstState(new EtatDemande(delai), "DEMANDE");
        fsm.registerState(new Etat("ATTENTE"), "ATTENTE");
        fsm.registerState(new Etat("CONFIRME"), "CONFIRME");
        fsm.registerState(new Etat("A_REPLANIFIER"), "A_REPLANIFIER");
        fsm.registerState(new Etat("DEPLACE"), "DEPLACE");
        fsm.registerLastState(new EtatFinal("A_RAPPELER"), "A_RAPPELER");
        fsm.registerLastState(new EtatFinal("ECHEC"), "ECHEC");

        fsm.registerTransition("DEMANDE", "ATTENTE", SUITE);
        fsm.registerTransition("ATTENTE", "CONFIRME", VERS_CONFIRME);
        fsm.registerTransition("ATTENTE", "ECHEC", VERS_ECHEC);
        fsm.registerTransition("ATTENTE", "A_REPLANIFIER", VERS_REPLANIFIER);
        fsm.registerTransition("CONFIRME", "A_REPLANIFIER", VERS_REPLANIFIER);
        fsm.registerTransition("CONFIRME", "DEPLACE", VERS_DEPLACE);
        fsm.registerTransition("A_REPLANIFIER", "CONFIRME", VERS_CONFIRME);
        fsm.registerTransition("A_REPLANIFIER", "ECHEC", VERS_ECHEC);
        fsm.registerTransition("DEPLACE", "CONFIRME", VERS_CONFIRME);
        fsm.registerTransition("DEPLACE", "A_RAPPELER", VERS_RAPPELER);
        fsm.registerTransition("DEPLACE", "ECHEC", VERS_ECHEC);
        fsm.registerTransition("DEPLACE", "A_REPLANIFIER", VERS_REPLANIFIER);
        addBehaviour(fsm);
    }

    /** Decision du patient face a un report : il accepte si le report ne depasse pas sa tolerance. */
    private boolean decider(int ancien, int nouveau) {
        return nouveau >= 0 && nouveau - ancien <= tolerance;
    }

    private static String nomEtat(String de, int evenement) {
        switch (de + "/" + evenement) {
            case "DEMANDE/0": return "ATTENTE";
            case "A_REPLANIFIER/3": case "ATTENTE/3": case "CONFIRME/3": case "DEPLACE/3": return "A_REPLANIFIER";
            case "ATTENTE/1": case "A_REPLANIFIER/1": case "DEPLACE/1": return "CONFIRME";
            case "CONFIRME/4": return "DEPLACE";
            case "DEPLACE/5": return "A_RAPPELER";
            default: return "ECHEC";
        }
    }

    // ------------------------------------------------------------------ etats

    /**
     * Etat DEMANDE : attend le delai d'arrivee, envoie M1 au triage, attend la reponse (M2), envoie M3 au coordinateur.
     */
    private class EtatDemande extends SimpleBehaviour {
        private final long debut;
        private int etape = 0;       // 0 = attendre l'heure d'arrivee, 1 = attendre le triage, 2 = fini
        private final MessageTemplate tpl = MessageTemplate.MatchConversationId("triage-" + getLocalName());

        EtatDemande(long delai) { super(PatientAgent.this); this.debut = System.currentTimeMillis() + delai; }

        @Override
        public void action() {
            if (etape == 0) {
                long reste = debut - System.currentTimeMillis();
                if (reste > 0) { block(reste); return; }
                envoyerAuTriage();
                etape = 1;
            }
            ACLMessage m = myAgent.receive(tpl);
            if (m == null) { block(); return; }
            envoyerDemande(Msg.parse(m));           // M3
            etape = 2;
        }

        @Override public boolean done() { return etape == 2; }
        @Override public int onEnd() { return SUITE; }
    }

    /** Etat "d'attente" : lit les messages du coordinateur et decide de la transition d'apres leur type. */
    private class Etat extends SimpleBehaviour {
        private final String nom;
        private final MessageTemplate tpl = MessageTemplate.MatchConversationId("rdv-" + getLocalName());
        private int evenement = -1;

        Etat(String nom) { super(PatientAgent.this); this.nom = nom; }

        @Override
        public void action() {
            ACLMessage m = myAgent.receive(tpl);
            if (m == null) { block(); return; }
            evenement = transition(nom, m);
        }

        @Override public boolean done() { return evenement >= 0; }

        @Override
        public int onEnd() {
            int e = evenement;
            evenement = -1;           // l'etat pourra etre traverse a nouveau (CONFIRME <-> A_REPLANIFIER)
            Log.log(getLocalName(), "etat " + nom + " -> " + nomEtat(nom, e));
            return e;
        }

        @Override public void reset() { super.reset(); evenement = -1; }
    }

    /** Etat final : le patient n'attend plus rien. */
    private class EtatFinal extends OneShotBehaviour {
        private final String nom;
        EtatFinal(String nom) { super(PatientAgent.this); this.nom = nom; }
        @Override public void action() { Log.log(getLocalName(), "etat final : " + nom); }
    }

    /** Selon l'etat courant et le message recu : affiche, puis retourne la transition (ou -1 pour ignorer). */
    private int transition(String etat, ACLMessage m) {
        Map<String, String> c = Msg.parse(m);
        String statut = c.get("statut");
        boolean echec = m.getPerformative() == ACLMessage.FAILURE;

        if (echec && !etat.equals("CONFIRME")) {
            Log.log(getLocalName(), "PAS DE RENDEZ-VOUS (" + c.get("raison") + ")");
            return VERS_ECHEC;
        }
        if ("confirme".equals(statut) && !etat.equals("CONFIRME")) {
            Log.log(getLocalName(), "RENDEZ-VOUS : " + c.get("medecin") + ", " + c.get("salle")
                    + ", creneau " + c.get("creneau") + " (attente " + Slots.attente(arrivee, Msg.getInt(c, "creneau", 0)) + ")");
            return VERS_CONFIRME;
        }
        if ("a_replanifier".equals(statut) && !etat.equals("A_REPLANIFIER")) {
            Log.log(getLocalName(), "son rendez-vous saute (" + c.get("raison") + "), en attente d'un nouveau");
            return VERS_REPLANIFIER;
        }
        if ("deplace".equals(statut) && etat.equals("CONFIRME")) {
            Log.log(getLocalName(), "DEPLACE : son creneau " + c.get("ancien") + " est cede a une urgence, il attend une proposition");
            return VERS_DEPLACE;
        }
        if ("a_rappeler".equals(statut) && etat.equals("DEPLACE")) {
            Log.log(getLocalName(), "A RAPPELER : il a decline la compensation (" + c.get("raison") + "), la clinique le recontactera");
            return VERS_RAPPELER;
        }
        return -1;   // message sans effet dans cet etat
    }

    // ------------------------------------------------------------------ messages

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

    /** M3 : demande de rendez-vous au coordinateur, avec la specialite et l'urgence decidees par le triage. */
    private void envoyerDemande(Map<String, String> triage) {
        AID coord = Df.un(this, Msg.SVC_COORD);
        if (coord == null) { Log.log(getLocalName(), "aucun coordinateur"); return; }
        ACLMessage d = new ACLMessage(ACLMessage.REQUEST);
        d.addReceiver(coord);
        d.setOntology(Msg.ONTO_DEMANDE);
        d.setConversationId(convRdv);
        d.setContent(Msg.build("patient", getLocalName(),
                "specialite", triage.get("specialite"), "urgence", triage.get("urgence"),
                "arrivee", "" + arrivee));
        send(d);
    }
}
