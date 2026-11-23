package clinique.eval;

import clinique.common.Df;
import clinique.common.Log;
import clinique.common.Msg;
import clinique.common.Urgence;
import clinique.triage.TriageRegles;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ProposeResponder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Le ScenarioRunner (V3) : il JOUE une journee de maniere reproductible.
 *
 * 1. Il fabrique N patients avec une graine : la meme graine donne toujours les memes patients.
 * 2. Il simule ces patients : il envoie leurs demandes (M3) a la place de N agents PatientAgent, ce qui fixe l'ordre
 *    d'arrivee (avec de vrais agents, l'ordre dependrait du timing des threads). Le texte de chaque patient passe par
 *    les memes regles de triage que le TriageAgent.
 * 3. Les demandes arrivent par LOTS : un lot par creneau d'arrivee. Il envoie un lot, puis interroge le coordinateur
 *    ("es-tu au repos ?") jusqu'a ce que tout le lot soit traite, avant d'envoyer le lot suivant.
 * 4. Il declenche l'absence puis le retour d'un medecin a des moments fixes.
 * 5. A la fin, il demande au Monitor de conclure ; Main arrete alors la plateforme.
 * Il repond aussi, a la place des patients, aux propositions de compensation (FIPA Propose), avec la meme regle
 * que PatientAgent : accepte si le report ne depasse pas la tolerance du patient.
 *
 * Arguments : n, graine, nombre de creneaux d'arrivee, medecin qui s'absente (ou ""), lot de l'absence, lot du retour.
 */
public class ScenarioRunner extends Agent {
    private static final double PART_URGENCES = 0.20;
    private static final double PART_CARDIO = 0.60;

    // textes tires au hasard ; la specialite et l'urgence viennent ensuite du triage par regles
    private static final String[] CARDIO_NORMAL = {"Palpitations et tension elevee", "Essoufflement a l'effort", "Mal au coeur depuis ce matin"};
    private static final String[] CARDIO_URGENT = {"Douleur dans la poitrine", "Douleur dans la poitrine et difficulte a respirer"};
    private static final String[] GENERAL_NORMAL = {"Forte fievre et toux", "Fatigue et mal de gorge", "Mal de tete et rhume"};
    private static final String[] GENERAL_URGENT = {"Perte de connaissance ce matin", "Saignement abondant apres une chute"};

    private static final int BATCH = 0, REPOS = 1, CMD = 2, PAUSE = 3, FIN = 4;

    /** Un patient simule. */
    private static final class Profil {
        String nom, texte, specialite;
        Urgence urgence;
        int arrivee, tolerance;
    }

    /** Une etape du plan de la journee. */
    private static final class Action {
        final int type; final int lot; final String cible, commande; final long ms;
        Action(int type, int lot, String cible, String commande, long ms) {
            this.type = type; this.lot = lot; this.cible = cible; this.commande = commande; this.ms = ms;
        }
    }

    private final List<Profil> profils = new ArrayList<>();
    private final Map<String, Profil> parNom = new HashMap<>();
    private final List<Action> plan = new ArrayList<>();
    private AID coordinateur, monitor;
    private int compteur = 0;

    @Override
    protected void setup() {
        Object[] a = getArguments();
        int n = (Integer) a[0];
        long graine = ((Number) a[1]).longValue();
        int lots = (Integer) a[2];
        String absent = (String) a[3];
        int lotAbsence = (Integer) a[4], lotRetour = (Integer) a[5];

        Campagne.termine = false;
        generer(n, graine, lots);
        for (int t = 0; t < lots; t++) {
            plan.add(new Action(BATCH, t, null, null, 0));
            plan.add(new Action(REPOS, 0, null, null, 0));
            if (!absent.isEmpty() && t == lotAbsence) {
                plan.add(new Action(CMD, 0, absent, "devenir_absent", 0));
                plan.add(new Action(PAUSE, 0, null, null, 40));
                plan.add(new Action(REPOS, 0, null, null, 0));
            }
            if (!absent.isEmpty() && t == lotRetour) {
                plan.add(new Action(CMD, 0, absent, "revenir", 0));
                plan.add(new Action(PAUSE, 0, null, null, 40));
                plan.add(new Action(REPOS, 0, null, null, 0));
            }
        }
        plan.add(new Action(FIN, 0, null, null, 0));

        coordinateur = Df.un(this, Msg.SVC_COORD);
        monitor = Df.un(this, Msg.SVC_MONITOR);
        if (coordinateur == null || monitor == null) {
            Log.toujours(getLocalName(), "coordinateur ou monitor introuvable : campagne impossible");
            Campagne.termine = true;
            return;
        }

        // Compensation : le coordinateur propose au "patient" (donc a nous) un nouveau creneau.
        addBehaviour(new ProposeResponder(this, MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_PROPOSE),
                MessageTemplate.MatchPerformative(ACLMessage.PROPOSE))) {
            @Override
            protected ACLMessage prepareResponse(ACLMessage propose) throws NotUnderstoodException, RefuseException {
                Map<String, String> c = Msg.parse(propose);
                Profil p = parNom.get(c.get("patient"));
                int nouveau = Msg.getInt(c, "nouveau", -1), ancien = Msg.getInt(c, "ancien", -1);
                boolean accepte = p != null && nouveau >= 0 && nouveau - ancien <= p.tolerance;
                ACLMessage rep = propose.createReply();
                rep.setPerformative(accepte ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                return rep;
            }
        });
        addBehaviour(new Pilote());
    }

    /** Fabrique les patients : la meme graine donne la meme journee. Tries par creneau d'arrivee (ordre stable). */
    private void generer(int n, long graine, int lots) {
        Random r = new Random(graine);
        for (int i = 1; i <= n; i++) {
            boolean urgent = r.nextDouble() < PART_URGENCES;
            boolean cardio = r.nextDouble() < PART_CARDIO;
            String[] pool = cardio ? (urgent ? CARDIO_URGENT : CARDIO_NORMAL) : (urgent ? GENERAL_URGENT : GENERAL_NORMAL);
            Profil p = new Profil();
            p.nom = "P" + i;
            p.texte = pool[r.nextInt(pool.length)];
            p.arrivee = r.nextInt(lots);
            p.tolerance = r.nextInt(5);
            TriageRegles.Resultat t = TriageRegles.classer(p.texte);     // les memes regles que le TriageAgent
            p.specialite = t.specialite;
            p.urgence = t.urgence;
            profils.add(p);
            parNom.put(p.nom, p);
        }
        profils.sort((x, y) -> Integer.compare(x.arrivee, y.arrivee));   // tri stable : l'ordre de generation departage
    }

    // ------------------------------------------------------------------ envois

    /** Envoie les demandes (M3) des patients dont le creneau d'arrivee est "lot", dans un ordre fixe. */
    private void envoyerLot(int lot) {
        for (Profil p : profils) {
            if (p.arrivee != lot) continue;
            ACLMessage d = new ACLMessage(ACLMessage.REQUEST);
            d.addReceiver(coordinateur);
            d.setOntology(Msg.ONTO_DEMANDE);
            d.setConversationId("rdv-" + p.nom);
            d.setContent(Msg.build("patient", p.nom, "specialite", p.specialite, "urgence", p.urgence.code(),
                    "arrivee", "" + p.arrivee));
            send(d);
        }
    }

    /** "Es-tu au repos ?" : le coordinateur repond oui quand la file est vide et aucune negociation n'est en cours. */
    private String envoyerRepos() {
        String conv = "etat-" + (compteur++);
        ACLMessage q = new ACLMessage(ACLMessage.REQUEST);
        q.addReceiver(coordinateur);
        q.setOntology(Msg.ONTO_ETAT);
        q.setConversationId(conv);
        q.setContent(Msg.build("question", "repos"));
        send(q);
        return conv;
    }

    private String envoyerCommande(Action a) {
        String conv = "cmd-" + (compteur++);
        ACLMessage m = new ACLMessage(ACLMessage.REQUEST);
        m.addReceiver(new AID(a.cible, AID.ISLOCALNAME));
        m.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        m.setConversationId(conv);
        m.setContent(Msg.build("action", a.commande));
        send(m);
        return conv;
    }

    private String envoyerFin() {
        String conv = "fin-" + (compteur++);
        ACLMessage m = new ACLMessage(ACLMessage.REQUEST);
        m.addReceiver(monitor);
        m.setOntology(Msg.ONTO_FIN);
        m.setConversationId(conv);
        m.setContent(Msg.build("action", "conclure"));
        send(m);
        return conv;
    }

    // ------------------------------------------------------------------ le pilote

    /**
     * Execute le plan etape par etape, sans jamais bloquer l'agent : une etape qui attend une reponse fait block()
     * et reprend quand la reponse arrive. Les envois de lots se suivent immediatement.
     */
    private class Pilote extends SimpleBehaviour {
        private int i = 0;
        private int attente = 0;          // 0 : rien, 1 : reponse "repos", 2 : reponse d'une commande, 3 : reponse du Monitor
        private String conv;
        private long reveil = 0;
        private boolean fini = false;

        Pilote() { super(ScenarioRunner.this); }

        @Override
        public void action() {
            long maintenant = System.currentTimeMillis();
            if (reveil > maintenant) { block(reveil - maintenant); return; }

            if (attente != 0) {
                ACLMessage m = myAgent.receive(MessageTemplate.MatchConversationId(conv));
                if (m == null) { block(); return; }
                if (attente == 1) {
                    boolean repos = "oui".equals(Msg.parse(m).get("repos"));
                    attente = 0;
                    if (repos) i++;                      // lot termine : etape suivante
                    else reveil = maintenant + 10;       // pas encore : on reposera la question
                } else if (attente == 2) {
                    attente = 0; i++;
                } else {
                    Campagne.termine = true;
                    fini = true;
                }
                return;
            }

            if (i >= plan.size()) { fini = true; return; }
            Action a = plan.get(i);
            switch (a.type) {
                case BATCH: envoyerLot(a.lot); i++; break;
                case REPOS: conv = envoyerRepos(); attente = 1; break;
                case CMD: conv = envoyerCommande(a); attente = 2; break;
                case PAUSE: reveil = maintenant + a.ms; i++; break;
                default: conv = envoyerFin(); attente = 3; break;
            }
        }

        @Override public boolean done() { return fini; }
    }
}
