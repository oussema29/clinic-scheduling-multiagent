# Clinique : planification de rendez-vous multi-agents (JADE)

Projet de mini-projet IAD. Chaque acteur de la clinique (patient, triage, médecin, salle, coordinateur) est un agent JADE. Un patient décrit ses symptômes, le triage en déduit une spécialité et une urgence, puis le coordinateur lance un appel d'offres (Contract-Net) aux médecins de la spécialité et réserve une salle.

## Documentation

Les documents sont dans le dossier `documents/` :

- **Comprendre le plan d'exécution** (conception, versions V0 à V3, résultats) : lire `documents/Rapport_Reservation_par_Agents_Final.pdf`.
- **Comprendre le flux de tous les agents ensemble** (parcours d'une demande, urgences, absences, messages, code) : lire `documents/Full_Flow.pdf`.
- **Comprendre chaque agent séparément** (variables, valeurs, interactions) : lire les autres documents.

| Document | Contenu |
|---|---|
| `Rapport_Reservation_par_Agents_Final.pdf` | le rapport : plan d'exécution, conception et résultats |
| `Full_Flow.pdf` | le flux complet de tous les agents ensemble |
| `Agent_Patient.pdf` | l'agent patient |
| `Agent_Medecin.pdf` | l'agent médecin |
| `Agent_Coordinateur.pdf` | l'agent coordinateur |
| `Agent_Salle.pdf` | l'agent salle |
| `Agent_Monitor.pdf` | l'agent Monitor (mesures) |
| `Agent_ScenarioRunner.pdf` | l'agent ScenarioRunner (campagnes) |

Seuls ces PDF et ce README sont suivis par git.

Ce qui est réalisé : versions V0 à V3.

- V0 : flux complet de demande de rendez-vous (triage, appel d'offres, salle).
- V1 : une urgence peut prendre la place d'un patient normal, qui reçoit ensuite un autre rendez-vous.
- V2 : un médecin peut s'absenter puis revenir, ses patients sont replanifiés.
- V3 : campagnes de mesure reproductibles, comparaison de trois stratégies (FIFO, priorité, négociation).

## Prérequis

- JDK 17 ou plus (testé avec JDK 23).
- Windows : les scripts fournis sont des `.bat`.
- JADE 4.3.3 : `code/lib/jade.jar` est déjà dans le dépôt.
- Python et `matplotlib`, seulement pour `comparer.py` (graphique de comparaison).

## Compiler et lancer

Depuis le dossier `code` :

```
build.bat
run.bat
```

`run.bat` accepte un mode en premier argument :

| Commande | Scénario |
|---|---|
| `run.bat` | journée type |
| `run.bat urgence` | urgence sur un planning plein, le patient déplacé accepte |
| `run.bat urgence refus` | idem, le patient déplacé refuse |
| `run.bat sature` | urgence sans victime possible |
| `run.bat double` | une deuxième urgence ne peut pas déplacer un patient déjà déplacé |
| `run.bat absence` | absence puis retour d'un médecin |
| `absence-comp` | absence d'un médecin pendant la compensation d'un patient déplacé (avec `patientLent=P3`, voir « Simuler une panne ») |
| `absence-negociation` | absence d'un médecin en pleine négociation (avec `salleLente=salle1`, voir « Simuler une panne ») |
| `run.bat aleatoire 20 42` | 20 demandes aléatoires, graine 42 |
| `run.bat campagne negociation 50 42` | une campagne de mesure (stratégie, patients, graine) |
| `run.bat --gui` | avec l'interface RMA de JADE |

À la fin d'un scénario, le coordinateur affiche un bilan, dont la ligne `doubles attributions : 0` qui doit toujours être à zéro.

## Campagnes de mesure

```
campagne.bat            50 patients, graines 42 7 123 2026 1
campagne.bat 100 42 7   100 patients, graines 42 et 7
```

Chaque graine est jouée avec les trois stratégies, puis `comparer.py` produit un tableau et un graphique. Les fichiers générés sont écrits dans `code/resultats/` (ignoré par git).

## Simuler une panne

Des options JVM déclenchent des pannes pour tester les cas d'échec. Sans option, elles n'ont aucun effet.

| Option | Effet |
|---|---|
| `-Dclinique.test.refuseTransfert=true` | les salles refusent le transfert d'une préemption |
| `-Dclinique.test.patientMuet=P3` | P3 ne répond jamais à une proposition |
| `-Dclinique.test.patientLent=P3` | P3 met 1,5 s à répondre |
| `-Dclinique.test.medecinLent=dr_leroy` | dr_leroy met 3 s à répondre à un appel d'offres |
| `-Dclinique.test.salleLente=salle1` | salle1 met 1,5 s à répondre |

`run.bat` ne transmet pas les options `-D` : il faut lancer `java` directement, depuis `code` :

```
java -Dclinique.test.refuseTransfert=true -cp "lib\jade.jar;out" clinique.Main urgence
java -Dclinique.test.patientLent=P3 -cp "lib\jade.jar;out" clinique.Main absence-comp
java -Dclinique.test.salleLente=salle1 -cp "lib\jade.jar;out" clinique.Main absence-negociation
```

## Organisation du code

```
code/src/clinique/
  Main.java            lance la plateforme et choisit le scénario
  agents/              Patient, Triage, Medecin, Salle, Coordinateur, Scenario
  triage/              règles de classification (mots-clés)
  eval/                campagnes : ScenarioRunner, MonitorAgent, Metrics
  common/              utilitaires : Df (annuaire), Msg (messages), Log, Slots, Urgence
```

Pour lire le code, commencer par `CoordinateurAgent` : c'est lui qui enchaîne l'appel d'offres, la réservation de salle et la réponse au patient.

## Limites connues

- Le coordinateur traite une seule négociation à la fois.
- Le temps est un numéro de créneau, il n'y a pas d'horloge globale.
- Le triage est à base de mots-clés.
- Pas de tests automatiques : les scénarios se vérifient en lisant le bilan.
