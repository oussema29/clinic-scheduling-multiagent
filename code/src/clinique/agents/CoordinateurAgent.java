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
import jade.proto.ProposeInitiator;
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
 * Le coordinateur (V0 + V1).
 *  - Un CyclicBehaviour recoit les demandes (M3), les absences (M7) et la commande de bilan.
 *  - Les demandes vont dans une file prioritaire : urgence haute d'abord, puis ordre d'arrivee (D6).
 *  - Une seule negociation a la fois : ContractNetInitiator vers les medecins (M4),
 *    puis AchieveREInitiator vers les salles (M5), l'une apres l'autre (D4).
 *  - La reponse finale au patient (INFORM / FAILURE) est envoyee ici meme (D1).
 *  - Si aucune salle n'est libre : annulation chez le medecin (M6) et une nouvelle tentative qui exclut ce creneau (exclusions cumulees, 5 essais au plus) (D3).
 *  V1 : une urgence haute sans creneau libre assez proche peut PREEMPTER un patient normal (propose par le medecin).
 *       Le creneau de salle est transfere, puis le patient deplace est COMPENSE : on lui trouve un autre creneau,
 *       on le reserve, puis on le lui propose (Propose). S'il refuse, le creneau est rendu.
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
        boolean replanification = false;      // V2 : demande issue de l'absence d'un medecin
        int creneauOrigine = Integer.MAX_VALUE;   // V2 : creneau perdu (sert a trier la replanification)
        boolean deplace = false;        // V1 : deja deplace une fois (ne peut plus etre une victime)
        boolean compensation = false;   // V1 : demande de compensation d'un patient deplace
        int ancien = -1;                // V1 : creneau perdu par le patient deplace
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
            Comparator.<Demande>comparingInt(d -> -d.urgence.poids)
                    .thenComparingInt(d -> d.compensation ? 0 : 1)   // V1 : a urgence egale, les compensations d'abord
                    .thenComparingInt(d -> d.creneauOrigine)         // V2 : puis les patients qui avaient un creneau, du plus ancien au plus recent
                    .thenComparingLong(d -> d.seq));
    private final Map<String, Rdv> registre = new TreeMap<>();     // patient -> rdv
    private final Map<String, Integer> echecs = new TreeMap<>();   // raison -> nombre
    private long compteur = 0;
    private int nbDeplaces = 0;     // V1 : nombre de preemptions
    private final Set<String> absents = new HashSet<>();   // medecins signales absents (M7) : jamais confirmes apres coup
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
        if ("disponible".equals(c.get("statut"))) {     // V2 : retour du medecin
            absents.remove(medecin);
            Log.log(getLocalName(), "RETOUR de " + medecin + " : de nouveau consulte pour les nouvelles demandes");
            return;
        }
        absents.add(medecin);
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
                d.deplace = r.demande.deplace;
                d.replanification = true;
                d.creneauOrigine = r.creneau;
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
                "arrivee", "" + d.arrivee, "exclu", d.exclus.toString().replaceAll("[\\[\\] ]", ""),
                "deplace", d.deplace ? "oui" : "non"));

        addBehaviour(new ContractNetInitiator(this, cfp) {
            private AID gagnant;
            private int creneau;
            private boolean preemption = false;
            private String victime = null;

            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            // Appelee quand tous les medecins ont repondu (ou delai ecoule).
            // Regle : un creneau libre assez proche l'emporte toujours ; sinon (urgence haute) la preemption la moins
            // couteuse ; sinon un creneau libre meme tardif ; sinon echec.
            protected void handleAllResponses(Vector responses, Vector acceptances) {
                ACLMessage libre = null, preempt = null;
                int libreCreneau = Integer.MAX_VALUE, preCreneau = Integer.MAX_VALUE, preCout = Integer.MAX_VALUE;
                boolean plein = false, sature = false;
                for (Object o : responses) {
                    ACLMessage rep = (ACLMessage) o;
                    Map<String, String> c = Msg.parse(rep);
                    if (rep.getPerformative() != ACLMessage.PROPOSE) {
                        if ("planning_plein".equals(c.get("raison"))) plein = true;
                        if ("planning_sature".equals(c.get("raison"))) sature = true;
                        continue;
                    }
                    int cr = Msg.getInt(c, "creneau", Integer.MAX_VALUE);
                    if ("oui".equals(c.get("preemption"))) {
                        // cout = poids d'urgence de la victime ; puis creneau le plus proche ; puis ordre alphabetique
                        Rdv rv = registre.get(c.get("victime"));
                        int cout = rv == null ? Integer.MAX_VALUE : rv.demande.urgence.poids;
                        if (preempt == null || cout < preCout || (cout == preCout && (cr < preCreneau
                                || (cr == preCreneau && rep.getSender().compareTo(preempt.getSender()) < 0)))) {
                            preempt = rep; preCout = cout; preCreneau = cr;
                        }
                    } else if (libre == null || cr < libreCreneau
                            || (cr == libreCreneau && rep.getSender().compareTo(libre.getSender()) < 0)) {
                        libre = rep; libreCreneau = cr;
                    }
                }

                boolean urgent = d.urgence == Urgence.HAUTE;
                ACLMessage choix;
                if (urgent && libre != null && libreCreneau <= d.arrivee + Slots.DELAI_URGENCE) choix = libre;
                else if (urgent && preempt != null) choix = preempt;
                else choix = libre;

                if (choix == null) {
                    echec(d, sature ? "planning_sature" : plein ? "planning_plein" : "aucun_medecin_disponible");
                    return;
                }
                for (Object o : responses) {
                    ACLMessage rep = (ACLMessage) o;
                    if (rep.getPerformative() != ACLMessage.PROPOSE) continue;
                    ACLMessage reponse = rep.createReply();
                    reponse.setPerformative(rep == choix ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
                    acceptances.add(reponse);
                }
                gagnant = choix.getSender();
                creneau = Msg.getInt(Msg.parse(choix), "creneau", -1);
                preemption = choix == preempt;
                victime = preemption ? Msg.parse(choix).get("victime") : null;
            }

            @Override
            protected void handleInform(ACLMessage inform) {
                if (preemption) preempter(d, gagnant, creneau, victime);
                else reserverSalle(d, gagnant, creneau, Df.chercher(CoordinateurAgent.this, Msg.SVC_SALLE, null), 0);
            }

            @Override
            protected void handleFailure(ACLMessage failure) {
                // le medecin n'a pas pu confirmer (hold expire, absence) : nouvel appel d'offres, limite par TENTATIVES_MAX
                if (d.tentative < TENTATIVES_MAX) negocier(d); else echec(d, "echec_medecin");
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

    // ------------------------------------------------------------------ V1 : preemption et compensation

    /**
     * Le medecin a remplace la victime par le patient urgent. Reste a transferer le creneau de SALLE de la victime
     * au patient urgent (M5 "transferer"). Si le transfert echoue : retour arriere chez le medecin (la victime retrouve
     * son creneau) et echec explicite.
     */
    private void preempter(final Demande d, final AID medecin, final int creneau, final String victime) {
        final Rdv rv = registre.get(victime);
        if (rv == null) {   // ne devrait pas arriver : la victime n'est plus connue du coordinateur
            Log.log(getLocalName(), "incoherence : victime " + victime + " inconnue, annulation de " + d.patient);
            annulerChezSalle(medecin.getLocalName(), creneau, d.patient);   // meme message M6, adresse au medecin
            echec(d, "incoherence_victime");
            return;
        }
        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
        req.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        req.addReceiver(new AID(rv.salle, AID.ISLOCALNAME));
        req.setConversationId(d.conv + "-transfert-" + d.tentative);
        req.setContent(Msg.build("action", "transferer", "creneau", "" + creneau, "de", victime, "vers", d.patient));
        addBehaviour(new AchieveREInitiator(this, req) {
            private boolean fait = false;
            @Override
            protected void handleInform(ACLMessage inform) {
                if (fait) return;
                fait = true;
                nbDeplaces++;
                Log.log(getLocalName(), "PREEMPTION : " + d.patient + " prend le creneau " + creneau + " de " + victime
                        + " (" + medecin.getLocalName() + ", " + rv.salle + ")");
                registre.remove(victime);
                lancerCompensation(rv, creneau);   // d'abord : la victime entre dans la file ...
                confirmer(d, medecin.getLocalName(), rv.salle, creneau);   // ... puis l'urgent est confirme (et la file avance)
            }
            private void echecTransfert() {
                if (fait) return;
                fait = true;
                Log.log(getLocalName(), "transfert de salle impossible : " + victime + " retrouve son creneau " + creneau);
                ACLMessage rb = new ACLMessage(ACLMessage.REQUEST);
                rb.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
                rb.addReceiver(medecin);
                rb.setConversationId(d.conv + "-restaurer-" + d.tentative);
                rb.setContent(Msg.build("action", "restaurer", "creneau", "" + creneau, "patient", victime,
                        "urgence", rv.demande.urgence.code()));
                addBehaviour(new AchieveREInitiator(CoordinateurAgent.this, rb));
                echec(d, "echec_transfert");
            }
            @Override protected void handleRefuse(ACLMessage m) { echecTransfert(); }
            @Override protected void handleFailure(ACLMessage m) { echecTransfert(); }
            @Override protected void handleNotUnderstood(ACLMessage m) { echecTransfert(); }
        });
    }

    /** La victime perd son rendez-vous : elle est prevenue et rentre dans la file, prioritaire parmi les normaux. */
    private void lancerCompensation(Rdv rv, int ancien) {
        Demande v = rv.demande;
        Demande dv = new Demande(v.patient, v.conv, v.specialite, v.urgence, v.arrivee, compteur++);
        dv.deplace = true;
        dv.compensation = true;
        dv.ancien = ancien;
        file.add(dv);
        ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
        inf.addReceiver(dv.aid());
        inf.setConversationId(dv.conv);
        inf.setContent(Msg.build("statut", "deplace", "raison", "urgence", "ancien", "" + ancien));
        send(inf);
        Log.log(getLocalName(), v.patient + " deplace (ancien creneau " + ancien + ") : compensation en file");
    }

    /**
     * Le nouveau creneau du patient deplace est deja reserve (medecin + salle) : on le lui PROPOSE (M8, FIPA Propose).
     * Tant qu'il n'a pas repondu, la negociation reste ouverte (occupe) : un seul dossier a la fois.
     */
    private void proposerCompensation(final Demande d, final String medecin, final String salle, final int creneau) {
        ACLMessage prop = new ACLMessage(ACLMessage.PROPOSE);
        prop.setProtocol(FIPANames.InteractionProtocol.FIPA_PROPOSE);
        prop.addReceiver(d.aid());
        prop.setConversationId("comp-" + d.patient + "-" + compteur++);
        prop.setReplyByDate(new java.util.Date(System.currentTimeMillis() + 3000));
        prop.setContent(Msg.build("nouveau", "" + creneau, "ancien", "" + d.ancien, "raison", "urgence",
                "medecin", medecin, "salle", salle));
        Log.log(getLocalName(), "PROPOSITION a " + d.patient + " : creneau " + d.ancien + " -> " + creneau + " (" + medecin + ", " + salle + ")");
        addBehaviour(new ProposeInitiator(this, prop) {
            private boolean resolu = false;
            @Override
            protected void handleAcceptProposal(ACLMessage m) {
                if (resolu) return;
                resolu = true;
                if (absents.contains(medecin)) { replanifier(d, medecin, salle, creneau); return; }   // absent pendant la proposition
                d.termine = true;
                registre.put(d.patient, new Rdv(d, medecin, salle, creneau));
                int attente = Slots.attente(d.arrivee, creneau);
                Log.log(getLocalName(), "COMPENSATION ACCEPTEE " + d.patient + " : " + medecin + ", " + salle + ", creneau " + creneau
                        + " (attente " + attente + ")");
                ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
                inf.addReceiver(d.aid());
                inf.setConversationId(d.conv);
                inf.setContent(Msg.build("statut", "confirme", "medecin", medecin, "salle", salle,
                        "creneau", "" + creneau, "attente", "" + attente));
                send(inf);
                fin();
            }
            @Override protected void handleRejectProposal(ACLMessage m) { refus("refus_du_patient"); }
            @Override protected void handleNotUnderstood(ACLMessage m) { refus("incomprise"); }
            @Override protected void handleOutOfSequence(ACLMessage m) { refus("hors_sequence"); }
            @Override
            @SuppressWarnings("rawtypes")
            protected void handleAllResponses(java.util.Vector responses) {
                if (!resolu && responses.isEmpty()) refus("sans_reponse");
            }
            /** Refus (ou silence) : on rend le creneau reserve, et le patient est a rappeler (issue explicite). */
            private void refus(String raison) {
                if (resolu) return;
                resolu = true;
                d.termine = true;
                annulerChezSalle(medecin, creneau, d.patient);   // M6 vers le medecin (fire-and-forget)
                annulerChezSalle(salle, creneau, d.patient);     // M6 vers la salle
                echecs.merge("compensation_" + raison, 1, Integer::sum);
                Log.log(getLocalName(), "COMPENSATION REFUSEE " + d.patient + " (" + raison + ") : creneau " + creneau + " rendu, patient a rappeler");
                ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
                inf.addReceiver(d.aid());
                inf.setConversationId(d.conv);
                inf.setContent(Msg.build("statut", "a_rappeler", "raison", raison));
                send(inf);
                fin();
            }
        });
    }

    /**
     * Le medecin retenu s'est absente avant que le rendez-vous soit confirme : on rend la salle et on remet la demande
     * dans la file (meme urgence, memes drapeaux), au lieu d'enregistrer un rendez-vous chez un medecin absent.
     */
    private void replanifier(Demande d, String medecin, String salle, int creneau) {
        d.termine = true;
        Log.log(getLocalName(), medecin + " est absent : le creneau " + creneau + " de " + d.patient + " n'est pas valide, replanification");
        annulerChezSalle(salle, creneau, d.patient);
        Demande n = new Demande(d.patient, d.conv, d.specialite, d.urgence, d.arrivee, compteur++);
        n.deplace = d.deplace;
        n.compensation = d.compensation;
        n.ancien = d.ancien;
        n.replanification = true;
        n.creneauOrigine = creneau;
        file.add(n);
        if (!d.compensation) {
            ACLMessage inf = new ACLMessage(ACLMessage.INFORM);
            inf.addReceiver(d.aid());
            inf.setConversationId(d.conv);
            inf.setContent(Msg.build("statut", "a_replanifier", "raison", "medecin_absent"));
            send(inf);
        }
        fin();
    }

    // ------------------------------------------------------------------ issues

    /** Issue positive : enregistre le rendez-vous, previent le patient (INFORM), puis passe a la demande suivante. */
    private void confirmer(Demande d, String medecin, String salle, int creneau) {
        if (d.termine) return;
        if (absents.contains(medecin)) { replanifier(d, medecin, salle, creneau); return; }   // le medecin s'est absente en cours de route
        if (d.compensation) { proposerCompensation(d, medecin, salle, creneau); return; }   // V1 : l'accord du patient d'abord
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
        // V2 : un patient replanifie a cause d'une absence qui ne trouve plus de place recoit une raison dediee
        if (d.replanification && (raison.equals("planning_plein") || raison.equals("planning_sature")
                || raison.equals("aucun_medecin") || raison.equals("aucun_medecin_disponible"))) raison = "capacite";
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
                    r.demande.patient, r.demande.urgence.code(), r.medecin, r.salle, r.creneau, att)
                    + (r.demande.deplace ? "  (deplace)" : ""));
            if (!vus.add("M|" + r.medecin + "|" + r.creneau)) doublons.add("medecin " + r.medecin + " creneau " + r.creneau);
            if (!vus.add("S|" + r.salle + "|" + r.creneau)) doublons.add("salle " + r.salle + " creneau " + r.creneau);
        }
        Log.log(getLocalName(), "rendez-vous confirmes : " + n + " ; echecs : " + echecs);
        Log.log(getLocalName(), "preemptions (patients deplaces) : " + nbDeplaces);
        if (n > 0) {
            Log.log(getLocalName(), String.format("attente moyenne : %.2f creneau(x) ; attente ponderee par l'urgence : %.2f",
                    somme / (double) n, sommePond / (double) poids));
        }
        Log.log(getLocalName(), "doubles attributions : " + doublons.size() + (doublons.isEmpty() ? "" : " " + doublons));
        Log.log(getLocalName(), "en file : " + file.size() + " ; negociation en cours : " + occupe);
    }
}
