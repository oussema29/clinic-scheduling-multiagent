"""
Comparaison des trois strategies (FIFO, PRIORITE, NEGOCIATION) a partir des fichiers ecrits par le Monitor.

Usage :  python comparer.py [N] [graine ...]
         python comparer.py 50 42            une graine
         python comparer.py 50 42 7 123      plusieurs graines : moyennes, et points = une graine

Entree  : resultats/resume_<strategie>_<N>_<graine>.csv   (un fichier par strategie et par graine)
Sortie  : un tableau dans la console, resultats/comparaison_<N>.csv et resultats/comparaison_<N>.png
Ce programme est EXTERIEUR au systeme d'agents : il ne fait que lire des fichiers.
"""
import csv
import math
import statistics
import sys
from pathlib import Path

DOSSIER = Path(__file__).parent / "resultats"
STRATEGIES = ["fifo", "priorite", "negociation"]
NOMS = {"fifo": "FIFO", "priorite": "PRIORITE", "negociation": "NEGOCIATION"}
COULEURS = {"fifo": "#9aa5b1", "priorite": "#2e6da4", "negociation": "#2e7d32"}

# (colonne du CSV, titre, "plus petit = meilleur" ?)
MESURES = [
    ("attente_ponderee", "Attente ponderee par l'urgence (critere du projet)", True),
    ("attente_haute", "Attente moyenne des urgences hautes", True),
    ("attente_normale", "Attente moyenne des patients normaux", True),
    ("urgences_a_temps_pct", "Urgences servies a temps (%)", False),
    ("rdv", "Patients avec un rendez-vous", False),
    ("echecs", "Patients sans rendez-vous (echecs)", True),
    ("deplacements", "Patients deplaces (preemptions)", True),
    ("conflits", "Conflits (doit rester a 0)", True),
]


def lire(strategie, n, graine):
    f = DOSSIER / f"resume_{strategie}_{n}_{graine}.csv"
    if not f.exists():
        sys.exit(f"Fichier manquant : {f}\nLancez d'abord les campagnes (campagne.bat {n} {graine}).")
    with open(f, newline="", encoding="utf-8") as fh:
        ligne = next(csv.DictReader(fh))
    return {k: (float(v) if v not in ("", None) and _nombre(v) else v) for k, v in ligne.items()}


def _nombre(s):
    try:
        float(s)
        return True
    except ValueError:
        return False


def valeur(r, col):
    v = r.get(col)
    return float(v) if isinstance(v, float) else math.nan


def moyenne(valeurs):
    v = [x for x in valeurs if not math.isnan(x)]
    return statistics.mean(v) if v else math.nan


def fmt(x, dec=2):
    return "-" if math.isnan(x) else f"{x:.{dec}f}"


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 50
    graines = [int(g) for g in sys.argv[2:]] or [42]

    # donnees[strategie][colonne] = [valeur par graine]
    donnees = {s: {c: [] for c, _, _ in MESURES} for s in STRATEGIES}
    for s in STRATEGIES:
        for g in graines:
            r = lire(s, n, g)
            for c, _, _ in MESURES:
                donnees[s][c].append(valeur(r, c))

    # ---- tableau console + CSV
    print(f"\nComparaison : {n} patients, graine(s) {', '.join(map(str, graines))}"
          f"{' (moyennes)' if len(graines) > 1 else ''}\n")
    entete = f"{'Mesure':<46}" + "".join(f"{NOMS[s]:>14}" for s in STRATEGIES)
    print(entete)
    print("-" * len(entete))
    lignes_csv = [["mesure"] + [NOMS[s] for s in STRATEGIES]]
    for c, titre, _ in MESURES:
        vals = [moyenne(donnees[s][c]) for s in STRATEGIES]
        print(f"{titre:<46}" + "".join(f"{fmt(v):>14}" for v in vals))
        lignes_csv.append([titre] + [fmt(v, 3) for v in vals])
    with open(DOSSIER / f"comparaison_{n}.csv", "w", newline="", encoding="utf-8") as fh:
        csv.writer(fh).writerows(lignes_csv)

    if len(graines) > 1:
        print("\nCritere du projet, graine par graine (attente ponderee) :")
        for i, g in enumerate(graines):
            print(f"  graine {g:>5} : " + "   ".join(
                f"{NOMS[s]} {fmt(donnees[s]['attente_ponderee'][i], 3)}" for s in STRATEGIES))
        gagne = sum(1 for i in range(len(graines))
                    if donnees['negociation']['attente_ponderee'][i] < donnees['priorite']['attente_ponderee'][i])
        print(f"  NEGOCIATION meilleure que PRIORITE sur le critere : {gagne} graine(s) sur {len(graines)}")

    # ---- graphique
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("\n(matplotlib absent : pas de graphique, le tableau ci-dessus reste valable)")
        return
    choisies = ["attente_ponderee", "attente_haute", "urgences_a_temps_pct", "echecs"]
    fig, axes = plt.subplots(1, len(choisies), figsize=(15, 4.2))
    for ax, col in zip(axes, choisies):
        titre = next(t for c, t, _ in MESURES if c == col)
        for i, s in enumerate(STRATEGIES):
            vals = donnees[s][col]
            m = moyenne(vals)
            ax.bar(i, 0 if math.isnan(m) else m, color=COULEURS[s], width=0.62)
            if len(graines) > 1:
                ax.scatter([i] * len(vals), vals, color="black", s=14, zorder=3)
            haut = max([0 if math.isnan(m) else m] + [v for v in vals if not math.isnan(v)])
            ax.text(i, haut, "  " + fmt(m), ha="center", va="bottom", fontsize=9, fontweight="bold")
        ax.set_xticks(range(3))
        ax.set_xticklabels([NOMS[s] for s in STRATEGIES], fontsize=8)
        ax.set_title(titre, fontsize=9)
        ax.spines[["top", "right"]].set_visible(False)
        ax.margins(y=0.15)
    suptitre = f"{n} patients, graine {graines[0]}" if len(graines) == 1 else \
        f"{n} patients, moyenne sur {len(graines)} graines (points = une graine)"
    fig.suptitle(suptitre, fontsize=10)
    fig.tight_layout()
    sortie = DOSSIER / f"comparaison_{n}.png"
    fig.savefig(sortie, dpi=130)
    print(f"\nGraphique : {sortie}")


if __name__ == "__main__":
    main()
