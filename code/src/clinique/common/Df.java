package clinique.common;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import java.util.Arrays;

/** Petits utilitaires pour l'annuaire DF (Directory Facilitator). */
public final class Df {
    private Df() {}

    /** Inscrit l'agent au DF. "nom" precise le service (ex. la specialite d'un medecin). */
    public static void inscrire(Agent a, String type, String nom) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(a.getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        sd.setName(nom);
        dfd.addServices(sd);
        try { DFService.register(a, dfd); } catch (FIPAException e) { e.printStackTrace(); }
    }

    public static void retirer(Agent a) {
        try { DFService.deregister(a); } catch (FIPAException e) { /* deja retire */ }
    }

    /** Cherche les agents d'un type ; si nom != null, seulement ceux de ce nom. Resultat trie (deterministe). */
    public static AID[] chercher(Agent a, String type, String nom) {
        DFAgentDescription modele = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(type);
        if (nom != null) sd.setName(nom);
        modele.addServices(sd);
        try {
            DFAgentDescription[] res = DFService.search(a, modele);
            AID[] ids = new AID[res.length];
            for (int i = 0; i < res.length; i++) ids[i] = res[i].getName();
            Arrays.sort(ids);
            return ids;
        } catch (FIPAException e) {
            return new AID[0];
        }
    }

    public static AID un(Agent a, String type) {
        AID[] r = chercher(a, type, null);
        return r.length == 0 ? null : r[0];
    }
}
